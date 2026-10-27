package com.wlwl.backend.service;

import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpJson;
import com.wlwl.backend.db.PhpRows;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * Port of drill/bossreview.php (108 lines) — the boss's reaction to an action.
 *
 * Called at the end of an action step. It counts how the team responded, turns that into
 * four percentages, looks up a `bossreview` row whose ranges contain those percentages,
 * and appends the resulting message to `content.bossRev` (which writeSummary later copies
 * into the step's summary so the client can show it).
 *
 * The three-way member classification is the load-bearing part:
 *   - a member who ACTED (`actioned`) — or anybody at all under SELECT_STYLE — counts as
 *     an IMPACT, split by `added` sign;
 *   - otherwise a member whose `placed` changed (`added != 0`) counts as an AFFECT;
 *   - otherwise a member with `emotion` is noted in `emotionCount`, which the PHP then
 *     never reads. Kept for parity, harmless.
 *
 * `efficiencyChange` in the matched row appends `_e` to the message only when efficiency
 * actually rose across the team (`$effAdd > 0`).
 */
public final class BossReview {

    private BossReview() {
    }

    public static void run(JdbcTemplate jdbc, PhpArray content, Map<String, Object> action, String qAction) {
        // $cont['log']['action'][] = array(actionName, noImpact, managementAction)
        PhpArray logged = new PhpArray();
        logged.put(0L, rowGet(action, "actionName"));
        logged.put(1L, rowGet(action, "noImpact"));
        logged.put(2L, rowGet(action, "managementAction"));
        content.child("log").child("action").append(logged);

        // PHP: if ($action['noImpact'] != 1 || $q->action == 'SELECT_STYLE')
        if (num(rowGet(action, "noImpact")) == 1 && !"SELECT_STYLE".equals(qAction)) return;

        DrillCommon.dbg(content, "bossReview", qAction);

        Object applyAll = rowGet(action, "applyAll");
        if ("SELECT_STYLE".equals(qAction)) applyAll = 0;      // local copy in the PHP too

        long maxMembers = (long) num(PhpJson.path(content, "control", "maxMembers"));
        long impactCount = 0, impactPlus = 0, impactMinus = 0;
        long affectCount = 0, affectPlus = 0, affectMinus = 0;
        long impactEmotionCount = 0, affectEmotionCount = 0;
        double effAdd = 0;

        for (long c = 1; c <= maxMembers; c++) {
            Object mv = PhpJson.path(content, "members", c);
            if (!(mv instanceof PhpArray m)) continue;
            if (!truthy(m.get("staffName"))) continue;

            if (truthy(m.get("actioned")) || "SELECT_STYLE".equals(qAction)) {
                impactCount++;
                double added = num(m.get("added"));
                if (added > 0) impactPlus++;
                else if (added < 0) impactMinus++;
                if (truthy(m.get("emotion")) && str(m.get("actionExp")).indexOf('/') > 0) {
                    impactEmotionCount = 1;
                }
            } else if (num(m.get("added")) != 0) {
                affectCount++;
                if (truthy(m.get("emotion")) && str(m.get("actionExp")).indexOf('/') > 0) {
                    affectEmotionCount = 1;
                }
                if (num(m.get("added")) > 0) affectPlus++;
                else affectMinus++;
            }
            // (the PHP also tallies `$emotionCount` for members with emotion and no change,
            //  then never reads it — nothing to reproduce)

            if (num(m.get("efficiencyAdded")) != 0) {
                effAdd += num(m.get("efficiencyAdded"));
            }
        }

        double impactApprove = impactCount == 0 ? 0 : (double) impactPlus / impactCount * 100;
        double impactReject = impactCount == 0 ? 0 : (double) impactMinus / impactCount * 100;
        double affectApprove = affectCount == 0 ? 0 : (double) affectPlus / affectCount * 100;
        double affectReject = affectCount == 0 ? 0 : (double) affectMinus / affectCount * 100;

        PhpArray reviews = content.child("bossReview");
        PhpArray entry = reviews.child((long) reviews.size());
        entry.put("impactApprove", PhpJson.jsonNumber(impactApprove));
        entry.put("impactReject", PhpJson.jsonNumber(impactReject));
        entry.put("affectApprove", PhpJson.jsonNumber(affectApprove));
        entry.put("affectReject", PhpJson.jsonNumber(affectReject));
        entry.put("impactEmotion", impactEmotionCount);
        entry.put("affectEmotion", affectEmotionCount);
        entry.put("phase", qAction);
        entry.put("action", applyAll);
        entry.put("actionName", PhpJson.path(content, "control", "action"));
        entry.put("actionExp", PhpJson.path(content, "control", "actionExp"));

        // PHP splices the percentages into the SQL as quoted strings; bind them as numbers,
        // which is what MySQL's implicit cast does with '50'.
        List<Map<String, Object>> rows = PhpRows.query(jdbc,
                "select * from bossreview where action = ? and applyAll = ? "
                        + "and impactAppFrom <= ? and impactAppTo >= ? "
                        + "and impactRejFrom <= ? and impactRejTo >= ? "
                        + "and affectAppFrom <= ? and affectAppTo >= ? "
                        + "and affectRejFrom <= ? and affectRejTo >= ? "
                        + "and managementAction = ?",
                // applyAll/managementAction are INT columns. The PHP splices them as quoted
                // strings, so MySQL casts '' to 0 — bind the number instead of "" or H2
                // refuses the comparison outright.
                qAction, (long) num(applyAll),
                impactApprove, impactApprove, impactReject, impactReject,
                affectApprove, affectApprove, affectReject, affectReject,
                (long) num(rowGet(action, "managementAction")));
        if (rows.isEmpty()) return;

        Map<String, Object> row = rows.get(0);
        PhpArray bossRev = content.child("bossRev");
        if (!bossRev.containsKey("count")) bossRev.put("count", 0L);
        long count = (long) num(bossRev.get("count"));

        // comment on efficiency only when efficiency actually went up
        String fx = (truthy(rowGet(row, "efficiencyChange")) && effAdd > 0) ? "_e" : "";
        bossRev.child("description").put(count, str(rowGet(row, "bossMsg")) + fx);
        bossRev.put("count", count + 1);

        DrillCommon.dbg(content, "effAdd", str(rowGet(row, "efficiencyChange")) + ", "
                + PhpJson.jsonNumber(effAdd));
        DrillCommon.dbg(content, "bossRev", fx + ", " + str(rowGet(row, "bossMsg")) + fx);
    }

    private static Object rowGet(Map<String, Object> row, String column) {
        return row == null ? null : PhpRows.get(row, column);
    }

    private static double num(Object v) {
        return PhpJson.num(v);
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        String s = v.toString();
        return !s.isEmpty() && !"0".equals(s);
    }
}
