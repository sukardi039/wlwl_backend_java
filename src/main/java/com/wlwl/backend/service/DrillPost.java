package com.wlwl.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.db.PhpUniqid;
import com.wlwl.backend.rpc.RpcContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * Port of drill/post.php (60 lines) — the POST verb, which ignores `mod` entirely.
 *
 * `POST /backend/drill/?U123` returns the drill home screen for a general user:
 * {@code {user, control, runs}}. The query string IS the userId (PHP:
 * `$_SERVER['QUERY_STRING']` spliced straight into the WHERE), so this must read
 * {@link RpcContext#queryString()} and never a parsed parameter.
 *
 * On first contact it CREATES the user's drillcontrol row and one drill per configured
 * company run (runOne/runTwo/runThree), each with `seq` 1..n, `run` naming the slot and
 * `drillStatus` 100 (not started). Sequence matters: the control row is inserted first,
 * then the drills, then the control is re-read so the reply carries the new id.
 *
 * Two shape details from the PHP:
 *  - the user object is the JOINED backenduser+company row, so company's `companyName`
 *    and `active` win over backenduser's duplicates (select * + assoc fetch = last wins);
 *  - `runs` is only added when at least one row exists — a control with no drills replies
 *    with `{user, control}` and NO `runs` key at all.
 */
public final class DrillPost {

    private DrillPost() {
    }

    private static final String[] RUN_SLOTS = {"runOne", "runTwo", "runThree"};

    public static void run(JdbcTemplate jdbc, ObjectMapper mapper, RpcContext ctx) throws Exception {
        String userId = ctx.queryString();

        List<Map<String, Object>> users = PhpRows.query(jdbc,
                "select * from backenduser u, company c where u.companyName = c.packageId "
                        + "and level = 0 and u.active = 1 and c.active = 1 and userId = ?", userId);
        if (users.isEmpty()) {
            ctx.noContent();                 // PHP: no user -> 204, no body
            return;
        }
        Map<String, Object> user = users.get(0);

        PhpArray out = new PhpArray();
        out.put("user", PhpArray.of(user));

        List<Map<String, Object>> control = PhpRows.query(jdbc,
                "select * from drillcontrol where userId = ?", userId);

        if (control.isEmpty()) {
            String drillControlId = PhpUniqid.next();
            jdbc.update("insert into drillcontrol (drillControlId, userId, createdOn) "
                    + "values (?, ?, now())", drillControlId, userId);

            long seq = 1;
            for (String slot : RUN_SLOTS) {
                Object modelId = PhpRows.get(user, slot);
                // PHP splices the value into the SQL, so NULL becomes '' — bind '' to match.
                List<Map<String, Object>> models = PhpRows.query(jdbc,
                        "select * from model m, template t where m.modelTemplate = t.templateName "
                                + "and modelId = ?", modelId == null ? "" : modelId.toString());
                if (models.isEmpty()) continue;

                Map<String, Object> m = models.get(0);
                jdbc.update("insert into drill (drillId, drillControlId, seq, run, drillStatus, "
                                + "modelName, modelTemplate, templateName, modelProduct, duration) "
                                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        PhpUniqid.next(), drillControlId, seq++, slot, 100,
                        PhpRows.get(m, "modelName"), PhpRows.get(m, "modelTemplate"),
                        PhpRows.get(m, "templateName"), PhpRows.get(m, "modelProduct"),
                        // template.duration is INT in production; the fixture declares it
                        // DECIMAL, so coerce like MySQL would rather than binding "0.00"
                        (long) com.wlwl.backend.db.PhpJson.num(PhpRows.get(m, "duration")));
            }

            control = PhpRows.query(jdbc, "select * from drillcontrol where userId = ?", userId);
        }

        Map<String, Object> controlRow = control.get(0);
        out.put("control", PhpArray.of(controlRow));

        List<Map<String, Object>> runs = PhpRows.query(jdbc,
                "select * from drill where drillControlId = ?",
                PhpRows.get(controlRow, "drillControlId"));
        if (!runs.isEmpty()) {
            PhpArray arr = new PhpArray();
            for (Map<String, Object> r : runs) arr.append(PhpArray.of(r));
            out.put("runs", arr);
        }

        ctx.json(out.toJsonNode());
    }
}
