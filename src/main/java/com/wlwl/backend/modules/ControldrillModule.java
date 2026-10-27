package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.wlwl.backend.db.MysqlPassword;
import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.rpc.ModuleHandler;
import com.wlwl.backend.rpc.RpcContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of controldrill/ — the drill activation switchboard for one company.
 *
 * The company row carries FOUR activation flags, one per run slot, and `mod` selects
 * which one this call turns on:
 *
 * <pre>
 *   $act = array('active', 'activeOne', 'activeTwo', 'activeThree');
 *   UPDATE company set $act[$mod] = 1 where packageId = ...
 * </pre>
 *
 * This is the third distinct meaning of `mod` in this codebase (it is also a WHERE
 * value in baseinfo/ and an action selector in drill/) — logged in the ledger.
 *
 * **`mod == 0` also re-pushes the shared company password.** That is the mechanism
 * documented in §9.1: on activation, every NON-INSTRUCTOR account in the company gets
 * `loginPassword = PASSWORD(company.commonPW)`, wiping any personal password. The
 * `where instructor = 0` is load-bearing — dropping it would overwrite instructor
 * passwords too. MySQL 8 removed `PASSWORD()`, so the hash is computed in Java
 * ({@link MysqlPassword}) and bound, giving the identical value and result set.
 */
@Component
public class ControldrillModule implements ModuleHandler {

    /** The four activation columns, in `mod` order. */
    private static final String[] ACTIVATION = {
            "active", "activeOne", "activeTwo", "activeThree"
    };

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final JdbcTemplate jdbc;

    public ControldrillModule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "controldrill";
    }

    // ---------------------------------------------------------------- POST
    // No writes: just the company row(s) for a package, decorated with model names.

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String packageId = text(env, "package");

        List<Map<String, Object>> rows = PhpRows.query(jdbc,
                "select * from company where packageId = ?", packageId);
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }

        Map<String, String> models = modelNames();
        for (Map<String, Object> row : rows) decorate(row, models);

        respond(ctx, rows, ctx.queryStringLength());
    }

    // ----------------------------------------------------------------- PUT
    // Flip one activation flag; on mod==0 also reset the company-wide password.

    @Override
    public void onPut(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        JsonNode qdata = env == null ? null : env.get("qdata");
        if (qdata == null || qdata.isNull()) qdata = F.objectNode();

        double mod = number(env, "mod");
        String packageId = text(env, "package");

        // PHP's $act[$mod] with an out-of-range index yields null, and
        // "UPDATE company set =1 ..." is a syntax error -> 500. Same here.
        int idx = (int) mod;
        if (idx < 0 || idx >= ACTIVATION.length) {
            throw new IllegalArgumentException(
                    "controldrill put: mod=" + mod + " is not a known activation slot");
        }

        jdbc.update("update company set " + ACTIVATION[idx] + " = 1 where packageId = ?",
                packageId);

        List<Map<String, Object>> rows = PhpRows.query(jdbc,
                "select * from company where packageId = ?", packageId);
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }

        Map<String, String> models = modelNames();
        for (Map<String, Object> row : rows) {
            // Drill activation (mod == 0): refresh the shared company password.
            if (idx == 0) {
                String commonPW = string(PhpRows.get(row, "commonPW"));
                jdbc.update("update backenduser set loginPassword = ? "
                                + "where companyName = ? and instructor = 0",
                        MysqlPassword.encode(commonPW),
                        string(PhpRows.get(row, "packageId")));
            }
            decorate(row, models);
        }

        respond(ctx, rows, ctx.queryStringLength());
    }

    // ------------------------------------------------------------- helpers

    /** $model[modelId] = modelName, plus the PHP's $model[''] = '' seeding. */
    private Map<String, String> modelNames() {
        Map<String, String> models = new LinkedHashMap<>();
        for (Map<String, Object> rw : PhpRows.query(jdbc, "select * from model")) {
            models.put(string(PhpRows.get(rw, "modelId")),
                    string(PhpRows.get(rw, "modelName")));
        }
        models.put("", "");       // so an empty runOne/runTwo/runThree resolves to ""
        return models;
    }

    /** runOneName / runTwoName / runThreeName — unguarded lookups, so null when absent. */
    private static void decorate(Map<String, Object> row, Map<String, String> models) {
        PhpRows.set(row, "runOneName", models.get(string(PhpRows.get(row, "runOne"))));
        PhpRows.set(row, "runTwoName", models.get(string(PhpRows.get(row, "runTwo"))));
        PhpRows.set(row, "runThreeName", models.get(string(PhpRows.get(row, "runThree"))));
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

    private static String string(Object v) {
        return v == null ? "" : v.toString();
    }

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
}
