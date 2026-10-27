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
        jdbc.update("insert into params_int (`key`, `value`) values ('ignored_tolerance_days', '4')");
        assertThat(DrillCommon.readParams(jdbc)).containsEntry("ignored_tolerance_days", "4");
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
}
