package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.rpc.ModuleHandler;
import com.wlwl.backend.rpc.RpcContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Port of baseinfo/post.php and baseinfo/put.php.
 *
 * Shows the two idioms nearly every module repeats:
 *  - `mod` is a WHERE-clause value here, not an action selector
 *  - array-vs-object response shape driven by strlen($_SERVER['QUERY_STRING'])
 *  - 204 (not 200) when the result set is empty
 *  - a synthetic 'key' column injected into each row
 */
@Component
public class BaseinfoModule implements ModuleHandler {

    private final JdbcTemplate jdbc;

    public BaseinfoModule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "baseinfo";
    }

    // ---------------------------------------------------------------- POST
    // PHP: select * from baseinfo where baseClass = '$mod' [limit $limit]

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        if (env == null) { ctx.noContent(); return; }

        // PHP interpolates null as '', not as SQL NULL. Binding NULL would match
        // nothing, which is a different result — so blank it out deliberately.
        String baseClass = env.get("mod") == null || env.get("mod").isNull()
                ? "" : env.get("mod").asText();

        StringBuilder sql = new StringBuilder("select * from baseinfo where baseClass = ?");
        List<Object> args = new ArrayList<>();
        args.add(baseClass);

        Integer limit = new com.wlwl.backend.rpc.RpcEnvelopeReader(env).limitOrNull();
        if (limit != null) {
            sql.append(" limit ?");
            args.add(limit);
        }

        List<Map<String, Object>> rows = PhpRows.query(jdbc, sql.toString(), args.toArray());
        respond(ctx, rows);
    }

    // ---------------------------------------------------------------- PUT
    // PHP: if (!$qdata->key) INSERT ... else UPDATE ... then re-select by name

    @Override
    public void onPut(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        if (env == null) { ctx.noContent(); return; }

        JsonNode qdata = env.get("qdata");
        if (qdata == null || qdata.isNull()) { ctx.noContent(); return; }

        String baseClass = env.get("mod") == null || env.get("mod").isNull()
                ? "" : env.get("mod").asText();
        String name = text(qdata, "name");
        String displayKey = text(qdata, "displayKey");
        String key = text(qdata, "key");

        if (key.isEmpty()) {
            jdbc.update("insert into baseinfo (baseClass, name, displayKey) values (?, ?, ?)",
                    baseClass, name, displayKey);
        } else {
            jdbc.update("update baseinfo set name = ?, displayKey = ? where name = ?",
                    name, displayKey, key);
        }

        List<Map<String, Object>> rows =
                PhpRows.query(jdbc, "select * from baseinfo where name = ?", name);
        respond(ctx, rows);
    }

    private void respond(RpcContext ctx, List<Map<String, Object>> rows) throws Exception {
        if (rows.isEmpty()) {
            ctx.noContent();          // PHP: http_response_code(204)
            return;
        }
        for (Map<String, Object> row : rows) {
            row.put("key", row.get("name"));   // PHP: $row['key'] = $row['name']
        }
        ctx.json(PhpRows.arrayOrLastRow(rows, ctx.queryStringLength()));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }
}
