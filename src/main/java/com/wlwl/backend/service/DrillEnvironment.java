package com.wlwl.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpJson;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.db.PhpUniqid;
import com.wlwl.backend.rpc.RpcContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Port of drill/environment.php (232 lines) — the step where a scheduled event fires.
 *
 * The client has already decided that an event happens today; this endpoint applies it:
 * impacts on the targeted member (or everyone), knock-on effects on the rest, resign /
 * set-away side effects, the event-stack shift, the summary snapshot, and the status
 * ladder (500 still playing, 750 failed, 900 completed).
 *
 * Reproduced oddities:
 *  1. `dbg($content, 'env_param', ...)` runs BEFORE `$content` is assigned, so it is a
 *     no-op in the PHP — not reproduced (the cal_* dbg calls DO land, and appear in blobs).
 *  2. `$grace` uses `=== 0` on `day[dn+3].evaluateStyle`; a missing day (or a STRING "0")
 *     therefore yields 4, not 3.
 *  3. `date_diff()->h * 60 + ->i` ignores whole days, so a drill three days out reports
 *     only its hour/minute remainder.
 *  4. The response is gzipped in the PHP (`text/javascript` + `Content-Encoding: gzip`).
 *     Browsers decompress transparently, so this port answers plain JSON — same bytes
 *     after decoding, no hand-rolled gzip. Divergence on the wire only.
 */
public final class DrillEnvironment {

    private DrillEnvironment() {
    }

    public static void run(JdbcTemplate jdbc, ObjectMapper mapper, RpcContext ctx) throws Exception {
        JsonNode qdata = qdataOf(mapper, ctx.envelope());
        String drillId = text(qdata, "drillId");
        String rawContent = text(qdata, "content");

        // 1. the client's copy of the row it was sitting on wins
        jdbc.update("update drilldetail set content = ?, updatedOn = now() where drillDetailId = ?",
                rawContent, text(qdata, "drillDetailId"));

        Map<String, Object> params = DrillCommon.readParams(jdbc);
        Object decoded = PhpJson.decodeAssoc(mapper, rawContent);
        PhpArray content = decoded instanceof PhpArray pa ? pa : new PhpArray();

        DrillCommon.initMemberValues(content, params);

        long maxMembers = (long) PhpJson.num(PhpJson.path(content, "control", "maxMembers"));
        long dayNumber = (long) PhpJson.num(qdata.get("dayNumber"));
        String action = text(qdata, "action");
        Object stepNumber = qdata.get("stepNumber");

        for (long c = 1; c <= maxMembers; c++) {
            PhpArray m = member(content, c);
            if (m != null) m.put("touched", 0L);
        }

        // ------------------------------------------------------------- the event
        if (num(path(content, "day", dayNumber, "event", "count")) > 0) {
            String env = str(path(content, "day", dayNumber, "event", "eventName", 0L));
            Object enx = path(content, "day", dayNumber, "event", "eventExp", 0L);
            String ens = str(path(content, "day", dayNumber, "event", "staffName", 0L));

            content.child("control").put("actionExp", enx);

            if (!env.isEmpty()) {
                Map<String, Object> envi = firstRow(jdbc, "select * from environment, baseinfo "
                        + "where environmentName = name and environmentName = ?", env);

                for (long c = 1; c <= maxMembers; c++) {
                    PhpArray m = member(content, c);
                    if (m == null || !hasName(m.get("staffName"))) continue;
                    if (!ens.isEmpty() && !ens.equals(str(m.get("staffName")))) continue;

                    m.put("actionExp", enx);
                    double enhance = 1;
                    if (truthy(m.get("newComer"))) enhance += num(rowGet(envi, "newComerImpact")) / 100;
                    if (truthy(m.get("oldTimer"))) enhance += num(rowGet(envi, "oldTimerImpact")) / 100;

                    DrillCommon.calImpacts(jdbc, content, (int) c, env, enhance, 0, 0, 0);
                    writeHistory(content, stepNumber, dayNumber, m, action, enx);

                    // effective days of the event, adjusted for weekends
                    if (num(m.get("added")) != 0) {
                        long effectDays = (long) num(rowGet(envi, "effectiveDays"));
                        long wd = dayNumber % 5;
                        wd = wd != 0 ? wd : 5;
                        long until = dayNumber + effectDays + 1;
                        if (effectDays + 1 + wd > 5) until++;
                        if (effectDays + 1 + wd > 10) until++;
                        m.child("envEffect").put(str(rowGet(envi, "environmentName")), until);
                    }

                    // everybody else is affected
                    for (long cx = 1; cx <= maxMembers; cx++) {
                        PhpArray other = member(content, cx);
                        if (other == null || !hasName(other.get("staffName"))) continue;
                        // PHP: (staffName != $ens) && ($ens != '') — an untargeted event
                        // broadcasts impacts only, it never runs cal_affect at all
                        if (ens.isEmpty()) continue;
                        if (ens.equals(str(other.get("staffName")))) continue;

                        DrillCommon.calAffect(jdbc, content, (int) cx, env, 0, 0, 0);
                        writeHistory(content, stepNumber, dayNumber, other, action, enx);
                    }

                    // scheduled resignation
                    String resignAction = str(rowGet(envi, "resignAction"));
                    if (!resignAction.isEmpty()) {
                        int grace = isZero(path(content, "day", dayNumber + 3, "evaluateStyle")) ? 3 : 4;
                        Map<String, Object> resg = firstRow(jdbc, "select * from environment, baseinfo "
                                + "where environmentName = name and environmentName = ?", resignAction);
                        long at = dayNumber + grace;
                        long idx = (long) num(path(content, "day", at, "event", "count"));
                        PhpArray ev = content.child("day").child(at).child("event");
                        mutableChild(ev, "description").put(idx, rowGet(resg, "environmentDescription"));
                        mutableChild(ev, "eventName").put(idx, rowGet(resg, "environmentName"));
                        mutableChild(ev, "eventExp").put(idx, rowGet(resg, "displayKey"));
                        mutableChild(ev, "staffName").put(idx, m.get("staffName"));
                        mutableChild(ev, "staffExp").put(idx, m.get("staffExp"));
                        mutableChild(ev, "criteria").put(idx, m.get("moraleValue"));
                        ev.put("count", idx + 1);
                    }
                    if (num(rowGet(envi, "setAway")) > 0) {
                        m.put("away", rowGet(envi, "duration"));
                    }
                }

                // resignCheck: the targeted member leaves
                if (num(rowGet(envi, "resignCheck")) > 0 && DrillCommon.memberWhere(content, ens) > 0) {
                    int i = DrillCommon.memberWhere(content, ens);
                    PhpArray m = member(content, (long) i);
                    m.put("staffExp", "");
                    m.put("actioned", 0L);
                    if (num(m.get("workingOn")) > 0) {
                        content.child("orders").child(num(m.get("workingOn")))
                                .put("state", -1L);
                    }
                    m.put("workingOn", 0L);
                    m.put("added", 0L);
                    m.put("addedAvg", 0L);
                    m.put("evaluated", 0L);
                    m.put("away", PhpJson.jsonNumber(rand(
                            (long) num(params.get("resign_embargo_min_days")),
                            (long) num(params.get("resign_embargo_max_days")))));
                    m.put("actionExp", "");
                    m.put("readMsg", 1L);
                    m.put("msgCount", 0L);
                    m.put("message", new PhpArray());
                    m.put("listen", params.get("ignored_tolerance_days"));
                    m.put("attention", params.get("dejected_tolerance_days"));
                    m.put("attenWarning", params.get("dejected_warning_days"));
                    m.put("touched", 0L);
                    m.put("staffName", "");
                }
            }

            // ------------------------------------------------- shift the event stack
            long count = (long) num(path(content, "day", dayNumber, "event", "count"));
            if (count > 0) {
                PhpArray ev = content.child("day").child(dayNumber).child("event");
                long v = 1;
                for (; v < count; v++) {
                    for (String key : EVENT_KEYS) {
                        Object prev = PhpJson.path(ev, key, v);
                        mutableChild(ev, key).put(v - 1, prev);
                    }
                }
                for (String key : EVENT_KEYS) {
                    mutableChild(ev, key).put(v, "");
                }
                ev.put("count", count - 1);
            }
        }

        // ------------------------------------------------------------- summary
        DrillCommon.writeSummary(content, "ENVIRONMENT", stepNumber, dayNumber);

        // ---------------------------------------------------------- status ladder
        String nextDetailId = PhpUniqid.next();
        long nextStep = (long) num(stepNumber) + 1;

        Map<String, Object> drill = firstRow(jdbc,
                "select now() as now, drill.* from drill where drillId = ?", drillId);
        double controlStatus = num(path(content, "control", "status"));

        long stat = controlStatus == 600 ? 600 : 500;
        if (DrillCommon.noMoreStaff(content)) {
            stat = controlStatus == 600 ? 900 : 750;
            action = controlStatus == 600 ? "COMPLETED" : "FAILED";
        }

        content.child("control").put("remainingMinutes",
                (stat == 500 || stat == 600) ? remaining(drill, "now", "expireOn") : "0.00");
        content.child("control").put("expireOn", rowGet(drill, "expireOn"));
        content.child("control").put("now", rowGet(drill, "now"));

        if (stat > 600) {
            jdbc.update("update drill set drillStatus = ?, endOn = now() where drillId = ?", stat, drillId);
        } else {
            jdbc.update("update drill set drillStatus = ? where drillId = ?", stat, drillId);
        }
        if (stat > 600) {
            jdbc.update("update drillcontrol set completedDrill = "
                    + "(select seq from drill where drillId = ?) "
                    + "where drillControlId = (select drillControlId from drill where drillId = ?)",
                    drillId, drillId);
        }
        content.child("control").put("status", stat);

        jdbc.update("insert into drilldetail (drillDetailId, drillId, stepNumber, dayNumber, "
                        + "action, createdOn, content) values (?, ?, ?, ?, ?, now(), ?)",
                nextDetailId, drillId, nextStep, dayNumber, action,
                content.toJsonNode().toString());

        // ------------------------------------------------------------------ reply
        List<Map<String, Object>> rows = PhpRows.query(jdbc,
                "select * from drilldetail where drillId = ? order by stepNumber desc limit 1", drillId);
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }
        Map<String, Object> row = rows.get(0);
        List<Map<String, Object>> nowRows = PhpRows.query(jdbc, "select now() as now");
        Object timeNow = nowRows.isEmpty() ? null : PhpRows.get(nowRows.get(0), "now");

        PhpArray out = PhpArray.of(row);
        out.set("timeNow", timeNow);
        out.set("content", PhpJson.decodeAssoc(mapper, PhpRows.get(row, "content")));
        ctx.json(out.toJsonNode());
    }

    /**
     * PHP: date_diff(date_create($now), date_create($expireOn)) then `$df->h * 60 + $df->i`.
     * The interval's DAY component is ignored, and a past expiry keeps positive h/i because
     * DateInterval is absolute — both reproduced.
     */
    private static String remaining(Map<String, Object> drill, String nowKey, String expireKey) {
        Object nowRaw = rowGet(drill, nowKey);
        Object expireRaw = rowGet(drill, expireKey);
        try {
            LocalDateTime now = parse(nowRaw);
            LocalDateTime expire = parse(expireRaw);
            if (now == null || expire == null) return "0:00";
            Duration d = Duration.between(now, expire).abs();
            long hours = d.toHoursPart();
            long minutes = d.toMinutesPart();
            long seconds = d.toSecondsPart();
            return (hours * 60 + minutes) + ":" + String.format("%02d", seconds);
        } catch (RuntimeException e) {
            return "0:00";
        }
    }

    private static final DateTimeFormatter SQL_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static LocalDateTime parse(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        if (s.length() > 19) s = s.substring(0, 19);
        return LocalDateTime.parse(s, SQL_DATETIME);
    }

    /** writeHistory() with the message the member is currently sitting on. */
    private static void writeHistory(PhpArray content, Object step, Object day, PhpArray m,
                                     String action, Object actExp) {
        Object msg = PhpJson.path(m, "message", (long) PhpJson.num(m.get("msgCount")));
        DrillCommon.writeHistory(content, step, day, m.get("staffName"), m.get("staffExp"),
                action, actExp, m.get("skillsAdded"), m.get("moraleAdded"),
                m.get("efficiencyAdded"), msg);
    }

    // ------------------------------------------------------------------ helpers

    private static JsonNode qdataOf(ObjectMapper mapper, JsonNode envelope) {
        JsonNode qdata = envelope == null ? null : envelope.get("qdata");
        return qdata == null || qdata.isNull() ? mapper.createObjectNode() : qdata;
    }

    private static final String[] EVENT_KEYS = {"description", "eventName", "eventExp",
            "staffName", "staffExp", "criteria"};

    /**
     * PHP arrays hold lists and maps alike, so writing into a decoded JSON ARRAY must not
     * replace it: `$event['description'][$v] = ''` edits the existing array in place.
     * PhpJson decodes a JSON array to a java List, so convert it back to a PhpArray on the
     * first write — otherwise the sibling elements are silently dropped.
     */
    private static PhpArray mutableChild(PhpArray parent, String key) {
        Object cur = parent.get(key);
        if (cur instanceof PhpArray a) return a;
        PhpArray created = new PhpArray();
        if (cur instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) created.put((long) i, l.get(i));
        }
        parent.put(key, created);
        return created;
    }

    private static PhpArray member(PhpArray content, long c) {
        Object v = PhpJson.path(content, "members", c);
        return v instanceof PhpArray a ? a : null;
    }

    private static Object path(Object root, Object... keys) {
        return PhpJson.path(root, keys);
    }

    private static Map<String, Object> firstRow(JdbcTemplate jdbc, String sql, Object... args) {
        List<Map<String, Object>> rows = PhpRows.query(jdbc, sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Object rowGet(Map<String, Object> row, String column) {
        return row == null ? null : PhpRows.get(row, column);
    }

    private static long rand(long min, long max) {
        if (max <= min) return min;
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    /** PHP `$x === 0` — strict, so null and the string "0" are both false. */
    private static boolean isZero(Object v) {
        return v instanceof Number n && n.doubleValue() == 0;
    }

    private static boolean hasName(Object staffName) {
        if (staffName == null) return false;
        String s = staffName.toString();
        return !s.isEmpty() && !"0".equals(s);
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        String s = v.toString();
        return !s.isEmpty() && !"0".equals(s);
    }

    private static double num(Object v) {
        return PhpJson.num(v);
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }
}
