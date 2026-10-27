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
 * Port of consoleuser/ — raw latest-step feed for the admin console.
 *
 * It is the only report module that filters on `drill.startOn is not null` (the three
 * other drill reports have that predicate COMMENTED OUT), so a drill that was created
 * but never started is excluded here but included there.
 *
 * It returns the WHOLE drilldetail row, with `content` re-encoded rather than
 * flattened — so this is a pure round-trip of the JSON blob through
 * `json_decode($x, true)` / `json_encode`. It never reads a key inside the blob, which
 * is why it could be ported without sample `drilldetail.content` data: assoc-mode
 * decode is reproduced by {@link PhpJson#decodeAssoc}, and the re-encode then follows
 * PHP's array-vs-object rule. (If the blob ever contains an object with numeric keys,
 * it comes back out as a JSON array — that IS the production behaviour.)
 */
@Component
public class ConsoleuserModule implements ModuleHandler {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ConsoleuserModule(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String module() {
        return "consoleuser";
    }

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String packageId = text(env, "package");
        double mod = number(env, "mod");

        StringBuilder sql = new StringBuilder(
                "select drill.* from backenduser, drillcontrol, drill "
                        + "where backenduser.active = 1 "
                        + "and drillcontrol.userId = backenduser.userId "
                        + "and drillcontrol.drillControlId = drill.drillControlId "
                        + "and drill.startOn is not null "
                        + "and companyName = ?");
        List<Object> args = new ArrayList<>();
        args.add(packageId);
        if (mod > 0) {
            sql.append(" and seq = ?");
            args.add((int) mod);
        }
        sql.append(" order by drill.drillControlId, drill.seq");

        List<Map<String, Object>> drills = PhpRows.query(jdbc, sql.toString(), args.toArray());

        PhpArray list = new PhpArray();
        int count = 0;
        for (Map<String, Object> rw : drills) {
            List<Map<String, Object>> steps = PhpRows.query(jdbc,
                    "select *, ? as drillControlId from drilldetail "
                            + "where drillId = ? and stepNumber = "
                            + "(select max(stepNumber) from drilldetail where drillId = ?)",
                    text(PhpRows.get(rw, "drillControlId")),
                    PhpRows.get(rw, "drillId"),
                    PhpRows.get(rw, "drillId"));

            for (Map<String, Object> row : steps) {
                PhpRows.set(row, "content",
                        PhpJson.decodeAssoc(mapper, PhpRows.get(row, "content")));
                list.append(PhpArray.of(row));
                count++;
            }
        }

        if (count == 0) {
            ctx.noContent();
            return;
        }

        // Unlike its siblings this one has no query-string switch: it always answers
        // with an array (post.php appends unconditionally).
        ctx.json(list.toJsonNode());
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
