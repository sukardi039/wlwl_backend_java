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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Port of profilesens/ (post.php + put.php) — the sensitivity matrix.
 *
 * Same triple-combination idea as profileimpact, but:
 *
 *  - the table is `profilesens` and there is NO impactClass column, so the seeding
 *    trigger is simply "the whole table is empty" (`num_rows === 0`);
 *  - post.php ALSO expands the profile NAME via baseinfo.displayKey into
 *    skillsPrf/moralePrf/efficiencyPrf, using the cross join
 *    `from profileclass, baseinfo where profileName = name`;
 *  - put.php does NOT add the Prf fields — only the three Exp ones. So the PUT
 *    response shape differs from the POST response shape. That is faithful.
 *
 * post.php ignores `mod` entirely (no 406 path), also unlike profileimpact.
 */
@Component
public class ProfilesensModule implements ModuleHandler {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final JdbcTemplate jdbc;
    private final ProfileSeeder seeder;
    private final ProfileExpansion expansion;

    public ProfilesensModule(JdbcTemplate jdbc, ProfileSeeder seeder,
                             ProfileExpansion expansion) {
        this.jdbc = jdbc;
        this.seeder = seeder;
        this.expansion = expansion;
    }

    @Override
    public String module() {
        return "profilesens";
    }

    // ---------------------------------------------------------------- POST

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        // PHP: the seeding check is on the WHOLE table, before the query-string filter.
        seeder.seedSensitivityIfEmpty();

        String qs = ctx.queryString();
        String sql = "select * from profilesens";
        List<Object> args = new ArrayList<>();
        if (!qs.isEmpty()) {
            sql = sql + " where profileSensitivityKey = ?";
            args.add(keyForSelect(qs));
        }

        List<Map<String, Object>> rows = PhpRows.query(jdbc, sql, args.toArray());
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }

        ProfileExpansion.Expanded both = expansion.displayNamesAndKeys();
        for (Map<String, Object> row : rows) {
            String skills = string(PhpRows.get(row, "skillsClass"));
            String morale = string(PhpRows.get(row, "moraleClass"));
            String efficiency = string(PhpRows.get(row, "efficiencyClass"));

            PhpRows.set(row, "skillsExp", both.displayNames().get(skills));
            PhpRows.set(row, "moraleExp", both.displayNames().get(morale));
            PhpRows.set(row, "efficiencyExp", both.displayNames().get(efficiency));
            PhpRows.set(row, "skillsPrf", both.displayKeys().get(skills));
            PhpRows.set(row, "moralePrf", both.displayKeys().get(morale));
            PhpRows.set(row, "efficiencyPrf", both.displayKeys().get(efficiency));
        }

        respond(ctx, rows, qs.length());
    }

    // ----------------------------------------------------------------- PUT

    @Override
    public void onPut(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        JsonNode qdata = env == null ? null : env.get("qdata");
        if (qdata == null || qdata.isNull()) qdata = F.objectNode();

        // Numeric values are interpolated UNQUOTED in the PHP and the key too, so any
        // non-numeric value is an SQL syntax error there -> 500 here.
        double skills = decimalOrFail(qdata, "skillsSensitivity");
        double morale = decimalOrFail(qdata, "moraleSensitivity");
        double efficiency = decimalOrFail(qdata, "efficiencySensitivity");
        int key = intOrFail(qdata, "profileSensitivityKey");

        jdbc.update("update profilesens set skillsSensitivity = ?, moraleSensitivity = ?, "
                        + "efficiencySensitivity = ? where profileSensitivityKey = ?",
                skills, morale, efficiency, key);

        List<Map<String, Object>> rows = PhpRows.query(jdbc,
                "select * from profilesens where profileSensitivityKey = ?", key);
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }

        // NOTE: only the Exp fields here — no Prf. Matches put.php.
        Map<String, String> exp = expansion.displayNames();
        for (Map<String, Object> row : rows) {
            PhpRows.set(row, "skillsExp", exp.get(string(PhpRows.get(row, "skillsClass"))));
            PhpRows.set(row, "moraleExp", exp.get(string(PhpRows.get(row, "moraleClass"))));
            PhpRows.set(row, "efficiencyExp", exp.get(string(PhpRows.get(row, "efficiencyClass"))));
        }

        respond(ctx, rows, ctx.queryStringLength());
    }

    // ------------------------------------------------------------- helpers

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

    /** post.php quotes the key, so a non-numeric value simply matches nothing. */
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
            throw new IllegalArgumentException("profilesens put: missing field '" + field + "'");
        }
        if (v.isNumber()) return v.asDouble();
        try {
            return Double.parseDouble(v.asText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "profilesens put: field '" + field + "' is not numeric");
        }
    }

    private static int intOrFail(JsonNode qdata, String field) {
        JsonNode v = qdata.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("profilesens put: missing key '" + field + "'");
        }
        if (v.isNumber()) return v.asInt();
        try {
            return Integer.parseInt(v.asText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "profilesens put: key '" + field + "' is not an integer");
        }
    }

    private static String string(Object v) {
        return v == null ? "" : v.toString();
    }
}
