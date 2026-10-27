package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpJson;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.db.PhpUniqid;
import com.wlwl.backend.rpc.ModuleHandler;
import com.wlwl.backend.rpc.RpcContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Port of model/ (154 LOC: get.php, post.php, put.php).
 *
 * A training "model" is a template plus a JSON document describing its staff slots,
 * environment events and recruits. The model row records how many of each it should
 * have (copied onto template), and the content blob holds the actual entries. When the
 * two disagree the code "repairs" the blob by resizing the arrays.
 *
 * Decoding here is OBJECT mode — `json_decode($row['modelContent'])` with no second
 * argument — so JSON objects stay objects (unlike the drill reports, which decode in
 * assoc mode). A Jackson tree is therefore the correct in-memory form, and
 * {@code {}} must survive as {@code {}}.
 *
 * THREE BUGS ARE REPRODUCED ON PURPOSE (see PORTING_NOTES §11):
 *
 *  1. `staffs` uses `!==` — a STRICT comparison — against `count()`. mysqli returns
 *     numeric columns as STRINGS, so `'2' !== 2` is always true and the rebuild runs
 *     for every row, even when the array is already the right size.
 *     `environments` and `recruits` use loose `!=`, so those rebuild only when the
 *     sizes actually differ.
 *
 *  2. The scratch array `$rov` is NEVER reset between rows, so its arrays keep
 *     growing as rows are processed — a second model inherits the first model's
 *     pads, then has its first entries overwritten. The port keeps one shared `rov`
 *     across the loop to match.
 *
 *  3. Assigning `->staffs` when the content blob has no such key and the wanted count
 *     is 0 sets it to NULL (the loop body never runs, so `$rov['staffs']` is
 *     undefined), not to `[]`.
 */
@Component
public class ModelModule implements ModuleHandler {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ModelModule(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String module() {
        return "model";
    }

    // ----------------------------------------------------------------- GET
    // get.php: one template by name, expanded into blank staff/env/recruit slots.

    @Override
    public void onGet(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String mod = text(env, "mod");

        // PHP: if ($mod) { ... } — when mod is falsy NOTHING runs: no status change
        // and no body, so the client sees an empty 200.
        if (falsy(mod)) return;

        List<Map<String, Object>> rows = PhpRows.query(jdbc,
                "select * from template where templateName = ?", mod);
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }

        // The while loop assigns $jsonarray = $row on every pass, so the LAST row wins.
        Map<String, Object> row = rows.get(rows.size() - 1);
        PhpRows.set(row, "productExp", "");
        PhpRows.set(row, "ordersToComplete", 0L);
        PhpRows.set(row, "valueToComplete", 0L);

        int staffSlots = count(PhpRows.get(row, "numberOfStaff"));
        int envSlots = count(PhpRows.get(row, "environmentEvents"));
        int recruitSlots = count(PhpRows.get(row, "recruits"));

        // A zero-slot list is NOT created at all: the for body never runs.
        if (staffSlots > 0) row.put("staffs", repeat(staffDefault(), staffSlots));
        if (envSlots > 0) row.put("environments", repeat(environmentDefault(), envSlots));
        if (recruitSlots > 0) row.put("recruits", repeat(recruitDefault(), recruitSlots));

        ctx.json(PhpArray.of(row).toJsonNode());
    }

    // ---------------------------------------------------------------- POST
    // post.php: models for a template, with the arrays resized to the template.

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String mod = text(env, "mod");
        if (falsy(mod)) return;

        StringBuilder sql = new StringBuilder(
                "select * from model m, template t "
                        + "where m.modelTemplate = t.templateName and modelTemplate = ?");
        List<Object> args = new ArrayList<>();
        args.add(mod);

        // PHP: if (strlen($_SERVER['QUERY_STRING'])) -> the raw query string is the modelId
        String qs = ctx.queryString();
        if (!qs.isEmpty()) {
            sql.append(" and modelId = ?");
            args.add(qs);
        }

        List<Map<String, Object>> rows = PhpRows.query(jdbc, sql.toString(), args.toArray());
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }

        ObjectNode rov = F.objectNode();      // shared across rows, like the PHP
        List<Object> out = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            ObjectNode content = objectOrNew(PhpRows.get(row, "modelContent"));
            repair(content, rov,
                    text(PhpRows.get(row, "numberOfStaff")),
                    text(PhpRows.get(row, "environmentEvents")),
                    text(PhpRows.get(row, "recruits")));
            PhpRows.set(row, "modelContent", content);
            out.add(PhpArray.of(row));
        }

        respond(ctx, out, qs.length());
    }

    // ----------------------------------------------------------------- PUT
    // put.php: insert or update, then echo the stored row back.

    @Override
    public void onPut(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        JsonNode qdata = env == null ? null : env.get("qdata");
        if (qdata == null || qdata.isNull()) qdata = F.objectNode();

        // PHP interpolates $qdata->modelContent into SQL. If the client sent a JSON
        // object/array rather than the JSON *text*, PHP dies with "Object of class
        // stdClass could not be converted to string" (500). Same here.
        String content = scalarOrFail(qdata, "modelContent");

        String modelId = text(qdata, "modelId");
        String modelName = scalarOrFail(qdata, "modelName");
        String modelTemplate = scalarOrFail(qdata, "modelTemplate");
        String modelProduct = scalarOrFail(qdata, "modelProduct");

        if (!falsy(modelId)) {
            jdbc.update("update model set modelName = ?, modelTemplate = ?, "
                            + "modelContent = ?, modelProduct = ? where modelId = ?",
                    modelName, modelTemplate, content, modelProduct, modelId);
        } else {
            modelId = PhpUniqid.next();
            jdbc.update("insert into model (modelId, modelName, modelTemplate, "
                            + "modelProduct, modelContent) values (?, ?, ?, ?, ?)",
                    modelId, modelName, modelTemplate, modelProduct, content);
        }

        List<Map<String, Object>> rows = PhpRows.query(jdbc,
                "select * from model m, template t "
                        + "where m.modelTemplate = t.templateName and modelId = ?", modelId);
        if (rows.isEmpty()) {
            ctx.noContent();
            return;
        }

        List<Object> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            // put.php only decodes; it does NOT run the repair pass.
            PhpRows.set(row, "modelContent", objectOrNew(PhpRows.get(row, "modelContent")));
            out.add(PhpArray.of(row));
        }
        respond(ctx, out, ctx.queryStringLength());
    }

    /**
     * The array-vs-object shape switch. Rows are PhpArray values, so they must be
     * rendered through PhpArray (never handed to Jackson directly, which would see a
     * Map and emit an object for an integer-keyed list).
     */
    private static void respond(RpcContext ctx, List<Object> rows, int queryStringLength)
            throws Exception {
        if (queryStringLength == 0) {
            PhpArray list = new PhpArray();
            for (Object r : rows) list.append(r);
            ctx.json(list.toJsonNode());
        } else {
            ctx.json(((PhpArray) rows.get(rows.size() - 1)).toJsonNode());
        }
    }

    // ------------------------------------------------------------ repair

    /**
     * Resize the three lists to the template's declared counts.
     *
     * @param rov scratch arrays shared across ALL rows (the PHP never resets $rov)
     */
    private static void repair(ObjectNode content, ObjectNode rov,
                               String nStaff, String nEnv, String nRecruit) {

        // staffs: STRICT !== against an int. The DB value is a string ('2'), count()
        // is an int (2), so the comparison is ALWAYS unequal and the block always
        // runs. Reproduced as an unconditional rebuild.
        int staff = (int) PhpJson.num(nStaff);
        resize(content, rov, "staffs", staff, staffDefault(), true);

        int envs = (int) PhpJson.num(nEnv);
        resize(content, rov, "environments", envs, environmentDefault(), false);

        int recruits = (int) PhpJson.num(nRecruit);
        resize(content, rov, "recruits", recruits, recruitDefault(), false);
    }

    private static void resize(ObjectNode content, ObjectNode rov, String field,
                               int wanted, ObjectNode blank, boolean always) {
        JsonNode existing = content.get(field);
        int have = countOf(existing);

        if (!always && wanted == have) return;   // PHP loose != : sizes match -> skip

        ArrayNode target = rovArray(rov, field);
        for (int c = 0; c < wanted; c++) target.add(blank);
        int copy = Math.min(wanted, have);
        for (int c = 0; c < copy; c++) target.set(c, existing.get(c));

        // If nothing was ever appended, the PHP reads an undefined $rov[...] and
        // stores NULL, not an empty array. Reproduce that.
        if (target.size() == 0) content.putNull(field);
        else content.set(field, target);
    }

    /** $rov['field'] persisted across rows (never reset). */
    private static ArrayNode rovArray(ObjectNode rov, String field) {
        JsonNode n = rov.get(field);
        if (n instanceof ArrayNode a) return a;
        ArrayNode created = F.arrayNode();
        rov.set(field, created);
        return created;
    }

    // ------------------------------------------------------------ defaults

    private static ObjectNode staffDefault() {
        ObjectNode o = F.objectNode();
        o.put("staffName", "");
        o.put("task", "");
        o.put("staffExp", "");
        o.put("taskExp", "");
        return o;
    }

    private static ObjectNode environmentDefault() {
        ObjectNode o = F.objectNode();
        o.put("environmentName", "");
        o.put("environmentExp", "");
        return o;
    }

    private static ObjectNode recruitDefault() {
        ObjectNode o = F.objectNode();
        o.put("staffName", "");
        o.put("staffExp", "");
        return o;
    }

    private static List<Object> repeat(ObjectNode value, int times) {
        List<Object> list = new ArrayList<>(times);
        for (int i = 0; i < times; i++) list.add(value);
        return list;
    }

    // ------------------------------------------------------------- helpers

    /** json_decode($s) with no $assoc: objects stay objects. Null/invalid -> new object. */
    private ObjectNode objectOrNew(Object raw) {
        Object value = PhpJson.decodeObject(mapper, raw);
        if (value instanceof ObjectNode o) return o;
        // PHP 5.6 warns "Creating default object from empty value" and continues.
        return F.objectNode();
    }

    /** count() over the decoded value: null -> 0, array -> size, object -> 1. */
    private static int countOf(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return 0;
        if (n.isArray()) return n.size();
        return 1;
    }

    private static int count(Object v) {
        return (int) PhpJson.num(v);
    }

    private static boolean falsy(String s) {
        return s == null || s.isEmpty() || "0".equals(s);
    }

    /** A field the PHP interpolates into SQL: must be scalar or the PHP fatals. */
    private static String scalarOrFail(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) return "";
        if (v.isObject() || v.isArray()) {
            throw new IllegalArgumentException(
                    "model/put.php: '" + field + "' must be a scalar; PHP would fatal "
                            + "converting " + v.getNodeType() + " to string");
        }
        return v.asText();
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
