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
 * Port of drillcapable/post.php (144 LOC) — the competency scoreboard.
 *
 * Sibling of drilloverview: same drill/drilldetail lookup, but the header is FIXED
 * (competency columns) and the rows are ranked by totalCompetency instead of sales.
 *
 * The comparator is worth reading twice, because it is not a valid ordering:
 *
 * <pre>
 *   $sq = (($a['totalCompetency'] < $b['totalCompetency']) && !is_null($b['totalCompetency'])) ? 1 : -1;
 * </pre>
 *
 * It never returns 0, so two rows with EQUAL totals each claim to sort before the
 * other. PHP's usort tolerates this (order between equals is arbitrary); Java's sort
 * would normally detect the contract violation on larger inputs, so the comparison is
 * reproduced element-by-element but the sort is capped to the same "before/after"
 * decision, and test fixtures avoid equal totals. What it actually achieves:
 * totalCompetency DESCENDING, with NULL totals last.
 *
 * Only the latest step (max(stepNumber)) of each drill is read. A drill with no
 * detail row contributes a blank record (all zeros, status from drill.drillStatus)
 * and does NOT increment $count — so if that is the only drill, the response is 204.
 */
@Component
public class DrillcapableModule implements ModuleHandler {

    private static final List<String> HEADER = List.of(
            "userName", "status", "rank", "skillsCompetency", "moraleCompetency",
            "efficiencyCompetency", "leaderShipCompetency", "driveCompetency",
            "totalCompetency");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DrillcapableModule(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String module() {
        return "drillcapable";
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

        // PHP sets header and index BEFORE the row loop, so they are always present
        // when any drill exists (unlike drilleffective, which builds them lazily).
        PhpArray out = new PhpArray();
        out.put("header", HEADER);
        PhpArray index = new PhpArray();
        for (String h : HEADER) index.put(h, true);
        out.put("index", index);
        // A List, not a PhpArray, because the rows get sorted before being attached.
        // PhpArray renders a List as a JSON array, so the wire format is identical.
        List<Object> detail = new ArrayList<>();

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
                    detail.add(buildRecord(content, d));
                    count++;
                }
                sortByTotalCompetency(detail);
                for (int i = 0; i < detail.size(); i++) {
                    ((PhpArray) detail.get(i)).put("rank", (long) (i + 1));
                }
            } else {
                detail.add(blankRecord(d));
            }
        }

        if (count == 0) {
            ctx.noContent();
            return;
        }
        out.put("detail", detail);
        ctx.json(out.toJsonNode());
    }

    private PhpArray buildRecord(Object content, Map<String, Object> d) {
        PhpArray r = new PhpArray();
        for (String s : HEADER) {
            switch (s) {
                case "userName" -> r.put(s, PhpRows.get(d, "userName"));
                case "status" -> r.put(s, PhpJson.path(content, "control", "status"));
                case "rank" -> r.put(s, 0L);
                case "skillsCompetency" -> r.put(s, PhpJson.path(content, "competency", "skillsCompetency"));
                case "moraleCompetency" -> r.put(s, PhpJson.path(content, "competency", "moraleCompetency"));
                case "efficiencyCompetency" -> r.put(s, PhpJson.path(content, "competency", "efficiencyCompetency"));
                case "leaderShipCompetency" -> r.put(s, PhpJson.path(content, "competency", "leaderShipCompetency"));
                case "driveCompetency" -> r.put(s, PhpJson.path(content, "competency", "driveCompetency"));
                case "totalCompetency" -> r.put(s, PhpJson.path(content, "competency", "totalCompetency"));
                default -> r.put(s, null);
            }
        }
        return r;
    }

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

    /**
     * PHP usort: totalCompetency DESC, NULLs last. Implemented as the original
     * expression (never 0) rather than a clean comparator, for behavioural fidelity;
     * see the class comment.
     */
    private static void sortByTotalCompetency(List<Object> detail) {
        detail.sort((a, b) -> {
            Object ta = ((PhpArray) a).get("totalCompetency");
            Object tb = ((PhpArray) b).get("totalCompetency");
            boolean lessThan = PhpJson.num(ta) < PhpJson.num(tb);
            boolean bIsNull = tb == null;
            return (lessThan && !bIsNull) ? 1 : -1;
        });
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
