package com.wlwl.backend.service;

import com.wlwl.backend.db.PhpArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the drill/commonLib.php helpers ported so far. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DrillCommonTest {

    @Autowired
    private JdbcTemplate jdbc;

    private static PhpArray content(int maxMembers) {
        PhpArray c = new PhpArray();
        // real blobs store maxMembers as a STRING
        c.child("control").put("maxMembers", String.valueOf(maxMembers));
        c.child("members");
        c.child("staffMsg");
        c.child("bossMsg");
        c.child("history");
        c.child("debug");
        return c;
    }

    @Test
    @DisplayName("readParams: the whole params_int table as key -> value")
    void readParams() {
        // The schema now seeds params_int (the drill engine's tuning values), so probe by
        // changing one of them instead of inserting a duplicate primary key.
        jdbc.update("update params_int set `value` = '4' where `key` = 'ignored_tolerance_days'");
        assertThat(DrillCommon.readParams(jdbc))
                .containsEntry("ignored_tolerance_days", "4")
                .containsEntry("target_method", "1");
    }

    @Test
    @DisplayName("initMemberValues: unread messages decay listen, read ones restore the tolerance")
    void initMemberValues() {
        PhpArray c = content(2);
        PhpArray m1 = c.child("members").child(1);
        m1.put("staffName", "A");
        m1.put("msgCount", 1);
        m1.put("readMsg", 1);
        m1.put("listen", 0);
        PhpArray m2 = c.child("members").child(2);
        m2.put("staffName", "B");
        m2.put("msgCount", 1);
        m2.put("readMsg", 0);
        m2.put("listen", 5);

        DrillCommon.initMemberValues(c, Map.of("ignored_tolerance_days", "4"));

        assertThat(m1.get("listen")).isEqualTo("4");
        assertThat(m1.get("msgCount")).isEqualTo(0);
        assertThat(m2.get("listen")).isEqualTo(4.0);
        assertThat(m2.get("msgCount")).isEqualTo(1);
        assertThat(((PhpArray) c.get("staffMsg")).get("count")).isEqualTo(0);
    }

    @Test
    @DisplayName("initMemberValues: the reset is a STRICT check, so the string \"0\" also resets")
    void readMsgStringZeroStillResets() {
        PhpArray c = content(1);
        PhpArray m = c.child("members").child(1);
        m.put("staffName", "A");
        m.put("msgCount", 3);
        m.put("readMsg", "0");
        m.put("listen", 2);

        DrillCommon.initMemberValues(c, Map.of());
        assertThat(m.get("msgCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("noMoreStaff: any named slot means staff remain")
    void noMoreStaff() {
        PhpArray withStaff = content(2);
        withStaff.child("members").child(2).put("staffName", "B");
        assertThat(DrillCommon.noMoreStaff(withStaff)).isFalse();

        PhpArray empty = content(2);
        empty.child("members").child(1).put("staffName", "");
        assertThat(DrillCommon.noMoreStaff(empty)).isTrue();
    }

    @Test
    @DisplayName("writeHistory appends one ten-field row")
    void writeHistory() {
        PhpArray c = content(1);
        DrillCommon.writeHistory(c, 7, 3, "A", "Sales", "ACTION", "coaching", 55, 60, 62, "m");
        DrillCommon.writeHistory(c, 8, 3, "B", "Sales", "ACTION", "training", 56, 61, 63, "m2");

        PhpArray h = (PhpArray) c.get("history");
        assertThat(h.isList()).isTrue();
        assertThat(h.toJsonNode().size()).isEqualTo(2);
        assertThat(((PhpArray) h.get(0)).get("act")).isEqualTo("coaching");
        assertThat(((PhpArray) h.get(1)).get("step")).isEqualTo(8);
    }

    @Test
    @DisplayName("dbg appends a timestamped line to content.debug")
    void dbg() {
        PhpArray c = content(1);
        DrillCommon.dbg(c, "No More Staff", true);
        assertThat(c.toJsonNode().toString()).contains("No More Staff: true");
    }

    /** 3 slots, two of them named. */
    private static PhpArray team() {
        PhpArray c = content(3);
        PhpArray ms = c.child("members");
        ms.child(1).put("staffName", "Ana");
        ms.child(1).put("staffExp", "e1");
        ms.child(1).put("moraleValue", 70);
        ms.child(1).put("efficiencyValue", 50);
        ms.child(2).put("staffName", "");
        ms.child(3).put("staffName", "Budi");
        ms.child(3).put("staffExp", "e3");
        ms.child(3).put("moraleValue", 30);
        ms.child(3).put("efficiencyValue", 90);
        return c;
    }

    @Test
    @DisplayName("currentMembers counts only slots holding a name")
    void currentMembers() {
        assertThat(DrillCommon.currentMembers(team())).isEqualTo(2);
    }

    @Test
    @DisplayName("lowMorale picks the lowest; ties keep the FIRST member")
    void lowMorale() {
        PhpArray t = team();
        DrillCommon.Pick p = DrillCommon.lowMorale(t);
        assertThat(p.name()).isEqualTo("Budi");
        assertThat(p.exp()).isEqualTo("e3");

        // equal morale -> strict < means the earlier member is kept
        PhpArray tie = team();
        ((PhpArray) tie.child("members").get(1)).put("moraleValue", 30);
        assertThat(DrillCommon.lowMorale(tie).name()).isEqualTo("Ana");
    }

    @Test
    @DisplayName("highEfficiency picks the highest, lowEfficiency the lowest")
    void efficiencyExtremes() {
        assertThat(DrillCommon.highEfficiency(team()).name()).isEqualTo("Budi");
        assertThat(DrillCommon.lowEfficiency(team()).name()).isEqualTo("Ana");
    }

    @Test
    @DisplayName("memberWhere maps a name to its slot, or -1")
    void memberWhere() {
        assertThat(DrillCommon.memberWhere(team(), "Budi")).isEqualTo(3);
        assertThat(DrillCommon.memberWhere(team(), "Nobody")).isEqualTo(-1);
    }

    @Test
    @DisplayName("anyOne always returns a named member")
    void anyOne() {
        PhpArray t = team();
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < 50; i++) {
            DrillCommon.Pick p = DrillCommon.anyOne(t, rnd);
            assertThat(p.name()).isIn("Ana", "Budi");
        }
    }

    @Test
    @DisplayName("an empty team does not hang the extremes (PHP would spin)")
    void emptyTeamExtremes() {
        PhpArray empty = content(3);
        empty.child("members").child(1).put("staffName", "");
        assertThat(DrillCommon.lowMorale(empty).name()).isNull();
        assertThat(DrillCommon.anyOne(empty, new java.util.Random(1)).name()).isNull();
    }
}
