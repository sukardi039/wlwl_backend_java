package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.rpc.ModuleHandler;
import com.wlwl.backend.rpc.RpcContext;
import com.wlwl.backend.rpc.RpcEnvelopeReader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared shape for the eight "catalogue" modules.
 *
 * environmental, template, product, style and task are all:
 *
 * <pre>
 *   select * from baseinfo b left join &lt;entity&gt; p on b.name = p.&lt;name column&gt;
 *   where b.baseclass = '&lt;class&gt;'  [limit n]
 * </pre>
 *
 * while agent, licensebatch and package read a view/table directly. All of them then
 * echo rows with the usual array-vs-object switch and 204 on empty, and all of them
 * answer PUT by upserting one row and re-reading it.
 *
 * The one thing worth noticing: the re-read after an INSERT is NOT a lookup by the new
 * key. It is the module's own listing query keyed on `$qdata->name` — i.e. the
 * baseinfo display name. So inserting an entity whose name has no matching baseinfo
 * row inserts the row and then answers 204, because the join finds nothing. That is
 * real behaviour (the entity tables are second-class citizens of baseinfo) and is
 * reproduced by each subclass returning the same query it lists with.
 */
public abstract class AbstractCatalogueModule implements ModuleHandler {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    protected final JdbcTemplate jdbc;

    protected AbstractCatalogueModule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The listing query, WITHOUT the optional LIMIT (the base appends it). */
    protected abstract String postSql();

    /** Bind values for {@link #postSql()}, in order. */
    protected abstract List<Object> postArgs(JsonNode env);

    /** Insert or update, according to the PHP's own branch. */
    protected abstract void upsert(JsonNode qdata);

    /** The re-read performed after the upsert. */
    protected abstract String putSelectSql();

    protected abstract List<Object> putSelectArgs(JsonNode qdata);

    // ---------------------------------------------------------------- POST

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String sql = postSql();
        List<Object> args = new ArrayList<>(postArgs(env));

        Integer limit = new RpcEnvelopeReader(env).limitOrNull();
        if (limit != null) {
            sql = sql + " limit ?";
            args.add(limit);
        }

        List<Map<String, Object>> rows = PhpRows.query(jdbc, sql, args.toArray());
        respond(ctx, rows, ctx.queryStringLength());
    }

    // ----------------------------------------------------------------- PUT

    @Override
    public void onPut(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        JsonNode qdata = env == null ? null : env.get("qdata");
        if (qdata == null || qdata.isNull()) qdata = F.objectNode();

        upsert(qdata);

        List<Map<String, Object>> rows = PhpRows.query(
                jdbc, putSelectSql(), putSelectArgs(qdata).toArray());
        respond(ctx, rows, ctx.queryStringLength());
    }

    // ------------------------------------------------------------- helpers

    protected static void respond(RpcContext ctx, List<Map<String, Object>> rows,
                                  int queryStringLength) throws Exception {
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }
        if (queryStringLength == 0) {
            PhpArray list = new PhpArray();
            for (Map<String, Object> row : rows) list.append(PhpArray.of(row));
            ctx.json(list.toJsonNode());
        } else {
            ctx.json(PhpArray.of(rows.get(rows.size() - 1)).toJsonNode());
        }
    }

    /** PHP concatenation: missing/null becomes "". */
    protected static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    /** The PHP interpolates these into numeric columns; '' would be a 0 there. */
    protected static int intOf(JsonNode node, String field) {
        if (node == null) return 0;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return 0;
        if (v.isNumber()) return v.asInt();
        try {
            return Integer.parseInt(v.asText().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    protected static double doubleOf(JsonNode node, String field) {
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

    protected static boolean truthy(String s) {
        return s != null && !s.isEmpty() && !"0".equals(s);
    }

    protected static boolean truthy(JsonNode node, String field) {
        if (node == null) return false;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return false;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber()) return v.asDouble() != 0;
        String s = v.asText();
        return !s.isEmpty() && !s.equals("0");
    }
}
