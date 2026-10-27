package com.wlwl.backend.service;

import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity test for drill/action.php's `if ($action['swapTask'])` block.
 *
 * Two members act and swap tasks. Weights: 'Inspect' 0.50/0.50, '2-evaluate' 0.30/0.70.
 *   m1 60/50 at 0.50 -> swap to 0.30: skills intval(60/0.5*0.3) = 36, morale would drop
 *      to intval(50/0.5*0.7) = 70 but a swap-task hold keeps 50.
 *   Older morale (30) is kept for m2 as well because the swap would LOWER it (21 < 30).
 */
class DrillSwapTaskTest {

    private static PhpArray content() {
        PhpArray c = new PhpArray();
        c.child("control").put("maxMembers", "2");
        c.child("debug");

        PhpArray m1 = c.child("members").child(1L);
        m1.put("staffName", "1");
        m1.put("staffExp", "Ann");
        m1.put("task", "Inspect");
        m1.put("taskExp", "Inspection");
        m1.put("action", "_u.a_coach");
        m1.put("actioned", 1);
        m1.put("skillsValue", 60);
        m1.put("moraleValue", 50);
        m1.put("efficiencyValue", 70);
        m1.put("skillsAdded", 0);
        m1.put("moraleAdded", 0);
        m1.put("efficiencyAdded", 0);
        m1.put("added", 0);
        m1.put("workingOn", 2);

        PhpArray m2 = c.child("members").child(2L);
        m2.put("staffName", "2");
        m2.put("staffExp", "Bob");
        m2.put("task", "2-evaluate");
        m2.put("taskExp", "Evaluate");
        m2.put("action", "_u.a_coach");
        m2.put("actioned", 1);
        m2.put("skillsValue", 40);
        m2.put("moraleValue", 30);
        m2.put("efficiencyValue", 50);
        m2.put("skillsAdded", 0);
        m2.put("moraleAdded", 0);
        m2.put("efficiencyAdded", 0);
        m2.put("added", 0);
        m2.put("workingOn", 0);

        PhpArray t0 = c.child("task_eff").child(0L);
        t0.put("taskName", "Inspect");
        t0.put("skillsWeight", "0.50");
        t0.put("moraleWeight", "0.50");
        PhpArray t1 = c.child("task_eff").child(1L);
        t1.put("taskName", "2-evaluate");
        t1.put("skillsWeight", "0.30");
        t1.put("moraleWeight", "0.70");

        c.child("orders").child(2L).put("state", 3);
        c.child("orders").child(2L).put("workNeeded", 5);
        return c;
    }

    private static Map<String, Object> actionRow(int swapTask) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("actionName", "coach");
        a.put("swapTask", swapTask);
        return a;
    }

    private static double num(Object v) {
        return PhpJson.num(v);
    }

    private static Object path(Object root, Object... keys) {
        return PhpJson.path(root, keys);
    }

    @Test
    @DisplayName("two acting members exchange tasks, stats re-expressed, orders released")
    void swapsTasks() {
        PhpArray c = content();

        DrillAction.applySwapTask(c, actionRow(1), 2);

        PhpArray m1 = (PhpArray) path(c, "members", 1L);
        PhpArray m2 = (PhpArray) path(c, "members", 2L);

        // tasks exchanged
        assertThat(m1.get("task")).isEqualTo("2-evaluate");
        assertThat(m1.get("taskExp")).isEqualTo("Evaluate");
        assertThat(m2.get("task")).isEqualTo("Inspect");
        assertThat(m2.get("taskExp")).isEqualTo("Inspection");

        // stats re-expressed at the new task's weights (intval truncation)
        assertThat(num(m1.get("skillsValue"))).isEqualTo(36);      // intval(60/0.5*0.3)
        assertThat(num(m2.get("skillsValue"))).isEqualTo(66);      // intval(40/0.3*0.5)

        // morale is held in both cases (swap-task hold for m1, a would-be drop for m2)
        assertThat(num(m1.get("moraleValue"))).isEqualTo(50);
        assertThat(num(m2.get("moraleValue"))).isEqualTo(30);

        // the temporary evaluate-* action strings are restored
        assertThat(m1.get("action")).isEqualTo("_u.a_coach");
        assertThat(m2.get("action")).isEqualTo("_u.a_coach");

        // m1 was working on order 2 -> nullified and released
        assertThat(num(path(c, "orders", 2L, "state"))).isEqualTo(-1);
        assertThat(num(m1.get("workingOn"))).isZero();
        assertThat(num(m2.get("workingOn"))).isZero();
    }

    @Test
    @DisplayName("without swapTask the tasks are left alone; a single actor never swaps")
    void noSwap() {
        PhpArray c = content();
        DrillAction.applySwapTask(c, actionRow(0), 2);
        assertThat(path(c, "members", 1L, "task")).isEqualTo("Inspect");

        PhpArray one = content();
        ((PhpArray) path(one, "members", 2L)).put("actioned", 0);
        DrillAction.applySwapTask(one, actionRow(1), 2);
        assertThat(path(one, "members", 1L, "task")).isEqualTo("Inspect");
        assertThat(num(path(one, "orders", 2L, "state"))).isEqualTo(3);   // order untouched
    }
}
