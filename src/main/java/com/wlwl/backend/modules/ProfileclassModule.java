package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.rpc.ModuleHandler;
import com.wlwl.backend.rpc.RpcContext;
import com.wlwl.backend.rpc.RpcEnvelopeReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Port of profileclass/ — the profile class catalogue.
 *
 * Adds two synthetic columns: key (= profileClassCode) and profileDisplayName
 * (= baseinfo[profileName].displayKey, from the whole baseinfo table).
 *
 * The PUT is the interesting one: the TABLE NAME comes from the request,
 * `"insert into ".$fileType` where `$fileType = $data->mod`. So the client chooses
 * which table to write. That is textbook SQL injection through an identifier, which
 * cannot be parameterised; the port therefore restricts `mod` to a plain identifier and
 * answers 500 for anything else, which is what an SQL error would do anyway. The
 * legitimate value is exactly "profileclass".
 */
@Component
public class ProfileclassModule implements ModuleHandler {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** A bare SQL identifier: letters, digits, underscore. */
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final JdbcTemplate jdbc;

    public ProfileclassModule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "profileclass";
    }

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String sql = "select * from profileclass";
        List<Object> args = new ArrayList<>();

        Integer limit = new RpcEnvelopeReader(env).limitOrNull();
        if (limit != null) {
            sql = sql + " limit ?";
            args.add(limit);
        }

        List<Map<String, Object>> rows = PhpRows.query(jdbc, sql, args.toArray());
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }
        enrich(rows);
        respond(ctx, rows, ctx.queryStringLength());
    }

    @Override
    public void onPut(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String table = identifier(text(env, "mod"));
        JsonNode qdata = env == null ? null : env.get("qdata");
        if (qdata == null || qdata.isNull()) qdata = F.objectNode();

        String code = text(qdata, "profileClassCode");
        String profileName = text(qdata, "profileName");
        String displayName = text(qdata, "profileClassDisplayName");
        int min = intOf(qdata, "profileClassMin");
        int max = intOf(qdata, "profileClassMax");

        if (!truthy(text(qdata, "key"))) {
            jdbc.update("insert into " + table + " (profileClassCode, profileName, "
                            + "profileClassDisplayName, profileClassMin, profileClassMax) "
                            + "values (?, ?, ?, ?, ?)",
                    code, profileName, displayName, min, max);
        } else {
            jdbc.update("update " + table + " set profileClassCode = ?, profileName = ?, "
                            + "profileClassDisplayName = ?, profileClassMin = ?, "
                            + "profileClassMax = ? where profileClassCode = ?",
                    code, profileName, displayName, min, max, text(qdata, "key"));
        }

        List<Map<String, Object>> rows = PhpRows.query(jdbc,
                "select * from " + table + " where profileClassCode = ?", code);
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }
        enrich(rows);
        respond(ctx, rows, ctx.queryStringLength());
    }

    private void enrich(List<Map<String, Object>> rows) {
        // $prof[$ros['name']] = $ros['displayKey'] over the WHOLE baseinfo table.
        List<Object[]> pairs = new ArrayList<>();
        for (Map<String, Object> ros : PhpRows.query(jdbc, "select * from baseinfo")) {
            pairs.add(new Object[]{PhpRows.get(ros, "name"), PhpRows.get(ros, "displayKey")});
        }
        for (Map<String, Object> row : rows) {
            PhpRows.set(row, "key", PhpRows.get(row, "profileClassCode"));
            Object profileName = PhpRows.get(row, "profileName");
            Object display = null;
            for (Object[] p : pairs) {
                if (p[0] != null && p[0].equals(profileName)) {
                    display = p[1];
                    break;
                }
            }
            PhpRows.set(row, "profileDisplayName", display);
        }
    }

    private static void respond(RpcContext ctx, List<Map<String, Object>> rows,
                                int queryStringLength) throws Exception {
        if (queryStringLength == 0) {
            PhpArray list = new PhpArray();
            for (Map<String, Object> row : rows) list.append(PhpArray.of(row));
            ctx.json(list.toJsonNode());
        } else {
            ctx.json(PhpArray.of(rows.get(rows.size() - 1)).toJsonNode());
        }
    }

    /** Rejects anything that is not a bare identifier, like the DB would. */
    private static String identifier(String raw) {
        if (raw == null || !IDENTIFIER.matcher(raw).matches()) {
            throw new IllegalArgumentException(
                    "profileclass put: 'mod' must be a table identifier, got: " + raw);
        }
        return raw;
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

    private static boolean truthy(String s) {
        return s != null && !s.isEmpty() && !"0".equals(s);
    }
}
