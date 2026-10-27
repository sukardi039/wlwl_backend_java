package com.wlwl.backend.service;

import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpJson;
import com.wlwl.backend.db.PhpRows;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of drill/commonLib.php — the shared state maths. Being built up function by
 * function in dependency order; this file currently holds the helpers that INIT and
 * ENVIRONMENT need.
 */
public final class DrillCommon {

    private DrillCommon() {
    }

    /**
     * writeHistory(): append one row to content.history.
     *
     * The PHP indexes with sizeof($cont['history']) — i.e. it appends — so a plain append is
     * equivalent. Ten fields, in this order.
     */
    public static void writeHistory(PhpArray cont, Object step, Object day, Object staff,
                                    Object staffExp, Object actType, Object act,
                                    Object skills, Object morale, Object eff, Object msg) {
        PhpArray entry = new PhpArray();
        entry.put("step", step);
        entry.put("day", day);
        entry.put("staff", staff);
        entry.put("staffExp", staffExp);
        entry.put("actType", actType);
        entry.put("act", act);
        entry.put("skills", skills);
        entry.put("morale", morale);
        entry.put("eff", eff);
        entry.put("msg", msg);
        cont.child("history").append(entry);
    }

    /**
     * initMemberValues(): clears the per-turn accumulators and decays the "listen" counter.
     *
     * Two rules worth naming:
     *   - a member who has messages but has NOT read them loses one listen point; one who
     *     did read them gets the full tolerance back
     *   - the message reset is guarded by `$readMsg !== 0`, a STRICT check, so the string
     *     "0" also triggers the reset; only an integer 0 holds messages back
     */
    public static void initMemberValues(PhpArray content, Map<String, Object> params) {
        int maxMembers = (int) PhpJson.num(PhpJson.path(content, "control", "maxMembers"));
        PhpArray members = content.child("members");
        Object tolerance = params.get("ignored_tolerance_days");

        for (int c = 1; c <= maxMembers; c++) {
            Object raw = members.get(c);
            if (!(raw instanceof PhpArray m)) continue;
            if (!truthy(m.get("staffName"))) continue;

            m.put("skillsAdded", 0);
            m.put("moraleAdded", 0);
            m.put("efficiencyAdded", 0);
            m.put("added", 0);
            m.put("emotion", 0);
            m.put("addedAvg", 0);

            if (PhpJson.num(m.get("msgCount")) > 0) {
                if (PhpJson.num(m.get("readMsg")) == 0) {
                    m.put("listen", PhpJson.num(m.get("listen")) - 1);
                } else {
                    m.put("listen", tolerance);
                }
            }
            if (!isStrictlyZero(m.get("readMsg"))) {
                m.put("msgCount", 0);
                m.put("message", new PhpArray());
            }
        }

        content.child("staffMsg").put("count", 0);
        content.child("bossMsg").put("count", 0);
    }

    /**
     * noMoreStaff(): true only when NO member slot 1..maxMembers has a name.
     * (The PHP also calls dbg() inside this loop with an undefined $content, which is a
     * no-op there — passing by reference creates a local — so it is not reproduced.)
     */
    public static boolean noMoreStaff(PhpArray ct) {
        int maxMembers = (int) PhpJson.num(PhpJson.path(ct, "control", "maxMembers"));
        PhpArray members = ct.child("members");
        for (int c = 1; c <= maxMembers; c++) {
            Object raw = members.get(c);
            if (raw instanceof PhpArray m && truthy(m.get("staffName"))) {
                return false;
            }
        }
        return true;
    }

    /** dbg(): append "Y-m-d H:i:s head: body" to content.debug. */
    public static void dbg(PhpArray ct, String head, Object body) {
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        ct.child("debug").append(stamp + " " + head + ": " + body);
    }

    /** readParams(): the whole params_int table as key -> value. */
    public static Map<String, Object> readParams(JdbcTemplate jdbc) {
        Map<String, Object> params = new LinkedHashMap<>();
        List<Map<String, Object>> rows = PhpRows.query(jdbc, "select * from params_int");
        for (Map<String, Object> row : rows) {
            params.put(String.valueOf(PhpRows.get(row, "key")), PhpRows.get(row, "value"));
        }
        return params;
    }

    /** currentMembers(): how many slots hold a name. */
    public static int currentMembers(PhpArray content) {
        int maxMembers = (int) PhpJson.num(PhpJson.path(content, "control", "maxMembers"));
        PhpArray members = content.child("members");
        int v = 0;
        for (int c = 1; c <= maxMembers; c++) {
            if (named(members, c)) v++;
        }
        return v;
    }

    /** anyOne(): a RANDOM named member. rand() is inclusive, so the range is 1..count. */
    public static Pick anyOne(PhpArray content, java.util.Random rnd) {
        int maxMembers = (int) PhpJson.num(PhpJson.path(content, "control", "maxMembers"));
        PhpArray members = content.child("members");
        int count = currentMembers(content);
        if (count <= 0) return new Pick(null, null);

        int v = 1 + rnd.nextInt(count);                     // PHP rand(1, count)
        while (!named(members, v) && v < maxMembers) v++;   // PHP: staffName < 1 && v < max
        PhpArray m = member(members, v);
        return m == null ? new Pick(null, null) : new Pick(m.get("staffName"), m.get("staffExp"));
    }

    /** lowMorale(): the member with the lowest moraleValue that still has a name. */
    public static Pick lowMorale(PhpArray content) {
        return extreme(content, "moraleValue", true);
    }

    /** lowEfficiency(): lowest efficiencyValue. */
    public static Pick lowEfficiency(PhpArray content) {
        return extreme(content, "efficiencyValue", true);
    }

    /** highEfficiency(): highest efficiencyValue. */
    public static Pick highEfficiency(PhpArray content) {
        return extreme(content, "efficiencyValue", false);
    }

    /**
     * Shared body of lowMorale / lowEfficiency / highEfficiency — they differ only in which
     * field they read and the direction of the comparison.
     *
     * The PHP opens with `while ($members[$sv]['staffName'] < 1) $sv++;` and NO bound, so an
     * all-empty team would spin forever reading undefined keys. Here the scan stops at
     * maxMembers+1 and returns an empty Pick; a hang is not a behaviour worth reproducing.
     */
    private static Pick extreme(PhpArray content, String field, boolean lowest) {
        int maxMembers = (int) PhpJson.num(PhpJson.path(content, "control", "maxMembers"));
        PhpArray members = content.child("members");

        int sv = 1;
        while (sv <= maxMembers + 1 && !named(members, sv)) sv++;
        if (sv > maxMembers) return new Pick(null, null);

        PhpArray first = member(members, sv);
        Object bestName = first.get("staffName");
        Object bestExp = first.get("staffExp");
        double best = PhpJson.num(first.get(field));

        for (int v = sv + 1; v <= maxMembers; v++) {
            if (!named(members, v)) continue;
            PhpArray m = member(members, v);
            double val = PhpJson.num(m.get(field));
            // strict < / > : PHP never replaces on an equal value, so the FIRST one wins ties
            if (lowest ? val < best : val > best) {
                bestName = m.get("staffName");
                bestExp = m.get("staffExp");
                best = val;
            }
        }
        return new Pick(bestName, bestExp);
    }

    /** memberWhere(): the slot holding this name, or -1. */
    public static int memberWhere(PhpArray content, Object staffName) {
        int maxMembers = (int) PhpJson.num(PhpJson.path(content, "control", "maxMembers"));
        PhpArray members = content.child("members");
        String wanted = staffName == null ? "" : staffName.toString();
        for (int c = 1; c <= maxMembers; c++) {
            PhpArray m = member(members, c);
            if (m == null) continue;
            Object name = m.get("staffName");
            if (name != null && wanted.equals(name.toString())) return c;
        }
        return -1;
    }

    /**
     * init_staff_ana(): seed content.analysis.staff[<name>] with a counter per MAIN action
     * (show == 1 AND actionName == parentAction), then the staff totals.
     *
     * Keyed by STAFF NAME, so two member slots holding the same name share one entry — the
     * PHP does that too, and it is why the analysis is per person rather than per slot.
     */
    public static void initStaffAna(JdbcTemplate jdbc, PhpArray content, Object staffName, Object staffExp) {
        PhpArray staff = content.child("analysis").child("staff").child(staffName);
        for (Map<String, Object> act : PhpRows.query(jdbc, "select * from action")) {
            Object show = PhpRows.get(act, "show");
            Object name = PhpRows.get(act, "actionName");
            Object parent = PhpRows.get(act, "parentAction");
            // PHP: ($act['show'] == 1) and ($act['actionName'] == $act['parentAction'])
            if (PhpJson.num(show) == 1 && name != null && name.toString().equals(
                    parent == null ? null : parent.toString())) {
                PhpArray a = staff.child(name);
                a.put("count", 0L);
                a.put("positive", 0L);
                a.put("negative", 0L);
                a.put("oneInFour", PhpRows.get(act, "1In4"));
            }
        }
        staff.put("staffExp", staffExp);
        staff.put("total", 0L);
        staff.put("positive", 0L);
        staff.put("negative", 0L);
    }

    /** A member plus the staffExp that travels with it (PHP returns one and passes the other by reference). */
    public record Pick(Object name, Object exp) {
    }

    /**
     * PHP compares `staffName > 0`. On PHP 5.x a non-numeric name casts to 0 and this would be
     * FALSE for every member; on PHP 7+/8 it is a string comparison against "0". The app runs
     * PHP 7+, where a real name is greater and an empty string is not - so "has a name" is the
     * faithful reading, and "0" is excluded because "0" > "0" is false.
     */
    private static boolean named(PhpArray members, int c) {
        PhpArray m = member(members, c);
        if (m == null) return false;
        Object name = m.get("staffName");
        if (name == null) return false;
        String s = name.toString();
        return !s.isEmpty() && !"0".equals(s);
    }

    /**
     * PHP `$member['staffName'] > 0`. staffName holds a staffId, but on PHP 7+ a non-numeric
     * string compares as a string greater than "0", so the faithful reading is "has a name".
     * Shared with writeSummary's loop guard; `named()` does the same for a members array.
     */
    private static boolean hasName(Object staffName) {
        if (staffName == null) return false;
        String s = staffName.toString();
        return !s.isEmpty() && !"0".equals(s);
    }

    private static PhpArray member(PhpArray members, int c) {
        Object v = members.get(c);
        return v instanceof PhpArray a ? a : null;
    }


    // ------------------------------------------------ movement maths

    /**
     * cal_works(): spend a member's available work on the order they are doing.
     *
     * Two units of measure meet here — the member's `workAvailable` and the order's
     * `workNeeded` — both rounded to 2 decimals, because the PHP calls `round($x, 2)`.
     * When the order is finished it is logged against `$state` (the state AT CALL TIME,
     * passed in, not re-read), `task_work[state-1].worked` counts up, and the order is
     * nullified (`state = -1`) until the count reaches the task's `passingRate`.
     */
    public static void calWorks(PhpArray content, int c, Object d, long day, long state,
                                Map<String, Object> params) {
        PhpArray m = memberAt(content, c);
        Object orderRaw = PhpJson.path(content, "orders", d);
        if (m == null || !(orderRaw instanceof PhpArray o)) return;

        double available = num(m.get("workAvailable"));
        double needed = num(o.get("workNeeded"));

        if (available >= needed) {
            m.put("workAvailable", round2(available - needed));
            o.put("workNeeded", 0L);
            o.put("assignedTo", "");
            m.put("workingOn", 0L);
            o.child("log").child(state).put("completedDay", day);

            long orderState = (long) num(o.get("state"));
            PhpArray tw = content.child("task_work").child(orderState - 1);
            tw.put("worked", num(tw.get("worked")) + 1);

            Object te = PhpJson.path(content, "task_eff", orderState - 1);
            double passingRate = num(PhpJson.path(te, "passingRate"));
            if (num(tw.get("worked")) < passingRate) {
                o.put("state", -1L);              // not enough repeats: the order dies
            } else {
                tw.put("worked", 0L);
                if (orderState == 5) {            // the deal is closed
                    content.put("newDeal", 1L);
                    double days = num(params.get("firstDeal_effective_days"));
                    PhpArray control = content.child("control");
                    control.put("newDeal", jsonNumber(day + days + 1));
                    if (!control.containsKey("firstDeal")) {
                        double firstDeal = day + days + 1;
                        long wd = day % 5;
                        wd = wd != 0 ? wd : 5;
                        if (days + wd + 1 > 5) firstDeal++;
                        if (days + wd + 1 > 10) firstDeal++;
                        control.put("firstDeal", jsonNumber(firstDeal));
                    }
                }
            }
        } else {
            o.put("workNeeded", round2(needed - available));
            m.put("workAvailable", 0L);
        }
    }

    /**
     * cal_swap(): move a member onto the task named in `member.action`, which action.php
     * sets to `'evaluate-' . task` — the hard-coded `substr($swapTask, 9)` strips exactly
     * that 9-character prefix, so the remainder is a bare taskName ("2-evaluate").
     *
     * Skills/morale are re-expressed at the new task's weights, so a task that leans on
     * skills converts morale into skills and vice versa. `intval()` (truncation, not
     * rounding) is used for the conversion, and efficiency is clamped to 0..100 here —
     * unlike recal_eff(), which has no clamp at all.
     *
     * THE PROFILESENS LOOKUP IN THE PHP CANNOT SUCCEED. It asks for
     * `impactClass`/`skillsMin`/`skillsMax`/`moraleMin`/`moraleMax`/`efficiencyMin`/
     * `efficiencyMax` FROM profilesens, and the production dump has none of those columns
     * there (they live in profileimpact). mysqli_query returns false, `$res` is null, and
     * on PHP 7 every `$res[...]` read is therefore 0 — so the sensitivity/impact terms drop
     * out of the efficiency formula. The port does not issue SQL that cannot succeed; it
     * reproduces the resulting arithmetic, and this comment is why the terms are missing.
     */
    public static Swap calSwap(PhpArray content, int c, String type) {
        PhpArray m = memberAt(content, c);
        if (m == null || !hasName(m.get("staffName"))) return new Swap(null, null);

        String currentTask = str(m.get("task"));
        String action = str(m.get("action"));
        String code = action.length() > 9 ? action.substring(9) : "";   // strips "evaluate-"

        double currSkillsWeight = 0;
        double currMoraleWeight = 0;
        for (Object tv : seq(content.get("task_eff"))) {
            if (!(tv instanceof PhpArray tsk)) continue;
            if (eq(tsk.get("taskName"), currentTask)) {
                currMoraleWeight = num(tsk.get("moraleWeight"));
                currSkillsWeight = num(tsk.get("skillsWeight"));
            }
        }

        double swapSkillsWeight = 0;
        double swapMoraleWeight = 0;
        Object swapTaskName = null;
        boolean matched = false;
        for (Object tv : seq(content.get("task_eff"))) {
            if (!(tv instanceof PhpArray tsk)) continue;
            if (code.equals(str(tsk.get("taskName")))) {
                swapMoraleWeight = num(tsk.get("moraleWeight"));
                swapSkillsWeight = num(tsk.get("skillsWeight"));
                swapTaskName = tsk.get("taskDisplayName");
                matched = true;
            }
        }

        double currSkills = num(m.get("skillsValue"));
        double currMorale = num(m.get("moraleValue"));
        double swapSkills = clamp(currSkillsWeight == 0 ? 0
                : Math.floor(currSkills / currSkillsWeight * swapSkillsWeight), 0, 100);
        double swapMorale = clamp(currMoraleWeight == 0 ? 0
                : Math.floor(currMorale / currMoraleWeight * swapMoraleWeight), 0, 100);

        m.put("skillsAdded", jsonNumber(num(m.get("skillsAdded")) + swapSkills - currSkills));

        // one branch keeps the old morale: over 50 already, or the swap would lower it, or
        // this is a swap-task and the member was evaluated for a different task
        boolean keepMorale = currMorale > 50 || swapMorale < currMorale
                || ("swap-task".equals(type) && !eq(m.get("evaluatedFor"), code));
        if (keepMorale) {
            swapMorale = currMorale;                        // moraleAdded += 0
        } else {
            m.put("moraleAdded", jsonNumber(num(m.get("moraleAdded")) + swapMorale - currMorale));
        }

        // $res is null in production (see javadoc): only the branch that does not multiply
        // by $res survives, and it still adds 0 * 0 for the impact term.
        double efficiencyAdd = 0;
        if (num(m.get("skillsAdded")) == 0 && num(m.get("moraleAdded")) != 0) {
            double moraleNow = num(m.get("moraleValue"));
            dbg(content, str(m.get("staffExp")), "skillsValue:" + numStr(swapSkills)
                    + ", moralAdd:" + numStr(num(m.get("moraleAdded")))
                    + ", moraleOld:" + numStr(moraleNow));
            efficiencyAdd = round(swapSkills * (moraleNow == 0 ? 0
                    : num(m.get("moraleAdded")) / moraleNow));
        }

        double efficiencyOld = num(m.get("efficiencyValue"));
        double swapEfficiency = clamp(num(m.get("skillsAdded")) + num(m.get("moraleAdded"))
                + efficiencyOld, 0, 100);
        m.put("efficiencyAdded", jsonNumber(num(m.get("efficiencyAdded"))
                + swapEfficiency - efficiencyOld));
        m.put("efficiencyValue", jsonNumber(swapEfficiency));
        m.put("skillsValue", jsonNumber(swapSkills));
        m.put("moraleValue", jsonNumber(swapMorale));
        m.put("added", jsonNumber(num(m.get("skillsAdded")) + num(m.get("moraleAdded"))
                + num(m.get("efficiencyAdded"))));
        m.put("addedAvg", jsonNumber(trunc(num(m.get("added")) / 3)));   // intval(), not round()

        return new Swap(matched ? swapTaskName : null, matched ? code : null);
    }

    /** The two by-reference outputs of cal_swap(): the display name and the task code. */
    public record Swap(Object taskName, Object taskCode) {
    }

    /**
     * recal_eff(): recompute efficiency from the member's task weights.
     *
     * `efficiency = round(skills * skillsWeight + morale * moraleWeight)` with NO clamp —
     * so this is the one place efficiency can exceed 100 and drop below 0. It also appends
     * a history line with an empty message. `$conn` in the PHP is unused.
     */
    public static void recalEff(PhpArray content, int c, Map<String, Map<String, Object>> eff,
                                Object stepNumber, Object dayNumber, String action) {
        PhpArray m = memberAt(content, c);
        if (m == null) return;

        Map<String, Object> weights = eff.get(str(m.get("task")));
        double skillsWeight = weights == null ? 0 : num(rowGet(weights, "skillsWeight"));
        double moraleWeight = weights == null ? 0 : num(rowGet(weights, "moraleWeight"));
        double efficiencyOld = num(m.get("efficiencyValue"));

        m.put("efficiencyValue", jsonNumber(round(num(m.get("skillsValue")) * skillsWeight
                + num(m.get("moraleValue")) * moraleWeight)));
        m.put("efficiencyAdded", jsonNumber(num(m.get("efficiencyValue")) - efficiencyOld));
        m.put("added", jsonNumber(num(m.get("skillsAdded")) + num(m.get("moraleAdded"))
                + num(m.get("efficiencyAdded"))));

        writeHistory(content, stepNumber, dayNumber, m.get("staffName"), m.get("staffExp"),
                action, m.get("actionExp"), m.get("skillsAdded"), m.get("moraleAdded"),
                m.get("efficiencyAdded"), "");
    }

    // ------------------------------------------------ weekly / analysis helpers

    /**
     * weekSummary(): reset the four per-week counters, then replay completed deals.
     *
     * A deal counts when its order sits at state 5 with no work left; it is attributed to
     * the week of the day logged in `order.log[5].completedDay`, using the *cumulative*
     * `order`/`value` running totals, while `weekOrder`/`weekValue` are per-week.
     */
    public static void weekSummary(PhpArray cont) {
        Object orderValue = cont.get("orderValue");
        PhpArray week = cont.get("week") instanceof PhpArray w ? w : new PhpArray();
        for (Object wv : seq(week)) {
            if (!(wv instanceof PhpArray w)) continue;
            PhpArray target = week.child(w.get("weekNumber"));
            target.put("weekOrder", 0L);
            target.put("weekValue", 0L);
            target.put("order", 0L);
            target.put("value", 0L);
        }

        long done = 0;
        double oValue = num(orderValue);
        long totalOrders = (long) num(PhpJson.path(cont, "control", "totalOrders"));
        for (long d = 1; d <= totalOrders; d++) {
            PhpArray order = (PhpArray) PhpJson.path(cont, "orders", d);
            if (order == null) continue;
            if (num(order.get("state")) != 5 || num(order.get("workNeeded")) != 0) continue;

            Object completedDay = PhpJson.path(order, "log", 5L, "completedDay");
            Object wk = PhpJson.path(cont, "day", completedDay, "week");
            PhpArray w = week.child(wk);
            w.put("weekOrder", num(w.get("weekOrder")) + 1);
            w.put("weekValue", jsonNumber(num(w.get("weekOrder")) * oValue));
            done++;
            w.put("order", done);
            w.put("value", jsonNumber(done * oValue));
        }
    }

    /**
     * setEnvironment(): schedule an environment for TOMORROW (`dayNumber + 1`).
     *
     * No horizon check: if today is the last day, this creates `day[numberOfDays+1]` and
     * the event sits outside the calendar. Preserved.
     *
     * @param c the member to attribute the event to, or 0 for a company-wide event
     */
    public static void setEnvironment(JdbcTemplate jdbc, PhpArray content, String environmentName,
                                      int c, Object dayNumber) {
        Map<String, Object> bi = new LinkedHashMap<>();
        for (Map<String, Object> r : PhpRows.query(jdbc, "select * from baseinfo")) {
            bi.put(String.valueOf(PhpRows.get(r, "name")), PhpRows.get(r, "displayKey"));
        }

        Map<String, Object> environment = firstRow(jdbc,
                "select * from environment where environmentName = ?", environmentName);
        if (environment == null) return;

        long when = (long) num(dayNumber) + 1;
        PhpArray event = content.child("day").child(when).child("event");
        mutableChild(event, "description").append(rowGet(environment, "environmentDescription"));
        mutableChild(event, "eventName").append(rowGet(environment, "environmentName"));
        mutableChild(event, "eventExp").append(bi.get(environmentName));
        PhpArray m = c > 0 ? memberAt(content, c) : null;
        mutableChild(event, "staffName").append(m == null ? "" : m.get("staffName"));
        mutableChild(event, "staffExp").append(m == null ? "" : m.get("staffExp"));
        mutableChild(event, "criteria").append(0L);
        event.put("count", num(event.get("count")) + 1);
    }

    /**
     * ana_style(): score one member's leadership style against the desired one.
     *
     * `analysis.style` is keyed by style NAME, and the three comparison buckets
     * (`desire_actual`, `desire_intent`, `intent_actual`) live in the same array as the
     * styles — reason enough to keep this in PhpArray rather than a typed map.
     */
    public static void anaStyle(PhpArray cont, String intent, Object desired) {
        PhpArray style = cont.child("analysis").child("style");
        style.put("total", num(style.get("total")) + 1);
        PhpArray s = style.child(intent);
        s.put("count", num(s.get("count")) + 1);
        PhpArray desireIntent = style.child("desire_intent");
        desireIntent.put("count", num(desireIntent.get("count")) + 1);

        if (eq(intent, desired)) {
            s.put("positive", num(s.get("positive")) + 1);
            desireIntent.put("positive", num(desireIntent.get("positive")) + 1);
        } else {
            s.put("negative", num(s.get("negative")) + 1);
            desireIntent.put("negative", num(desireIntent.get("negative")) + 1);
        }
    }

    /**
     * ana_action(): score one action — against its PARENT action, against the member, and
     * (when the action carries an `actualStyle`) against the desired style.
     *
     * `positive`/`negative` is decided by the member's net `added` for the step, not by the
     * action itself. The style block only runs when `strlen($actual)` — an empty
     * `actualStyle` skips all three style counters.
     */
    public static void anaAction(JdbcTemplate jdbc, PhpArray cont, int c, String intent,
                                 Object desired) {
        Map<String, Object> act = firstRow(jdbc,
                "select * from action where actionName = ?", intent);
        Object parentAction = rowGet(act, "parentAction");
        Object actual = rowGet(act, "actualStyle");

        PhpArray action = cont.child("analysis").child("action");
        action.put("total", num(action.get("total")) + 1);
        PhpArray parent = action.child(parentAction);
        parent.put("count", num(parent.get("count")) + 1);

        PhpArray m = memberAt(cont, c);
        Object staffName = m == null ? null : m.get("staffName");
        PhpArray staff = cont.child("analysis").child("staff").child(staffName);
        PhpArray staffParent = staff.child(parentAction);
        staffParent.put("count", num(staffParent.get("count")) + 1);
        staff.put("total", num(staff.get("total")) + 1);

        boolean positive = m != null && num(m.get("added")) > 0;
        String bucket = positive ? "positive" : "negative";
        parent.put(bucket, num(parent.get(bucket)) + 1);
        staffParent.put(bucket, num(staffParent.get(bucket)) + 1);
        staff.put(bucket, num(staff.get(bucket)) + 1);

        if (actual != null && !actual.toString().isEmpty()) {
            PhpArray style = cont.child("analysis").child("style");
            PhpArray desireActual = style.child("desire_actual");
            PhpArray intentActual = style.child("intent_actual");
            desireActual.put("count", num(desireActual.get("count")) + 1);
            intentActual.put("count", num(intentActual.get("count")) + 1);

            PhpArray actionStyle = cont.child("analysis").child("action_style");
            actionStyle.put("total", num(actionStyle.get("total")) + 1);
            PhpArray as = actionStyle.child(actual);
            as.put("count", num(as.get("count")) + 1);

            if (eq(desired, actual)) {
                desireActual.put("positive", num(desireActual.get("positive")) + 1);
                as.put("positive", num(as.get("positive")) + 1);
            } else {
                desireActual.put("negative", num(desireActual.get("negative")) + 1);
                as.put("negative", num(as.get("negative")) + 1);
            }
            if (m != null && eq(m.get("style"), actual)) {
                intentActual.put("positive", num(intentActual.get("positive")) + 1);
            } else {
                intentActual.put("negative", num(intentActual.get("negative")) + 1);
            }
        }
    }

    /**
     * PHP array write through a possibly-List value (json_decode gives arrays as Lists).
     * Same concern as DrillEnvironment.mutableChild(): rewriting element 1 of a decoded
     * JSON array must not drop element 0.
     */
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

    // ------------------------------------------------ movement support

    /** PHP round($x, 2) — two decimal places. */
    private static Object round2(double v) {
        return jsonNumber(Math.round(v * 100.0) / 100.0);
    }

    /** PHP intval() — truncate toward zero. */
    private static double trunc(double v) {
        return v < 0 ? Math.ceil(v) : Math.floor(v);
    }

    /** A json_decode'd PHP array, either shape (object -> PhpArray, JSON array -> List). */
    private static List<Object> seq(Object v) {
        if (v instanceof PhpArray a) return new ArrayList<>(a.values());
        if (v instanceof List<?> l) return new ArrayList<>(l);
        return new ArrayList<>();
    }

    // ------------------------------------------------ impact / affect maths

    /**
     * cal_impacts(): apply an impact (an action name OR an environment name) to one member.
     *
     * The PHP signature carries `$eff` and `&$res`; `$eff` is only read inside `if (false)`
     * (dead task-based evaluation) and the one caller that matters — environment.php —
     * passes an undefined `$dummy` for `$res`, so neither is reproduced here.
     *
     * The `evaluate` branch is NOT ported: it calls cal_swap(), which belongs to the
     * action.php slice. It throws loudly instead of silently doing nothing.
     */
    public static void calImpacts(JdbcTemplate jdbc, PhpArray content, int c, String imp,
                                  double enhance, double skillsForced,
                                  double moraleForced, double efficiencyForced) {
        PhpArray m = memberAt(content, c);
        if (m == null) return;                       // PHP would fatal on a missing slot

        Map<String, Object> res = profileRow(jdbc, "impactprofile_view", imp, m);
        Map<String, Object> act = firstRow(jdbc, "select * from action where actionName = ?", imp);
        String parentAction = act == null ? null : str(rowGet(act, "parentAction"));

        if ("evaluate".equals(parentAction)) {
            throw new IllegalStateException("cal_impacts(): the evaluate branch needs "
                    + "cal_swap(), which is not ported yet (action.php slice)");
        }

        // PHP: else if ($act['evaluated'] != 1 || member.evaluated != 1 || (evaluated == 1 && evaluatedFor != task))
        if (num(act == null ? null : rowGet(act, "evaluated")) != 1
                || num(m.get("evaluated")) != 1
                || (num(m.get("evaluated")) == 1 && !eq(m.get("evaluatedFor"), m.get("task")))) {

            m.put("touched", 1L);

            double skillsOld = num(m.get("skillsValue"));
            double moraleOld = num(m.get("moraleValue"));
            double efficiencyOld = num(m.get("efficiencyValue"));

            double skillsAdd = round(num(rowGet(res, "skillsImpact")) * num(rowGet(res, "skillsSensitivity")) * enhance
                    + skillsForced * num(rowGet(res, "skillsSensitivity")));
            double skillsValue = clamp(skillsOld + skillsAdd, 0, 100);
            skillsAdd = skillsValue - skillsOld;

            double moraleAdd = round(num(rowGet(res, "moraleImpact")) * num(rowGet(res, "moraleSensitivity")) * enhance
                    + moraleForced * num(rowGet(res, "moraleSensitivity")));
            double moraleValue = clamp(moraleOld + moraleAdd, 0, 100);
            moraleAdd = moraleValue - moraleOld;

            // morale already full and nothing else moved: nudge skills so the action still shows
            if (moraleOld == moraleValue && moraleOld == 100 && skillsAdd == 0) {
                dbg(content, "100% before", numStr(skillsAdd) + ", " + numStr(skillsValue));
                skillsAdd = skillsOld > 50 ? 1 : 2;
                skillsValue = clamp(skillsOld + skillsAdd, 0, 100);
                skillsAdd = skillsValue - skillsOld;
                dbg(content, "100% after", numStr(skillsAdd) + ", " + numStr(skillsValue));
            }

            Map<String, Object> seff = skillseffect(jdbc, moraleValue);

            double efficiencyAdd;
            if (skillsAdd != 0) {
                efficiencyAdd = round(skillsAdd * num(rowGet(seff, "skillsEffect"))
                        * num(rowGet(res, "efficiencySensitivity")) * Math.abs(enhance)
                        + (num(rowGet(res, "efficiencyImpact")) + efficiencyForced)
                        * num(rowGet(res, "efficiencySensitivity")));
            } else if (moraleAdd != 0) {
                dbg(content, str(m.get("staffExp")), "skillsValue:" + numStr(skillsValue)
                        + ", moralAdd:" + numStr(moraleAdd) + ", moraleOld:" + numStr(moraleOld));
                efficiencyAdd = round(skillsValue * (moraleOld == 0 ? 0 : moraleAdd / moraleOld)
                        * Math.abs(enhance)
                        + (num(rowGet(res, "efficiencyImpact")) + efficiencyForced)
                        * num(rowGet(res, "efficiencySensitivity")));
            } else {
                efficiencyAdd = round((num(rowGet(res, "efficiencyImpact")) + efficiencyForced)
                        * num(rowGet(res, "efficiencySensitivity")));
            }

            double efficiencyValue = clamp(efficiencyOld + efficiencyAdd, 0, 120);   // cap 120%
            efficiencyAdd = efficiencyValue - efficiencyOld;

            m.put("skillsValue", jsonNumber(skillsValue));
            m.put("skillsAdded", jsonNumber(num(m.get("skillsAdded")) + skillsAdd));
            m.put("moraleValue", jsonNumber(moraleValue));
            m.put("moraleAdded", jsonNumber(num(m.get("moraleAdded")) + moraleAdd));
            m.put("efficiencyValue", jsonNumber(efficiencyValue));
            m.put("efficiencyAdded", jsonNumber(num(m.get("efficiencyAdded")) + efficiencyAdd));
            m.put("added", jsonNumber(num(m.get("added")) + skillsAdd + moraleAdd + efficiencyAdd));
            m.put("addedAvg", jsonNumber(round(num(m.get("added")) / 3)));

            if (!str(rowGet(res, "impactMsg")).isEmpty()) {
                long mc = (long) num(m.get("msgCount")) + 1;
                m.put("msgCount", mc);
                m.child("message").put(mc, rowGet(res, "impactMsg"));
                m.put("readMsg", 0L);
            }
        }

        if ("swap-task".equals(parentAction)) {
            m.put("evalResult", "");
            m.put("evaluated", 0L);
            m.put("evaluatedFor", "");
        }
        // PHP max($member['maxMorale'], moraleValue): a missing key reads as 0
        m.put("maxMorale", jsonNumber(Math.max(num(m.get("maxMorale")), num(m.get("moraleValue")))));
        dbg(content, str(m.get("staffExp")) + "/" + imp,
                numStr(num(m.get("skillsAdded"))) + ", " + numStr(num(m.get("moraleAdded")))
                        + ", " + numStr(num(m.get("efficiencyAdded"))));
    }

    /**
     * cal_affect(): the knock-on effect of somebody ELSE's action, applied to a bystander.
     *
     * Same shape as cal_impacts but three differences that matter: no `enhance` factor and
     * no abs(), the message column is `affectMsg`, and a net loss burns a mark into
     * `actEffect[<imp>]`. environment.php passes only 8 arguments, so the PHP's
     * `$day_number` is null there and `null + 2` is 2.
     */
    public static void calAffect(JdbcTemplate jdbc, PhpArray content, int c, String imp,
                                 double skillsForced, double moraleForced, double efficiencyForced) {
        PhpArray m = memberAt(content, c);
        if (m == null) return;

        Map<String, Object> res = profileRow(jdbc, "impactprofile_view", imp, m);

        double skillsOld = num(m.get("skillsValue"));
        double moraleOld = num(m.get("moraleValue"));
        double efficiencyOld = num(m.get("efficiencyValue"));

        double skillsAdd = round(num(rowGet(res, "skillsAffect")) * num(rowGet(res, "skillsSensitivity"))
                + skillsForced * num(rowGet(res, "skillsSensitivity")));
        double skillsValue = clamp(skillsOld + skillsAdd, 0, 100);
        skillsAdd = skillsValue - skillsOld;

        double moraleAdd = round(num(rowGet(res, "moraleAffect")) * num(rowGet(res, "moraleSensitivity"))
                + moraleForced * num(rowGet(res, "moraleSensitivity")));
        double moraleValue = clamp(moraleOld + moraleAdd, 0, 100);
        moraleAdd = moraleValue - moraleOld;

        Map<String, Object> seff = skillseffect(jdbc, moraleValue);

        double efficiencyAdd;
        if (skillsAdd != 0) {
            efficiencyAdd = round(skillsAdd * num(rowGet(seff, "skillsEffect"))
                    * num(rowGet(res, "efficiencySensitivity"))
                    + (num(rowGet(res, "efficiencyImpact")) + efficiencyForced)
                    * num(rowGet(res, "efficiencySensitivity")));
        } else if (moraleAdd != 0) {
            dbg(content, str(m.get("staffExp")), "skillsValue:" + numStr(skillsValue)
                    + ", moralAdd:" + numStr(moraleAdd) + ", moraleOld:" + numStr(moraleOld));
            efficiencyAdd = round(skillsValue * (moraleOld == 0 ? 0 : moraleAdd / moraleOld)
                    + (num(rowGet(res, "efficiencyImpact")) + efficiencyForced)
                    * num(rowGet(res, "efficiencySensitivity")));
        } else {
            efficiencyAdd = round((num(rowGet(res, "efficiencyImpact")) + efficiencyForced)
                    * num(rowGet(res, "efficiencySensitivity")));
        }

        double efficiencyValue = clamp(efficiencyOld + efficiencyAdd, 0, 120);
        efficiencyAdd = efficiencyValue - efficiencyOld;

        m.put("skillsValue", jsonNumber(skillsValue));
        m.put("skillsAdded", jsonNumber(num(m.get("skillsAdded")) + skillsAdd));
        m.put("moraleValue", jsonNumber(moraleValue));
        m.put("moraleAdded", jsonNumber(num(m.get("moraleAdded")) + moraleAdd));
        m.put("efficiencyValue", jsonNumber(efficiencyValue));
        m.put("efficiencyAdded", jsonNumber(num(m.get("efficiencyAdded")) + efficiencyAdd));
        m.put("added", jsonNumber(num(m.get("added")) + skillsAdd + moraleAdd + efficiencyAdd));
        m.put("addedAvg", jsonNumber(round(num(m.get("added")) / 3)));

        if (!str(rowGet(res, "affectMsg")).isEmpty()) {
            long mc = (long) num(m.get("msgCount")) + 1;
            m.put("msgCount", mc);
            m.child("message").put(mc, rowGet(res, "affectMsg"));
            m.put("readMsg", 0L);
        }
        m.put("maxMorale", jsonNumber(Math.max(num(m.get("maxMorale")), num(m.get("moraleValue")))));
        if (num(m.get("added")) < 0) m.child("actEffect").put(imp, 2L);   // null + 2
        if (num(m.get("addedAvg")) != 0) m.put("touched", 1L);
    }

    /**
     * writeSummary(): append the step's per-member and team snapshot to content.summary.
     *
     * Load-bearing oddities, all preserved:
     *  - `$curm = currentMembers($cont);` is immediately overwritten by maxMembers, so every
     *    average divides by the TEMPLATE's headcount, not the live one.
     *  - the trend block sits OUTSIDE the `if staffName` guard, so empty slots also get a
     *    trend update — using whatever `$stotal` the last named member left behind.
     *  - `$stotal` comes from summary[cs-1][member][name].total; at cs = 0 that is null -> 0.
     *  - `+=` on missing keys reads as 0 (PHP notice, not an error).
     */
    public static void writeSummary(PhpArray cont, String action, Object stepNumber, Object dayNumber) {
        PhpArray summary = cont.child("summary");
        long cs = summary.size();
        long maxMembers = (long) num(PhpJson.path(cont, "control", "maxMembers"));
        PhpArray members = cont.get("members") instanceof PhpArray ma ? ma : new PhpArray();

        double stot = 0, mtot = 0, etot = 0, satot = 0, matot = 0, eatot = 0;
        double ototal = num(PhpJson.path(cont, "current", "teamAvg", "total"));
        double ttotal = ototal;

        PhpArray entry = summary.child(cs);
        double stotal = 0;                       // survives the loop, like the PHP's

        for (long c = 1; c <= maxMembers; c++) {
            PhpArray m = memberAt(cont, (int) c);
            if (m != null && hasName(m.get("staffName"))) {
                Object name = m.get("staffName");
                stotal = num(PhpJson.path(summary, cs - 1, "member", name, "total"));

                // inside the staffName guard in the PHP too: a team with nobody named never
                // gets step/day/phase, only the team blocks written after the loop
                entry.put("step", stepNumber);
                entry.put("day", dayNumber);
                entry.put("phase", action);

                PhpArray sm = entry.child("member").child(name);
                sm.put("staff", m.get("staffExp"));
                sm.put("skills", m.get("skillsValue"));
                sm.put("morale", m.get("moraleValue"));
                sm.put("efficiency", m.get("efficiencyValue"));
                sm.put("total", jsonNumber(round((num(m.get("skillsValue"))
                        + num(m.get("moraleValue")) + num(m.get("efficiencyValue"))) / 3)));
                sm.put("skillsAdd", jsonNumber(num(sm.get("skillsAdd")) + num(m.get("skillsAdded"))));
                sm.put("moraleAdd", jsonNumber(num(sm.get("moraleAdd")) + num(m.get("moraleAdded"))));
                sm.put("efficiencyAdd", jsonNumber(num(sm.get("efficiencyAdd")) + num(m.get("efficiencyAdded"))));
                sm.put("totalAdd", jsonNumber(num(sm.get("total")) - stotal));
                boolean actionPhase = isActionPhase(action);
                sm.put("action", actionPhase ? m.get("actionExp") : m.get("styleExp"));
                sm.put("message", implode(" / ", m.get("message")));

                stot += num(m.get("skillsValue"));
                mtot += num(m.get("moraleValue"));
                etot += num(m.get("efficiencyValue"));
                satot += num(m.get("skillsAdded"));
                matot += num(m.get("moraleAdded"));
                eatot += num(m.get("efficiencyAdded"));
            }

            if (m == null) {
                // PHP auto-vivifies: $cont['members'][$c]['trendLast'] = ... creates the slot
                m = members.child(c);
            }
            {
                boolean hadTrendLast = m.containsKey("trendLast");
                Object trendLast = m.get("trendLast");
                m.put("trendLast", hadTrendLast ? m.get("trend") : jsonNumber(stotal));
                double curTotal = num(PhpJson.path(summary, cs, "member", m.get("staffName"), "total"));
                m.put("trend", jsonNumber(num(m.get("trend")) + curTotal - stotal));
                dbg(cont, str(m.get("staffExp")) + "/trend",
                        str(trendLast) + ":" + numStr(num(m.get("trend"))));
            }
        }

        double tot = stot + mtot + etot;
        double atot = satot + matot + eatot;

        PhpArray team = entry.child("team");
        team.put("skills", jsonNumber(stot));
        team.put("morale", jsonNumber(mtot));
        team.put("efficiency", jsonNumber(etot));
        team.put("total", jsonNumber(tot));
        team.put("skillsAdd", jsonNumber(satot));
        team.put("moraleAdd", jsonNumber(matot));
        team.put("efficiencyAdd", jsonNumber(eatot));
        team.put("totalAdd", jsonNumber(atot));

        PhpArray avg = entry.child("teamAvg");
        avg.put("skills", jsonNumber(round(stot / maxMembers)));
        avg.put("morale", jsonNumber(round(mtot / maxMembers)));
        avg.put("efficiency", jsonNumber(round(etot / maxMembers)));
        avg.put("total", jsonNumber(round(tot / maxMembers / 3)));
        avg.put("skillsAdd", jsonNumber(round(satot / maxMembers)));
        avg.put("moraleAdd", jsonNumber(round(matot / maxMembers)));
        avg.put("efficiencyAdd", jsonNumber(round(eatot / maxMembers)));
        avg.put("totalAdd", jsonNumber(num(avg.get("total")) - ttotal));

        team.put("action", isActionPhase(action) ? PhpJson.path(cont, "control", "actionExp") : "");
        entry.put("orderDone", PhpJson.path(cont, "orderStatus", "ordersCompleted"));

        PhpArray current = cont.child("current");
        current.child("team").put("skills", jsonNumber(stot));
        current.child("team").put("morale", jsonNumber(mtot));
        current.child("team").put("efficiency", jsonNumber(etot));
        current.child("team").put("total", jsonNumber(tot));
        current.child("teamAvg").put("skills", jsonNumber(round(stot / maxMembers)));
        current.child("teamAvg").put("morale", jsonNumber(round(mtot / maxMembers)));
        current.child("teamAvg").put("efficiency", jsonNumber(round(etot / maxMembers)));
        current.child("teamAvg").put("total", jsonNumber(round(tot / maxMembers / 3)));
        current.child("teamAvg").put("totalAdd",
                jsonNumber(num(PhpJson.path(current, "teamAvg", "total")) - ototal));

        if ("MORE_ACTIONS".equals(action)) {
            PhpArray a = cont.child("analysis").child("action")
                    .child(PhpJson.path(cont, "control", "action"));
            a.put("skillsAdded", jsonNumber(num(a.get("skillsAdded"))
                    + num(PhpJson.path(summary, summary.size() - 1, "teamAvg", "skillsAdd"))));
            a.put("moraleAdded", jsonNumber(num(a.get("moraleAdded"))
                    + num(PhpJson.path(summary, summary.size() - 1, "teamAvg", "moraleAdd"))));
        }

        long bossMsgCount = (long) num(PhpJson.path(cont, "bossMsg", "count"));
        for (long b = 0; b < bossMsgCount; b++) {
            entry.child("bossMsg").append(PhpJson.path(cont, "bossMsg", "description", b));
        }
        long bossRevCount = (long) num(PhpJson.path(cont, "bossRev", "count"));
        for (long b = 0; b < bossRevCount; b++) {
            Object desc = PhpJson.path(cont, "bossRev", "description", b);
            if (desc != null && !desc.toString().isEmpty()) entry.child("bossRev").append(desc);
        }
    }

    private static boolean isActionPhase(String action) {
        return "MORE_ACTIONS".equals(action) || "ENVIRONMENT".equals(action);
    }

    // ------------------------------------------------ impact/affect support

    /**
     * Column read for rows coming from a JDBC query. MUST go through PhpRows.get: MySQL
     * reports `skillsImpact`, H2 lowercases it to `skillsimpact`, and a bare Map.get()
     * silently yields null — i.e. every impact factors to 0, in tests only.
     */
    private static Object rowGet(Map<String, Object> row, String column) {
        return row == null ? null : PhpRows.get(row, column);
    }

    private static Map<String, Object> profileRow(JdbcTemplate jdbc, String view, String imp, PhpArray m) {
        double skills = num(m.get("skillsValue"));
        double morale = num(m.get("moraleValue"));
        double efficiency = num(m.get("efficiencyValue"));
        Map<String, Object> row = firstRow(jdbc, "select * from " + view + " where impactClass = ?"
                        + " and skillsMin <= ? and skillsMax >= ?"
                        + " and moraleMin <= ? and moraleMax >= ?"
                        + " and efficiencyMin <= ? and efficiencyMax >= ?",
                imp, skills, skills, morale, morale, efficiency, efficiency);
        return row == null ? new LinkedHashMap<>() : row;      // PHP: false -> $res['x'] is null
    }

    private static Map<String, Object> skillseffect(JdbcTemplate jdbc, double moraleValue) {
        Map<String, Object> row = firstRow(jdbc,
                "select * from skillseffect where minMorale <= ? and maxMorale >= ?",
                moraleValue, moraleValue);
        return row == null ? new LinkedHashMap<>() : row;
    }

    private static Map<String, Object> firstRow(JdbcTemplate jdbc, String sql, Object... args) {
        List<Map<String, Object>> rows = PhpRows.query(jdbc, sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static PhpArray memberAt(PhpArray content, int c) {
        Object v = PhpJson.path(content, "members");
        return v instanceof PhpArray members && members.get((long) c) instanceof PhpArray m ? m : null;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String numStr(double v) {
        return jsonNumber(v).toString();
    }

    /** PHP implode(' / ', $array) — a missing array is the empty string. */
    private static String implode(String glue, Object v) {
        StringBuilder sb = new StringBuilder();
        if (v instanceof PhpArray a) {
            for (Object o : a.values()) { if (sb.length() > 0) sb.append(glue); sb.append(o == null ? "" : o); }
        } else if (v instanceof List<?> l) {
            for (Object o : l) { if (sb.length() > 0) sb.append(glue); sb.append(o == null ? "" : o); }
        }
        return sb.toString();
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.toString().equals(b == null ? null : b.toString());
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static double num(Object v) {
        return PhpJson.num(v);
    }

    private static double round(double v) {
        return PhpJson.round(v);
    }

    private static Object jsonNumber(double v) {
        return PhpJson.jsonNumber(v);
    }

    // ------------------------------------------------------------- helpers

    /** PHP truthiness for the values that reach these functions. */
    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        String s = v.toString();
        return !s.isEmpty() && !"0".equals(s);
    }

    /** PHP `$v !== 0` — strict, so the string "0" is NOT zero. */
    private static boolean isStrictlyZero(Object v) {
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return ((Number) v).doubleValue() == 0;
        }
        return false;
    }
}
