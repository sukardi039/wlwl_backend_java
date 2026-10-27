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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of action/ — actions are baseinfo rows (baseclass 'actions') with an
 * `action` table half, enriched with THREE lookups:
 *
 *   typeExp     = actiontype[actionType]          (numeric key -> description)
 *   affectText  = baseinfo[affectedWhen].displayKey
 *   affectCode  = baseinfo[affectedIf].displayKey
 *
 * All three are unguarded array reads, so a missing lookup yields JSON null.
 *
 * Note the join asymmetry again: POST uses a LEFT JOIN (so a baseinfo action with no
 * action row still appears, with null action columns), while the post-upsert re-read
 * uses an INNER JOIN (`from baseinfo b, action p`).
 */
@Component
public class ActionModule implements ModuleHandler {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final JdbcTemplate jdbc;

    public ActionModule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "action";
    }

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String sql = "select * from baseinfo b left join action p "
                + "on b.name = p.actionName where b.baseclass = 'actions'";
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
        JsonNode qdata = env == null ? null : env.get("qdata");
        if (qdata == null || qdata.isNull()) qdata = F.objectNode();

        String duration = text(qdata, "duration");
        String description = text(qdata, "actionDescription");
        int applyAll = intOf(qdata, "applyAll");
        int minStaff = intOf(qdata, "minStaff");
        int maxStaff = intOf(qdata, "maxStaff");
        int actionType = intOf(qdata, "actionType");
        int withTask = intOf(qdata, "withTask");
        int swapTask = intOf(qdata, "swapTask");
        String parentAction = text(qdata, "parentAction");
        int minMemberSize = intOf(qdata, "minMemberSize");
        String affectedWhen = text(qdata, "affectedWhen");
        String affectedIf = text(qdata, "affectedIf");
        int away = intOf(qdata, "away");

        if (!truthy(text(qdata, "actionName"))) {
            // The new action's NAME comes from qdata.name, not actionName.
            jdbc.update("insert into action (actionName, actionDescription, duration, "
                            + "applyAll, minStaff, maxStaff, actionType, withTask, swapTask, "
                            + "parentAction, minMemberSize, affectedWhen, affectedIf, away) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    text(qdata, "name"), description, duration, applyAll, minStaff, maxStaff,
                    actionType, withTask, swapTask, parentAction, minMemberSize,
                    affectedWhen, affectedIf, away);
        } else {
            // actionName is the WHERE key and is not updated.
            jdbc.update("update action set duration = ?, actionDescription = ?, applyAll = ?, "
                            + "minStaff = ?, maxStaff = ?, actionType = ?, withTask = ?, "
                            + "swapTask = ?, parentAction = ?, minMemberSize = ?, "
                            + "affectedWhen = ?, affectedIf = ?, away = ? where actionName = ?",
                    duration, description, applyAll, minStaff, maxStaff, actionType,
                    withTask, swapTask, parentAction, minMemberSize, affectedWhen,
                    affectedIf, away, text(qdata, "actionName"));
        }

        // Re-read by qdata.name with an INNER join, so a name with no baseinfo row
        // answers 204 even though the action row was written.
        List<Map<String, Object>> rows = PhpRows.query(jdbc,
                "select * from baseinfo b, action p where b.name = p.actionName "
                        + "and b.baseclass = 'actions' and actionName = ?",
                text(qdata, "name"));
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }
        enrich(rows);
        respond(ctx, rows, ctx.queryStringLength());
    }

    /** Adds typeExp / affectText / affectCode with the PHP's unguarded lookups. */
    private void enrich(List<Map<String, Object>> rows) {
        Map<String, String> act = new HashMap<>();
        for (Map<String, Object> ros : PhpRows.query(jdbc, "select * from actiontype")) {
            act.put(string(PhpRows.get(ros, "actionType")),
                    string(PhpRows.get(ros, "typeDescription")));
        }
        Map<String, String> binfo = new HashMap<>();
        for (Map<String, Object> ros : PhpRows.query(jdbc, "select * from baseinfo")) {
            binfo.put(string(PhpRows.get(ros, "name")),
                    string(PhpRows.get(ros, "displayKey")));
        }

        for (Map<String, Object> row : rows) {
            PhpRows.set(row, "typeExp", act.get(string(PhpRows.get(row, "actionType"))));
            PhpRows.set(row, "affectText", binfo.get(string(PhpRows.get(row, "affectedWhen"))));
            PhpRows.set(row, "affectCode", binfo.get(string(PhpRows.get(row, "affectedIf"))));
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

    private static boolean truthy(String s) {
        return s != null && !s.isEmpty() && !"0".equals(s);
    }
}
