package com.wlwl.backend.service;

import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity test for the block that opens drill/action.php's MORE_ACTIONS case: moving
 * actioned recruits into free slots (`$action['actionType'] == 'recruits'`).
 *
 * Fixture staff 2 (Bob) resolves through staffprofile_view with skills 10..90, morale 20..80,
 * efficiency 30..100 — so the rolled values can only be asserted as ranges.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DrillRecruitMathTest {

    @Autowired
    private JdbcTemplate jdbc;

    /** Two slots: 1 is taken, 2 is free and wants the action's task. */
    private static PhpArray content(String freeSlotTask) {
        PhpArray c = new PhpArray();
        c.child("control").put("maxMembers", "2");
        c.child("control").put("task", "Inspect");
        c.child("control").put("taskExp", "Inspection");
        c.child("control").put("action", "recruit");
        c.child("control").put("actionExp", "_u.a_recruit");

        PhpArray m1 = c.child("members").child(1L);
        m1.put("staffName", "1");
        m1.put("staffExp", "Ann");
        m1.put("task", "Inspect");

        PhpArray m2 = c.child("members").child(2L);
        m2.put("staffName", "");
        m2.put("staffExp", "");
        m2.put("task", freeSlotTask);

        PhpArray r0 = c.child("recruits").child(0L);
        r0.put("staffName", "2");
        r0.put("staffExp", "Bob");
        r0.put("actioned", 1);

        c.child("staffs").child(0L).put("staffName", "1");
        c.child("analysis").child("staff").child("1").put("total", 0L);
        return c;
    }

    private static Map<String, Object> params() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("default_style", "Formal");
        p.put("dejected_tolerance_days", "10");
        p.put("dejected_warning_days", "8");
        p.put("ignored_tolerance_days", "5");
        return p;
    }

    private static Map<String, Object> actionRow(String actionType) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("actionName", "recruit");
        a.put("actionType", actionType);
        a.put("parentAction", "recruit");
        return a;
    }

    private static double num(Object v) {
        return PhpJson.num(v);
    }

    private static Object path(Object root, Object... keys) {
        return PhpJson.path(root, keys);
    }

    @Test
    @DisplayName("an actioned recruit fills the free slot, joins staffs and is blanked off the list")
    void recruitFillsSlot() {
        PhpArray c = content("Inspect");

        DrillAction.applyRecruits(jdbc, c, params(), actionRow("recruits"), 2);

        PhpArray m2 = (PhpArray) path(c, "members", 2L);
        assertThat(m2.get("staffName")).isEqualTo("2");
        assertThat(m2.get("staffExp")).isEqualTo("Bob");
        assertThat(m2.get("task")).isEqualTo("Inspect");          // from control.task, not the slot
        assertThat(m2.get("taskExp")).isEqualTo("Inspection");
        assertThat(num(m2.get("introduced"))).isEqualTo(1);
        assertThat(m2.get("style")).isEqualTo("Formal");          // params_int.default_style
        assertThat(m2.get("styleExp")).isEqualTo("FO");           // baseinfo displayKey
        assertThat(m2.get("attention")).isEqualTo("10");
        assertThat(m2.get("listen")).isEqualTo("5");
        // stats rolled from staffprofile_view (P1/P2/P3 for Bob)
        assertThat(num(m2.get("skillsValue"))).isBetween(10.0, 90.0);
        assertThat(num(m2.get("moraleValue"))).isBetween(20.0, 80.0);
        assertThat(num(m2.get("efficiencyValue"))).isBetween(30.0, 100.0);
        // the sniffed initial values mirror the rolled ones
        assertThat(num(m2.get("initSkillsValue"))).isEqualTo(num(m2.get("skillsValue")));
        // analysis.staff seeded for the new hire
        assertThat(num(path(c, "analysis", "staff", "2", "total"))).isZero();
        // staffs grew by one entry
        assertThat(((PhpArray) c.get("staffs")).size()).isEqualTo(2);
        assertThat(path(c, "staffs", 1L, "staffName")).isEqualTo("2");
        // the recruit row is consumed
        assertThat(path(c, "recruits", 0L, "staffName")).isEqualTo("");
        assertThat(num(path(c, "recruits", 0L, "actioned"))).isZero();
    }

    @Test
    @DisplayName("a non-recruit action leaves the slots alone")
    void notARecruitAction() {
        PhpArray c = content("Inspect");

        DrillAction.applyRecruits(jdbc, c, params(), actionRow("coach"), 2);

        assertThat(((PhpArray) path(c, "members", 2L)).get("staffName")).isEqualTo("");
        assertThat(((PhpArray) c.get("staffs")).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a slot with a different task is skipped")
    void mismatchedSlotTask() {
        PhpArray c = content("2-evaluate");   // the free slot is on another task

        DrillAction.applyRecruits(jdbc, c, params(), actionRow("recruits"), 2);

        assertThat(((PhpArray) path(c, "members", 2L)).get("staffName")).isEqualTo("");
        assertThat(num(path(c, "recruits", 0L, "actioned"))).isEqualTo(1);   // still waiting
    }
}
