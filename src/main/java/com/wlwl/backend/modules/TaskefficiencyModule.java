package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.wlwl.backend.db.PhpArray;
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
 * Port of taskefficiency/ — per-(product, task) efficiency settings.
 *
 * Seeded lazily: the first read for a productName copies every baseinfo task
 * (baseclass 'tasks', ordered by name) into a row with skillsWeight/moraleWeight 0.5,
 * duration 1.0, taskDisplayName = baseinfo.orgKey, passingRate 1, target 1.
 *
 * A REAL BUG IS PRESERVED: the query-string filter is
 * `and profileImpactKey = '<query string>'` — a column that does not exist on
 * `taskefficiency`. So passing a query string to this module is an SQL error, i.e. a
 * 500, in production too. Reproduced by issuing the same statement.
 */
@Component
public class TaskefficiencyModule implements ModuleHandler {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final JdbcTemplate jdbc;

    public TaskefficiencyModule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "taskefficiency";
    }

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String mod = text(env, "mod");

        if (!truthy(mod)) {
            ctx.status(406);
            return;
        }

        List<Map<String, Object>> existing = PhpRows.query(jdbc,
                "select * from taskefficiency where productName = ?", mod);
        if (existing.isEmpty()) {
            for (Map<String, Object> task : PhpRows.query(jdbc,
                    "select * from baseinfo where baseclass = 'tasks' order by name")) {
                jdbc.update("insert into taskefficiency (taskEfficiencyId, productName, "
                                + "taskName, skillsWeight, moraleWeight, duration, "
                                + "taskDisplayName, passingRate, target) "
                                + "values (NULL, ?, ?, 0.5, 0.5, 1.0, ?, 1, 1)",
                        mod, PhpRows.get(task, "name"), PhpRows.get(task, "orgKey"));
            }
        }

        String sql = "select * from taskefficiency where productName = ?";
        List<Object> args = new ArrayList<>();
        args.add(mod);

        String qs = ctx.queryString();
        if (!qs.isEmpty()) {
            // Faithful bug: profileImpactKey does not exist on taskefficiency, so this
            // is a SQL error -> 500, exactly as in the PHP.
            sql = sql + " and profileImpactKey = ?";
            args.add(qs);
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
        JsonNode qdata = env == null ? null : env.get("qdata");
        if (qdata == null || qdata.isNull()) qdata = F.objectNode();

        int id = intOf(qdata, "taskEfficiencyId");

        jdbc.update("update taskefficiency set skillsWeight = ?, moraleWeight = ?, "
                        + "duration = ?, taskDisplayName = ?, passingRate = ?, target = ? "
                        + "where taskEfficiencyId = ?",
                doubleOf(qdata, "skillsWeight"), doubleOf(qdata, "moraleWeight"),
                doubleOf(qdata, "duration"), text(qdata, "taskDisplayName"),
                doubleOf(qdata, "passingRate"), doubleOf(qdata, "target"), id);

        List<Map<String, Object>> rows = PhpRows.query(jdbc,
                "select * from taskefficiency where taskEfficiencyId = ?", id);
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }
        enrich(rows);
        respond(ctx, rows, ctx.queryStringLength());
    }

    private void enrich(List<Map<String, Object>> rows) {
        Map<String, String> exp = new HashMap<>();
        for (Map<String, Object> row : PhpRows.query(jdbc,
                "select * from baseinfo where baseclass = 'tasks'")) {
            exp.put(string(PhpRows.get(row, "name")), string(PhpRows.get(row, "displayKey")));
        }
        for (Map<String, Object> row : rows) {
            PhpRows.set(row, "taskExp", exp.get(string(PhpRows.get(row, "taskName"))));
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

    private static String string(Object v) {
        return v == null ? "" : v.toString();
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

    private static double doubleOf(JsonNode node, String field) {
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

    private static boolean truthy(String s) {
        return s != null && !s.isEmpty() && !"0".equals(s);
    }
}
