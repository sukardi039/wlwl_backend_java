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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Port of drilleffective/post.php (165 LOC) — per-action effectiveness percentages.
 *
 * The most structurally unusual of the drill reports:
 *
 *  1. THE HEADER IS DATA-DRIVEN. It is built from the first row's
 *     `content.analysis.action[]` array, split into "1-in-4" actions first
 *     (oneInFour == 1) then the rest (oneInFour == 0). Nothing in the response has a
 *     fixed column set.
 *
 *  2. THE FOOTER IS APPENDED INTO `detail`. buildFooter() computes company-wide
 *     percentages from the accumulated totals and pushes them as the LAST element of
 *     detail — so detail always has one more row than there are drills. That footer's
 *     `userName` is "" because $fb has no 'userName' bucket.
 *
 *  3. `fb` (the running totals) is emitted as its own top-level key, and the PHP
 *     re-assigns `$jsonarray['fb'] = $fb` after every drill that had detail rows.
 *     Since totals only grow, the last assignment is the final state.
 *
 *  4. NOTHING IS SORTED OR RANKED. The usort and ranking blocks are commented out in
 *     the original, so rows come out in join order and there is no `rank` key at all.
 *
 *  5. Roughly half the file is commented-out code. It is dead; the live path is
 *     reproduced and the disabled ranking is documented rather than implemented.
 *
 * Edge cases faithfully kept: when the header has not been built yet (the first drills
 * have no drilldetail), blankRecord produces only a userName key — and an empty array
 * if the header is still undefined.
 */
@Component
public class DrilleffectiveModule implements ModuleHandler {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DrilleffectiveModule(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String module() {
        return "drilleffective";
    }

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String packageId = text(env, "package");
        double mod = number(env, "mod");

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

        PhpArray out = new PhpArray();
        PhpArray fb = new PhpArray();
        PhpArray header = null;      // PHP: undefined until the first detail row
        boolean builtHeader = false;
        int count = 0;

        for (Map<String, Object> d : drills) {
            List<Map<String, Object>> steps = PhpRows.query(jdbc,
                    "select *, ? as drillControlId from drilldetail "
                            + "where drillId = ? and stepNumber = "
                            + "(select max(stepNumber) from drilldetail where drillId = ?)",
                    text(PhpRows.get(d, "drillControlId")),
                    PhpRows.get(d, "drillId"),
                    PhpRows.get(d, "drillId"));

            if (!steps.isEmpty()) {
                for (Map<String, Object> w : steps) {
                    Object content = PhpJson.decodeAssoc(mapper, PhpRows.get(w, "content"));
                    Object actions = PhpJson.path(content, "analysis", "action");

                    if (!builtHeader) {
                        header = buildHeader(actions);
                        out.put("header", header);
                        PhpArray index = new PhpArray();
                        for (Object h : header.values()) index.put(h, true);
                        out.put("index", index);
                        builtHeader = true;
                    }

                    out.child("detail").append(buildRecord(fb, actions, d));
                    count++;
                }
                out.put("fb", fb);
            } else {
                // Header may still be null here: blankRecord then yields an EMPTY ARRAY.
                out.child("detail").append(blankRecord(header, d));
            }
        }

        // NOTE: outside the `if (drills non-empty)` block in the PHP — always runs.
        buildFooter(out.child("detail"), fb, header);

        if (count == 0) {
            ctx.noContent();
            return;
        }
        ctx.json(out.toJsonNode());
    }

    /** PHP buildHeader(): 'userName', then oneInFour == 1 actions, then oneInFour == 0. */
    private static PhpArray buildHeader(Object actions) {
        PhpArray h = new PhpArray();
        h.append("userName");
        for (int i = 1; i >= 0; i--) {
            for (Object o : asList(actions)) {
                Object oneInFour = PhpJson.path(o, "oneInFour");
                Object name = PhpJson.path(o, "name");
                if (looseEqualsInt(oneInFour, i) && truthy(name)) {
                    h.append(name);
                }
            }
        }
        return h;
    }

    /** PHP buildRecord($fb, &$r, $actions, $drillRow). */
    private static PhpArray buildRecord(PhpArray fb, Object actions, Map<String, Object> d) {
        PhpArray r = new PhpArray();
        r.put("userName", PhpRows.get(d, "userName"));

        for (int i = 1; i >= 0; i--) {
            for (Object o : asList(actions)) {
                if (!looseEqualsInt(PhpJson.path(o, "oneInFour"), i)) continue;

                Object name = PhpJson.path(o, "name");
                Object positive = PhpJson.path(o, "positive");
                Object cnt = PhpJson.path(o, "count");

                if (PhpJson.num(cnt) > 0) {
                    // round() with no precision -> a FLOAT, so the JSON is 35.0 not 35.
                    r.put(name, PhpJson.round(PhpJson.num(positive) / PhpJson.num(cnt) * 100));
                    // Accumulation happens ONLY in this branch, mirroring the PHP.
                    accumulate(fb, name, "positive", positive);
                    accumulate(fb, name, "count", cnt);
                } else {
                    r.put(name, "");
                }
            }
        }
        return r;
    }

    /**
     * PHP blankRecord() against a DATA-DRIVEN header: only the hard-coded case names
     * can match, so with an action-name header the result is just {userName}, and an
     * empty array when the header was never built.
     */
    private static Object blankRecord(PhpArray header, Map<String, Object> d) {
        PhpArray r = new PhpArray();
        if (header == null) return r;          // foreach over undefined -> no iterations

        for (Object hObj : header.values()) {
            String s = String.valueOf(hObj);
            switch (s) {
                case "userName" -> r.put(s, PhpRows.get(d, "userName"));
                case "status" -> r.put(s, PhpRows.get(d, "drillStatus"));
                default -> {
                    switch (s) {
                        case "rank", "totalCompetency", "skillsCompetency",
                             "moraleCompetency", "efficiencyCompetency",
                             "leaderShipCompetency", "driveCompetency" -> r.put(s, 0L);
                        default -> { /* no case in the PHP either: key is simply absent */ }
                    }
                }
            }
        }
        return r;
    }

    /** PHP buildFooter(&$detail, $fb, $header) — appends the totals row to detail. */
    private static void buildFooter(PhpArray detail, PhpArray fb, PhpArray header) {
        PhpArray footer = new PhpArray();
        if (header != null) {
            for (Object hObj : header.values()) {
                String s = String.valueOf(hObj);
                Object bucket = fb.get(s);
                double cnt = PhpJson.num(PhpJson.path(bucket, "count"));
                if (cnt > 0) {
                    footer.put(s, PhpJson.round(
                            PhpJson.num(PhpJson.path(bucket, "positive")) / cnt * 100));
                } else {
                    footer.put(s, "");
                }
            }
        }
        detail.append(footer);
    }

    /** PHP +=: int stays int, any float makes it float; null counts as absent. */
    private static void accumulate(PhpArray fb, Object name, String field, Object value) {
        PhpArray bucket = fb.child(name);
        Object cur = bucket.get(field);
        Object sum;
        if (cur == null) {
            sum = PhpJson.isIntegral(value) ? (Object) PhpJson.toLong(value)
                                            : (Object) PhpJson.num(value);
        } else if (PhpJson.isIntegral(cur) && PhpJson.isIntegral(value)) {
            sum = PhpJson.toLong(cur) + PhpJson.toLong(value);
        } else {
            sum = PhpJson.num(cur) + PhpJson.num(value);
        }
        bucket.put(field, sum);
    }

    // ------------------------------------------------------------- helpers

    private static List<?> asList(Object v) {
        if (v instanceof List<?> l) return l;
        return List.of();
    }

    /**
     * PHP's loose `== $i`. null == 0 is TRUE, which is why actions missing the
     * oneInFour field land in the second (non-1-in-4) group.
     *
     * Deliberate small divergence: in PHP 5.6 a non-numeric string also compared equal
     * to 0, so such an action would join that group; here it does not. The field is
     * numeric in every stored blob, so this is unreachable in practice.
     */
    private static boolean looseEqualsInt(Object v, int i) {
        if (v == null) return i == 0;
        if (v instanceof Boolean b) return (b ? 1 : 0) == i;
        if (v instanceof Number n) return n.doubleValue() == i;
        String s = v.toString().trim();
        if (s.isEmpty()) return false;
        try {
            return Double.parseDouble(s) == i;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        String s = v.toString();
        return !s.isEmpty() && !s.equals("0");
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
