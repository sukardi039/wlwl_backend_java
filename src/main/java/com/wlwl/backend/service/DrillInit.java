package com.wlwl.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpJson;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.db.PhpUniqid;
import com.wlwl.backend.rpc.RpcContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Port of drill/init.php (350 lines) — the one request that CREATES a drill's state.
 *
 * INIT is idempotent per drillId: if any drilldetail row exists it returns the LAST one
 * untouched; only when the drill has no rows does it build the whole content document
 * from template + model + product + params_int and INSERT it as step 0 / INTRODUCTION.
 *
 * Three things in the PHP are load-bearing and easy to "clean up" by mistake:
 *
 *  1. The body is emitted from the LAST SELECT executed, while the HTTP status comes from
 *     a separate `$err` variable. They are not the same decision. `$err` is 404 when the
 *     drill is unknown, 204 once the drill exists. On a successful build the PHP echoes
 *     200 first and then calls http_response_code($err) — which is a NO-OP there because
 *     headers were already flushed by the (multi-kilobyte) echo. So a built drill answers
 *     200 + body, and we reproduce that by only applying $err on the no-body path.
 *  2. `$content['day'][$when]['event']['criteria'][] = 0` uses $when, not $here — inside
 *     the duration loop that is a bug the moment an environment lasts more than one day,
 *     and it is preserved.
 *  3. Arithmetic types: PHP's `/` returns an int when evenly divisible, `round()` returns
 *     a float, and the deployment's json_encode renders integral floats without ".0".
 *     {@link #jsonNumber(double)} collapses those, which is what the real blobs show
 *     (orders "day": 1, manpower "pos": 2).
 */
public final class DrillInit {

    private DrillInit() {
    }

    public static void run(JdbcTemplate jdbc, ObjectMapper mapper, RpcContext ctx) throws Exception {
        JsonNode qdata = qdataOf(mapper, ctx.envelope());
        String drillId = text(qdata, "drillId");

        int err = 0;
        List<Map<String, Object>> res = lastDetail(jdbc, drillId);

        if (res.isEmpty()) {
            err = 404;
            if (!drillId.isEmpty()) {                       // PHP: if ($qdata->drillId)
                res = PhpRows.query(jdbc, "select * from drill where drillId = ?", drillId);
                if (!res.isEmpty()) {
                    err = 204;
                    res = PhpRows.query(jdbc, "select * from template t, drill d "
                            + "where d.templateName = t.templateName and d.drillId = ?", drillId);
                    if (!res.isEmpty()) {
                        Map<String, Object> template = res.get(0);
                        res = PhpRows.query(jdbc, "select * from model m, drill d "
                                + "where d.modelName = m.modelName and d.drillId = ?", drillId);
                        if (!res.isEmpty()) {
                            Map<String, Object> model = res.get(0);
                            Object decoded = PhpJson.decodeAssoc(mapper, PhpRows.get(model, "modelContent"));
                            PhpArray content = decoded instanceof PhpArray pa ? pa : new PhpArray();

                            res = PhpRows.query(jdbc,
                                    "select * from product where productShortName = ?",
                                    text(qdata, "modelProduct"));
                            if (!res.isEmpty()) {
                                build(jdbc, qdata, drillId, template, content, res.get(0));
                                res = lastDetail(jdbc, drillId);   // PHP re-reads the row
                            }
                        }
                    }
                }
            }
        }

        // PHP: if ($result->num_rows) { echo 200 + row } else { http_response_code(204) }
        if (res.isEmpty()) {
            ctx.noContent();
            if (err != 0) ctx.status(err);      // 404 wins over 204, exactly as the PHP ends
            return;
        }

        Map<String, Object> row = res.get(0);
        List<Map<String, Object>> nowRows = PhpRows.query(jdbc, "select now() as now");
        Object timeNow = nowRows.isEmpty() ? null : PhpRows.get(nowRows.get(0), "now");

        PhpArray out = PhpArray.of(row);
        out.set("timeNow", timeNow);                                    // appended after updatedOn
        out.set("content", PhpJson.decodeAssoc(mapper, PhpRows.get(row, "content")));
        out.put("eTime", PhpJson.path(out.get("content"), "control", "eTime"));
        ctx.json(out.toJsonNode());

        // The two "start the clock" updates. They run AFTER the echo in the PHP, so a
        // failure here still leaves the client with a 200; the values are read from the
        // drill row, not from the response.
        List<Map<String, Object>> durRows = PhpRows.query(jdbc,
                "select duration from drill where drillId = ?", drillId);
        long minutes = durRows.isEmpty() ? 0 : numLong(PhpRows.get(durRows.get(0), "duration"));
        jdbc.update("update drill set drillStatus = 200, startOn = now(), "
                        + "expireOn = timestampadd(MINUTE, ?, now()) "
                        + "where drillId = ? and startOn is null",
                minutes, drillId);
        jdbc.update("update drillcontrol set drillStartedOn = now(), currentDrill = ? "
                        + "where drillControlId = (select drillControlId from drill where drillId = ?)",
                seqOf(qdata), drillId);
    }

    // --------------------------------------------------------------- the builder

    private static void build(JdbcTemplate jdbc, JsonNode qdata, String drillId,
                              Map<String, Object> template, PhpArray content,
                              Map<String, Object> product) {
        Map<String, Object> params = DrillCommon.readParams(jdbc);

        // orderValue is rolled from the product's range (inclusive rand) times 1000.
        double orderValue = rand(intOf(product, "minOrderValue"), intOf(product, "maxOrderValue")) * 1000;
        content.put("orderValue", (long) orderValue);
        content.put("valueToComplete",
                jsonNumber(orderValue * num(content.get("ordersToComplete"))));

        // ---------------------------------------------------- task efficiency table
        List<Map<String, Object>> eff = PhpRows.query(jdbc,
                "select * from taskefficiency e, task t where e.taskName = t.taskName "
                        + "and productName = ?", text(qdata, "modelProduct"));
        if (!eff.isEmpty()) {
            PhpArray arr = content.child("task_eff");
            for (int c = 0; c < eff.size(); c++) {
                Map<String, Object> te = eff.get(c);
                PhpArray e = arr.child((long) c);
                e.put("productName", PhpRows.get(te, "productName"));
                e.put("taskName", PhpRows.get(te, "taskName"));
                e.put("taskDisplayName", PhpRows.get(te, "taskDisplayName"));
                e.put("skillsWeight", PhpRows.get(te, "skillsWeight"));
                e.put("moraleWeight", PhpRows.get(te, "moraleWeight"));
                e.put("duration", PhpRows.get(te, "duration"));
                e.put("stage", PhpRows.get(te, "stage"));
                e.put("passingRate", PhpRows.get(te, "passingRate"));
                e.put("target", PhpRows.get(te, "target"));
                e.put("worked", 0L);
            }
        }

        // $param is a second, local copy of params_int (the PHP reads the table twice).
        Map<String, Object> param = new LinkedHashMap<>();
        for (Map<String, Object> r : PhpRows.query(jdbc, "select * from params_int")) {
            param.put(String.valueOf(PhpRows.get(r, "key")), PhpRows.get(r, "value"));
        }

        // First style row: the initial style every member is put into.
        Map<String, Object> stl = first(PhpRows.query(jdbc,
                "select * from baseinfo b, style s where s.styleName = b.name"));

        content.put("history", new PhpArray());

        long maxMembers = numLong(template, "numberOfStaff");
        long numberOfDays = numLong(template, "numberOfDays");

        // ------------------------------------------------------------- members
        java.util.List<Object> staffs = seq(content.get("staffs"));
        if (staffs != null) {
            PhpArray members = content.child("members");
            long mbr = 0;
            for (Object sv : staffs) {
                if (!(sv instanceof PhpArray staff)) continue;
                mbr++;
                Map<String, Object> stf = first(PhpRows.query(jdbc,
                        "select * from staffprofile_view where staffId = ?",
                        textOf(staff.get("staffName"))));

                PhpArray m = members.child(mbr);
                m.put("state", mbr == 1 ? "blink" : "halt");
                m.put("staffName", staff.get("staffName"));
                m.put("task", staff.get("task"));
                m.put("staffExp", staff.get("staffExp"));
                m.put("taskExp", staff.get("taskExp"));
                m.put("introduced", 0L);
                m.put("style", stl == null ? null : PhpRows.get(stl, "name"));
                m.put("styleExp", stl == null ? null : PhpRows.get(stl, "displayKey"));

                if (truthy(staff.get("skillsInit"))) {
                    m.put("skillsValue", staff.get("skillsInit"));
                } else {
                    m.put("skillsValue", Math.max(rand(numLong(rowNum(stf, "skillsMin")), numLong(rowNum(stf, "skillsMax"))), 2));
                }
                if (truthy(staff.get("moraleInit"))) {
                    m.put("moraleValue", staff.get("moraleInit"));
                } else {
                    m.put("moraleValue", Math.max(rand(numLong(rowNum(stf, "moraleMin")), numLong(rowNum(stf, "moraleMax"))), 2));
                }
                if (truthy(staff.get("efficiencyInit"))) {
                    m.put("efficiencyValue", staff.get("efficiencyInit"));
                } else {
                    m.put("efficiencyValue", Math.max(rand(numLong(rowNum(stf, "efficiencyMin")), numLong(rowNum(stf, "efficiencyMax"))), 10));
                }
                m.put("offdays", 0L);
                m.put("away", 0L);
                m.put("newComer", rowNum(stf, "newComer"));
                m.put("oldTimer", rowNum(stf, "oldTimer"));
                m.put("yearsWorked", rowNum(stf, "yearsWorked"));
                m.put("experience", rowNum(stf, "experience"));

                if (truthy(m.get("staffName"))) {
                    DrillCommon.writeHistory(content, 0L, 0L,
                            m.get("staffName"), m.get("staffExp"), "INIT", "",
                            m.get("skillsValue"), m.get("moraleValue"), m.get("efficiencyValue"), "");
                }

                m.put("attention", params.get("dejected_tolerance_days"));
                m.put("attenWarning", params.get("dejected_warning_days"));
                m.put("listen", params.get("ignored_tolerance_days"));
                m.put("trend", 0L);
                m.put("initSkillsValue", m.get("skillsValue"));
                m.put("initMoraleValue", m.get("moraleValue"));
                m.put("initEfficiencyValue", m.get("efficiencyValue"));
            }
        }

        // ------------------------------------------------------ calendar: days/weeks
        PhpArray day = content.child("day");
        PhpArray week = content.child("week");
        long weekday = 1;
        long wk = 1;
        for (long c = 1; c <= numberOfDays; c++) {
            PhpArray d = day.child(c);
            d.child("event").put("count", 0L);
            d.put("newOrders", PhpRows.get(product, "ordersPerDay"));
            d.put("evaluateStyle", weekday == 1 ? 1L : 0L);
            d.put("week", wk);
            d.put("weekday", weekday);
            week.child(wk).put("weekNumber", wk);
            if (++weekday > 5) {
                weekday = 1;
                wk++;
            }
        }

        // ------------------------------------------------------------- week targets
        PhpArray control = content.child("control");
        control.put("funnelType", param.get("target_method"));
        long totalWeeks = 0;
        for (Object wkv : week.values()) totalWeeks = numLong(PhpJson.path(wkv, "weekNumber"));

        switch (textOf(param.get("target_method"))) {
            case "1" -> {
                double toComplete = num(content.get("ordersToComplete"));
                for (long w = 1; w <= totalWeeks; w++) {
                    PhpArray ww = week.child(w);
                    Object orderTarget = jsonNumber(phpRound(toComplete / totalWeeks * w));
                    ww.put("orderTarget", orderTarget);
                    Object prev = PhpJson.path(week, w - 1, "orderTarget");   // week[0] is null
                    Object weekTarget = jsonNumber(num(orderTarget) - num(prev));
                    ww.put("weekOrderTarget", weekTarget);
                    ww.put("valueTarget", jsonNumber(num(orderTarget) * orderValue));
                    ww.put("weekValueTarget", jsonNumber(num(weekTarget) * orderValue));
                }
            }
            case "2" -> {
                PhpArray status = content.child("orderStatus");
                java.util.List<Object> effArr = seq(content.get("task_eff"));
                if (effArr != null) {
                    for (Object ev : effArr) {
                        if (!(ev instanceof PhpArray ef)) continue;
                        double target = num(ef.get("target"));
                        PhpArray st = status.child(ef.get("stage"));
                        st.put("orderTarget",
                                jsonNumber(phpRound(num(content.get("ordersToComplete")) * target)));
                        st.put("valueTarget",
                                jsonNumber(phpRound(num(content.get("ordersToComplete")) * target * orderValue)));
                        st.put("taskName", ef.get("taskDisplayName"));
                        st.put("done", 0L);
                    }
                }
            }
            default -> {
                // The PHP switch has no default: target_method outside 1|2 leaves no
                // week/orderStatus targets at all.
            }
        }

        // ----------------------------------------------------------- environments
        Map<String, Object> bi = new LinkedHashMap<>();
        for (Map<String, Object> r : PhpRows.query(jdbc, "select * from baseinfo")) {
            bi.put(String.valueOf(PhpRows.get(r, "name")), PhpRows.get(r, "displayKey"));
        }

        java.util.List<Object> envs = seq(content.get("environments"));
        if (envs != null) {
            for (Object ev : envs) {
                if (!(ev instanceof PhpArray envi)) continue;
                if (!truthy(envi.get("environmentName"))) continue;

                Map<String, Object> environment = first(PhpRows.query(jdbc,
                        "select * from environment where environmentName = ?",
                        textOf(envi.get("environmentName"))));
                if (environment == null) continue;

                long when = numLong(envi.get("happenOn")) > 0
                        ? numLong(envi.get("happenOn"))
                        : rand(2, numberOfDays - 5);

                long duration = numLong(environment, "duration");
                for (long c = 0; c < duration; c++) {
                    long here = when + c;
                    if (numberOfDays <= here) continue;

                    PhpArray hereDay = day.child(here);
                    if (c == 0) {
                        PhpArray event = hereDay.child("event");
                        event.child("description").append(PhpRows.get(environment, "environmentDescription"));
                        event.child("eventName").append(PhpRows.get(environment, "environmentName"));
                        event.child("eventExp").append(bi.get(text(environment, "environmentName")));
                        event.child("staffName").append("");
                        event.child("staffExp").append("");
                        // $when, not $here — preserved bug (see class javadoc)
                        day.child(when).child("event").child("criteria").append(0L);
                        event.put("count", numLong(event.get("count")) + 1);
                    }
                    hereDay.put("newOrders",
                            jsonNumber(phpRound(num(hereDay.get("newOrders"))
                                    * num(PhpRows.get(environment, "orderImpact")))));
                }
            }
        }

        // --------------------------------------------------------------- manpower
        List<Map<String, Object>> tasks = PhpRows.query(jdbc, "select * from task");
        if (!tasks.isEmpty()) {                       // PHP divides by num_rows; 0 would be INF
            Object positions = phpDivide(maxMembers, tasks.size());
            PhpArray manpower = content.child("manpower");
            for (Map<String, Object> t : tasks) {
                PhpArray pos = manpower.child(String.valueOf(PhpRows.get(t, "taskName")));
                pos.put("pos", positions);
                pos.put("mbr", 0L);
                pos.put("short", 0L);
            }
        }
        PhpArray team = content.child("manpowerTeam");
        team.put("pos", maxMembers);                  // PHP: $template['numberOfStaff'] * 1
        team.put("mbr", 0L);

        // ------------------------------------------------------------ order ledger
        long totalOrders = 0;
        PhpArray orders = content.child("orders");
        for (long c = 1; c <= numberOfDays; c++) {
            long perDay = numLong(PhpJson.path(day, c, "newOrders"));
            for (long d = 0; d < perDay; d++) {
                orders.child(++totalOrders).put("day", c);
            }
        }

        // -------------------------------------------------------------- control row
        control.put("totalOrders", totalOrders);
        control.put("numberOfDays", PhpRows.get(template, "numberOfDays"));
        control.put("maxMembers", PhpRows.get(template, "numberOfStaff"));
        control.put("maxDays", PhpRows.get(template, "numberOfDays"));
        control.put("timeLimit", PhpRows.get(template, "duration"));
        control.put("maxEvents", PhpRows.get(template, "environmentEvents"));
        control.put("maxRecruits", PhpRows.get(template, "recruits"));
        control.put("reporting", PhpRows.get(template, "report"));
        control.put("welcome", 1L);
        control.put("eTime", textOf(PhpRows.get(template, "duration")) + ":00");
        control.put("countTime", false);
        control.put("status", 200L);

        if (content.get("orderStatus") instanceof PhpArray os) os.put("ordersCompleted", 0L);
        else content.child("orderStatus").put("ordersCompleted", 0L);

        content.put("summary", new PhpArray());
        content.child("used").put("actions", new PhpArray());

        // ------------------------------------------------------ analysis: staff/action
        List<Object> act = new ArrayList<>();
        List<Object> oif = new ArrayList<>();
        for (Map<String, Object> r : PhpRows.query(jdbc,
                "select * from baseinfo b, action a where a.actionName = b.name "
                        + "and a.actionName = a.parentAction")) {
            if (truthy(PhpRows.get(r, "show"))) {
                act.add(PhpRows.get(r, "actionName"));
                oif.add(PhpRows.get(r, "1In4"));
            }
        }

        PhpArray members = content.get("members") instanceof PhpArray ma ? ma : null;
        if (members != null) {
            for (long c = 1; c <= members.size(); c++) {
                Object m = members.get(c);
                DrillCommon.initStaffAna(jdbc, content, PhpJson.path(m, "staffName"), PhpJson.path(m, "staffExp"));
            }
        }

        PhpArray analysisAction = content.child("analysis").child("action");
        for (int c = 0; c < act.size(); c++) {
            PhpArray a = analysisAction.child(act.get(c));
            a.put("name", act.get(c));
            a.put("count", 0L);
            a.put("positive", 0L);
            a.put("negative", 0L);
            a.put("oneInFour", oif.get(c));
            a.put("skillsAdded", 0L);
            a.put("moraleAdded", 0L);
        }

        // ------------------------------------------------------------ analysis: style
        List<Object> sty = new ArrayList<>();
        for (Map<String, Object> r : PhpRows.query(jdbc,
                "select * from baseinfo b, style s where s.styleName = b.name")) {
            sty.add(PhpRows.get(r, "styleName"));
        }
        PhpArray analysis = content.child("analysis");
        PhpArray style = analysis.child("style");
        PhpArray actionStyle = analysis.child("action_style");
        for (Object s : sty) {
            PhpArray st = style.child(s);
            st.put("count", 0L);
            st.put("positive", 0L);
            st.put("negative", 0L);
            st.put("skillsAdded", 0L);
            st.put("moraleAdded", 0L);

            PhpArray as = actionStyle.child(s);
            as.put("count", 0L);
            as.put("positive", 0L);
            as.put("negative", 0L);
        }
        style.put("total", 0L);
        actionStyle.put("total", 0L);
        for (String combo : new String[]{"desire_actual", "desire_intent", "intent_actual"}) {
            PhpArray pair = style.child(combo);
            pair.put("count", 0L);
            pair.put("positive", 0L);
            pair.put("negative", 0L);
        }

        // ------------------------------------------------------- analysis: headings
        Map<String, Object> hdr = first(PhpRows.query(jdbc,
                "SELECT createdOn, userName FROM drill d, drillcontrol c, backenduser u "
                        + "where d.drillControlId = c.drillcontrolId and c.userId = u.userId "
                        + "and d.drillID = ?", drillId));
        Object createdOn = hdr == null ? null : PhpRows.get(hdr, "createdOn");
        analysis.put("reportUser", hdr == null ? null : PhpRows.get(hdr, "userName"));
        analysis.put("reportDate", substr(createdOn, 0, 10));

        // ------------------------------------------------------------------ persist
        jdbc.update("insert into drilldetail (drillDetailId, drillId, stepNumber, dayNumber, "
                        + "action, createdOn, content) values (?, ?, 0, 0, 'INTRODUCTION', now(), ?)",
                PhpUniqid.next(), drillId, content.toJsonNode().toString());
    }

    // ------------------------------------------------------------------ helpers

    private static List<Map<String, Object>> lastDetail(JdbcTemplate jdbc, String drillId) {
        return PhpRows.query(jdbc,
                "select * from drilldetail where drillId = ? order by stepNumber desc limit 1", drillId);
    }

    private static JsonNode qdataOf(ObjectMapper mapper, JsonNode envelope) {
        JsonNode qdata = envelope == null ? null : envelope.get("qdata");
        return qdata == null || qdata.isNull() ? mapper.createObjectNode() : qdata;
    }

    /** PHP rand($min,$max) — inclusive both ends; NULLs behave like 0. */
    private static long rand(long min, long max) {
        if (max <= min) return min;          // also covers rand(0,0) from a missing view row
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    /** PHP round() — half away from zero (values here are non-negative). */
    private static double phpRound(double v) {
        return v < 0 ? -Math.floor(-v + 0.5) : Math.floor(v + 0.5);
    }

    /** PHP int division returns an int when evenly divisible, a float otherwise. */
    private static Object phpDivide(long a, long b) {
        if (b != 0 && a % b == 0) return a / b;
        return jsonNumber((double) a / b);
    }

    /**
     * json_encode() of a PHP number as this deployment emits it: an integral double comes
     * out without a fractional part ("2", not "2.0"), a fractional one keeps it.
     */
    static Object jsonNumber(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 9.0e15) return (long) v;
        return v;
    }

    private static Object substr(Object v, int from, int to) {
        String s = v == null ? "" : String.valueOf(v);
        return s.length() <= from ? "" : s.substring(Math.min(from, s.length()), Math.min(to, s.length()));
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.toString().equals(b == null ? null : b.toString());
    }

    private static PhpArray asArray(Object v) {
        return v instanceof PhpArray a ? a : null;
    }

    /**
     * A json_decode'd PHP array, either shape: an object becomes PhpArray, a JSON array
     * becomes a java List. `foreach` in PHP walks both, so the port must too — model
     * files store staffs/environments as JSON arrays and the loop over them is by value.
     */
    private static java.util.List<Object> seq(Object v) {
        if (v instanceof PhpArray a) return new ArrayList<>(a.values());
        if (v instanceof java.util.List<?> l) return new ArrayList<>(l);
        return null;
    }

    private static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private static Object rowNum(Map<String, Object> row, String column) {
        return row == null ? null : PhpRows.get(row, column);
    }

    private static long intOf(Map<String, Object> row, String column) {
        return numLong(PhpRows.get(row, column));
    }

    private static double num(Object v) {
        return PhpJson.num(v);
    }

    private static long numLong(Object v) {
        return (long) PhpJson.num(v);
    }

    private static long numLong(Map<String, Object> row, String column) {
        return row == null ? 0 : numLong(PhpRows.get(row, column));
    }

    private static String textOf(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String text(Map<String, Object> row, String column) {
        return textOf(PhpRows.get(row, column));
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    /** qdata.seq is written into drillcontrol.currentDrill; missing means NULL, not "". */
    private static Long seqOf(JsonNode qdata) {
        JsonNode v = qdata == null ? null : qdata.get("seq");
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.asLong();
        String s = v.asText();
        if (s.isEmpty() || "0".equals(s)) return "0".equals(s) ? 0L : null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        String s = v.toString();
        return !s.isEmpty() && !"0".equals(s);
    }
}
