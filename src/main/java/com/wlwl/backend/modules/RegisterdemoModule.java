package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.wlwl.backend.db.MysqlPassword;
import com.wlwl.backend.db.PhpArray;
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

/**
 * Port of registerdemo/ — the public self-registration endpoint.
 *
 * Unlike every restdx module this one:
 *  - is reachable WITHOUT a restkey (it sits on the pre-auth surface);
 *  - dispatches on the HTTP verb only, so the body's "method" field is ignored;
 *  - answers "No method specified!" for anything but PUT;
 *  - sets CORS `Access-Control-Allow-Origin: *`.
 *
 * Flow (all in put.php):
 *  1. blank companyName becomes the literal 'admin';
 *  2. REJECT with 406 if loginName is already taken by a DIFFERENT userId;
 *  3. resolve a licence batch (see LicenseAssignment#batchFor) — no seat means 405;
 *  4. insert (new userId = uniqid()) or update the backenduser row. The INSERT hashes
 *     the password with MySQL's PASSWORD(); the UPDATE deliberately does NOT touch
 *     loginPassword, so a re-registration never changes the password;
 *  5. attach the licence, then echo the stored user row.
 *
 * `$data` in registerdemo/index.php comes from etc/db.inc, which is gitignored, so the
 * envelope is assumed to be the usual parsed body — the only source the client sends.
 */
@Component
public class RegisterdemoModule implements ModuleHandler {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final JdbcTemplate jdbc;
    private final LicenseAssignment licenses;

    public RegisterdemoModule(JdbcTemplate jdbc, LicenseAssignment licenses) {
        this.jdbc = jdbc;
        this.licenses = licenses;
    }

    @Override
    public String module() {
        return "registerdemo";
    }

    /** switch(strtoupper($_SERVER['REQUEST_METHOD'])) — never reads the body's method. */
    @Override
    public boolean ignoresBodyMethod() {
        return true;
    }

    @Override
    public void onGet(RpcContext ctx) throws Exception {
        ctx.text("No method specified!");
    }

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        ctx.text("No method specified!");
    }

    @Override
    public void onDelete(RpcContext ctx) throws Exception {
        ctx.text("No method specified!");
    }

    @Override
    public void onPut(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        JsonNode qdata = env == null ? null : env.get("qdata");
        if (qdata == null || qdata.isNull()) qdata = F.objectNode();

        // 1. default company
        String companyName = text(qdata, "companyName");
        if (companyName.isEmpty()) companyName = "admin";

        // 2. duplicate login name -> 406 with no body
        String userId = text(qdata, "userId");
        String loginName = text(qdata, "loginName");
        List<Map<String, Object>> dupe = PhpRows.query(jdbc,
                "select * from backenduser where loginName = ? and userId <> ?",
                loginName, userId);
        if (!dupe.isEmpty()) {
            ctx.status(406);
            return;
        }

        // 3. licence batch, else 405
        String batchId = licenses.batchFor(qdata);
        if (!truthy(batchId)) {
            ctx.status(405);
            return;
        }

        // 4. insert or update the user
        if (!truthy(userId)) {
            userId = PhpUniqid.next();
            jdbc.update("insert into backenduser (userId, userName, loginName, "
                            + "loginPassword, level, active, companyName, instructor) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                    userId, text(qdata, "userName"), loginName,
                    // MySQL 8 dropped PASSWORD(); compute the 4.1 hash in Java.
                    MysqlPassword.encode(text(qdata, "loginPassword")),
                    intOf(qdata, "level"), intOf(qdata, "active"),
                    companyName, intOf(qdata, "instructor"));
        } else {
            // NOTE: loginPassword is absent on purpose (see the class comment).
            jdbc.update("update backenduser set userName = ?, loginName = ?, level = ?, "
                            + "active = ?, instructor = ?, companyName = ? where userId = ?",
                    text(qdata, "userName"), loginName, intOf(qdata, "level"),
                    intOf(qdata, "active"), intOf(qdata, "instructor"),
                    companyName, userId);
        }

        // 5. licence, then echo the row. The PHP mutated $qdata->userId before this
        // call, so the licence is written against the id we just minted.
        licenses.attach(qdata, batchId, userId);

        List<Map<String, Object>> rows = PhpRows.query(jdbc,
                "select * from backenduser where userId = ?", userId);
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }

        List<Object> out = new ArrayList<>();
        for (Map<String, Object> row : rows) out.add(PhpArray.of(row));

        if (ctx.queryStringLength() == 0) {
            PhpArray list = new PhpArray();
            for (Object r : out) list.append(r);
            ctx.json(list.toJsonNode());
        } else {
            ctx.json(((PhpArray) out.get(out.size() - 1)).toJsonNode());
        }
    }

    private static boolean truthy(String s) {
        return s != null && !s.isEmpty() && !"0".equals(s);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static int intOf(JsonNode node, String field) {
        if (node == null) return 0;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return 0;
        if (v.isNumber()) return v.asInt();
        try {
            return Integer.parseInt(v.asText().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
