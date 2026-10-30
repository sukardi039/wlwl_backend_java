package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.rpc.ModuleHandler;
import com.wlwl.backend.rpc.RpcContext;
import com.wlwl.backend.rpc.RpcEnvelopeReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Staff list and save handlers (staff/post.php and staff/put.php).
 *
 * Same idiom as baseinfo, plus the profile-class expansion join that the PHP
 * does in a second query and splices into each row (skillsExp / moraleExp /
 * efficiencyExp). Note the PHP reads `$exp[$row['skillsProfile']]` with no isset
 * guard, so a staff row pointing at a missing profile class yields JSON null —
 * reproduced by Map.get returning null.
 */
@Component
public class StaffModule implements ModuleHandler {

    private final JdbcTemplate jdbc;

    public StaffModule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "staff";
    }

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        if (env == null) { ctx.noContent(); return; }

        StringBuilder sql = new StringBuilder("select * from staff");
        List<Object> args = new ArrayList<>();
        Integer limit = new com.wlwl.backend.rpc.RpcEnvelopeReader(env).limitOrNull();
        if (limit != null) {
            sql.append(" limit ?");
            args.add(limit);
        }

        List<Map<String, Object>> rows = PhpRows.query(jdbc, sql.toString(), args.toArray());
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }

        expandProfiles(rows);
        ctx.json(PhpRows.arrayOrLastRow(rows, ctx.queryStringLength()));
    }

    @Override
    public void onPut(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        JsonNode qdata = env == null ? null : env.get("qdata");
        if (qdata == null || !qdata.isObject()) {
            ctx.statusJson(400, Map.of("error", "qdata must be a staff object"));
            return;
        }

        RpcEnvelopeReader fields = new RpcEnvelopeReader(qdata);
        long staffId;
        int yearsWorked;
        int experience;
        try {
            staffId = Long.parseLong(fields.textOrEmpty("staffId").trim());
            yearsWorked = Integer.parseInt(fields.textOrEmpty("yearsWorked").trim());
            experience = Integer.parseInt(fields.textOrEmpty("experience").trim());
        } catch (NumberFormatException e) {
            ctx.statusJson(400, Map.of("error", "staffId, yearsWorked and experience must be integers"));
            return;
        }
        if (staffId != -1 && staffId <= 0) {
            ctx.statusJson(400, Map.of("error", "staffId must be -1 for a new staff member or a positive ID"));
            return;
        }

        Object[] values = {
                fields.textOrEmpty("staffName"), fields.textOrEmpty("staffDescription"),
                fields.textOrEmpty("staffSelfIntroduction"), fields.textOrEmpty("staffInterview"),
                fields.textOrEmpty("staffPhoto"), fields.textOrEmpty("skillsProfile"),
                fields.textOrEmpty("moraleProfile"), fields.textOrEmpty("efficiencyProfile"),
                yearsWorked, experience
        };
        if (staffId == -1) {
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement(
                        "insert into staff (staffName, staffDescription, staffSelfIntroduction, "
                                + "staffInterview, staffPhoto, skillsProfile, moraleProfile, "
                                + "efficiencyProfile, yearsWorked, experience) "
                                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                for (int i = 0; i < values.length; i++) {
                    statement.setObject(i + 1, values[i]);
                }
                return statement;
            }, keys);
            Number key = keys.getKey();
            if (key == null) throw new IllegalStateException("Staff insert returned no generated ID");
            staffId = key.longValue();
        } else {
            List<Object> args = new ArrayList<>(List.of(values));
            args.add(staffId);
            jdbc.update("update staff set staffName = ?, staffDescription = ?, "
                    + "staffSelfIntroduction = ?, staffInterview = ?, staffPhoto = ?, "
                    + "skillsProfile = ?, moraleProfile = ?, efficiencyProfile = ?, "
                    + "yearsWorked = ?, experience = ? where staffId = ?", args.toArray());
        }

        List<Map<String, Object>> rows =
                PhpRows.query(jdbc, "select * from staff where staffId = ?", staffId);
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }
        expandProfiles(rows);
        // Save always returns an array: the editor reads NewData[0].
        ctx.json(rows);
    }

    private void expandProfiles(List<Map<String, Object>> rows) {
        Map<String, Object> exp = new HashMap<>();
        for (Map<String, Object> ros : PhpRows.query(jdbc, "select * from profileclass")) {
            exp.put(String.valueOf(PhpRows.get(ros, "profileClassCode")),
                    PhpRows.get(ros, "profileClassDisplayName"));
        }

        for (Map<String, Object> row : rows) {
            PhpRows.set(row, "skillsExp", exp.get(String.valueOf(PhpRows.get(row, "skillsProfile"))));
            PhpRows.set(row, "moraleExp", exp.get(String.valueOf(PhpRows.get(row, "moraleProfile"))));
            PhpRows.set(row, "efficiencyExp", exp.get(String.valueOf(PhpRows.get(row, "efficiencyProfile"))));
        }
    }
}
