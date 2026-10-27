package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.rpc.ModuleHandler;
import com.wlwl.backend.rpc.RpcContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of staff/post.php.
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

        // PHP: select * from profileclass, then $exp[profileClassCode] = profileClassDisplayName
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

        ctx.json(PhpRows.arrayOrLastRow(rows, ctx.queryStringLength()));
    }
}
