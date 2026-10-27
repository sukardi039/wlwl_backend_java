package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpJson;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.rpc.ModuleHandler;
import com.wlwl.backend.rpc.RpcContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Port of simulator/ — the drill "what-if" stepper.
 *
 * Two very different halves:
 *
 *  POST  lists one session's steps, newest first. It ALWAYS answers with a JSON array,
 *        even when a query string is present (unlike every other module, which would
 *        collapse to a single object). Faithful.
 *
 *  PUT   appends the next step. Two modes, chosen by whether `lastAction` is empty:
 *        - empty  = INITIALISATION. Rolls a starting skills/morale from the staff
 *          member's profile range (unless the client passes skillsValue !== -1) and an
 *          efficiency roll, then stores skillsWeight/moraleWeight/duration from
 *          taskefficiency. efficiencyValue = skills*wSkills + morale*wMorale.
 *        - present = APPLY AN ACTION. Looks the action up in `impactprofile_view` by
 *          matching the current triple against the skills/morale/efficiency ranges,
 *          then adjusts:
 *              add    = round(sensitivity * impact)
 *              value  = min(100, previous + add)
 *              effAdd = round((skillsAdd*wSkills + moraleAdd*wMorale) * effSensitivity
 *                             + effImpact)
 *          and records modValue, the net change of the triple.
 *
 * The random rolls use PHP's rand($min, $max), which is INCLUSIVE at both ends. Tests
 * therefore assert ranges, not exact values — the original is nondeterministic too.
 */
@Component
public class SimulatorModule implements ModuleHandler {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final JdbcTemplate jdbc;

    public SimulatorModule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "simulator";
    }

    // ---------------------------------------------------------------- POST

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String sessionId = text(env, "mod");

        if (!truthy(sessionId)) {
            ctx.status(406);
            return;
        }

        List<Map<String, Object>> rows = PhpRows.query(jdbc,
                "select * from simulator where sessionId = ? order by seqNumber desc",
                sessionId);
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }

        // Always an ARRAY here — post.php appends unconditionally.
        PhpArray list = new PhpArray();
        for (Map<String, Object> row : rows) list.append(PhpArray.of(row));
        ctx.json(list.toJsonNode());
    }

    // ----------------------------------------------------------------- PUT

    @Override
    public void onPut(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        JsonNode qdata = env == null ? null : env.get("qdata");
        if (qdata == null || qdata.isNull()) qdata = F.objectNode();

        String mod = text(env, "mod");
        String sessionId = text(qdata, "sessionId");
        long staffId = intOf(qdata, "staffId");

        // PHP: if (($mod == $qdata->sessionId) && ($qdata->staffId)) { ... }
        // When this fails the file emits NOTHING: an empty 200.
        if (!phpEquals(mod, sessionId) || staffId == 0) {
            return;
        }

        double seqNumber = num(qdata, "seqNumber");
        long nextSeq = (long) (seqNumber + 1);

        if (text(qdata, "lastAction").isEmpty()) {
            initialise(qdata, sessionId, staffId, nextSeq);
        } else {
            applyAction(qdata, sessionId, staffId, nextSeq);
        }

        List<Map<String, Object>> rows = PhpRows.query(jdbc,
                "select * from simulator where sessionId = ? and seqNumber = ?",
                sessionId, nextSeq);
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }

        if (ctx.queryStringLength() == 0) {
            PhpArray list = new PhpArray();
            for (Map<String, Object> row : rows) list.append(PhpArray.of(row));
            ctx.json(list.toJsonNode());
        } else {
            ctx.json(PhpArray.of(rows.get(rows.size() - 1)).toJsonNode());
        }
    }

    /** First run of a session: roll the profile values, seed weights from taskefficiency. */
    private void initialise(JsonNode qdata, String sessionId, long staffId, long nextSeq) {
        Map<String, Object> prf = first(jdbc, "select * from staffprofile_view where staffId = ?",
                staffId);
        Map<String, Object> eff = first(jdbc,
                "select * from taskefficiency where productName = ? and taskName = ?",
                text(qdata, "productName"), text(qdata, "taskName"));

        long skillsValue;
        long moraleValue;
        // PHP: if ($qdata->skillsValue === -1) { ...rand... } else { use the given values }
        // `===` is STRICT, so only a real number -1 counts; the string "-1" would not.
        if (isStrictMinusOne(qdata.get("skillsValue"))) {
            skillsValue = rand(PhpRows.get(prf, "skillsMin"), PhpRows.get(prf, "skillsMax"));
            moraleValue = rand(PhpRows.get(prf, "moraleMin"), PhpRows.get(prf, "moraleMax"));
        } else {
            skillsValue = (long) num(qdata, "skillsValue");
            moraleValue = (long) num(qdata, "moraleValue");
        }
        long efficiencyValue = rand(PhpRows.get(prf, "efficiencyMin"),
                PhpRows.get(prf, "efficiencyMax"));

        double skillsWeight = num(PhpRows.get(eff, "skillsWeight"));
        double moraleWeight = num(PhpRows.get(eff, "moraleWeight"));

        jdbc.update("insert into simulator (sessionId, seqNumber, productName, taskName, "
                        + "staffId, skillsWeight, moraleWeight, skillsValue, moraleValue, "
                        + "efficiencyValue, productExp, taskExp, staffName) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                sessionId, nextSeq, text(qdata, "productName"), text(qdata, "taskName"),
                staffId, skillsWeight, moraleWeight, skillsValue, moraleValue,
                // computed last, from the values above
                Math.round(skillsValue * skillsWeight + moraleValue * moraleWeight),
                text(qdata, "productExp"), text(qdata, "taskExp"), text(qdata, "staffName"));
    }

    /** Apply one action and store the deltas. */
    private void applyAction(JsonNode qdata, String sessionId, long staffId, long nextSeq) {
        double skills = num(qdata, "skillsValue");
        double morale = num(qdata, "moraleValue");
        double efficiency = num(qdata, "efficiencyValue");
        double skillsWeight = num(qdata, "skillsWeight");
        double moraleWeight = num(qdata, "moraleWeight");

        Map<String, Object> impact = first(jdbc,
                "select * from impactprofile_view where impactClass = ? "
                        + "and (skillsMin <= ? and skillsMax >= ?) "
                        + "and (moraleMin <= ? and moraleMax >= ?) "
                        + "and (efficiencyMin <= ? and efficiencyMax >= ?)",
                text(qdata, "lastAction"), skills, skills, morale, morale, efficiency, efficiency);

        double skillsAdd = PhpJson.round(num(PhpRows.get(impact, "skillsSensitivity"))
                * num(PhpRows.get(impact, "skillsImpact")));
        double moraleAdd = PhpJson.round(num(PhpRows.get(impact, "moraleSensitivity"))
                * num(PhpRows.get(impact, "moraleImpact")));

        double newSkills = Math.min(100, skills + skillsAdd);
        double newMorale = Math.min(100, morale + moraleAdd);
        double efficiencyAdd = PhpJson.round(
                (skillsAdd * skillsWeight + moraleAdd * moraleWeight)
                        * num(PhpRows.get(impact, "efficiencySensitivity"))
                        + num(PhpRows.get(impact, "efficiencyImpact")));
        double newEfficiency = Math.min(100, efficiency + efficiencyAdd);

        double modValue = (newSkills + newMorale + newEfficiency) - (skills + morale + efficiency);

        jdbc.update("insert into simulator (sessionId, seqNumber, productName, taskName, "
                        + "staffId, skillsWeight, moraleWeight, skillsValue, moraleValue, "
                        + "efficiencyValue, productExp, taskExp, staffName, lastAction, "
                        + "actionExp, skillsAddedValue, moraleAddedValue, "
                        + "efficiencyAddedValue, modValue, skillsSensitivity, "
                        + "moraleSensitivity, efficiencySensitivity, skillsImpact, "
                        + "moraleImpact, efficiencyImpact, lastSkillsValue, "
                        + "lastMoraleValue, lastEfficiencyValue) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "?, ?, ?, ?, ?, ?, ?, ?, ?)",
                sessionId, nextSeq, text(qdata, "productName"), text(qdata, "taskName"),
                staffId, skillsWeight, moraleWeight,
                Math.round(newSkills), Math.round(newMorale), Math.round(newEfficiency),
                text(qdata, "productExp"), text(qdata, "taskExp"), text(qdata, "staffName"),
                text(qdata, "lastAction"), text(qdata, "actionExp"),
                Math.round(skillsAdd), Math.round(moraleAdd), Math.round(efficiencyAdd),
                Math.round(modValue),
                num(PhpRows.get(impact, "skillsSensitivity")),
                num(PhpRows.get(impact, "moraleSensitivity")),
                num(PhpRows.get(impact, "efficiencySensitivity")),
                Math.round(num(PhpRows.get(impact, "skillsImpact"))),
                Math.round(num(PhpRows.get(impact, "moraleImpact"))),
                Math.round(num(PhpRows.get(impact, "efficiencyImpact"))),
                Math.round(skills), Math.round(morale), Math.round(efficiency));
    }

    // ------------------------------------------------------------- helpers

    private static Map<String, Object> first(JdbcTemplate jdbc, String sql, Object... args) {
        List<Map<String, Object>> rows = PhpRows.query(jdbc, sql, args);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** PHP rand($min, $max): inclusive both ends, strings cast to int, null becomes 0. */
    private static long rand(Object min, Object max) {
        long lo = (long) PhpJson.num(min);
        long hi = (long) PhpJson.num(max);
        if (lo > hi) {
            long t = lo;
            lo = hi;
            hi = t;
        }
        return ThreadLocalRandom.current().nextLong(lo, hi + 1);
    }

    /** PHP `===` against int -1: a numeric JSON -1 only. */
    private static boolean isStrictMinusOne(JsonNode v) {
        return v != null && v.isIntegralNumber() && v.asLong() == -1;
    }

    /** PHP `==` between two strings of digits is numeric; otherwise a plain compare. */
    private static boolean phpEquals(String a, String b) {
        if (a.equals(b)) return true;
        try {
            return Double.parseDouble(a) == Double.parseDouble(b);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean truthy(String s) {
        return s != null && !s.isEmpty() && !"0".equals(s);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static double num(JsonNode node, String field) {
        if (node == null) return 0;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return 0;
        if (v.isNumber()) return v.asDouble();
        try {
            return Double.parseDouble(v.asText().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long intOf(JsonNode node, String field) {
        return (long) num(node, field);
    }

    private static double num(Object v) {
        return PhpJson.num(v);
    }
}
