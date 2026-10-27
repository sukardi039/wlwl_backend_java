package com.wlwl.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpJson;
import com.wlwl.backend.db.PhpRows;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * Port of drill/competency.php::saveCompetency() - the user's evaluation.
 *
 * Three separate scales, each 0-10, summed to totalCompetency (0-50):
 *
 *   skills      start = summary[0].teamAvg.skills, end = current.teamAvg.skills
 *               IMPROVEMENT  ratio = (end-start)/start      >0.5:10 >0.4:9 >0.3:8 >0.2:7 >0.1:6 else 5
 *               DECLINE      ratio = (start-end)/start      <0.15:4 <0.2:3 <0.25:2 else 1
 *   morale      same, but the improvement ratio divides by (100 - start), NOT by start
 *   efficiency  same as morale, on the efficiency triple
 *   leadership  (desire_intent.count + desire_actual.count) vs their positives,
 *               capped at 1, then rounded to 0-10
 *   drive       (ordersCompleted * orderValue) vs valueToComplete, capped at 10
 *
 * Divides by a raw start value without a zero guard, exactly as the PHP does: a start of
 * 0 yields NaN/Infinity, every comparison is false, and the score stays at its default 1.
 *
 * The six scores are written into $content['competency'] BEFORE the snapshot is saved,
 * which is why drillcapable can read them out of the terminal blob. avg_competency is
 * peer averages and is ALL ZEROS unless more than 1000 peers share seq AND duration.
 */
public final class Competency {

    private Competency() {
    }

    public static void save(JdbcTemplate jdbc, JsonNode qdata, PhpArray content) {
        double ssk = PhpJson.num(PhpJson.path(content, "summary", 0, "teamAvg", "skills"));
        double esk = PhpJson.num(PhpJson.path(content, "current", "teamAvg", "skills"));
        int psk = skillsScore(ssk, esk);

        double smr = PhpJson.num(PhpJson.path(content, "summary", 0, "teamAvg", "morale"));
        double emr = PhpJson.num(PhpJson.path(content, "current", "teamAvg", "morale"));
        int pmr = cappedScore(smr, emr);

        double sef = PhpJson.num(PhpJson.path(content, "summary", 0, "teamAvg", "efficiency"));
        double eef = PhpJson.num(PhpJson.path(content, "current", "teamAvg", "efficiency"));
        int pef = cappedScore(sef, eef);

        // leadership: ratio of positives to opportunities across both desire groups
        double sld = PhpJson.num(PhpJson.path(content, "analysis", "style", "desire_intent", "count"))
                + PhpJson.num(PhpJson.path(content, "analysis", "style", "desire_actual", "count"));
        double eld = PhpJson.num(PhpJson.path(content, "analysis", "style", "desire_intent", "positive"))
                + PhpJson.num(PhpJson.path(content, "analysis", "style", "desire_actual", "positive"));
        int pld = 1;
        if (eld > 0 && sld > 0) {
            double cld = eld / sld;
            cld = (cld >= 1) ? 1 : cld;
            pld = (int) Math.round(cld * 10);
        }

        // drive: sales achieved vs sales target
        double sdr = PhpJson.num(PhpJson.path(content, "valueToComplete"));
        double edr = PhpJson.num(PhpJson.path(content, "orderStatus", "ordersCompleted"))
                * PhpJson.num(PhpJson.path(content, "orderValue"));
        int pdr = 1;
        if (sdr > 0 && edr > 0) {
            if (edr > sdr) {
                pdr = 10;
            } else {
                pdr = (int) Math.round((edr / sdr) * 10);
            }
        }

        int ptt = psk + pmr + pef + pld + pdr;

        PhpArray c = content.child("competency");
        c.put("skillsCompetency", psk);
        c.put("moraleCompetency", pmr);
        c.put("efficiencyCompetency", pef);
        c.put("leaderShipCompetency", pld);
        c.put("driveCompetency", pdr);
        c.put("totalCompetency", ptt);

        String drillId = text(qdata, "drillId");

        // who is this, and which seq/duration/companyType bucket do they belong to?
        Map<String, Object> rx = null;
        List<Map<String, Object>> who = PhpRows.query(jdbc,
                "select d.drillId, m.userId, seq, duration, y.companyType "
                        + "from drilldetail d, drill l, drillcontrol c, backenduser m, company y "
                        + "where d.drillId = l.drillId and l.drillControlId = c.drillControlId "
                        + "and c.userId = m.userId and m.companyName = y.packageId "
                        + "and d.drillId = ? order by stepNumber desc limit 1", drillId);
        if (!who.isEmpty()) {
            rx = who.get(0);
            Object userId = PhpRows.get(rx, "userId");
            Object seq = PhpRows.get(rx, "seq");
            List<Map<String, Object>> existing = PhpRows.query(jdbc,
                    "select * from competency where userId = ? and seq = ?", userId, seq);
            if (existing.isEmpty()) {
                jdbc.update("insert into competency (drillId, userId, seq, duration, companyType, "
                                + "updatedOn, startSkills, endSkills, startMorale, endMorale, "
                                + "startEfficiency, endEfficiency, desiredTotal, desiredPositive, "
                                + "salesTarget, salesAchieved, skillsCompetency, moraleCompetency, "
                                + "efficiencyCompetency, leadershipCompetency, driveCompetency, "
                                + "totalCompetency) values (?,?,?,?,?,now(),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        PhpRows.get(rx, "drillId"), userId, seq, PhpRows.get(rx, "duration"), PhpRows.get(rx, "companyType"),
                        ssk, esk, smr, emr, sef, eef, sld, eld, sdr, edr,
                        psk, pmr, pef, pld, pdr, ptt);
            } else {
                jdbc.update("update competency set startSkills = ?, endSkills = ?, startMorale = ?, "
                                + "endMorale = ?, startEfficiency = ?, endEfficiency = ?, "
                                + "skillsCompetency = ?, moraleCompetency = ?, "
                                + "efficiencyCompetency = ?, leadershipCompetency = ?, "
                                + "driveCompetency = ?, totalCompetency = ?, updatedOn = now(), "
                                + "desiredTotal = ?, desiredPositive = ?, salesTarget = ?, "
                                + "salesAchieved = ?, drillId = ? where userId = ? and seq = ?",
                        ssk, esk, smr, emr, sef, eef, psk, pmr, pef, pld, pdr, ptt,
                        sld, eld, sdr, edr, PhpRows.get(rx, "drillId"), userId, seq);
            }
        }

        // Peer averages. NOTE: this runs OUTSIDE the guard above, so when no drill matched it
        // still executes with nulls, finds nothing, and writes zeros - always present.
        Map<String, Object> rt = Map.of();
        if (rx != null) {
            List<Map<String, Object>> peers = PhpRows.query(jdbc,
                    "select count(*) cnt, avg(skillsCompetency) psk, avg(moraleCompetency) pmr, "
                            + "avg(efficiencyCompetency) pef, avg(leaderShipCompetency) pld, "
                            + "avg(driveCompetency) pdr, avg(totalCompetency) ptt "
                            + "from competency where seq = ? and duration = ? and userId <> ?",
                    PhpRows.get(rx, "seq"), PhpRows.get(rx, "duration"), PhpRows.get(rx, "userId"));
            if (!peers.isEmpty()) rt = peers.get(0);
        }

        PhpArray avg = content.child("avg_competency");
        boolean enoughPeers = PhpJson.num(rt.get("cnt")) > 1000;
        avg.put("skillsCompetency", enoughPeers ? rt.get("psk") : 0);
        avg.put("moraleCompetency", enoughPeers ? rt.get("pmr") : 0);
        avg.put("efficiencyCompetency", enoughPeers ? rt.get("pef") : 0);
        avg.put("leaderShipCompetency", enoughPeers ? rt.get("pld") : 0);
        avg.put("driveCompetency", enoughPeers ? rt.get("pdr") : 0);
        avg.put("totalCompetency", enoughPeers ? rt.get("ptt") : 0);
    }

    /** skills: the decline branch divides by start, the improvement branch also by start. */
    private static int skillsScore(double start, double end) {
        if (start >= end) {
            double c = (start - end) / start;
            if (c < 0.15) return 4;
            if (c < 0.20) return 3;
            if (c < 0.25) return 2;
            return 1;
        }
        double c = (end - start) / start;
        if (c > 0.5) return 10;
        if (c > 0.4) return 9;
        if (c > 0.3) return 8;
        if (c > 0.2) return 7;
        if (c > 0.1) return 6;
        return 5;
    }

    /** morale/efficiency: improvement is measured against the REMAINING headroom, 100-start. */
    private static int cappedScore(double start, double end) {
        if (start >= end) {
            double c = (start - end) / start;
            if (c < 0.15) return 4;
            if (c < 0.20) return 3;
            if (c < 0.25) return 2;
            return 1;
        }
        double headroom = 100 - start;
        if (headroom == 0) return 6;
        double c = (end - start) / headroom;
        if (c > 0.9) return 10;
        if (c > 0.8) return 9;
        if (c > 0.6) return 8;
        if (c > 0.4) return 7;
        if (c > 0.2) return 6;
        return 5;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }
}
