package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.wlwl.backend.db.MysqlPassword;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.db.PhpUniqid;
import com.wlwl.backend.rpc.ModuleHandler;
import com.wlwl.backend.rpc.RpcContext;
import com.wlwl.backend.service.LicenseAssignment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Port of backenduser/ — user listing and user create/update.
 *
 * Files:
 *   index.php               includes etc/restdx.inc -> standard gateway, restkey gated
 *   post.php                list users (33 LOC)     -> onPost
 *   put.php                 create/update a user (112 LOC) -> onPut
 *   post_saved_20231209.php older copy of post.php, unreachable -> NOT ported
 *
 * Three behaviours here are easy to lose:
 *
 *  1. put.php NEVER updates loginPassword. The UPDATE statement simply has no
 *     password column. Reproduced literally: changing a user's level or company does
 *     not reset their password.
 *
 *  2. put.php RETURNS THE STORED PASSWORD HASH. It re-selects the whole row with
 *     `select *` and json_encodes it without blanking loginPassword (unlike
 *     staffsignin, which blanks it). That is a real information leak in the original
 *     and is reproduced deliberately — see the ledger.
 *
 *  3. `post.php` echoes `json_encode(array())` = `[]` with status 200 when nothing
 *     matches. It does NOT send 204 (its own predecessor post_saved_20231209.php did).
 *     Two files, two different empty-result contracts; the live one is post.php.
 */
@Component
public class BackenduserModule implements ModuleHandler {

    /**
     * Approximation of PHP's FILTER_VALIDATE_EMAIL, which (like this) requires a dot
     * in the domain part. Only used to decide 406-vs-proceed on level > 0.
     */
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+@"
                    + "[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?"
                    + "(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$");

    private final JdbcTemplate jdbc;
    private final LicenseAssignment licenses;

    public BackenduserModule(JdbcTemplate jdbc, LicenseAssignment licenses) {
        this.jdbc = jdbc;
        this.licenses = licenses;
    }

    @Override
    public String module() {
        return "backenduser";
    }

    // ---------------------------------------------------------------- POST
    // post.php: select * from backenduser where level <= $mod
    //           [and companyName = '$package'] [and level > 0] [limit $limit]

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();

        // PHP interpolates $mod straight into the SQL. A missing or non-numeric mod
        // therefore produces a syntax error and a fatal (500) — reproduced rather
        // than silently ignored, because the client never sends anything else.
        Integer mod = parseInt(text(env, "mod"));
        if (mod == null) {
            throw new IllegalArgumentException(
                    "backenduser/post.php requires a numeric 'mod' (level <= mod)");
        }

        StringBuilder sql = new StringBuilder("select * from backenduser where level <= ?");
        List<Object> args = new ArrayList<>();
        args.add(mod);

        String packageId = text(env, "package");
        if (!packageId.isEmpty()) {
            sql.append(" and companyName = ?");
            args.add(packageId);
        }

        if (truthy(env, "backend")) {
            sql.append(" and level > 0");
        }

        Integer limit = new com.wlwl.backend.rpc.RpcEnvelopeReader(env).limitOrNull();
        if (limit != null) {
            sql.append(" limit ?");
            args.add(limit);
        }

        List<Map<String, Object>> rows = PhpRows.query(jdbc, sql.toString(), args.toArray());

        // No rows -> `[]` with 200 (not 204): $jsonarray stays array() and is echoed.
        ctx.json(PhpRows.arrayOrLastRow(rows, ctx.queryStringLength()));
    }

    // ----------------------------------------------------------------- PUT
    // put.php: validate -> duplicate check -> licence seat -> insert/update -> re-read

    @Override
    public void onPut(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        // PHP: $qdata = $data->qdata; reading properties of null warns and yields null,
        // so an absent qdata behaves like an empty one.
        JsonNode qdata = env == null ? null : env.get("qdata");
        if (qdata == null || qdata.isNull()) {
            qdata = JsonNodeFactory.instance.objectNode();
        }

        // PHP: if ($qdata->companyName == '') — null and "" both match; "0" does not.
        String companyName = text(qdata, "companyName");
        if (companyName.isEmpty()) {
            companyName = "admin";
        }

        double level = number(qdata, "level");
        String email = text(qdata, "email");

        // PHP: if ($qdata->level > 0 && !filter_var($qdata->email, FILTER_VALIDATE_EMAIL))
        if (level > 0 && !EMAIL.matcher(email).matches()) {
            ctx.status(406);
            return;
        }
        // PHP builds either a quoted literal or the bare keyword NULL; binding null
        // produces the same stored value for level 0.
        Object emailParam = level > 0 ? email : null;

        String userId = text(qdata, "userId");
        String loginName = text(qdata, "loginName");
        String userName = text(qdata, "userName");

        // Duplicate login name. For a NEW user $qdata->userId is still empty, so
        // `userId <> ''` matches every existing row with that loginName.
        List<Map<String, Object>> dupes = PhpRows.query(jdbc,
                "select * from backenduser where loginName = ? and userId <> ?",
                loginName, userId);
        if (!dupes.isEmpty()) {
            ctx.status(406);
            return;
        }

        String batchId = licenses.batchFor(qdata);

        // PHP: if ($lic) — "" and "0" are falsy, in which case no user is created.
        if (LicenseAssignment.phpFalsy(batchId)) {
            ctx.status(405);
            return;
        }

        boolean creating = userId.isEmpty();
        if (creating) {
            userId = PhpUniqid.next();
            // password('...') -> the MySQL 4.1 hash, computed in Java (see MysqlPassword).
            String hash = MysqlPassword.encode(text(qdata, "loginPassword"));
            jdbc.update("insert into backenduser "
                            + "(userId, userName, loginName, loginPassword, level, active, "
                            + "companyName, instructor, email) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    userId, userName, loginName, hash, (int) level,
                    (int) number(qdata, "active"), companyName,
                    (int) number(qdata, "instructor"), emailParam);
        } else {
            // NOTE: no loginPassword here — that is faithful, put.php never rehashes
            // or resets the password on update.
            jdbc.update("update backenduser set userName = ?, loginName = ?, level = ?, "
                            + "active = ?, instructor = ?, companyName = ?, email = ? "
                            + "where userId = ?",
                    userName, loginName, (int) level, (int) number(qdata, "active"),
                    (int) number(qdata, "instructor"), companyName, emailParam, userId);
        }

        // Pass the effective id: for a new user it was just minted locally, whereas
        // the PHP mutates $qdata->userId so update_license sees it.
        licenses.attach(qdata, batchId, userId);

        List<Map<String, Object>> rows = PhpRows.query(jdbc,
                "select * from backenduser where userId = ?", userId);
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }
        // Deliberately NOT blanking loginPassword: the PHP returns the whole row.
        ctx.json(PhpRows.arrayOrLastRow(rows, ctx.queryStringLength()));
    }

    // ------------------------------------------------------------- helpers

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static double number(JsonNode node, String field) {
        if (node == null) return 0;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return 0;
        if (v.isNumber()) return v.asDouble();
        try {
            return Double.parseDouble(v.asText().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean truthy(JsonNode node, String field) {
        if (node == null) return false;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return false;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber()) return v.asDouble() != 0;
        String s = v.asText();
        return !s.isEmpty() && !s.equals("0");
    }

    private static Integer parseInt(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
