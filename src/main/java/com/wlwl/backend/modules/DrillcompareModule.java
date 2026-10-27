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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of drillcompare/ — each user's team efficiency at their FIRST drill vs their
 * LATEST one, for a company.
 *
 * Two queries, both filtered to `backenduser.instructor = 0` (trainees, not trainers) and
 * ordered by drillControlId then userName:
 *
 *   start  -> seq = 1  (the first exercise)
 *   end    -> seq = 3, falling back to 2, then 1 if no rows come back
 *
 * So a user who only ever ran one drill gets startEff == endEff, and a user with a
 * three-drill history is compared 1 -> 3. Only three fields are emitted per user:
 * userName, startEff, endEff. endEff starts at the INT 0 so a user with no matching end
 * drill reports 0 rather than null.
 *
 * Quirks preserved: the whole body is skipped unless the seq=1 query returned rows; the
 * end-ladder is tried in order 3,2,1 and stops at the first non-empty result; and the
 * output is a LIST (the PHP builds an assoc keyed by userId but then re-appends only the
 * values with `foreach ($jx as $j) $jsonarray[] = $j`).
 */
@Component
public class DrillcompareModule implements ModuleHandler {

    private static final String BASE_SQL = "select drill.*, backenduser.userName, "
            + "backenduser.userId from backenduser, drillcontrol, drill "
            + "where backenduser.active = 1 and drillcontrol.userId = backenduser.userId "
            + "and backenduser.instructor = 0 "
            + "and drillcontrol.drillControlId = drill.drillControlId "
            + "and companyName = ? and seq = ? "
            + "order by drillControlId, backenduser.userName";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DrillcompareModule(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String module() {
        return "drillcompare";
    }

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String pkg = text(env, "package");

        int count = 0;
        Map<Object, PhpArray> jx = new LinkedHashMap<>();

        List<Map<String, Object>> startRows = PhpRows.query(jdbc, BASE_SQL, pkg, 1);
        if (!startRows.isEmpty()) {
            for (Map<String, Object> rw : startRows) {
                for (Map<String, Object> row : lastStep(rw)) {
                    Object content = PhpJson.decodeAssoc(mapper, PhpRows.get(row, "content"));
                    PhpArray e = jx.computeIfAbsent(PhpRows.get(rw, "userId"), k -> new PhpArray());
                    e.put("userName", PhpRows.get(rw, "userName"));
                    e.put("startEff", PhpJson.path(content, "current", "teamAvg", "efficiency"));
                    e.put("endEff", 0);
                    count++;
                }
            }

            // PHP: try seq 3, else 2, else 1 — first non-empty wins.
            List<Map<String, Object>> endRows = List.of();
            for (int seq : new int[]{3, 2, 1}) {
                endRows = PhpRows.query(jdbc, BASE_SQL, pkg, seq);
                if (!endRows.isEmpty()) break;
            }
            for (Map<String, Object> rw1 : endRows) {
                for (Map<String, Object> row1 : lastStep(rw1)) {
                    Object content = PhpJson.decodeAssoc(mapper, PhpRows.get(row1, "content"));
                    // PHP creates the entry on demand here, so an end-only user appears
                    // with just {"endEff": n} and no userName/startEff.
                    PhpArray e = jx.computeIfAbsent(PhpRows.get(rw1, "userId"), k -> new PhpArray());
                    e.put("endEff", PhpJson.path(content, "current", "teamAvg", "efficiency"));
                }
            }
        }

        if (count == 0) {
            ctx.noContent();
            return;
        }

        PhpArray out = new PhpArray();
        for (PhpArray v : jx.values()) out.append(v);
        ctx.json(out.toJsonNode());
    }

    /** The drill's final snapshot, with drillControlId aliased in — as the PHP does. */
    private List<Map<String, Object>> lastStep(Map<String, Object> rw) {
        return PhpRows.query(jdbc,
                "select *, ? as drillControlId from drilldetail where drillId = ? "
                        + "and stepNumber = (select max(stepNumber) from drilldetail where drillId = ?)",
                text(PhpRows.get(rw, "drillControlId")),
                PhpRows.get(rw, "drillId"),
                PhpRows.get(rw, "drillId"));
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static String text(Object v) {
        return v == null ? "" : v.toString();
    }
}
