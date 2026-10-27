package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.rpc.ModuleHandler;
import com.wlwl.backend.rpc.RpcContext;
import com.wlwl.backend.service.ProfileExpansion;
import com.wlwl.backend.service.ProfileSeeder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared implementation for profileaffect/ and profileimpact/.
 *
 * These two directories are near-duplicates: their post.php files are byte-identical
 * and both read the SAME `profileimpact` table (the affect() columns live on the same
 * rows as the impact() columns). They differ only in which column triple put.php
 * writes:
 *
 *   profileimpact -> skillsImpact, moraleImpact, efficiencyImpact, impactMsg
 *   profileaffect -> skillsAffect, moraleAffect, efficiencyAffect, affectMsg
 *
 * So the dispatch, the seeding, the expansion and the response shape are shared here,
 * and each subclass supplies only its column prefix.
 */
public abstract class AbstractProfileImpactModule implements ModuleHandler {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    protected final JdbcTemplate jdbc;
    protected final ProfileSeeder seeder;
    protected final ProfileExpansion expansion;

    protected AbstractProfileImpactModule(JdbcTemplate jdbc, ProfileSeeder seeder,
                                          ProfileExpansion expansion) {
        this.jdbc = jdbc;
        this.seeder = seeder;
        this.expansion = expansion;
    }

    /** "Impact" or "Affect" — the capitalised infix shared by the four columns. */
    protected abstract String columnPrefix();

    // ---------------------------------------------------------------- POST
    // Identical in both modules: list the matrix for one impactClass, seeding it
    // first when empty.

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String mod = text(env, "mod");

        // PHP: if ($mod) { ... } else { http_response_code(406); }
        if (falsy(mod)) {
            ctx.status(406);
            return;
        }

        // PHP: if ($pn) { ... } — with no baseinfo profiles the file emits NOTHING at
        // all: no 406, no 204, just an empty 200.
        if (!seeder.hasProfiles()) return;

        seeder.seedImpactIfEmpty(mod);

        StringBuilder sql = new StringBuilder(
                "select * from profileimpact where impactClass = ?");
        List<Object> args = new ArrayList<>();
        args.add(mod);

        String qs = ctx.queryString();
        if (!qs.isEmpty()) {
            sql.append(" and profileImpactKey = ?");
            args.add(keyForSelect(qs));
        }

        List<Map<String, Object>> rows = PhpRows.query(jdbc, sql.toString(), args.toArray());
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }

        Map<String, String> exp = expansion.displayNames();
        for (Map<String, Object> row : rows) {
            PhpRows.set(row, "skillsExp", exp.get(string(PhpRows.get(row, "skillsClass"))));
            PhpRows.set(row, "moraleExp", exp.get(string(PhpRows.get(row, "moraleClass"))));
            PhpRows.set(row, "efficiencyExp", exp.get(string(PhpRows.get(row, "efficiencyClass"))));
        }

        respond(ctx, rows, qs.length());
    }

    // ----------------------------------------------------------------- PUT
    // Writes only this module's column triple, then returns the row with Exp fields.

    @Override
    public void onPut(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        JsonNode qdata = env == null ? null : env.get("qdata");
        if (qdata == null || qdata.isNull()) qdata = F.objectNode();

        String prefix = columnPrefix();
        String msgColumn = prefix.toLowerCase() + "Msg";

        // PHP interpolates these NUMERIC values unquoted, and the key unquoted too.
        // A non-numeric value or key is an SQL syntax error there, so it is a 500 here.
        double skills = decimalOrFail(qdata, "skills" + prefix);
        double morale = decimalOrFail(qdata, "morale" + prefix);
        double efficiency = decimalOrFail(qdata, "efficiency" + prefix);
        String msg = text(qdata, msgColumn);
        int key = intOrFail(qdata, "profileImpactKey");

        jdbc.update("update profileimpact set skills" + prefix + " = ?, morale" + prefix
                        + " = ?, efficiency" + prefix + " = ?, " + msgColumn
                        + " = ? where profileImpactKey = ?",
                skills, morale, efficiency, msg, key);

        List<Map<String, Object>> rows = PhpRows.query(jdbc,
                "select * from profileimpact where profileImpactKey = ?", key);
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }

        Map<String, String> exp = expansion.displayNames();
        for (Map<String, Object> row : rows) {
            PhpRows.set(row, "skillsExp", exp.get(string(PhpRows.get(row, "skillsClass"))));
            PhpRows.set(row, "moraleExp", exp.get(string(PhpRows.get(row, "moraleClass"))));
            PhpRows.set(row, "efficiencyExp", exp.get(string(PhpRows.get(row, "efficiencyClass"))));
        }

        respond(ctx, rows, ctx.queryStringLength());
    }

    // ------------------------------------------------------------- helpers

    /** Same array-vs-object switch as every other module; PhpArray decides the JSON. */
    protected static void respond(RpcContext ctx, List<Map<String, Object>> rows,
                                 int queryStringLength) throws Exception {
        if (queryStringLength == 0) {
            PhpArray list = new PhpArray();
            for (Map<String, Object> row : rows) list.append(PhpArray.of(row));
            ctx.json(list.toJsonNode());
        } else {
            ctx.json(PhpArray.of(rows.get(rows.size() - 1)).toJsonNode());
        }
    }

    /**
     * post.php quotes the key ('...'), so a non-numeric value simply matches nothing.
     * Bind a number when it looks like one, otherwise the string.
     */
    private static Object keyForSelect(String raw) {
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static double decimalOrFail(JsonNode qdata, String field) {
        JsonNode v = qdata.get(field);
        if (v == null || v.isNull()) {
            // PHP would emit "= " -> syntax error.
            throw new IllegalArgumentException(
                    "profile put: missing numeric field '" + field + "'");
        }
        if (v.isNumber()) return v.asDouble();
        try {
            return Double.parseDouble(v.asText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "profile put: field '" + field + "' is not numeric: " + v.asText());
        }
    }

    private static int intOrFail(JsonNode qdata, String field) {
        JsonNode v = qdata.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException(
                    "profile put: missing key field '" + field + "'");
        }
        if (v.isNumber()) return v.asInt();
        try {
            return Integer.parseInt(v.asText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "profile put: key field '" + field + "' is not an integer: " + v.asText());
        }
    }

    private static boolean falsy(String s) {
        return s == null || s.isEmpty() || "0".equals(s);
    }

    private static String string(Object v) {
        return v == null ? "" : v.toString();
    }

    protected static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }
}
