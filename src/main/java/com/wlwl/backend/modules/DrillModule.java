package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpJson;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.db.PhpUniqid;
import com.wlwl.backend.rpc.ModuleHandler;
import com.wlwl.backend.rpc.RpcContext;
import com.wlwl.backend.service.Competency;
import com.wlwl.backend.service.DrillInit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Port of drill/ — the exercise engine.
 *
 * drill/index.php is just include(restdx.inc) and drill/put.php dispatches on the envelope's
 * `mod`, so this is ONE module with an internal switch:
 *
 *   INIT | ACTION | ENVIRONMENT | RESET | TIMEOUT | UPDATENAME   -> include the matching file
 *   anything else                                                -> echoes "No Mod"
 *   falsy                                                        -> emits nothing at all
 *
 * Currently ported: TIMEOUT. The other five return 501 with the mod name in the body -
 * deliberately loud, because a silent "No Mod" or an empty 200 would make an unported
 * branch look like a working one.
 *
 * TIMEOUT (drill/timeout.php, 29 lines) is client-triggered: the browser notices expiry and
 * submits. It refuses to write if the last row already ended the drill, but still returns
 * that row with 200, so a polling client gets the same answer forever.
 */
@Component
public class DrillModule implements ModuleHandler {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DrillModule(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String module() {
        return "drill";
    }

    @Override
    public void onPut(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String mod = text(env, "mod");
        if (mod.isEmpty() || "0".equals(mod)) {
            return;                       // PHP: if ($mod) { ... } -- falsy emits nothing
        }
        JsonNode qdata = env.get("qdata");
        if (qdata == null || qdata.isNull()) qdata = mapper.createObjectNode();

        switch (mod) {
            case "INIT" -> DrillInit.run(jdbc, mapper, ctx);
            case "TIMEOUT" -> timeout(ctx, qdata);
            case "RESET" -> reset(ctx, qdata);
            case "UPDATENAME" -> updateName(ctx, qdata);
            case "ACTION", "ENVIRONMENT" -> {
                // body first: ctx.text() resets the status to 200
                ctx.text("drill mod " + mod + " is not ported yet");
                ctx.status(501);
            }
            default -> ctx.text("No Mod");
        }
    }

    /** drill/timeout.php */
    private void timeout(RpcContext ctx, JsonNode qdata) throws Exception {
        String drillId = text(qdata, "drillId");

        Map<String, Object> row = lastRow(drillId);
        String lastAction = row == null ? null : str(row.get("action"));

        boolean alreadyFinished = "TIME_OUT".equals(lastAction)
                || "COMPLETED".equals(lastAction) || "FAILED".equals(lastAction);

        if (!alreadyFinished) {
            // stamp the row the CLIENT was sitting on (may differ from the row just read)
            jdbc.update("update drilldetail set updatedOn = now() where drillDetailId = ?",
                    text(qdata, "drillDetailId"));

            // the new snapshot is built from the client's content, not from the database
            PhpArray content = (PhpArray) PhpJson.decodeAssoc(mapper, text(qdata, "content"));

            long stepNumber = (long) PhpJson.num(qdata.get("stepNumber")) + 1;   // from the client
            double status = PhpJson.num(PhpJson.path(content, "control", "status"));

            int stat = status == 600 ? 900 : 800;
            String action = status == 600 ? "COMPLETED" : "TIME_OUT";

            if (stat > 600) {
                jdbc.update("update drill set drillStatus = ?, endOn = now() where drillId = ?",
                        stat, drillId);
            } else {
                jdbc.update("update drill set drillStatus = ? where drillId = ?", stat, drillId);
            }
            if (stat > 500) {
                jdbc.update("update drillcontrol set completedDrill = "
                                + "(select seq from drill where drillId = ?) "
                                + "where drillControlId = (select drillControlId from drill where drillId = ?)",
                        drillId, drillId);
            }

            content.child("control").put("status", stat);
            Competency.save(jdbc, qdata, content);

            jdbc.update("insert into drilldetail (drillDetailId, drillId, stepNumber, dayNumber, "
                            + "action, createdOn, content) values (?, ?, ?, ?, ?, now(), ?)",
                    PhpUniqid.next(), drillId, stepNumber,
                    (long) PhpJson.num(qdata.get("dayNumber")), action,
                    content.toJsonNode().toString());
        }

        // Reply: the LAST row as an object. Note this endpoint adds no timeNow.
        Map<String, Object> last = lastRow(drillId);
        if (last == null) {
            ctx.noContent();
            return;
        }
        PhpRows.set(last, "content", PhpJson.decodeAssoc(mapper, last.get("content")));
        ctx.json(PhpArray.of(last).toJsonNode());
    }

    /** drill/reset.php (18 lines) — delete a drillControl, relying on FK cascade. */
    private void reset(RpcContext ctx, JsonNode qdata) throws Exception {
        String drillControlId = text(qdata, "drillControlId");
        if (drillControlId.isEmpty() || "0".equals(drillControlId)) {
            return;                       // PHP: if ($qdata->drillControlId) { ... }
        }
        jdbc.update("delete from drillcontrol where drillControlId = ?", drillControlId);
        List<Map<String, Object>> left = PhpRows.query(jdbc,
                "select * from drillcontrol where drillControlId = ?", drillControlId);
        if (!left.isEmpty()) {
            // The PHP echoes a hand-written string with SINGLE quotes, so the body is not
            // valid JSON: {'status':'notdone'}. Passed through byte-for-byte on purpose.
            ctx.rawJson(200, "{'status':'notdone'}");
        } else {
            ctx.noContent();
        }
    }

    /** drill/updatename.php (23 lines) — rename a user, then return the fresh row. */
    private void updateName(RpcContext ctx, JsonNode qdata) throws Exception {
        jdbc.update("update backenduser set userName = ? where userId = ?",
                text(qdata, "userName"), text(qdata, "userId"));
        List<Map<String, Object>> rows = PhpRows.query(jdbc,
                "select * from backenduser where userId = ?", text(qdata, "userId"));
        if (rows.isEmpty()) {
            return;                       // PHP emits nothing when the user is gone
        }
        if (ctx.queryStringLength() == 0) {
            PhpArray list = new PhpArray();
            for (Map<String, Object> row : rows) list.append(PhpArray.of(row));
            ctx.json(list.toJsonNode());
        } else {
            ctx.json(PhpArray.of(rows.get(rows.size() - 1)).toJsonNode());
        }
    }

    private Map<String, Object> lastRow(String drillId) {
        List<Map<String, Object>> rows = PhpRows.query(jdbc,
                "select * from drilldetail where drillId = ? order by stepNumber desc limit 1",
                drillId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
