package com.wlwl.backend.service;

import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the commonLib maths that environment.php needs: cal_impacts, cal_affect,
 * writeSummary.
 *
 * The numbers are hand-checkable on purpose. Profile row for 'Offshore':
 *   skillsImpact 10 x sensitivity 0.5 = 5, moraleImpact 5 x 0.5 = 2.5 -> round() 3,
 *   efficiencyImpact 2 x 1.0, skillseffect band for morale 53 = 0.80 -> 5*0.8 + 2 = 6.
 * Impact: 60/50/70 -> 65/53/76, added 14, addedAvg round(14/3) = 5.
 * Affect (skillsAffect -4, moraleAffect -2): -> 63/52/76, added 11, addedAvg 4.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DrillImpactMathTest {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void profileRows() {
        jdbc.update("delete from profileimpact where impactClass = 'Offshore'");
        jdbc.update("insert into profileimpact (impactClass, desiredStyle, skillsClass, moraleClass, "
                + "efficiencyClass, skillsImpact, moraleImpact, efficiencyImpact, impactMsg, "
                + "skillsAffect, moraleAffect, efficiencyAffect, affectMsg) "
                + "values ('Offshore', '2-coach', 'P1', 'P2', 'P3', 10, 5, 2, 'bad', -4, -2, -3, 'ouch')");
        jdbc.update("delete from profilesens where skillsClass = 'P1' and moraleClass = 'P2' "
                + "and efficiencyClass = 'P3'");
        jdbc.update("insert into profilesens (skillsClass, moraleClass, efficiencyClass, "
                + "skillsSensitivity, moraleSensitivity, efficiencySensitivity) "
                + "values ('P1', 'P2', 'P3', 0.50, 0.50, 1.00)");
        // A second row whose skills band reaches 95, to exercise the 100-point clamp.
        jdbc.update("delete from profileimpact where impactClass = 'Boost'");
        jdbc.update("insert into profileimpact (impactClass, skillsClass, moraleClass, "
                + "efficiencyClass, skillsImpact, moraleImpact, efficiencyImpact) "
                + "values ('Boost', 'P4', 'P2', 'P3', 10, 0, 0)");
        jdbc.update("insert into profilesens (skillsClass, moraleClass, efficiencyClass, "
                + "skillsSensitivity, moraleSensitivity, efficiencySensitivity) "
                + "values ('P4', 'P2', 'P3', 1.00, 1.00, 1.00)");
    }

    private static PhpArray content() {
        PhpArray c = new PhpArray();
        c.child("control").put("maxMembers", "2");
        c.child("debug");
        c.child("staffMsg");
        c.child("bossMsg").put("count", 0L);
        PhpArray m = c.child("members").child(1L);
        m.put("staffName", "A");
        m.put("staffExp", "Ann");
        m.put("task", "Inspect");
        m.put("skillsValue", "60");
        m.put("moraleValue", "50");
        m.put("efficiencyValue", "70");
        m.put("skillsAdded", 0L);
        m.put("moraleAdded", 0L);
        m.put("efficiencyAdded", 0L);
        m.put("added", 0L);
        m.put("addedAvg", 0L);
        m.put("msgCount", 0L);
        m.put("readMsg", 0L);
        m.put("trend", 0L);
        m.put("touched", 0L);
        c.child("orderStatus").put("ordersCompleted", 4L);
        return c;
    }

    private static PhpArray member(PhpArray c, int i) {
        return (PhpArray) PhpJson.path(c, "members", (long) i);
    }

    @Test
    @DisplayName("cal_impacts: skills/morale move by impact x sensitivity, efficiency by the band")
    void calImpacts() {
        PhpArray c = content();

        DrillCommon.calImpacts(jdbc, c, 1, "Offshore", 1.0, 0, 0, 0);

        PhpArray m = member(c, 1);
        assertThat(PhpJson.num(m.get("skillsValue"))).isEqualTo(65);
        assertThat(PhpJson.num(m.get("skillsAdded"))).isEqualTo(5);
        assertThat(PhpJson.num(m.get("moraleValue"))).isEqualTo(53);      // round(2.5) away from 0
        assertThat(PhpJson.num(m.get("moraleAdded"))).isEqualTo(3);
        assertThat(PhpJson.num(m.get("efficiencyValue"))).isEqualTo(76);  // 5*0.8 + 2
        assertThat(PhpJson.num(m.get("efficiencyAdded"))).isEqualTo(6);
        assertThat(PhpJson.num(m.get("added"))).isEqualTo(14);
        assertThat(PhpJson.num(m.get("addedAvg"))).isEqualTo(5);
        assertThat(PhpJson.num(m.get("maxMorale"))).isEqualTo(53);
        assertThat(PhpJson.num(m.get("touched"))).isEqualTo(1);
        // the impact message is queued on the member
        assertThat(PhpJson.num(m.get("msgCount"))).isEqualTo(1);
        assertThat(PhpJson.path(m, "message", 1L)).isEqualTo("bad");
        assertThat(PhpJson.num(m.get("readMsg"))).isEqualTo(0);
        // dbg() is part of the document, exactly like the real blobs
        assertThat(PhpJson.path(c, "debug").toString()).contains("Ann/Offshore");
    }

    @Test
    @DisplayName("cal_impacts clamps at 100 skills / 120 efficiency and records only the real delta")
    void calImpactsClamps() {
        PhpArray c = content();
        PhpArray m = member(c, 1);
        m.put("skillsValue", "94");

        DrillCommon.calImpacts(jdbc, c, 1, "Boost", 1.0, 0, 0, 0);

        assertThat(PhpJson.num(m.get("skillsValue"))).isEqualTo(100);
        // only the delta that actually happened is recorded (94 -> 100, not +10)
        assertThat(PhpJson.num(m.get("skillsAdded"))).isEqualTo(6);
    }

    @Test
    @DisplayName("cal_affect: the bystander uses skillsAffect/affectMsg, not the impact columns")
    void calAffect() {
        PhpArray c = content();
        member(c, 1).put("staffName", "A");

        DrillCommon.calAffect(jdbc, c, 1, "Offshore", 0, 0, 0);

        PhpArray m = member(c, 1);
        assertThat(PhpJson.num(m.get("skillsValue"))).isEqualTo(58);      // 60 - 4*0.5
        assertThat(PhpJson.num(m.get("skillsAdded"))).isEqualTo(-2);
        assertThat(PhpJson.num(m.get("moraleValue"))).isEqualTo(49);      // 50 - 2*0.5
        assertThat(PhpJson.num(m.get("moraleAdded"))).isEqualTo(-1);
        assertThat(PhpJson.path(m, "message", 1L)).isEqualTo("ouch");
        assertThat(PhpJson.num(m.get("added"))).isEqualTo(-3);
    }

    @Test
    @DisplayName("cal_affect stamps actEffect on a net loss only")
    void calAffectNetLoss() {
        PhpArray c = content();
        member(c, 1).put("moraleValue", "25");   // inside P2's band (20..80)

        DrillCommon.calAffect(jdbc, c, 1, "Offshore", 0, 0, 0);

        PhpArray m = member(c, 1);
        assertThat(PhpJson.num(PhpJson.path(m, "actEffect", "Offshore"))).isEqualTo(2);  // null + 2
    }

    @Test
    @DisplayName("writeSummary: averages divide by maxMembers, not by the live headcount")
    void writeSummaryDividesByTemplateHeadcount() {
        PhpArray c = content();
        PhpArray m2 = c.child("members").child(2L);
        m2.put("staffName", "B");
        m2.put("staffExp", "Bob");
        m2.put("skillsValue", "40");
        m2.put("moraleValue", "30");
        m2.put("efficiencyValue", "50");
        m2.put("skillsAdded", 0L);
        m2.put("moraleAdded", 0L);
        m2.put("efficiencyAdded", 0L);
        m2.put("msgCount", 0L);
        m2.put("trend", 0L);
        m2.put("actionExp", "_u.e_badprod");

        DrillCommon.writeSummary(c, "ENVIRONMENT", 7L, 3L);

        assertThat(PhpJson.num(PhpJson.path(c, "summary", 0L, "step"))).isEqualTo(7);
        assertThat(PhpJson.num(PhpJson.path(c, "summary", 0L, "day"))).isEqualTo(3);
        assertThat(PhpJson.path(c, "summary", 0L, "phase")).isEqualTo("ENVIRONMENT");
        // team totals are plain sums...
        assertThat(PhpJson.num(PhpJson.path(c, "summary", 0L, "team", "skills"))).isEqualTo(100);
        assertThat(PhpJson.num(PhpJson.path(c, "summary", 0L, "team", "morale"))).isEqualTo(80);
        assertThat(PhpJson.num(PhpJson.path(c, "summary", 0L, "team", "efficiency"))).isEqualTo(120);
        // ...while the averages divide by control.maxMembers (2 named here, so no distortion)
        assertThat(PhpJson.num(PhpJson.path(c, "summary", 0L, "teamAvg", "skills"))).isEqualTo(50);
        assertThat(PhpJson.num(PhpJson.path(c, "summary", 0L, "teamAvg", "efficiency"))).isEqualTo(60);
        assertThat(PhpJson.num(PhpJson.path(c, "summary", 0L, "teamAvg", "total"))).isEqualTo(50);
        // per-member block, and ENVIRONMENT uses actionExp rather than styleExp
        assertThat(PhpJson.path(c, "summary", 0L, "member", "B", "action")).isEqualTo("_u.e_badprod");
        assertThat(PhpJson.num(PhpJson.path(c, "summary", 0L, "member", "A", "total"))).isEqualTo(60);
        assertThat(PhpJson.num(PhpJson.path(c, "summary", 0L, "orderDone"))).isEqualTo(4);
        // current mirrors the summary and totalAdd is measured against the previous value
        assertThat(PhpJson.num(PhpJson.path(c, "current", "team", "total"))).isEqualTo(300);
        assertThat(PhpJson.num(PhpJson.path(c, "current", "teamAvg", "total"))).isEqualTo(50);
        assertThat(PhpJson.num(PhpJson.path(c, "current", "teamAvg", "totalAdd"))).isEqualTo(50);
    }

    @Test
    @DisplayName("writeSummary: the trend block runs for empty slots too, reusing the last stotal")
    void writeSummaryTrendOnEmptySlot() {
        PhpArray c = content();                 // slot 2 is left empty on purpose
        DrillCommon.writeSummary(c, "MORE_ACTIONS", 2L, 1L);

        // empty slot: staffName missing -> not counted, but trend still moves
        assertThat(PhpJson.path(c, "summary", 0L, "member", "A", "staff")).isEqualTo("Ann");
        PhpArray slot2 = (PhpArray) PhpJson.path(c, "members", 2L);
        assertThat(slot2).isNotNull();
        // teamAvg divides by maxMembers (2) although only one member is present
        assertThat(PhpJson.num(PhpJson.path(c, "summary", 0L, "teamAvg", "skills"))).isEqualTo(30);
        assertThat(PhpJson.num(slot2.get("trend"))).isZero();      // 0 + (no total) - stotal(0)
    }

    @Test
    @DisplayName("cal_impacts' evaluate branch records the verdict WITHOUT touching the stats")
    void evaluateBranchRecordsVerdict() {
        jdbc.update("insert into action (actionName, actionType, parentAction, seq, show) "
                + "values ('evaluate-Inspect', 2, 'evaluate', 9, 1)");
        PhpArray c = content();
        // cal_swap resolves both the current and the target task through content.task_eff
        PhpArray te = new PhpArray();
        te.put("taskName", "Inspect");
        te.put("taskDisplayName", "Inspect");
        te.put("skillsWeight", 0.5);
        te.put("moraleWeight", 0.5);
        c.child("task_eff").append(te);
        PhpArray m = (PhpArray) PhpJson.path(c, "members", 1L);
        // the action string names the task being evaluated after the "evaluate-" prefix
        m.put("action", "evaluate-Inspect");
        double skillsBefore = PhpJson.num(m.get("skillsValue"));
        double moraleBefore = PhpJson.num(m.get("moraleValue"));
        double efficiencyBefore = PhpJson.num(m.get("efficiencyValue"));

        DrillCommon.calImpacts(jdbc, c, 1, "evaluate-Inspect", 1.0, 0, 0, 0);

        // cal_swap ran on a COPY: the evaluation itself changes nothing about the member
        assertThat(PhpJson.num(m.get("skillsValue"))).isEqualTo(skillsBefore);
        assertThat(PhpJson.num(m.get("moraleValue"))).isEqualTo(moraleBefore);
        assertThat(PhpJson.num(m.get("efficiencyValue"))).isEqualTo(efficiencyBefore);

        // the verdict is recorded and the action is consumed
        assertThat(PhpJson.num(m.get("evaluated"))).isEqualTo(1);
        assertThat(m.get("action")).isEqualTo("");
        assertThat((String) m.get("evalResult")).startsWith("Inspect: ");
        assertThat(m.get("evaluatedFor")).isEqualTo("Inspect");
        // staffMsg had no "count" key in this fixture, so PHP increments null -> 1 and
        // writes the entry under the "" key (array[null] === array[""]). Faithful.
        assertThat(PhpJson.num(PhpJson.path(c, "staffMsg", "count"))).isEqualTo(1);
    }
}
