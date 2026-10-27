package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.rpc.ModuleHandler;
import com.wlwl.backend.rpc.RpcContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Port of basicinformation/post.php (272 lines, 18 independent sections).
 *
 * This is the "load every lookup table in one call" module: the SPA calls it once
 * at startup and caches the whole payload. There is no get/put/delete PHP file,
 * so every other verb falls through to the interface defaults (empty 200).
 *
 * Three things make this module different from baseinfo/staff:
 *
 *  1. The response is ~300 keys of nested PHP associative arrays, not a row list.
 *     Whether each level serialises as a JSON array or object depends on PHP key
 *     coercion — hence {@link PhpArray}.
 *
 *  2. Every section is wrapped in `if ($result->num_rows > 0)` and bumps
 *     `$datacount`. A section with zero rows contributes NO KEYS AT ALL — not an
 *     empty object, not an empty array, absent. If every section is empty the
 *     response is 204. So a partially-populated database yields a partial object,
 *     and the client's `.hasOwnProperty` checks are load-bearing.
 *
 *  3. `company` has an explicit else branch (lines 222-229) that writes six EMPTY
 *     arrays when there are no companies. That is the only section that
 *     materialises keys on an empty result. Reproduced exactly, including the
 *     keys it omits (there is no `livepackage_exp`).
 *
 * Note: basicinformation/x_post.php is a near-duplicate of post.php whose only
 * difference is that it is MISSING that company else-branch (verified by diff).
 * index.php includes restdx.inc, which includes post.php, so post.php is live and
 * x_post.php is dead. This class ports post.php.
 */
@Component
public class BasicinformationModule implements ModuleHandler {

    private static final DateTimeFormatter PHP_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public BasicinformationModule(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String module() {
        return "basicinformation";
    }

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        if (env == null) {
            // restdx.inc dereferences $data->restkey without a null check; the
            // request dies before any module code runs. Nothing to emit.
            ctx.noContent();
            return;
        }

        PhpArray out = new PhpArray();
        int datacount = 0;

        // ---------------------------------------------------------- baseinfo
        List<Map<String, Object>> rows = PhpRows.query(jdbc, "select * from baseinfo");
        if (!rows.isEmpty()) {
            datacount++;
            for (Map<String, Object> row : rows) {
                Object baseclass = PhpRows.get(row, "baseclass");
                Object displayKey = PhpRows.get(row, "displayKey");
                Object name = PhpRows.get(row, "name");
                out.child("base_ref").child(baseclass).put(displayKey, name);
                out.child("base_lst").child(baseclass).append(displayKey);
                out.child("base_exp").child(baseclass).put(name, displayKey);
                out.child("base_det").child(baseclass).append(PhpArray.of(row));
            }
        }

        // --------------------------------------------------------- baseclass
        rows = PhpRows.query(jdbc, "select * from baseclass");
        if (!rows.isEmpty()) {
            datacount++;
            for (Map<String, Object> row : rows) {
                Object type = PhpRows.get(row, "baseClassType");
                Object dk = PhpRows.get(row, "baseClassDisplayKey");
                Object cn = PhpRows.get(row, "baseClassName");
                Object actType = PhpRows.get(row, "baseActionType");
                out.child("class_ref").child(type).put(dk, cn);
                out.child("class_lst").child(type).append(dk);
                // NOTE: flat here, unlike class_ref — no type level in the key.
                out.child("class_exp").put(cn, dk);
                out.child("act_ref").child(actType).put(dk, cn);
                out.child("act_lst").child(actType).append(dk);
                out.child("baseclass_lst").child("baseclass").append(dk);
            }
        }

        // ------------------------------------------------------------- staff
        rows = PhpRows.query(jdbc, "select * from staff");
        if (!rows.isEmpty()) {
            datacount++;
            // The profile-class expansion is loaded ONCE, outside the row loop.
            PhpArray exp = new PhpArray();
            for (Map<String, Object> ros : PhpRows.query(jdbc, "select * from profileclass")) {
                exp.put(PhpRows.get(ros, "profileClassCode"),
                        PhpRows.get(ros, "profileClassDisplayName"));
            }
            for (Map<String, Object> row : rows) {
                // PHP reads $exp[$row['x']] with no isset guard: a missing profile
                // class becomes JSON null, and the key IS still created.
                PhpRows.set(row, "skillsExp", exp.get(PhpRows.get(row, "skillsProfile")));
                PhpRows.set(row, "moraleExp", exp.get(PhpRows.get(row, "moraleProfile")));
                PhpRows.set(row, "efficiencyExp", exp.get(PhpRows.get(row, "efficiencyProfile")));
                Object staffId = PhpRows.get(row, "staffId");
                Object staffName = PhpRows.get(row, "staffName");
                PhpArray r = PhpArray.of(row);
                out.child("staff_ref").child("staff").put(staffName, staffId);
                out.child("staff_lst").child("staff").append(staffName);
                out.child("staff_exp").child("staff").put(staffId, staffName);
                out.child("staff_det").child("staff").put(staffId, r);
                out.child("staff_rec").child("staff").append(r);
            }
        }

        // -------------------------------------------------------- profileclass
        rows = PhpRows.query(jdbc, "select * from profileclass");
        if (!rows.isEmpty()) {
            datacount++;
            for (Map<String, Object> row : rows) {
                Object profileName = PhpRows.get(row, "profileName");
                Object displayName = PhpRows.get(row, "profileClassDisplayName");
                Object code = PhpRows.get(row, "profileClassCode");
                out.child("profile_ref").child(profileName).put(displayName, code);
                out.child("profile_lst").child(profileName).append(displayName);
                out.child("profile_exp").child(profileName).put(code, displayName);
                out.child("profile_det").put(code, PhpArray.of(row));
            }
        }

        // ------------------------------------------------------------ language
        rows = PhpRows.query(jdbc, "select * from lang");
        if (!rows.isEmpty()) {
            datacount++;
            for (Map<String, Object> row : rows) {
                out.child("language_lst").child("language").append(PhpRows.get(row, "langCode"));
            }
        }

        // --------------------------------------------------------- companyType
        rows = PhpRows.query(jdbc, "select * from companytype");
        if (!rows.isEmpty()) {
            datacount++;
            for (Map<String, Object> row : rows) {
                out.child("companyType_lst").child("companyType")
                        .append(PhpRows.get(row, "companyTypeCode"));
            }
        }

        // ------------------------------------------------------------- product
        // Join with baseinfo; displayKey/name are then OVERWRITTEN with
        // productShortName, keeping their original positions in the row.
        rows = PhpRows.query(jdbc, "select * from product p, baseinfo b "
                + "where p.productName = b.name and productShortName is not null");
        if (!rows.isEmpty()) {
            datacount++;
            for (Map<String, Object> row : rows) {
                Object shortName = PhpRows.get(row, "productShortName");
                PhpRows.set(row, "displayKey", shortName);
                PhpRows.set(row, "name", shortName);
                out.child("product_ref").child("product").put(shortName, shortName);
                out.child("product_lst").child("product").append(shortName);
                out.child("product_exp").child("product").put(shortName, shortName);
                out.child("product_det").child("product").append(PhpArray.of(row));
            }
        }

        // ------------------------------------------------------------ template
        rows = PhpRows.query(jdbc, "select * from template p, baseinfo b "
                + "where p.templateName = b.name");
        if (!rows.isEmpty()) {
            datacount++;
            for (Map<String, Object> row : rows) {
                Object dk = PhpRows.get(row, "displayKey");
                Object name = PhpRows.get(row, "name");
                out.child("template_ref").child("template").put(dk, name);
                out.child("template_lst").child("template").append(dk);
                // Second, PARALLEL list under a sibling key — appended in the same
                // loop iteration, so index N of 'template' and 'templateName' line up.
                out.child("template_lst").child("templateName")
                        .append(PhpRows.get(row, "templateDescription"));
                out.child("template_exp").child("template").put(name, dk);
                out.child("template_det").child("template").append(PhpArray.of(row));
            }
        }

        // ---------------------------------------------------------------- task
        rows = PhpRows.query(jdbc, "select * from task p, baseinfo b where p.taskName = b.name");
        if (!rows.isEmpty()) {
            datacount++;
            for (Map<String, Object> row : rows) {
                Object dk = PhpRows.get(row, "displayKey");
                Object name = PhpRows.get(row, "name");
                out.child("task_ref").child("task").put(dk, name);
                out.child("task_lst").child("task").append(dk);
                out.child("task_exp").child("task").put(name, dk);
                out.child("task_det").child("task").append(PhpArray.of(row));
            }
        }

        // --------------------------------------------------------------- style
        rows = PhpRows.query(jdbc, "select * from style p, baseinfo b where p.styleName = b.name");
        if (!rows.isEmpty()) {
            datacount++;
            for (Map<String, Object> row : rows) {
                Object dk = PhpRows.get(row, "displayKey");
                Object name = PhpRows.get(row, "name");
                out.child("style_ref").child("style").put(dk, name);
                out.child("style_des").child("style").put(dk, PhpRows.get(row, "styleDescription"));
                out.child("style_lst").child("style").append(dk);
                out.child("style_exp").child("style").put(name, dk);
                out.child("style_det").child("style").append(PhpArray.of(row));
            }
        }

        // -------------------------------------------------------------- action
        rows = PhpRows.query(jdbc, "select * from action p, baseinfo b "
                + "where p.actionName = b.name order by seq");
        if (!rows.isEmpty()) {
            datacount++;
            PhpArray act = new PhpArray();
            for (Map<String, Object> ros : PhpRows.query(jdbc, "select * from actiontype")) {
                act.put(PhpRows.get(ros, "actionType"), PhpRows.get(ros, "typeDescription"));
            }
            for (Map<String, Object> row : rows) {
                Object dk = PhpRows.get(row, "displayKey");
                Object name = PhpRows.get(row, "name");
                Object actionName = PhpRows.get(row, "actionName");
                Object parentAction = PhpRows.get(row, "parentAction");
                PhpRows.set(row, "typeExp", act.get(PhpRows.get(row, "actionType")));
                PhpArray r = PhpArray.of(row);

                out.child("action_ref").child("action").put(dk, name);
                out.child("action_lst").child("action").append(dk);
                out.child("action_exp").child("action").put(name, dk);

                if (number(PhpRows.get(row, "show")) > 0) {
                    if (phpEquals(actionName, parentAction)) {
                        out.child("action_lst").child("mainAction").append(dk);
                    } else {
                        out.child("action_lst").child("subAction").child(parentAction).append(dk);
                        // A SEPARATE row instance is appended to subAction, before
                        // typeExp/displayKey mutations? No: r is already mutated,
                        // same object as 'action'. Reproduced as the same content.
                        out.child("action_det").child("subAction").child(parentAction).append(r);
                    }
                }
                out.child("action_det").child("action").append(r);
            }
        }

        // ------------------------------------------------------ task-efficiency
        rows = PhpRows.query(jdbc, "select * from taskefficiency");
        if (!rows.isEmpty()) {
            datacount++;
            for (Map<String, Object> row : rows) {
                // PHP: $jsonarray['task_eff'][productName][taskName] = $row;
                // The ROW is the value — nested two levels deep, not flattened.
                out.child("task_eff")
                        .child(PhpRows.get(row, "productName"))
                        .put(PhpRows.get(row, "taskName"), PhpArray.of(row));
            }
        }

        // --------------------------------------------------------- action type
        rows = PhpRows.query(jdbc, "select * from actiontype");
        if (!rows.isEmpty()) {
            datacount++;
            for (Map<String, Object> row : rows) {
                Object type = PhpRows.get(row, "actionType");
                Object desc = PhpRows.get(row, "typeDescription");
                out.child("actionType_ref").child("actionType").put(desc, type);
                out.child("actionType_exp").child("actionType").put(type, desc);
                out.child("actionType_lst").child("actionType").append(desc);
            }
        }

        // --------------------------------------------------------- environment
        rows = PhpRows.query(jdbc, "select * from environment p, baseinfo b "
                + "where p.environmentName = b.name");
        if (!rows.isEmpty()) {
            datacount++;
            for (Map<String, Object> row : rows) {
                Object dk = PhpRows.get(row, "displayKey");
                Object name = PhpRows.get(row, "name");
                out.child("environment_ref").child("environment").put(dk, name);
                out.child("environment_lst").child("environment").append(dk);
                out.child("environment_exp").child("environment").put(name, dk);
                out.child("environment_det").child("environment").append(PhpArray.of(row));
            }
        }

        // -------------------------------------------------------------- models
        rows = PhpRows.query(jdbc, "select * from model p, template b "
                + "where p.modelTemplate = b.templateName");
        if (!rows.isEmpty()) {
            datacount++;
            for (Map<String, Object> row : rows) {
                // json_decode($row['modelContent']) — the column holds JSON text and
                // is re-emitted as a nested structure, not as a string.
                String content = text(PhpRows.get(row, "modelContent"));
                PhpRows.set(row, "modelContent", decodeJson(content));
                Object modelId = PhpRows.get(row, "modelId");
                Object modelName = PhpRows.get(row, "modelName");
                out.child("model_ref").child("model").put(modelName, modelId);
                out.child("model_lst").child("model").append(modelName);
                out.child("model_exp").child("model").put(modelId, modelName);
                out.child("model_det").child("model").append(PhpArray.of(row));
            }
        }

        // ------------------------------------------------------------ packages
        rows = PhpRows.query(jdbc, "select * from company where active < 2");
        if (!rows.isEmpty()) {
            datacount++;
            String timeNow = LocalDate.now().format(PHP_DATE);   // PHP: date("Y-m-d")
            for (Map<String, Object> row : rows) {
                Object companyName = PhpRows.get(row, "companyName");
                Object packageId = PhpRows.get(row, "packageId");
                out.child("package_ref").child("package").put(companyName, packageId);
                out.child("package_lst").child("package").append(companyName);
                out.child("package_exp").child("package").put(packageId, companyName);
                out.child("package_det").child("package").append(PhpArray.of(row));
                // PHP compares date STRINGS lexicographically here, not dates.
                if (phpStringLte(PhpRows.get(row, "availableFrom"), timeNow)
                        && phpStringGte(PhpRows.get(row, "availableTill"), timeNow)) {
                    out.child("livepackage_ref").child("package").put(companyName, packageId);
                    out.child("livepackage_lst").child("package").append(companyName);
                }
            }
        } else {
            // The ONLY section that materialises keys when empty (lines 222-229).
            // Note there is no livepackage_exp — do not add one.
            out.child("package_ref").child("package");
            out.child("package_lst").child("package");
            out.child("package_exp").child("package");
            out.child("package_det").child("package");
            out.child("livepackage_ref").child("package");
            out.child("livepackage_lst").child("package");
        }

        // --------------------------------------------------------------- agent
        rows = PhpRows.query(jdbc, "select * from agentlicense_view");
        if (!rows.isEmpty()) {
            datacount++;
            String timeNow = LocalDate.now().format(PHP_DATE);
            for (Map<String, Object> row : rows) {
                Object agentName = PhpRows.get(row, "agentName");
                Object agentId = PhpRows.get(row, "agentId");
                out.child("agent_ref").child("agent").put(agentName, agentId);
                out.child("agent_lst").child("agent").append(agentName);
                out.child("agent_exp").child("agent").put(agentId, agentName);
                out.child("agent_det").child("agent").append(PhpArray.of(row));
                if (phpStringLte(PhpRows.get(row, "availableFrom"), timeNow)
                        && phpStringGte(PhpRows.get(row, "availableTill"), timeNow)) {
                    out.child("liveagent_ref").child("agent").put(agentName, agentId);
                    out.child("liveagent_lst").child("agent").append(agentName);
                }
            }
        }

        // -------------------------------------------------------------- params
        // `key` and `value` are reserved words in MySQL; select * needs no quoting.
        rows = PhpRows.query(jdbc, "select * from params");
        if (!rows.isEmpty()) {
            datacount++;
            for (Map<String, Object> row : rows) {
                out.child("params_ref").child("params")
                        .put(PhpRows.get(row, "key"), PhpRows.get(row, "value"));
            }
        }

        // --------------------------------------------------------- bossmessage
        rows = PhpRows.query(jdbc, "select * from bossmessage");
        if (!rows.isEmpty()) {
            datacount++;
            for (Map<String, Object> row : rows) {
                out.child("bossmessage_ref").child("msg")
                        .put(PhpRows.get(row, "messageCode"), PhpRows.get(row, "messageText"));
            }
        }

        if (datacount > 0) {
            ctx.json(out.toJsonNode());
        } else {
            ctx.noContent();
        }
    }

    // ------------------------------------------------------------- helpers

    private JsonNode decodeJson(String content) {
        if (content == null || content.isEmpty()) return null;
        try {
            return mapper.readTree(content);
        } catch (Exception e) {
            return null;   // PHP json_decode() returns null on malformed input
        }
    }

    private static String text(Object v) {
        return v == null ? "" : v.toString();
    }

    private static double number(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0;
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** PHP == on two scalars: numeric when both look numeric, else string compare. */
    private static boolean phpEquals(Object a, Object b) {
        String sa = text(a);
        String sb = text(b);
        if (isNumeric(sa) && isNumeric(sb)) {
            return Double.compare(Double.parseDouble(sa), Double.parseDouble(sb)) == 0;
        }
        return sa.equals(sb);
    }

    /** PHP: $a <= $b on strings — numeric if both numeric, else lexicographic. */
    private static boolean phpStringLte(Object a, Object b) {
        String sa = text(a);
        String sb = text(b);
        if (isNumeric(sa) && isNumeric(sb)) {
            return Double.parseDouble(sa) <= Double.parseDouble(sb);
        }
        return sa.compareTo(sb) <= 0;
    }

    private static boolean phpStringGte(Object a, Object b) {
        String sa = text(a);
        String sb = text(b);
        if (isNumeric(sa) && isNumeric(sb)) {
            return Double.parseDouble(sa) >= Double.parseDouble(sb);
        }
        return sa.compareTo(sb) >= 0;
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) return false;
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
