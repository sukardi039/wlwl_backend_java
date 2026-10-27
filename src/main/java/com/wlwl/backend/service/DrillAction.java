package com.wlwl.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpJson;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.db.PhpUniqid;
import com.wlwl.backend.rpc.RpcContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of drill/action.php — the player's move. PARTIAL: only SELECT_STYLE.
 *
 * Structure of the PHP, which this mirrors:
 *   1. preamble — rewrite the client's row, load weights/params, decode content,
 *      initMemberValues, clear `touched`;
 *   2. a big `switch ($qdata->action)` with one branch per move;
 *   3. a SHARED tail — weekSummary, writeSummary, reset the action flags, advance the day,
 *      recompute the status ladder (including assigning staff to the new day's events),
 *      persist and answer.
 *
 * Every other branch returns 501 with the branch name, loudly, until it is ported.
 */
public final class DrillAction {

    private DrillAction() {
    }

    private static final DateTimeFormatter START_CLOCK = DateTimeFormatter.ofPattern("hh:mm:ssa");

    public static void run(JdbcTemplate jdbc, ObjectMapper mapper, RpcContext ctx) throws Exception {
        JsonNode qdata = qdataOf(mapper, ctx.envelope());
        String drillId = text(qdata, "drillId");
        String rawContent = text(qdata, "content");
        String action = text(qdata, "action");
        long dayNumber = (long) PhpJson.num(qdata.get("dayNumber"));
        Object stepNumber = qdata.get("stepNumber");
        String startClock = LocalTime.now().format(START_CLOCK);

        // 1. the client's copy of the row it was sitting on wins
        jdbc.update("update drilldetail set content = ?, updatedOn = now() where drillDetailId = ?",
                rawContent, text(qdata, "drillDetailId"));

        // task weights for this drill's product (only the dead `if (false)` block reads them)
        Map<String, Object> ignoredEff = new LinkedHashMap<>();
        for (Map<String, Object> r : PhpRows.query(jdbc, "select * from taskefficiency where "
                + "productName = (select modelProduct from drill where drillId = ?)", drillId)) {
            ignoredEff.put(str(PhpRows.get(r, "taskName")), r);
        }

        Map<String, Object> params = DrillCommon.readParams(jdbc);
        Object decoded = PhpJson.decodeAssoc(mapper, rawContent);
        PhpArray content = decoded instanceof PhpArray pa ? pa : new PhpArray();

        Map<String, Object> actionRow = firstRow(jdbc,
                "select * from action where actionName = ?",
                str(PhpJson.path(content, "control", "action")));

        DrillCommon.initMemberValues(content, params);

        long maxMembers = (long) num(PhpJson.path(content, "control", "maxMembers"));
        for (long c = 1; c <= maxMembers; c++) {
            PhpArray m = member(content, c);
            if (m != null) m.put("touched", 0L);
        }

        long daysNeeded = 0;
        Map<String, Object> prf = new LinkedHashMap<>();

        switch (action) {
            case "SELECT_STYLE" -> daysNeeded = selectStyle(jdbc, content, qdata, drillId, params,
                    maxMembers, dayNumber, stepNumber, action, prf);
            default -> {
                ctx.text("drill action " + action + " is not ported yet");
                ctx.status(501);
                return;
            }
        }

        // ------------------------------------------------------------- shared tail
        DrillCommon.weekSummary(content);
        DrillCommon.writeSummary(content, action, stepNumber, dayNumber);

        for (long c = 1; c <= maxMembers; c++) {
            PhpArray m = member(content, c);
            if (m == null) continue;
            m.put("actioned", 0L);
            m.put("actionExp", "");
        }

        String nextDetailId = PhpUniqid.next();
        long nextStep = (long) num(stepNumber) + 1;
        long nextDay = dayNumber + 1;
        if (daysNeeded > 1) {
            content.child("control").put("numberOfDays",
                    num(PhpJson.path(content, "control", "numberOfDays")) - (daysNeeded - 1));
        }

        content.child("control").put("welcome", 0L);
        if (num(PhpJson.path(content, "day", nextDay, "evaluateStyle")) != 0) {
            action = "SELECT_STYLE";
        } else {
            action = "MORE_ACTIONS";
            content.child("control").put("action", "");
            content.child("control").put("task", "");
        }

        // ---- target met?
        Map<String, Object> drill = firstRow(jdbc,
                "select now() as now, drill.* from drill where drillId = ?", drillId);
        double status = num(PhpJson.path(content, "control", "status"));
        if (num(PhpJson.path(content, "orderStatus", "ordersCompleted"))
                >= num(content.get("ordersToComplete"))) {
            if (status != 600) {
                content.put("celebrate", 1L);
                content.child("control").put("metTarget", nextDay + 2);
            }
            content.child("control").put("status", 600L);
            status = 600;
        }

        long stat;
        boolean finished = nextDay >= num(PhpJson.path(content, "control", "numberOfDays"))
                || DrillCommon.noMoreStaff(content);
        if (finished) {
            boolean outOfDays = nextDay >= num(PhpJson.path(content, "control", "numberOfDays"));
            stat = status == 600 ? 900 : (outOfDays ? 700 : 750);
            action = status == 600 ? "COMPLETED" : "FAILED";
            content.child("control").put("eTime", "0:00");
            Competency.save(jdbc, qdata, content);
        } else {
            stat = status == 600 ? 600 : 500;
            assignEventStaff(jdbc, content, nextDay, maxMembers);
        }

        content.child("control").put("remainingMinutes",
                (stat == 500 || stat == 600) ? remaining(drill) : "0.00");
        content.child("control").put("expireOn", rowGet(drill, "expireOn"));
        content.child("control").put("now", rowGet(drill, "now"));

        if (stat > 600) {
            jdbc.update("update drill set drillStatus = ?, endOn = now() where drillId = ?", stat, drillId);
            jdbc.update("update drillcontrol set completedDrill = "
                    + "(select seq from drill where drillId = ?) "
                    + "where drillControlId = (select drillControlId from drill where drillId = ?)",
                    drillId, drillId);
        } else {
            jdbc.update("update drill set drillStatus = ? where drillId = ?", stat, drillId);
        }
        content.child("control").put("status", stat);

        DrillCommon.dbg(content, "action", startClock + "-" + LocalTime.now().format(START_CLOCK));

        jdbc.update("insert into drilldetail (drillDetailId, drillId, stepNumber, dayNumber, "
                        + "action, createdOn, content) values (?, ?, ?, ?, ?, now(), ?)",
                nextDetailId, drillId, nextStep, nextDay, action, content.toJsonNode().toString());

        // ------------------------------------------------------------------ reply
        List<Map<String, Object>> rows = PhpRows.query(jdbc,
                "select * from drilldetail where drillId = ? order by stepNumber desc limit 1", drillId);
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }
        Map<String, Object> row = rows.get(0);
        List<Map<String, Object>> nowRows = PhpRows.query(jdbc, "select now() as now");
        PhpArray out = PhpArray.of(row);
        out.set("timeNow", nowRows.isEmpty() ? null : PhpRows.get(nowRows.get(0), "now"));
        out.set("content", PhpJson.decodeAssoc(mapper, PhpRows.get(row, "content")));
        ctx.json(out.toJsonNode());
    }

    /**
     * case "SELECT_STYLE": score every member against the leadership style they chose, then
     * let the boss react.
     *
     * `$prf` is the profile row of the LAST member processed — `cal_impacts` returns it
     * through its by-reference `&$res`, and the PHP then reuses it for `desiredStyle` and
     * `impactClass`. That is why the boss review is driven by one member's profile rather
     * than by the action.
     */
    private static long selectStyle(JdbcTemplate jdbc, PhpArray content, JsonNode qdata,
                                    String drillId, Map<String, Object> params, long maxMembers,
                                    long dayNumber, Object stepNumber, String action,
                                    Map<String, Object> prf) {
        for (long c = 1; c <= maxMembers; c++) {
            PhpArray m = member(content, c);
            if (m == null || !hasName(m.get("staffName"))) continue;

            Map<String, Object> row = DrillCommon.calImpacts(jdbc, content, (int) c,
                    str(m.get("style")), 1, 0, 0, 0);
            if (row != null) prf.putAll(row);
            DrillCommon.anaStyle(content, str(m.get("style")), rowGet(prf, "desiredStyle"));

            DrillCommon.writeHistory(content, stepNumber, dayNumber,
                    m.get("staffName"), m.get("staffExp"), action,
                    m.get("styleExp"), m.get("skillsAdded"), m.get("moraleAdded"),
                    m.get("efficiencyAdded"), m.get("message"));

            if (num(m.get("away")) > 0) m.put("away", num(m.get("away")) - 1);
            if (num(m.get("away")) < 1) m.put("awayFor", "");
            m.put("styleMatch", m.get("added"));
        }

        content.child("day").child(dayNumber).put("evaluateStyle", 0L);

        // MORE_ACTIONS || SELECT_STYLE: the boss reviews the style's impact
        Map<String, Object> reviewed = firstRow(jdbc,
                "select * from action where actionName = ?", str(rowGet(prf, "impactClass")));
        BossReview.run(jdbc, content, reviewed, action);
        return 0;                                   // daysNeeded stays 0 for this branch
    }

    /**
     * Give the new day's still-unclaimed events a target member.
     *
     * A resignCheck event is first given a chance to be called off: if the member's morale
     * now beats the criteria recorded when the event was booked (or is already 100), the
     * event is blanked out. Otherwise an unnamed event picks a member via the environment's
     * selector flags (applyAll / randomOne / lowMorale / lowEfficiency / highEfficiency).
     */
    private static void assignEventStaff(JdbcTemplate jdbc, PhpArray content, long day, long maxMembers) {
        long count = (long) num(PhpJson.path(content, "day", day, "event", "count"));
        for (long v = 0; v < count; v++) {
            PhpArray event = (PhpArray) PhpJson.path(content, "day", day, "event");
            String name = str(PhpJson.path(event, "eventName", v));
            Map<String, Object> env = firstRow(jdbc,
                    "select * from environment where environmentName = ?", name);
            Object staffName = PhpJson.path(event, "staffName", v);

            if (num(rowGet(env, "resignCheck")) > 0 && truthy(staffName)) {
                int where = DrillCommon.memberWhere(content, staffName);
                if (where > 0 && (num(PhpJson.path(event, "criteria", v))
                        < num(PhpJson.path(content, "members", (long) where, "moraleValue"))
                        || num(PhpJson.path(content, "members", (long) where, "moraleValue")) == 100)) {
                    for (String key : new String[]{"description", "eventName", "eventExp",
                            "staffName", "staffExp", "criteria"}) {
                        PhpArray arr = mutableChild(event, key);
                        arr.put(v, "");
                    }
                }
                continue;
            }
            if (truthy(staffName)) continue;

            Object pickedName = "";
            Object pickedExp = "";
            if (num(rowGet(env, "applyAll")) == 0) {
                DrillCommon.Pick pick = null;
                if (num(rowGet(env, "randomOne")) > 0) {
                    pick = DrillCommon.anyOne(content, java.util.concurrent.ThreadLocalRandom.current());
                } else if (num(rowGet(env, "lowMorale")) > 0) {
                    pick = DrillCommon.lowMorale(content);
                } else if (num(rowGet(env, "lowEfficiency")) > 0) {
                    pick = DrillCommon.lowEfficiency(content);
                } else if (num(rowGet(env, "highEfficiency")) > 0) {
                    pick = DrillCommon.highEfficiency(content);
                }
                if (pick != null) {
                    pickedName = pick.name();
                    pickedExp = pick.exp();
                }
            }
            mutableChild(event, "staffName").put(v, pickedName);
            mutableChild(event, "staffExp").put(v, pickedExp);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String remaining(Map<String, Object> drill) {
        try {
            LocalDateTime now = parse(rowGet(drill, "now"));
            LocalDateTime expire = parse(rowGet(drill, "expireOn"));
            if (now == null || expire == null) return "0:00";
            Duration d = Duration.between(now, expire).abs();
            return (d.toHoursPart() * 60 + d.toMinutesPart()) + ":"
                    + String.format("%02d", d.toSecondsPart());
        } catch (RuntimeException e) {
            return "0:00";
        }
    }

    private static final DateTimeFormatter SQL_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static LocalDateTime parse(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        if (s.length() > 19) s = s.substring(0, 19);
        return LocalDateTime.parse(s, SQL_DATETIME);
    }

    private static PhpArray mutableChild(PhpArray parent, String key) {
        Object cur = parent.get(key);
        if (cur instanceof PhpArray a) return a;
        PhpArray created = new PhpArray();
        if (cur instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) created.put((long) i, l.get(i));
        }
        parent.put(key, created);
        return created;
    }

    private static JsonNode qdataOf(ObjectMapper mapper, JsonNode envelope) {
        JsonNode qdata = envelope == null ? null : envelope.get("qdata");
        return qdata == null || qdata.isNull() ? mapper.createObjectNode() : qdata;
    }

    private static PhpArray member(PhpArray content, long c) {
        Object v = PhpJson.path(content, "members", c);
        return v instanceof PhpArray a ? a : null;
    }

    private static Map<String, Object> firstRow(JdbcTemplate jdbc, String sql, Object... args) {
        List<Map<String, Object>> rows = PhpRows.query(jdbc, sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Object rowGet(Map<String, Object> row, String column) {
        return row == null ? null : PhpRows.get(row, column);
    }

    private static boolean hasName(Object staffName) {
        if (staffName == null) return false;
        String s = staffName.toString();
        return !s.isEmpty() && !"0".equals(s);
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        String s = v.toString();
        return !s.isEmpty() && !"0".equals(s);
    }

    private static double num(Object v) {
        return PhpJson.num(v);
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }
}
