package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpJson;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.rpc.ModuleHandler;
import com.wlwl.backend.rpc.RpcContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Port of drilloverview/post.php (178 LOC) — the per-run scoreboard table.
 *
 * Response shape:
 * <pre>
 * { "header": [...17 column names...],
 *   "index":  { "userName": true, ... },          // flags, same 17 keys
 *   "detail": [ { userName, status, rank, day, ... }, ... ] }
 * </pre>
 *
 * The whole tag/rank logic lives in two free functions, buildRecord() and
 * blankRecord(), driven by the header list — the PHP builds each record by looping
 * over the header names and switching on them. Reproduced the same way, so adding a
 * column to the header automatically adds it to every record in both versions.
 *
 * Load-bearing quirks:
 *
 *  1. `json_decode($row['content'], true)` — ASSOC MODE, unlike basicinformation's
 *     object mode. A JSON object with numeric string keys therefore comes back as a
 *     PHP array with int keys, and json_encode re-emits it as a JSON **array**. The
 *     decode is reproduced with PHP key coercion, not left to Jackson.
 *
 *  2. Ranking is done inside the loop: usort + re-number over the accumulated list
 *     after every drill. Intermediate results are overwritten, so only the final
 *     pass is observable — but the tie-break is unusual: equal sales sort by
 *     teamEff ASCENDING (lower efficiency ranks first).
 *
 *  3. A drill with no drillDetail rows still contributes a BLANK record
 *     (blankRecord: status from drill.drillStatus, every number 0) — yet it does not
 *     increment $count. So if such a drill is the only one, $count stays 0 and the
 *     whole response is 204 with the blank record thrown away.
 *
 *  4. The SQL is database-qualified (`wlwl.backenduser`). The qualifier is dropped
 *     here: the JDBC connection's catalog IS that database, so the result is
 *     identical, and the drop is what lets the service be pointed at a differently
 *     named database at cutover (see PORTING_NOTES §10).
 */
@Component
public class DrilloverviewModule implements ModuleHandler {

    /** Exactly the list in post.php line 16-17, order included. */
    private static final List<String> HEADER = List.of(
            "userName", "status", "rank", "day", "daysLeft", "timeLeft", "convert",
            "sales", "teamSkill", "teamMorale", "teamEff", "teamSkillImp",
            "teamSkillImpPct", "teamMoraleImp", "teamMoraleImpPct", "teamEffImp",
            "teamEffImpPct");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DrilloverviewModule(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String module() {
        return "drilloverview";
    }

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String packageId = text(env, "package");
        double mod = number(env, "mod");

        // `wlwl.backenduser` in the PHP -> plain backenduser, see the class comment.
        // ORDER BY is qualified to drill.* : MySQL resolves unqualified ORDER BY names
        // against the SELECT list first, and the select list is `drill.*`, so this is
        // the same column — just unambiguous for other engines.
        StringBuilder sql = new StringBuilder(
                "select drill.*, backenduser.userName "
                        + "from backenduser, drillcontrol, drill "
                        + "where backenduser.active = 1 "
                        + "and drillcontrol.userId = backenduser.userId "
                        + "and drillcontrol.drillControlId = drill.drillControlId "
                        + "and companyName = ?");
        List<Object> args = new ArrayList<>();
        args.add(packageId);

        if (mod > 0) {
            sql.append(" and seq = ?");
            args.add((int) mod);
        }
        sql.append(" order by drill.drillControlId, drill.seq");

        List<Map<String, Object>> drills = PhpRows.query(jdbc, sql.toString(), args.toArray());

        List<Object> detail = new ArrayList<>();
        int count = 0;

        for (Map<String, Object> d : drills) {
            List<Map<String, Object>> steps = PhpRows.query(jdbc,
                    // '$drillControlId' AS drillControlId is a literal in the original;
                    // it overwrites the real column in the assoc row. Unused downstream,
                    // kept so the SQL matches.
                    "select *, ? as drillControlId from drilldetail "
                            + "where drillId = ? and stepNumber = "
                            + "(select max(stepNumber) from drilldetail where drillId = ?)",
                    text(PhpRows.get(d, "drillControlId")),
                    PhpRows.get(d, "drillId"),
                    PhpRows.get(d, "drillId"));

            if (!steps.isEmpty()) {
                for (Map<String, Object> w : steps) {
                    Object content = decodeAssoc(PhpRows.get(w, "content"));
                    detail.add(buildRecord(content, w, d));
                    count++;
                }
                sortBySalesThenEfficiency(detail);
                for (int i = 0; i < detail.size(); i++) {
                    ((PhpArray) detail.get(i)).put("rank", (long) (i + 1));
                }
            } else {
                // Note: appends a record but does NOT increment $count.
                detail.add(blankRecord(d));
            }
        }

        if (count == 0) {
            ctx.noContent();
            return;
        }

        PhpArray out = new PhpArray();
        out.put("header", HEADER);
        PhpArray index = new PhpArray();
        for (String h : HEADER) index.put(h, true);
        out.put("index", index);
        out.put("detail", detail);
        ctx.json(out.toJsonNode());
    }

    // ------------------------------------------------------- buildRecord()

    /** PHP: buildRecord($h, &$r, $w, $d) — $w is the drilldetail row, $d the drill row. */
    private PhpArray buildRecord(Object content, Map<String, Object> w, Map<String, Object> d) {
        PhpArray r = new PhpArray();
        Object current = path(content, "current", "teamAvg");
        Object baseline = path(content, "summary", 0L, "teamAvg");

        for (String s : HEADER) {
            switch (s) {
                case "userName" -> r.put(s, PhpRows.get(d, "userName"));
                case "status" -> r.put(s, path(content, "control", "status"));
                case "rank" -> r.put(s, 0L);
                case "day" -> r.put(s, PhpRows.get(w, "dayNumber"));
                case "daysLeft" -> r.put(s, subtract(
                        path(content, "control", "numberOfDays"), PhpRows.get(w, "dayNumber")));
                case "timeLeft" -> r.put(s, path(content, "control", "remainingMinutes"));
                case "convert" -> r.put(s, path(content, "orderStatus", "ordersCompleted"));
                case "sales" -> r.put(s, multiply(
                        path(content, "orderStatus", "ordersCompleted"),
                        path(content, "orderValue")));
                case "teamSkill" -> r.put(s, path(current, "skills"));
                case "teamMorale" -> r.put(s, path(current, "morale"));
                case "teamEff" -> r.put(s, path(current, "efficiency"));
                case "teamSkillImp" -> r.put(s, subtract(
                        path(current, "skills"), path(baseline, "skills")));
                case "teamSkillImpPct" -> r.put(s, percent(
                        path(current, "skills"), path(baseline, "skills")));
                case "teamMoraleImp" -> r.put(s, subtract(
                        path(current, "morale"), path(baseline, "morale")));
                case "teamMoraleImpPct" -> r.put(s, percent(
                        path(current, "morale"), path(baseline, "morale")));
                case "teamEffImp" -> r.put(s, subtract(
                        path(current, "efficiency"), path(baseline, "efficiency")));
                case "teamEffImpPct" -> r.put(s, percent(
                        path(current, "efficiency"), path(baseline, "efficiency")));
                default -> r.put(s, null);
            }
        }
        return r;
    }

    /** PHP: blankRecord() — status comes from drill.drillStatus; everything numeric is 0. */
    private PhpArray blankRecord(Map<String, Object> d) {
        PhpArray r = new PhpArray();
        for (String s : HEADER) {
            switch (s) {
                case "userName" -> r.put(s, PhpRows.get(d, "userName"));
                case "status" -> r.put(s, PhpRows.get(d, "drillStatus"));
                default -> r.put(s, 0L);
            }
        }
        return r;
    }

    // ------------------------------------------------------------ sorting

    /**
     * PHP usort: sales DESC; on equal sales, teamEff ASC; on equal teamEff, 0.
     * Java's List.sort is stable, so ties keep insertion order like PHP 8's usort.
     */
    private static void sortBySalesThenEfficiency(List<Object> detail) {
        detail.sort((a, b) -> {
            double sa = num(((PhpArray) a).get("sales"));
            double sb = num(((PhpArray) b).get("sales"));
            if (sa == sb) {
                double ea = num(((PhpArray) a).get("teamEff"));
                double eb = num(((PhpArray) b).get("teamEff"));
                if (ea == eb) return 0;
                return ea < eb ? -1 : 1;
            }
            return sa < sb ? 1 : -1;
        });
    }

    // -------------------------------------------------------- number work

    /** PHP arithmetic on ints stays int; a float operand makes it float. */
    private static Object subtract(Object a, Object b) {
        if (isIntegral(a) && isIntegral(b)) return toLong(a) - toLong(b);
        if (a == null || b == null) return null;
        return toDouble(a) - toDouble(b);
    }

    private static Object multiply(Object a, Object b) {
        if (isIntegral(a) && isIntegral(b)) return toLong(a) * toLong(b);
        if (a == null || b == null) return null;
        return toDouble(a) * toDouble(b);
    }

    /** PHP: round((($cur - $base) / $base) * 100, 2) — always a float. */
    private static Object percent(Object cur, Object base) {
        if (cur == null || base == null) return null;
        double b = toDouble(base);
        if (b == 0) {
            // PHP 5.6 would produce INF here and json_encode() would then fail,
            // yielding an empty body. No sane drill reaches this; emit null instead
            // of a 500 and note it rather than reproducing a broken response.
            return null;
        }
        double pct = ((toDouble(cur) - b) / b) * 100.0;
        return BigDecimal.valueOf(pct).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static boolean isIntegral(Object v) {
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return true;
        }
        // PHP is loosely typed and the blob really does store numbers as strings:
        // control.numberOfDays is 37 in some rows and "37" in others, and
        // ordersToComplete is "6"/"8". PHP's `"37" - 33` yields the INT 4, so a numeric
        // string must take the integer path or the JSON comes out as 4.0.
        if (v instanceof String s) {
            String t = s.trim();
            return !t.isEmpty() && t.matches("[-+]?\\d+");
        }
        return false;
    }

    private static long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            return (long) toDouble(v);
        }
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double num(Object v) {
        return toDouble(v);
    }

    // ------------------------------------------------------ JSON decoding

    /**
     * json_decode($json, true): objects become PHP arrays WITH KEY COERCION, so
     * {"0":"a","1":"b"} re-encodes as ["a","b"]. Producing PhpArray here is what
     * preserves that; a Jackson ObjectNode would emit {"0":"a","1":"b"} instead.
     */
    private Object decodeAssoc(Object raw) {
        return PhpJson.decodeAssoc(mapper, raw);
    }

    /** Null-safe path walk: $a['x'][0]['y'] in PHP warns and yields null if missing. */
    private static Object path(Object root, Object... keys) {
        return PhpJson.path(root, keys);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static String text(Object v) {
        return v == null ? "" : v.toString();
    }

    private static double number(JsonNode node, String field) {
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
}
