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

        // task weights for this drill's product: $eff[taskName] = row. Only the PHP's dead
        // `if (false)` block reads the weights, but action.php's withTask branch does use
        // the map's existence, and recal_eff() takes it — so it is loaded properly.
        Map<String, Map<String, Object>> eff = new LinkedHashMap<>();
        for (Map<String, Object> r : PhpRows.query(jdbc, "select * from taskefficiency where "
                + "productName = (select modelProduct from drill where drillId = ?)", drillId)) {
            eff.put(str(PhpRows.get(r, "taskName")), r);
        }

        Map<String, Object> params = DrillCommon.readParams(jdbc);
        Object decoded = PhpJson.decodeAssoc(mapper, rawContent);
        PhpArray content = decoded instanceof PhpArray pa ? pa : new PhpArray();

        Map<String, Object> actionRow = firstRow(jdbc,
                "select * from action where actionName = ?",
                str(PhpJson.path(content, "control", "action")));
        // PHP keeps one `$action` variable for the leader's action AND then overwrites it
        // with each member's action row inside the MORE_ACTIONS loop. Everything after the
        // loop therefore sees the LAST member's row. A slot models that shadowing.
        ActionSlot slot = new ActionSlot(actionRow);

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
            case "MORE_ACTIONS" -> {
                // pass 1 and 2 of §29 run before the per-member loop, exactly like the PHP
                applyRecruits(jdbc, content, params, actionRow, maxMembers);
                applySwapTask(content, actionRow, maxMembers);
                // pass 3: the per-member impact loop. It OVERWRITES $action on every
                // iteration (the PHP reuses one variable), so the row that comes out is
                // the LAST acted member's action — not the leader's. Passes 4-5 depend on
                // that, so the slot is threaded through rather than reloaded.
                slot.row = actionRow;
                daysNeeded = applyMemberImpacts(jdbc, content, params, eff, slot,
                        maxMembers, dayNumber, stepNumber, action, prf);
                // pass 4: boss review, sack clean-up, emotion, orders, manpower
                afterMemberImpacts(jdbc, content, params, eff, slot, maxMembers,
                        dayNumber, stepNumber, action, prf);
            }
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



    /**
     * case "MORE_ACTIONS", the `if ($action['swapTask'])` block: pairwise task swapping
     * between the members who acted.
     *
     * The first acting member's task is remembered, then for every FURTHER acting member the
     * two swap: their `action` fields are temporarily set to `'evaluate-' . otherTask` (that
     * is the string cal_swap parses — the 9-char prefix), cal_swap runs on both with type
     * `swap-task`, the original `action` strings are restored, the tasks are exchanged, and
     * any order either was working on is nullified (`state = -1`, `workingOn = 0`).
     *
     * Note the `actioned` check has no `staffName` guard in the PHP: an empty slot with
     * `actioned` set would be swapped too.
     */
    static void applySwapTask(PhpArray content, Map<String, Object> actionRow, long maxMembers) {
        if (!truthy(rowGet(actionRow, "swapTask"))) return;

        long first = 0;
        Object firstTask = null;
        Object firstTaskExp = null;

        for (long c = 1; c <= maxMembers; c++) {
            PhpArray m = member(content, c);
            if (m == null || !truthy(m.get("actioned"))) continue;

            if (first == 0) {
                first = c;
                firstTask = m.get("task");
                firstTaskExp = m.get("taskExp");
                continue;
            }

            PhpArray m1 = member(content, first);
            Object keptAction1 = m1.get("action");
            Object keptAction2 = m.get("action");
            m1.put("action", "evaluate-" + str(m.get("task")));
            m.put("action", "evaluate-" + str(m1.get("task")));

            DrillCommon.calSwap(content, (int) first, "swap-task");
            DrillCommon.calSwap(content, (int) c, "swap-task");

            m1.put("action", keptAction1);
            m.put("action", keptAction2);

            Object task2 = m.get("task");
            Object taskExp2 = m.get("taskExp");
            m1.put("task", task2);
            m1.put("taskExp", taskExp2);
            m.put("task", firstTask);
            m.put("taskExp", firstTaskExp);

            releaseOrder(content, m1);
            releaseOrder(content, m);
        }
    }

    /** workingOn = 0, and the order it pointed at is nullified. */
    private static void releaseOrder(PhpArray content, PhpArray m) {
        long workingOn = (long) num(m.get("workingOn"));
        if (workingOn > 0) {
            Object o = PhpJson.path(content, "orders", workingOn);
            if (o instanceof PhpArray order) order.put("state", -1L);
        }
        m.put("workingOn", 0L);
    }

    /**
     * case "MORE_ACTIONS", the block guarded by `$action['actionType'] == 'recruits'`:
     * move each actioned recruit into the first free slot that wants the action's task.
     *
     * A recruit is dropped into a slot when the slot is EMPTY and its task matches
     * `control.task` (or the slot has no task at all). Stats are rolled from
     * `staffprofile_view`; the new hire gets `params_int.default_style` as its leadership
     * style, full tolerances, and an entry appended to `content.staffs` so later steps see
     * them as staff. The recruit row itself is blanked. Ten recruit rows are scanned
     * unconditionally (the PHP's `for ($r = 0; $r < 10; $r++)`), and only ONE slot is filled
     * per recruit (`break`).
     */
    static void applyRecruits(JdbcTemplate jdbc, PhpArray content, Map<String, Object> params,
                              Map<String, Object> actionRow, long maxMembers) {
        if (!"recruits".equals(str(rowGet(actionRow, "actionType")))) return;

        Object defaultStyle = params.get("default_style");
        for (long r = 0; r < 10; r++) {
            PhpArray recruit = (PhpArray) PhpJson.path(content, "recruits", r);
            if (recruit == null || !truthy(recruit.get("actioned"))) continue;

            for (long c = 1; c <= maxMembers; c++) {
                PhpArray m = member(content, c);
                if (m == null || truthy(m.get("staffName"))) continue;
                Object slotTask = m.get("task");
                Object actionTask = PhpJson.path(content, "control", "task");
                if (!eq(slotTask, actionTask) && !str(slotTask).isEmpty()) continue;

                Map<String, Object> stf = firstRow(jdbc,
                        "select * from staffprofile_view where staffId = ?",
                        str(recruit.get("staffName")));
                Map<String, Object> styleRow = firstRow(jdbc,
                        "select displayKey from baseinfo where name = ?", str(defaultStyle));

                // rand(min,max) over a missing view row is rand(0,0) = 0, exactly like PHP
                m.put("staffName", recruit.get("staffName"));
                m.put("task", actionTask);
                m.put("staffExp", recruit.get("staffExp"));
                m.put("taskExp", PhpJson.path(content, "control", "taskExp"));
                m.put("introduced", 1L);
                m.put("skillsValue", rand(numLong(rowGet(stf, "skillsMin")), numLong(rowGet(stf, "skillsMax"))));
                m.put("moraleValue", rand(numLong(rowGet(stf, "moraleMin")), numLong(rowGet(stf, "moraleMax"))));
                m.put("efficiencyValue", rand(numLong(rowGet(stf, "efficiencyMin")), numLong(rowGet(stf, "efficiencyMax"))));
                m.put("offdays", 0L);
                m.put("style", defaultStyle);
                m.put("styleExp", rowGet(styleRow, "displayKey"));
                m.put("away", 0L);
                m.put("attention", params.get("dejected_tolerance_days"));
                m.put("attenWarning", params.get("dejected_warning_days"));
                m.put("listen", params.get("ignored_tolerance_days"));
                m.put("trend", 0L);

                DrillCommon.initStaffAna(jdbc, content, m.get("staffName"), m.get("staffExp"));

                // the recruit joins content.staffs, so later steps treat them as staff
                Object ss = content.get("staffs");
                PhpArray staffs = mutableChild(content, "staffs");
                PhpArray entry = staffs.child((long) seqSize(ss));
                entry.put("staffName", m.get("staffName"));
                entry.put("staffExp", m.get("staffExp"));
                entry.put("task", m.get("task"));
                entry.put("taskExp", m.get("taskExp"));

                m.put("initSkillsValue", m.get("skillsValue"));
                m.put("initMoraleValue", m.get("moraleValue"));
                m.put("initEfficiencyValue", m.get("efficiencyValue"));

                recruit.put("staffName", "");
                recruit.put("staffExp", "");
                recruit.put("actioned", 0L);
                break;                          // one slot per recruit
            }
        }
    }

    /**
     * case "MORE_ACTIONS", pass 4: everything the drill does to the world after the
     * members' own impacts have been applied — the boss's reaction, sack clean-up, the
     * emotion pass, day-away decay, order assignment/progress/status, and shorthanded
     * detection.
     *
     * <p>Traps reproduced here:
     *
     * <ul>
     *   <li><b>The boss-review guard reads the SHADOWED {@code $action}</b> — the last
     *       acted member's action row, not the leader's. It is also inside a condition
     *       that is always true in this branch
     *       ({@code $qdata->action == 'MORE_ACTIONS' || ...}), so the only real test is
     *       {@code !$action['noImpact']}.</li>
     *   <li><b>{@code $contentr}</b> (sic) — the order-initialisation line writes
     *       {@code assignedTo} into a NEW variable instead of {@code $content}, so a
     *       fresh order never gets {@code assignedTo = ''}. Preserved by doing nothing:
     *       the variable is discarded, so the only visible effect is that
     *       {@code orders[d].state = 0} is the sole initialisation.</li>
     *   <li><b>{@code $short} is one variable reused for every task</b> and reset inside
     *       the {@code while} that loads the task list, so it ends up as the count of
     *       short-handed tasks accumulated by the {@code foreach}.</li>
     *   <li>The emotion checks other than "resigned" are NOT guarded by {@code staffName}.</li>
     * </ul>
     */
    private static void afterMemberImpacts(JdbcTemplate jdbc, PhpArray content,
                                           Map<String, Object> params,
                                           Map<String, Map<String, Object>> eff,
                                           ActionSlot slot, long maxMembers, long dayNumber,
                                           Object stepNumber, String qdataAction,
                                           Map<String, Object> prf) {
        // ---- the boss reacts to the action profile ($prf is cal_impacts' last &$res)
        if (num(rowGet(slot.row, "noImpact")) == 0) {          // !$action['noImpact']
            Map<String, Object> action1 = firstRow(jdbc,
                    "select * from action where actionName = ?",
                    str(rowGet(prf, "impactClass")));
            BossReview.run(jdbc, content, action1, qdataAction);
        }

        // ---- sacking removes the member and starts a recruiting embargo
        if ("sack".equals(str(PhpJson.path(content, "control", "action")))) {
            for (long c = 1; c <= maxMembers; c++) {
                PhpArray m = member(content, c);
                if (m == null || !hasName(m.get("staffName")) || !truthy(m.get("actioned"))) continue;

                m.put("staffName", "");
                m.put("staffExp", "");
                m.put("actioned", 0L);
                Object workingOn = m.get("workingOn");
                if (num(workingOn) > 0) {
                    content.child("orders").child(PhpArray.key(workingOn)).put("state", -1L);
                }
                m.put("listen", params.get("ignored_tolerance_days"));
                m.put("attention", params.get("dejected_tolerance_days"));
                m.put("attenWarning", params.get("dejected_warning_days"));
                m.put("msgCount", 0L);
                m.put("message", new PhpArray());
                m.put("workingOn", 0L);
                m.put("evaluated", 0L);
                m.put("evalResult", "");
                m.put("added", 0L);
                m.put("addedAvg", 0L);
                m.put("away", PhpJson.jsonNumber(PhpJson.round(rand(
                        numLong(params.get("sack_embargo_min_days")),
                        numLong(params.get("sack_embargo_max_days"))))));
                m.put("touched", 0L);
            }
        }

        // ---- emotion: resignation, being ignored, dejection, sick leave
        long ignoredCount = 0;
        long dejectedCount = 0;
        for (long c = 1; c <= maxMembers; c++) {
            PhpArray m = member(content, c);
            if (m == null) continue;

            if (hasName(m.get("staffName")) && num(m.get("maxMorale")) > 5
                    && num(m.get("moraleValue")) < 5) {
                DrillCommon.setEnvironment(jdbc, content, "resigned", (int) c, dayNumber);
            }

            if (num(m.get("listen")) < 0) {
                String emoExp = displayKey(jdbc, "ignored");
                DrillCommon.calImpacts(jdbc, content, (int) c, "ignored", 1, 0, 0, 0);
                DrillCommon.writeHistory(content, stepNumber, dayNumber, m.get("staffName"),
                        m.get("staffExp"), qdataAction, emoExp,
                        m.get("skillsAdded"), m.get("moraleAdded"), m.get("efficiencyAdded"),
                        mutableChild(m, "message").get(PhpArray.key(m.get("msgCount"))));
                appendActionExp(m, emoExp);
                m.put("listen", params.get("ignored_tolerance_days"));
                ignoredCount++;
                m.put("emotion", 1L);
            }

            if (num(m.get("attenWarning")) < 0) {
                String emoExp = displayKey(jdbc, "dejected");
                DrillCommon.calImpacts(jdbc, content, (int) c, "dejected", 1, 0, 0, 0);
                DrillCommon.writeHistory(content, stepNumber, dayNumber, m.get("staffName"),
                        m.get("staffExp"), qdataAction, emoExp,
                        m.get("skillsAdded"), m.get("moraleAdded"), m.get("efficiencyAdded"),
                        mutableChild(m, "message").get(PhpArray.key(m.get("msgCount"))));
                appendActionExp(m, emoExp);
                m.put("attenWarning", params.get("dejected_warning_days"));
                dejectedCount++;
                m.put("emotion", 1L);
            }

            if (num(m.get("attention")) < 0) {
                DrillCommon.setEnvironment(jdbc, content, "sick-leave", (int) c, dayNumber);
                m.put("attention", params.get("dejected_tolerance_days"));
                m.put("attenWarning", params.get("dejected_warning_days"));
            }
        }
        if (ignoredCount > 0) {
            PhpArray bossMsg = content.child("bossMsg");
            mutableChild(bossMsg, "description").put(PhpArray.key(bossMsg.get("count")), "mustReadMsg");
            bossMsg.put("count", num(bossMsg.get("count")) + 1);
        }
        if (dejectedCount > 0) {
            PhpArray bossMsg = content.child("bossMsg");
            mutableChild(bossMsg, "description").put(PhpArray.key(bossMsg.get("count")), "mustAttend");
            bossMsg.put("count", num(bossMsg.get("count")) + 1);
        }

        // ---- the embargo ticks down for everybody
        for (long c = 1; c <= maxMembers; c++) {
            PhpArray m = member(content, c);
            if (m != null && num(m.get("away")) > 0) m.put("away", num(m.get("away")) - 1);
        }

        // ---- available work, and progress on whatever each member already holds
        for (long c = 1; c <= maxMembers; c++) {
            PhpArray m = member(content, c);
            if (m == null || !hasName(m.get("staffName"))) continue;

            if (num(m.get("away")) < 1) {
                m.put("workAvailable", round2(num(m.get("efficiencyValue")) / 100));
            } else {
                m.put("workAvailable", 0L);
            }
            if (truthy(m.get("workingOn"))) {
                Object d = m.get("workingOn");
                Object state = PhpJson.path(content, "orders", d, "state");
                DrillCommon.calWorks(content, (int) c, d, dayNumber, (long) num(state), params);
            }
        }

        // ---- hand out the order list, stage by stage
        long totalOrders = (long) num(PhpJson.path(content, "control", "totalOrders"));
        for (long t = 1; t < 6; t++) {
            for (long c = 1; c <= maxMembers; c++) {
                PhpArray m = member(content, c);
                if (m == null) continue;
                if (!taskStageMatches(m.get("task"), t) || num(m.get("away")) >= 1) continue;

                PhpArray orders = content.child("orders");
                for (long d = 1; d < totalOrders
                        && num(PhpJson.path(orders, d, "day")) <= dayNumber; d++) {
                    PhpArray order = (PhpArray) PhpJson.path(orders, d);
                    if (order == null) continue;
                    if (!order.containsKey(PhpArray.key("state"))) {
                        // PHP also writes state into a NEW variable here ($contentr), so only
                        // state is initialised on the real order.
                        order.put("state", 0L);
                    }
                    if (num(order.get("state")) == (t - 1)
                            && eq(order.get("assignedTo"), "")
                            && num(m.get("workAvailable")) > 0
                            && num(m.get("workingOn")) == 0) {
                        Object taskName = m.get("task");
                        double taskDuration = num(rowGet(eff.get(str(taskName)), "duration"));
                        order.put("state", t);
                        order.put("workNeeded", PhpJson.jsonNumber(taskDuration));
                        order.put("assignedTo", m.get("staffName"));
                        PhpArray log = mutableChild(order, "log").child(PhpArray.key(t));
                        log.put("assignedTo", m.get("staffName"));
                        log.put("assignedDay", dayNumber);
                        log.put("workDuration", PhpJson.jsonNumber(taskDuration));
                        m.put("workingOn", d);
                        DrillCommon.calWorks(content, (int) c, d, dayNumber, t, params);
                    }
                }
            }
        }

        // ---- order status overview
        PhpArray orderStatus = content.child("orderStatus");
        for (long t = 1; t < 6; t++) {
            PhpArray cell = mutableChild(orderStatus, String.valueOf(t));
            cell.put("state", t);
            cell.put("waitAssignment", 0L);
            cell.put("inProgress", 0L);
            cell.put("done", 0L);
        }
        orderStatus.put("ordersCompleted", 0L);
        PhpArray orders = content.child("orders");
        for (long d = 1; d < totalOrders && num(PhpJson.path(orders, d, "day")) <= dayNumber; d++) {
            PhpArray order = (PhpArray) PhpJson.path(orders, d);
            if (order == null) continue;
            double state = num(order.get("state"));
            double workNeeded = num(order.get("workNeeded"));
            Object assignedTo = order.get("assignedTo");
            Object log = order.get("log");

            if (state == 5 && workNeeded == 0) {
                orderStatus.put("ordersCompleted", num(orderStatus.get("ordersCompleted")) + 1);
                for (long t = 1; t < 6; t++) {
                    PhpArray cell = mutableChild(orderStatus, String.valueOf(t));
                    cell.put("done", num(cell.get("done")) + 1);
                }
            } else {
                if (state >= 0) {
                    if (eq(assignedTo, "") && workNeeded == 0) {
                        PhpArray cell = mutableChild(orderStatus, String.valueOf((long) state + 1));
                        cell.put("state", (long) state + 1);
                        cell.put("waitAssignment", num(cell.get("waitAssignment")) + 1);
                    } else if (!eq(assignedTo, "") && workNeeded > 0) {
                        PhpArray cell = mutableChild(orderStatus, String.valueOf((long) state));
                        cell.put("state", state);
                        cell.put("inProgress", num(cell.get("inProgress")) + 1);
                    }
                    for (long t = 1; t < 6; t++) {
                        if ((state > t) || (state == t && eq(assignedTo, "") && workNeeded == 0)) {
                            PhpArray cell = mutableChild(orderStatus, String.valueOf(t));
                            cell.put("done", num(cell.get("done")) + 1);
                        }
                    }
                }
                if (state == -1) {
                    long logSize = seqSize(log);
                    for (long t = 1; t < logSize; t++) {
                        PhpArray cell = mutableChild(orderStatus, String.valueOf(t));
                        cell.put("done", num(cell.get("done")) + 1);
                    }
                }
            }
        }

        // ---- shorthanded detection
        List<String> taskNames = new java.util.ArrayList<>();
        Map<String, Object> taskStage = new LinkedHashMap<>();
        PhpArray manpower = content.child("manpower");
        long shortTasks = 0;                                   // $short, reset per task row
        for (Map<String, Object> tt : PhpRows.query(jdbc, "select * from task")) {
            Object taskName = PhpRows.get(tt, "taskName");
            taskNames.add(str(taskName));
            taskStage.put(str(taskName), PhpRows.get(tt, "stage"));
            mutableChild(manpower, str(taskName)).put("mbr", 0L);
            shortTasks = 0;                                    // yes, inside the loop
        }
        PhpArray manpowerTeam = content.child("manpowerTeam");
        manpowerTeam.put("mbr", 0L);
        for (long c = 1; c <= maxMembers; c++) {
            PhpArray m = member(content, c);
            if (m == null || !hasName(m.get("staffName")) || num(m.get("away")) >= 1) continue;
            PhpArray taskCell = mutableChild(manpower, str(m.get("task")));
            taskCell.put("mbr", num(taskCell.get("mbr")) + 1);
            manpowerTeam.put("mbr", num(manpowerTeam.get("mbr")) + 1);
        }
        for (String t : taskNames) {
            PhpArray cell = mutableChild(manpower, t);
            Object stage = taskStage.get(t);
            double waitAssignment = num(PhpJson.path(orderStatus, stage, "waitAssignment"));
            if (num(cell.get("mbr")) < num(cell.get("pos")) && waitAssignment > 0) {
                cell.put("short", num(cell.get("short")) + 1);
                shortTasks++;
                if (num(cell.get("short")) > num(params.get("staff_shorthanded_tolerance_days"))) {
                    for (long c = 1; c <= maxMembers; c++) {
                        PhpArray m = member(content, c);
                        if (m == null || !hasName(m.get("staffName"))) continue;
                        if (eq(m.get("task"), t) && num(m.get("away")) < 1) {
                            DrillCommon.setEnvironment(jdbc, content, "shorthanded-task",
                                    (int) c, dayNumber);
                        }
                    }
                    cell.put("short", params.get("staff_shorthanded_reset_days"));
                }
            } else {
                cell.put("short", 0L);
            }
        }
        if (num(manpowerTeam.get("mbr")) < num(manpowerTeam.get("pos")) && shortTasks != 0) {
            manpowerTeam.put("short", num(manpowerTeam.get("short")) + 1);
            if (num(manpowerTeam.get("short")) > num(params.get("team_shorthanded_tolerance_days"))) {
                DrillCommon.setEnvironment(jdbc, content, "shorthanded-team", 0, dayNumber);
                manpowerTeam.put("short", params.get("team_shorthanded_reset_days"));
            }
        } else {
            manpowerTeam.put("short", 0L);
        }
    }

    /** baseinfo.displayKey for an environment/emotion name, as the PHP reads it. */
    private static String displayKey(JdbcTemplate jdbc, String name) {
        Map<String, Object> row = firstRow(jdbc,
                "select * from baseinfo where name = ?", name);
        return str(rowGet(row, "displayKey"));
    }

    /** {@code actionExp} accumulates as "A/B"; an empty value does not get a leading slash. */
    private static void appendActionExp(PhpArray m, String emoExp) {
        String current = str(m.get("actionExp"));
        m.put("actionExp", current.isEmpty() ? emoExp : current + "/" + emoExp);
    }

    /** {@code substr($task, 0, 1) == $t} — only a numeric first character can match. */
    private static boolean taskStageMatches(Object task, long t) {
        String s = str(task);
        return !s.isEmpty() && s.charAt(0) == (char) ('0' + t) && t >= 0 && t <= 9;
    }

    /** PHP {@code round($v, 2)}. */
    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * PHP's {@code $action}: one variable reused for the leader's action row and then for
     * every member's action row inside the MORE_ACTIONS loop.
     *
     * <p>The reuse is not cosmetic. Everything after the loop reads {@code $action} — the
     * boss-review guard {@code if (!$action['noImpact'])}, and the sack/emotion/order
     * passes — so they see the LAST ACTED MEMBER's action, not the leader's. Passes 4-5
     * must read {@link #row}, never the row that was loaded before the loop.
     */
    static final class ActionSlot {
        Map<String, Object> row;

        ActionSlot(Map<String, Object> row) {
            this.row = row;
        }
    }

    /**
     * case "MORE_ACTIONS", pass 3 (§29): "work on the impact of leader action for each
     * member acted upon".
     *
     * <p>Five traps live in these 250 PHP lines, all reproduced:
     *
     * <ol>
     *   <li><b>{@code $action} is shadowed</b> per member (see {@link ActionSlot}).</li>
     *   <li><b>The guard is misspelled</b>: {@code if (!$action['noInmpact'])} (note the
     *       "m"), so the array key never exists, {@code !null} is true, and cal_impacts
     *       ALWAYS runs. The correctly-spelled {@code noImpact} is checked only after the
     *       loop. Reproducing the typo is what keeps this branch reachable.</li>
     *   <li><b>{@code $cont = $content} is a COPY.</b> cal_impacts runs on the copy, and
     *       if the forced amounts apply it is re-run on the ORIGINAL content from its
     *       pre-action state; otherwise the copy simply replaces the original. Java needed
     *       {@link PhpArray#copy()} for this.</li>
     *   <li><b>{@code $efficiencyFRorced}</b> (capital R) is a different, undefined
     *       variable than {@code $efficiencyForced}: PHP reads null, so the knock-on
     *       cal_affect always gets 0.</li>
     *   <li><b>teamLunch shares teamBuild's enhance branch.</b> The teamLunch-specific
     *       code is commented out, so a teamLunch action is scaled by
     *       {@code teamBuild_low_impact}, not by any teamLunch parameter.</li>
     * </ol>
     *
     * @return the branch's {@code $daysNeeded} (max of the acted actions' durations)
     */
    private static long applyMemberImpacts(JdbcTemplate jdbc, PhpArray content,
                                           Map<String, Object> params,
                                           Map<String, Map<String, Object>> eff,
                                           ActionSlot slot, long maxMembers, long dayNumber,
                                           Object stepNumber, String qdataAction,
                                           Map<String, Object> prf) {
        long daysNeeded = 0;
        // $enhance is declared once before the loop and only reassigned by teamBuild /
        // teamLunch; it is NOT reset per member.
        double enhance = 1;

        PhpArray usedActions = mutableChild(content.child("used"), "actions");
        Object leaderParent = rowGet(slot.row, "parentAction");
        Object ledgerKey = PhpArray.key(leaderParent);
        usedActions.put(ledgerKey, num(usedActions.get(ledgerKey)) + 1);   // PHP: ++ on null = 1

        for (long c = 1; c <= maxMembers; c++) {
            PhpArray m = member(content, c);
            if (m == null || !hasName(m.get("staffName"))) continue;

            if (!truthy(m.get("actioned"))) {
                // PHP: attention/attenWarning decrement for members who did NOT act
                m.put("attention", num(m.get("attention")) - 1);
                m.put("attenWarning", num(m.get("attenWarning")) - 1);
                m.put("offdays", 0L);
                continue;
            }

            double skillsForced = 0;
            double moraleForced = 0;
            double efficiencyForced = 0;

            String memberAction = str(m.get("action"));
            Map<String, Object> act = firstRow(jdbc,
                    "select * from action where actionName = ?", memberAction);
            slot.row = act;                                   // PHP: $action = fetch_assoc()
            Object parentAction = rowGet(act, "parentAction");
            Object comboObj = rowGet(act, "combo");
            String combo = str(comboObj);

            String nve = "";
            boolean teamOverUsed = false;
            if (num(rowGet(act, "checkTrend")) > 0 && num(m.get("trendLast")) > num(m.get("trend"))) {
                nve = "-ve";
            }
            if (num(usedActions.get(PhpArray.key(parentAction))) > num(rowGet(act, "maximumUse"))) {
                if (!"teamBuild".equals(combo) && !"teamLunch".equals(combo)) {
                    nve = "-ve";
                } else {
                    teamOverUsed = true;
                }
            }

            // ---- combo charges, gated on a previous action still being in effect
            PhpArray effect = mutableChild(m, "effect");
            if (num(effect.get(PhpArray.key(comboObj))) >= dayNumber) {
                switch (combo) {
                    case "training" -> {
                        if (num(effect.get(PhpArray.key("set-goal"))) < dayNumber) {
                            skillsForced = num(params.get("combo_training_skillsforced"));
                        }
                        moraleForced = num(params.get("combo_training_moraleforced"));
                    }
                    case "set-goal" -> {
                        // note the asymmetry: `training` must still be ACTIVE (>=) while
                        // `send-email-praise` must have EXPIRED (<)
                        if (num(effect.get(PhpArray.key("training"))) >= dayNumber
                                && num(effect.get(PhpArray.key("send-email-praise"))) < dayNumber) {
                            skillsForced = num(params.get("combo_set-goal_skillsforced"));
                            moraleForced = num(params.get("combo_set-goal_moraleforced"));
                        }
                    }
                    default -> { }
                }
            }

            // ---- event combos dampen the effect
            if ("teamBuild".equals(combo) || "teamLunch".equals(combo)) {
                if (num(m.get("styleMatch")) < 0) {
                    nve = "-ve";
                }
                PhpArray envEffect = mutableChild(m, "envEffect");
                PhpArray actEffect = mutableChild(m, "actEffect");
                boolean dampened = !(num(envEffect.get(PhpArray.key("360-feedback"))) >= dayNumber
                        || num(envEffect.get(PhpArray.key("Financial-Crisis"))) >= dayNumber
                        || num(envEffect.get(PhpArray.key("take-over"))) >= dayNumber
                        || num(actEffect.get(PhpArray.key("sack"))) >= dayNumber
                        || num(actEffect.get(PhpArray.key("resigned"))) >= dayNumber
                        || num(PhpJson.path(content, "control", "metTarget")) >= dayNumber
                        || dayNumber <= 10);
                if (!dampened) {
                    enhance = 1;
                } else {
                    // teamLunch lands here too: its own parameter is not used
                    enhance = num(params.get("teamBuild_low_impact"));
                    if ("-ve".equals(nve)) enhance = 1;
                }
                DrillCommon.dbg(content, "teamBuild Enhance", enhance);
            }

            long duration = (long) num(rowGet(act, "duration"));
            daysNeeded = Math.max(daysNeeded, duration);

            double oldSkills = num(m.get("skillsValue"));
            double oldMorale = num(m.get("moraleValue"));
            double oldEfficiency = num(m.get("efficiencyValue"));

            // ---- the action also moves the member onto the leader's task
            if (truthy(rowGet(act, "withTask"))) {
                m.put("task", PhpJson.path(content, "control", "task"));
                m.put("taskExp", PhpJson.path(content, "control", "taskExp"));
                // no guard in the PHP: with workingOn == 0 this writes orders[0].state
                Object workingOn = m.get("workingOn");
                content.child("orders").child(PhpArray.key(workingOn)).put("state", -1L);
                m.put("workingOn", 0L);

                // $sw/$mw are read then never used (the weighted formula is commented out),
                // so only the skillseffect lookup matters:
                //   efficiencyValue = min(round(skillsValue * skillsEffect), 100)
                Map<String, Object> seff = firstRow(jdbc,
                        "select * from skillseffect where minMorale <= ? and maxMorale >= ?",
                        num(m.get("moraleValue")), num(m.get("moraleValue")));
                double eo = num(m.get("efficiencyValue"));
                double efficiencyValue = Math.min(
                        PhpJson.round(num(m.get("skillsValue")) * num(rowGet(seff, "skillsEffect"))), 100);
                m.put("efficiencyValue", PhpJson.jsonNumber(efficiencyValue));
                double efficiencyAdded = efficiencyValue - eo;
                m.put("efficiencyAdded", PhpJson.jsonNumber(efficiencyAdded));
                // note: `=` here, not `+=` — the accumulators are rebuilt from scratch
                m.put("added", PhpJson.jsonNumber(num(m.get("skillsAdded")) + num(m.get("moraleAdded"))
                        + efficiencyAdded));

                DrillCommon.writeHistory(content, stepNumber, dayNumber, m.get("staffName"),
                        m.get("staffExp"), qdataAction, m.get("actionExp"),
                        m.get("skillsAdded"), m.get("moraleAdded"),
                        PhpJson.jsonNumber(efficiencyAdded), "");
            }

            if ("swap-task".equals(memberAction)) {
                DrillCommon.dbg(content, "swap+-", nve + ", " + moraleForced);
            }

            // ---- the impact itself, on a COPY first
            String intent = memberAction + nve;
            PhpArray cont = content.copy();
            // PHP: if (!$action['noInmpact']) -- misspelled, so this is ALWAYS true.
            // $prf is cal_impacts' &$res out-parameter: whatever the LAST call resolved.
            Map<String, Object> res = DrillCommon.calImpacts(jdbc, cont, (int) c, intent,
                    enhance, 0, 0, 0);
            PhpArray contMember = member(cont, c);
            boolean forced = (skillsForced + moraleForced + efficiencyForced) != 0;
            if (forced && contMember != null && num(contMember.get("added")) > 0) {
                // re-run from the ORIGINAL content, so the action is not applied twice
                res = DrillCommon.calImpacts(jdbc, content, (int) c, intent, enhance,
                        skillsForced, moraleForced, efficiencyForced);
            } else {
                // PHP: $content = $cont. In place, because callers hold this reference.
                content.clear();
                content.putAll(cont);
            }
            // the copy REPLACED every nested array, so the cached member is stale
            m = member(content, c);
            effect = mutableChild(m, "effect");
            if (res != null) prf.putAll(res);

            DrillCommon.anaAction(jdbc, content, (int) c, intent, rowGet(prf, "desiredStyle"));

            DrillCommon.writeHistory(content, stepNumber, dayNumber, m.get("staffName"),
                    m.get("staffExp"), qdataAction, m.get("actionExp"),
                    m.get("skillsAdded"), m.get("moraleAdded"), m.get("efficiencyAdded"),
                    mutableChild(m, "message").get(PhpArray.key(m.get("msgCount"))));

            // ---- weekly tally of the action group
            long perWeek = (long) num(rowGet(act, "perWeek"));
            Object weekKey = PhpArray.key(PhpJson.path(content, "day", dayNumber, "week"));
            for (long w = 0; w < perWeek; w++) {
                PhpArray cell = content.child("week")
                        .child(PhpArray.key(num(weekKey) + w))
                        .child(PhpArray.key(str(PhpJson.path(content, "control", "action"))));
                cell.put("count", num(cell.get("count")) + 1);
            }

            // ---- days away
            if (num(m.get("away")) > 0) m.put("away", num(m.get("away")) - 1);
            if (num(m.get("away")) < 1) m.put("awayFor", "");
            m.put("away", num(m.get("away")) + num(rowGet(act, "away")) + 1);
            m.put("awayFor", m.get("action"));

            // ---- how long the action stays effective (with the Monday fudge)
            if (num(m.get("added")) > 0) {
                long away = (long) num(rowGet(act, "away"));
                long effectiveDays = (long) num(rowGet(act, "effectiveDays"));
                long effectiveUntil = dayNumber + away + effectiveDays + 1;
                PhpArray effArr = mutableChild(m, "effect");
                Object parentKey = PhpArray.key(parentAction);
                effArr.put(parentKey, effectiveUntil);
                long wd = dayNumber % 5;
                wd = (wd != 0) ? wd : 5;
                if ((away + effectiveDays + 1 + wd) > 5) {
                    effArr.put(parentKey, num(effArr.get(parentKey)) + 1);
                }
                if ((away + effectiveDays + 1 + wd) > 10) {
                    effArr.put(parentKey, num(effArr.get(parentKey)) + 1);
                }
            }

            // ---- bystanders
            if (!"X".equals(str(rowGet(act, "affectedIf")))) {
                for (long cx = 1; cx <= maxMembers; cx++) {
                    PhpArray other = member(content, cx);
                    if (other == null || !hasName(other.get("staffName")) || truthy(other.get("actioned"))) {
                        continue;
                    }
                    double diff = switch (str(rowGet(act, "affectedWhen")).isEmpty() ? ""
                            : str(rowGet(act, "affectedWhen")).substring(0, 1)) {
                        case "1" -> num(other.get("skillsValue")) - oldSkills;
                        case "2" -> num(other.get("moraleValue")) - oldMorale;
                        case "3" -> num(other.get("efficiencyValue")) - oldEfficiency;
                        default -> 0;
                    };
                    if ("absentNoAffect".equals(combo) && num(other.get("away")) > 0) {
                        diff = 0;
                    }
                    String affectedIf = str(rowGet(act, "affectedIf"));
                    if ("><".equals(affectedIf)
                            || ("<".equals(affectedIf) && diff < 0)
                            || (">".equals(affectedIf) && diff > 0)
                            || (">=".equals(affectedIf) && diff >= 0)) {
                        double afSkills = 0;
                        double afMorale = 0;
                        // sack inside the familialisation window, against a member with no
                        // recorded effect, costs 10 morale
                        if ("sack".equals(str(PhpJson.path(content, "control", "action")))
                                && dayNumber <= num(params.get("team_familialisation_days"))) {
                            if (seqSize(effect) == 0) afMorale = -10;
                        }
                        if ("recruit".equals(str(PhpJson.path(content, "control", "action")))
                                && eq(other.get("task"), m.get("task"))) {
                            afMorale = 5;
                        }
                        // $efficiencyFRorced (sic) is undefined in the PHP -> null -> 0
                        DrillCommon.calAffect(jdbc, content, (int) cx,
                                str(rowGet(act, "actionName")), afSkills, afMorale, 0);
                        DrillCommon.writeHistory(content, stepNumber, dayNumber,
                                other.get("staffName"), other.get("staffExp"), qdataAction,
                                other.get("actionExp"), other.get("skillsAdded"),
                                other.get("moraleAdded"), other.get("efficiencyAdded"),
                                mutableChild(other, "message").get(PhpArray.key(other.get("msgCount"))));
                    }
                }
            }

            m.put("attention", params.get("dejected_tolerance_days"));
            m.put("attenWarning", params.get("dejected_warning_days"));
            m.put("offdays", 0L);
        }

        return daysNeeded;
    }

    private static long seqSize(Object v) {
        if (v instanceof PhpArray a) return a.size();
        if (v instanceof List<?> l) return l.size();
        return 0;
    }

    private static long rand(long min, long max) {
        if (max <= min) return min;
        return java.util.concurrent.ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    private static long numLong(Object v) {
        return (long) num(v);
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.toString().equals(b == null ? null : b.toString());
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
