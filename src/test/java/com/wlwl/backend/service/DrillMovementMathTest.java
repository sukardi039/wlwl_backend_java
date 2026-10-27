package com.wlwl.backend.service;

import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the movement maths: cal_works, recal_eff, cal_swap.
 *
 * Plain JUnit — none of the three touches the database. cal_swap's profilesens lookup is
 * deliberately absent (the PHP's query names columns profilesens does not have, so $res is
 * always null); see the javadoc on DrillCommon.calSwap.
 */
class DrillMovementMathTest {

    private static PhpArray content() {
        PhpArray c = new PhpArray();
        c.child("control").put("maxMembers", "1");
        c.child("debug");

        PhpArray m = c.child("members").child(1L);
        m.put("staffName", "1");
        m.put("staffExp", "Ann");
        m.put("task", "Inspect");
        m.put("skillsValue", 60);
        m.put("moraleValue", 50);
        m.put("efficiencyValue", 70);
        m.put("skillsAdded", 0);
        m.put("moraleAdded", 0);
        m.put("efficiencyAdded", 0);
        m.put("added", 0);
        m.put("workAvailable", 10);
        m.put("workingOn", 0);

        c.child("task_eff").child(0L)
                .put("taskName", "Inspect");
        PhpArray t0 = (PhpArray) PhpJson.path(c, "task_eff", 0L);
        t0.put("taskDisplayName", "Inspection");
        t0.put("skillsWeight", "0.50");
        t0.put("moraleWeight", "0.50");
        t0.put("passingRate", "1");
        PhpArray t1 = (PhpArray) c.child("task_eff").child(1L);
        t1.put("taskName", "2-evaluate");
        t1.put("taskDisplayName", "Evaluate");
        t1.put("skillsWeight", "0.30");
        t1.put("moraleWeight", "0.70");

        PhpArray o = c.child("orders").child(1L);
        o.put("workNeeded", 4);
        o.put("state", 1);
        o.put("assignedTo", "1");
        o.child("log");

        c.child("task_work").child(0L).put("worked", 0);
        c.child("history");
        c.child("summary");
        return c;
    }

    private static PhpArray member(PhpArray c) {
        return (PhpArray) PhpJson.path(c, "members", 1L);
    }

    private static double num(Object v) {
        return PhpJson.num(v);
    }

    // ---------------------------------------------------------------- cal_works

    @Test
    @DisplayName("cal_works: enough work available completes the order and resets the counter")
    void worksCompletesOrder() {
        PhpArray c = content();
        Map<String, Object> params = new LinkedHashMap<>();

        DrillCommon.calWorks(c, 1, 1L, 3L, 1L, params);

        PhpArray m = member(c);
        assertThat(num(m.get("workAvailable"))).isEqualTo(6);
        assertThat(num(m.get("workingOn"))).isZero();

        PhpArray o = (PhpArray) PhpJson.path(c, "orders", 1L);
        assertThat(num(o.get("workNeeded"))).isZero();
        assertThat(o.get("assignedTo")).isEqualTo("");
        // logged against the state passed in, not the (possibly changed) order state
        assertThat(num(PhpJson.path(o, "log", 1L, "completedDay"))).isEqualTo(3);
        // worked count reached passingRate 1 -> reset, order stays alive
        assertThat(num(PhpJson.path(c, "task_work", 0L, "worked"))).isZero();
        assertThat(num(o.get("state"))).isEqualTo(1);
    }

    @Test
    @DisplayName("cal_works: too little work moves the shortfall back onto the order")
    void worksPartial() {
        PhpArray c = content();
        member(c).put("workAvailable", 3);

        DrillCommon.calWorks(c, 1, 1L, 3L, 1L, new LinkedHashMap<>());

        assertThat(num(member(c).get("workAvailable"))).isZero();
        assertThat(num(PhpJson.path(c, "orders", 1L, "workNeeded"))).isEqualTo(1);
    }

    @Test
    @DisplayName("cal_works: below passingRate the order is nullified (state -1)")
    void worksNullifiesOrder() {
        PhpArray c = content();
        ((PhpArray) PhpJson.path(c, "task_eff", 0L)).put("passingRate", "2");

        DrillCommon.calWorks(c, 1, 1L, 3L, 1L, new LinkedHashMap<>());

        assertThat(num(PhpJson.path(c, "orders", 1L, "state"))).isEqualTo(-1);
        assertThat(num(PhpJson.path(c, "task_work", 0L, "worked"))).isEqualTo(1);
    }

    @Test
    @DisplayName("cal_works: closing the last stage records the first-deal window")
    void worksRecordsFirstDeal() {
        PhpArray c = content();
        PhpArray o = (PhpArray) PhpJson.path(c, "orders", 1L);
        o.put("state", 5);
        c.child("task_work").child(4L).put("worked", 0);
        ((PhpArray) PhpJson.path(c, "task_eff", 0L)).put("taskName", "Inspect");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("firstDeal_effective_days", "2");

        DrillCommon.calWorks(c, 1, 1L, 3L, 5L, params);

        assertThat(num(PhpJson.path(c, "newDeal"))).isEqualTo(1);
        assertThat(num(PhpJson.path(c, "control", "newDeal"))).isEqualTo(6);      // 3 + 2 + 1
        // day 3 is a Wednesday (wd 3): 2 + 3 + 1 = 6 > 5 -> pushed by one
        assertThat(num(PhpJson.path(c, "control", "firstDeal"))).isEqualTo(7);
    }

    // ---------------------------------------------------------------- recal_eff

    @Test
    @DisplayName("recal_eff: efficiency is skills/morale weighted and NOT clamped")
    void recalEff() {
        PhpArray c = content();
        Map<String, Map<String, Object>> eff = new LinkedHashMap<>();
        eff.put("Inspect", Map.of("skillsWeight", "0.20", "moraleWeight", "0.80"));

        DrillCommon.recalEff(c, 1, eff, 4L, 2L, "MORE_ACTIONS");

        PhpArray m = member(c);
        assertThat(num(m.get("efficiencyValue"))).isEqualTo(52);      // 60*0.2 + 50*0.8
        assertThat(num(m.get("efficiencyAdded"))).isEqualTo(-18);     // 52 - 70
        assertThat(num(m.get("added"))).isEqualTo(-18);
        assertThat(PhpJson.path(c, "history").toString()).contains("MORE_ACTIONS");
    }

    @Test
    @DisplayName("recal_eff: no 0..100 clamp here — 100/100 at 0.6 weights gives 120")
    void recalEffCanExceedHundred() {
        PhpArray c = content();
        PhpArray m = member(c);
        m.put("skillsValue", 100);
        m.put("moraleValue", 100);

        DrillCommon.recalEff(c, 1,
                Map.of("Inspect", Map.of("skillsWeight", "0.60", "moraleWeight", "0.60")),
                4L, 2L, "MORE_ACTIONS");

        assertThat(num(m.get("efficiencyValue"))).isEqualTo(120);
    }

    // ----------------------------------------------------------------- cal_swap

    @Test
    @DisplayName("cal_swap: skills re-expressed at the new weights, morale held above 50")
    void swapKeepsMorale() {
        PhpArray c = content();
        PhpArray m = member(c);
        m.put("action", "evaluate-2-evaluate");
        m.put("evaluatedFor", "");

        DrillCommon.Swap swap = DrillCommon.calSwap(c, 1, "swap-task");

        assertThat(swap.taskName()).isEqualTo("Evaluate");
        assertThat(swap.taskCode()).isEqualTo("2-evaluate");
        // intval(60 / 0.50 * 0.30) = 36
        assertThat(num(m.get("skillsValue"))).isEqualTo(36);
        assertThat(num(m.get("skillsAdded"))).isEqualTo(-24);
        // morale 50 is not > 50 and the swap does not lower it, but this IS a swap-task for
        // a task the member was not evaluated for -> morale is kept where it was
        assertThat(num(m.get("moraleValue"))).isEqualTo(50);
        assertThat(num(m.get("moraleAdded"))).isZero();
        // efficiency is clamped to 0..100 and tracked as a delta
        assertThat(num(m.get("efficiencyValue"))).isEqualTo(46);
        assertThat(num(m.get("efficiencyAdded"))).isEqualTo(-24);
        assertThat(num(m.get("added"))).isEqualTo(-48);
        assertThat(num(m.get("addedAvg"))).isEqualTo(-16);   // intval(-16.0)
    }

    @Test
    @DisplayName("cal_swap: an 'evaluate' swap moves morale too, and intval() truncates toward 0")
    void swapMovesMorale() {
        PhpArray c = content();
        PhpArray m = member(c);
        m.put("action", "evaluate-2-evaluate");
        m.put("evaluatedFor", "2-evaluate");       // already evaluated for it -> no hold

        DrillCommon.calSwap(c, 1, "evaluate");

        assertThat(num(m.get("skillsValue"))).isEqualTo(36);
        assertThat(num(m.get("moraleValue"))).isEqualTo(70);     // intval(50/0.5*0.7)
        assertThat(num(m.get("moraleAdded"))).isEqualTo(20);
        assertThat(num(m.get("efficiencyValue"))).isEqualTo(66); // -24 + 20 + 70, clamped
        assertThat(num(m.get("added"))).isEqualTo(-8);
        assertThat(num(m.get("addedAvg"))).isEqualTo(-2);        // intval(-2.67) -> -2, not -3
    }

    @Test
    @DisplayName("cal_swap: no staffName means nothing happens at all")
    void swapWithoutMember() {
        PhpArray c = content();
        member(c).put("staffName", "");

        DrillCommon.Swap swap = DrillCommon.calSwap(c, 1, "swap-task");

        assertThat(swap.taskName()).isNull();
        assertThat(num(member(c).get("skillsValue"))).isEqualTo(60);
    }
}
