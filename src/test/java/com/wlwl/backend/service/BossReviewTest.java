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
 * Parity tests for drill/bossreview.php.
 *
 * The fixture seeds three bossreview rows (see schema-test.sql):
 *   1  SELECT_STYLE  applyAll 0  impact 100..100  management 1  efficiencyChange 1  'Nice pick'
 *   2  SELECT_STYLE  applyAll 0  impact 0..100    management 1  efficiencyChange 0  'Boss shrugs'
 *   3  MORE_ACTIONS  applyAll 1  any rate         management 2  efficiencyChange 0  'Keep going'
 * so a unanimous "all good" step hits row 1, anything less hits row 2, and an action step
 * with managementAction 2 hits row 3.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BossReviewTest {

    @Autowired
    private JdbcTemplate jdbc;

    /** member 1 always acted; {@code m2Actioned} decides whether member 2 did or was a bystander. */
    private static PhpArray content(boolean withMemberTwo, boolean m2Actioned) {
        PhpArray c = new PhpArray();
        c.child("control").put("maxMembers", "2");
        c.child("control").put("action", "coaching");
        c.child("control").put("actionExp", "_u.a_coach");

        PhpArray m1 = c.child("members").child(1L);
        m1.put("staffName", "A");
        m1.put("staffExp", "Ann");
        m1.put("added", 4);
        m1.put("efficiencyAdded", 6);
        m1.put("emotion", 0);
        m1.put("actionExp", "_u.a_coach");
        m1.put("actioned", 1);

        if (withMemberTwo) {
            PhpArray m2 = c.child("members").child(2L);
            m2.put("staffName", "B");
            m2.put("staffExp", "Bob");
            m2.put("added", m2Actioned ? 2 : -3);
            m2.put("efficiencyAdded", 0);
            m2.put("emotion", 0);
            m2.put("actionExp", "_u.a_coach");
            m2.put("actioned", m2Actioned ? 1 : 0);
        }
        return c;
    }

    private static Map<String, Object> action(int noImpact, int applyAll, int management) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("actionName", "coaching");
        a.put("noImpact", noImpact);
        a.put("applyAll", applyAll);
        a.put("managementAction", management);
        return a;
    }

    private static double num(Object v) {
        return PhpJson.num(v);
    }

    private static Object path(Object root, Object... keys) {
        return PhpJson.path(root, keys);
    }

    @Test
    @DisplayName("SELECT_STYLE: everybody counts as IMPACT, unanimous approval picks the good row")
    void selectStyleAllApprove() {
        PhpArray c = content(true, true);

        BossReview.run(jdbc, c, action(0, 1, 1), "SELECT_STYLE");

        // applyAll is forced to 0 for SELECT_STYLE, regardless of the action row
        assertThat(num(path(c, "bossReview", 0L, "action"))).isZero();
        assertThat(path(c, "bossReview", 0L, "phase")).isEqualTo("SELECT_STYLE");
        assertThat(num(path(c, "bossReview", 0L, "impactApprove"))).isEqualTo(100);
        assertThat(num(path(c, "bossReview", 0L, "impactReject"))).isZero();
        assertThat(num(path(c, "bossReview", 0L, "affectApprove"))).isZero();
        assertThat(path(c, "bossReview", 0L, "actionName")).isEqualTo("coaching");
        assertThat(path(c, "bossReview", 0L, "actionExp")).isEqualTo("_u.a_coach");

        // row 1 has efficiencyChange 1 and the team's efficiency went up -> "_e" suffix
        assertThat(num(path(c, "bossRev", "count"))).isEqualTo(1);
        assertThat(str(path(c, "bossRev", "description", 0L))).isEqualTo("Nice pick_e");
        // the action is logged as [actionName, noImpact, managementAction]
        assertThat(((PhpArray) path(c, "log", "action", 0L)).toJsonNode().toString())
                .isEqualTo("[\"coaching\",0,1]");
    }

    @Test
    @DisplayName("MORE_ACTIONS: one actor and one bystander, and no '_e' when the row says 0")
    void mixedImpactAndAffect() {
        PhpArray c = content(true, false);   // member 1 actioned +4, member 2 affected -3

        BossReview.run(jdbc, c, action(0, 1, 2), "MORE_ACTIONS");

        // member 1 acted -> impact (100% approve); member 2 has added != 0 -> affect (100% reject)
        assertThat(num(path(c, "bossReview", 0L, "impactApprove"))).isEqualTo(100);
        assertThat(num(path(c, "bossReview", 0L, "affectReject"))).isEqualTo(100);
        assertThat(num(path(c, "bossReview", 0L, "affectApprove"))).isZero();

        assertThat(str(path(c, "bossRev", "description", 0L))).isEqualTo("Keep going");
        assertThat(num(path(c, "bossRev", "count"))).isEqualTo(1);
    }

    @Test
    @DisplayName("Selecting a style still reviews even when the action is flagged noImpact")
    void selectStyleIgnoresNoImpact() {
        PhpArray c = content(false, true);

        BossReview.run(jdbc, c, action(1, 0, 1), "SELECT_STYLE");

        assertThat(num(path(c, "bossReview", 0L, "impactApprove"))).isEqualTo(100);
        assertThat(num(path(c, "bossRev", "count"))).isEqualTo(1);
    }

    @Test
    @DisplayName("A noImpact action outside SELECT_STYLE only appends to the log")
    void noImpactIsSkipped() {
        PhpArray c = content(true, true);

        BossReview.run(jdbc, c, action(1, 1, 1), "MORE_ACTIONS");

        assertThat(c.containsKey("bossReview")).isFalse();
        assertThat(c.containsKey("bossRev")).isFalse();
        // the log append happens before the early return
        assertThat(((PhpArray) path(c, "log", "action", 0L)).toJsonNode().toString())
                .isEqualTo("[\"coaching\",1,1]");
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }
}
