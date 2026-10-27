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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the last four commonLib helpers: weekSummary, setEnvironment,
 * ana_style, ana_action.
 *
 * The analysis schema they write into is the one drill/init.php creates: style names and
 * the three comparison buckets share `analysis.style`, and `action` carries only the MAIN
 * actions while `staff` is keyed by staff name.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DrillAnalysisMathTest {

    @Autowired
    private JdbcTemplate jdbc;

    private static PhpArray content() {
        PhpArray c = new PhpArray();
        c.put("orderValue", 1000L);
        c.child("control").put("maxMembers", "1");
        c.child("control").put("totalOrders", 3);

        PhpArray m = c.child("members").child(1L);
        m.put("staffName", "A");
        m.put("staffExp", "Ann");
        m.put("style", "Formal");
        m.put("added", 10);

        // day 3 is in week 1, day 6 in week 2
        c.child("day").child(3L).put("week", 1);
        c.child("day").child(6L).put("week", 2);
        c.child("day").child(1L).put("week", 1);
        c.child("week").child(1L).put("weekNumber", 1);
        c.child("week").child(2L).put("weekNumber", 2);

        // two closed deals (state 5, no work left) on different weeks, one still open
        PhpArray o1 = c.child("orders").child(1L);
        o1.put("state", 5);
        o1.put("workNeeded", 0);
        o1.child("log").child(5L).put("completedDay", 3);
        PhpArray o2 = c.child("orders").child(2L);
        o2.put("state", 5);
        o2.put("workNeeded", 0);
        o2.child("log").child(5L).put("completedDay", 6);
        PhpArray o3 = c.child("orders").child(3L);
        o3.put("state", 4);
        o3.put("workNeeded", 0);

        // analysis skeleton exactly as init.php lays it out
        PhpArray analysis = c.child("analysis");
        PhpArray style = analysis.child("style");
        style.child("Formal").put("count", 0L);
        style.child("Formal").put("positive", 0L);
        style.child("Formal").put("negative", 0L);
        style.put("total", 0);
        for (String bucket : new String[]{"desire_actual", "desire_intent", "intent_actual"}) {
            style.child(bucket).put("count", 0);
            style.child(bucket).put("positive", 0);
            style.child(bucket).put("negative", 0);
        }
        analysis.child("action_style").child("Formal");
        analysis.child("action_style").put("total", 0);
        PhpArray action = analysis.child("action");
        action.child("Plan").put("count", 0);
        action.child("Plan").put("positive", 0);
        action.child("Plan").put("negative", 0);
        action.put("total", 0);
        PhpArray staff = analysis.child("staff");
        staff.child("A").put("total", 0);
        staff.child("A").put("positive", 0);
        staff.child("A").put("negative", 0);
        staff.child("A").child("Plan").put("count", 0);
        staff.child("A").child("Plan").put("positive", 0);
        staff.child("A").child("Plan").put("negative", 0);
        return c;
    }

    private static double num(Object v) {
        return PhpJson.num(v);
    }

    private static Object path(Object root, Object... keys) {
        return PhpJson.path(root, keys);
    }

    // ------------------------------------------------------------- weekSummary

    @Test
    @DisplayName("weekSummary: per-week totals plus cumulative running totals across weeks")
    void weekSummary() {
        PhpArray c = content();

        DrillCommon.weekSummary(c);

        // week 1: one deal -> weekOrder 1, cumulative order 1
        assertThat(num(path(c, "week", 1L, "weekOrder"))).isEqualTo(1);
        assertThat(num(path(c, "week", 1L, "weekValue"))).isEqualTo(1000);
        assertThat(num(path(c, "week", 1L, "order"))).isEqualTo(1);
        assertThat(num(path(c, "week", 1L, "value"))).isEqualTo(1000);
        // week 2: one deal again, but the cumulative counters are now at 2
        assertThat(num(path(c, "week", 2L, "weekOrder"))).isEqualTo(1);
        assertThat(num(path(c, "week", 2L, "weekValue"))).isEqualTo(1000);
        assertThat(num(path(c, "week", 2L, "order"))).isEqualTo(2);
        assertThat(num(path(c, "week", 2L, "value"))).isEqualTo(2000);
    }

    @Test
    @DisplayName("weekSummary: an order not at state 5 is ignored, and counters restart at 0")
    void weekSummaryIgnoresOpenOrders() {
        PhpArray c = content();
        c.child("orders").child(3L).put("state", 5);   // third order now closed too

        DrillCommon.weekSummary(c);                     // only day-less log -> week null

        // order 3 is closed but has no log[5].completedDay, so it belongs to week '' and
        // the two real weeks keep a single deal each
        assertThat(num(path(c, "week", 1L, "weekOrder"))).isEqualTo(1);
        assertThat(num(path(c, "week", 2L, "weekOrder"))).isEqualTo(1);
    }

    // ---------------------------------------------------------- setEnvironment

    @Test
    @DisplayName("setEnvironment: the event is scheduled for TOMORROW and attributed by slot")
    void setEnvironmentForMember() {
        PhpArray c = content();
        c.child("day").child(5L).child("event").put("count", 0);

        DrillCommon.setEnvironment(jdbc, c, "Offshore", 1, 4L);

        assertThat(num(path(c, "day", 5L, "event", "count"))).isEqualTo(1);
        assertThat(str(path(c, "day", 5L, "event", "eventName", 0L))).isEqualTo("Offshore");
        assertThat(str(path(c, "day", 5L, "event", "eventExp", 0L))).isEqualTo("OF");   // baseinfo
        assertThat(str(path(c, "day", 5L, "event", "staffName", 0L))).isEqualTo("A");
        assertThat(str(path(c, "day", 5L, "event", "staffExp", 0L))).isEqualTo("Ann");
        assertThat(num(path(c, "day", 5L, "event", "criteria", 0L))).isZero();
    }

    @Test
    @DisplayName("setEnvironment: slot 0 means company-wide, and an unknown name does nothing")
    void setEnvironmentCompanyWide() {
        PhpArray c = content();
        c.child("day").child(5L).child("event").put("count", 0);

        DrillCommon.setEnvironment(jdbc, c, "Offshore", 0, 4L);
        assertThat(str(path(c, "day", 5L, "event", "staffName", 0L))).isEmpty();
        assertThat(str(path(c, "day", 5L, "event", "staffExp", 0L))).isEmpty();

        DrillCommon.setEnvironment(jdbc, c, "NoSuchEvent", 1, 4L);
        assertThat(num(path(c, "day", 5L, "event", "count"))).isEqualTo(1);   // unchanged
    }

    // ---------------------------------------------------------------- ana_style

    @Test
    @DisplayName("ana_style: intent vs desired feeds style totals and desire_intent")
    void anaStyle() {
        PhpArray c = content();

        DrillCommon.anaStyle(c, "Formal", "Formal");
        DrillCommon.anaStyle(c, "Formal", "Driver");

        assertThat(num(path(c, "analysis", "style", "total"))).isEqualTo(2);
        assertThat(num(path(c, "analysis", "style", "Formal", "count"))).isEqualTo(2);
        assertThat(num(path(c, "analysis", "style", "Formal", "positive"))).isEqualTo(1);
        assertThat(num(path(c, "analysis", "style", "Formal", "negative"))).isEqualTo(1);
        assertThat(num(path(c, "analysis", "style", "desire_intent", "count"))).isEqualTo(2);
        assertThat(num(path(c, "analysis", "style", "desire_intent", "positive"))).isEqualTo(1);
        assertThat(num(path(c, "analysis", "style", "desire_intent", "negative"))).isEqualTo(1);
    }

    // --------------------------------------------------------------- ana_action

    @Test
    @DisplayName("ana_action: scores the parent action, the staff member and the style trio")
    void anaAction() {
        jdbc.update("update action set actualStyle = 'Formal' where actionName = 'Advise'");
        PhpArray c = content();

        // member added 10 > 0 -> positive
        DrillCommon.anaAction(jdbc, c, 1, "Advise", "Formal");

        assertThat(num(path(c, "analysis", "action", "total"))).isEqualTo(1);
        assertThat(num(path(c, "analysis", "action", "Plan", "count"))).isEqualTo(1);
        assertThat(num(path(c, "analysis", "action", "Plan", "positive"))).isEqualTo(1);
        assertThat(num(path(c, "analysis", "action", "Plan", "negative"))).isZero();

        assertThat(num(path(c, "analysis", "staff", "A", "total"))).isEqualTo(1);
        assertThat(num(path(c, "analysis", "staff", "A", "positive"))).isEqualTo(1);
        assertThat(num(path(c, "analysis", "staff", "A", "Plan", "count"))).isEqualTo(1);

        assertThat(num(path(c, "analysis", "style", "desire_actual", "count"))).isEqualTo(1);
        assertThat(num(path(c, "analysis", "style", "desire_actual", "positive"))).isEqualTo(1);
        assertThat(num(path(c, "analysis", "style", "intent_actual", "positive"))).isEqualTo(1);
        assertThat(num(path(c, "analysis", "action_style", "total"))).isEqualTo(1);
        assertThat(num(path(c, "analysis", "action_style", "Formal", "count"))).isEqualTo(1);
    }

    @Test
    @DisplayName("ana_action: a member with negative added scores the negative bucket")
    void anaActionNegative() {
        jdbc.update("update action set actualStyle = 'Formal' where actionName = 'Advise'");
        PhpArray c = content();
        ((PhpArray) path(c, "members", 1L)).put("added", -5);
        ((PhpArray) path(c, "members", 1L)).put("style", "Driver");

        DrillCommon.anaAction(jdbc, c, 1, "Advise", "Driver");

        assertThat(num(path(c, "analysis", "action", "Plan", "negative"))).isEqualTo(1);
        assertThat(num(path(c, "analysis", "staff", "A", "negative"))).isEqualTo(1);
        // desired Driver != actual Formal -> negative; member style Driver != Formal -> negative
        assertThat(num(path(c, "analysis", "style", "desire_actual", "negative"))).isEqualTo(1);
        assertThat(num(path(c, "analysis", "style", "intent_actual", "negative"))).isEqualTo(1);
    }

    @Test
    @DisplayName("ana_action: no actualStyle means the style trio is left alone")
    void anaActionWithoutStyle() {
        jdbc.update("update action set actualStyle = null where actionName = 'Advise'");
        PhpArray c = content();

        DrillCommon.anaAction(jdbc, c, 1, "Advise", "Formal");

        assertThat(num(path(c, "analysis", "action", "Plan", "count"))).isEqualTo(1);
        assertThat(num(path(c, "analysis", "style", "desire_actual", "count"))).isZero();
        assertThat(num(path(c, "analysis", "action_style", "total"))).isZero();
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }
}
