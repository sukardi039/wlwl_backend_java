package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Orders must actually move: members pick up freshly created orders, spend their daily work on
 * them, and close them, so {@code orderStatus.ordersCompleted} — and with it the week counters and
 * the team progress — advance.
 *
 * <p>This is the path the suite never asserted. {@code DrillMoreActionsTest} checks
 * {@code workAvailable == 0.76} (the capacity computed in pass 4) but never that the capacity is
 * <em>spent</em>, so a total freeze still looked green.
 *
 * <p>The freeze was real. The assignment gate read {@code eq(order.get("assignedTo"), "")};
 * {@code eq(null, "")} is false in Java while PHP's {@code null == ''} is true. Orders are created
 * by {@code init.php:213} with only a {@code day}, and the PHP's
 * {@code $contentr[...]['assignedTo'] = ''} typo ({@code action.php:629}) means {@code
 * assignedTo} is never written, so the PHP depends on that loose comparison to accept a fresh
 * order. With {@code eq} nothing was ever assigned, {@code cal_works} never ran, and both task and
 * team progress stayed frozen at zero.
 *
 * <p>The fixture is physical: PHP routes an order through FIVE stages, and a member only works the
 * stage its task name starts with ({@code substr($task,0,1) == $t}), so it takes one member per
 * stage. With {@code efficiencyValue = 100} each has {@code workAvailable = 1.0}, the task
 * {@code duration} is 1.00 and {@code passingRate} is 1, so every stage completes the moment it is
 * assigned and a single step can carry one order from stage 1 to 5.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DrillOrderProgressTest {

    private static final String KEY = "K1";

    /** taskName must start with its stage digit; PHP looks weights up by the full name. */
    private static final String[] TASKS = {
            "1-Inspect", "2-Assess", "3-Design", "4-Build", "5-Deliver"
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void scenario() {
        jdbc.update("update drill set modelProduct = 'W1' where drillId = 'D1'");
        for (String task : TASKS) {
            jdbc.update("delete from taskefficiency where productName = 'W1' and taskName = ?", task);
            jdbc.update("insert into taskefficiency (productName, taskName, skillsWeight, moraleWeight, "
                    + "duration, passingRate) values ('W1', ?, 0.50, 0.50, 1.00, 1.00)", task);
        }
    }

    /** What init.php really writes: orders carry only `day`; no state, no assignedTo. */
    private ObjectNode initialContent() {
        ObjectNode c = mapper.createObjectNode();

        ObjectNode control = c.putObject("control");
        control.put("maxMembers", TASKS.length);
        control.put("status", 500);
        control.put("action", "Advise");
        control.put("actionExp", "AD");
        control.put("task", TASKS[0]);
        control.put("taskExp", "TI");
        control.put("welcome", 1);
        control.put("numberOfDays", 10);
        control.put("totalOrders", 2);          // PHP iterates d < totalOrders, so only d = 1
        c.put("ordersToComplete", "8");
        // weekSummary multiplies the deal count by this, so a missing value would make
        // weekValue 0 and hide the movement
        c.put("orderValue", 100);

        c.putObject("orders").putObject("1").put("day", 1);

        ObjectNode days = c.putObject("day");
        ObjectNode weeks = c.putObject("week");
        for (int d = 1; d <= 11; d++) {
            int week = (d - 1) / 5 + 1;
            ObjectNode day = days.putObject(String.valueOf(d));
            day.put("evaluateStyle", 0);
            day.put("week", week);
            day.put("weekday", (d - 1) % 5 + 1);
            day.putObject("event").put("count", 0);
            day.put("newOrders", "3");
            weeks.putObject(String.valueOf(week)).put("weekNumber", week);
        }

        c.putObject("used").putObject("actions");

        var taskEff = c.putArray("task_eff");
        for (String task : TASKS) {             // indexed by state-1
            ObjectNode t = taskEff.addObject();
            t.put("taskName", task);
            t.put("passingRate", 1.00);
        }

        ObjectNode members = c.putObject("members");
        for (int i = 0; i < TASKS.length; i++) {
            int c1 = i + 1;
            ObjectNode m = members.putObject(String.valueOf(c1));
            m.put("staffName", String.valueOf(c1));
            m.put("staffExp", "S" + c1);
            m.put("task", TASKS[i]);
            m.put("taskExp", "T" + c1);
            m.put("style", "Formal");
            m.put("styleExp", "FO");
            m.put("skillsValue", "60");
            m.put("moraleValue", "50");
            m.put("efficiencyValue", "100");
            m.put("skillsAdded", 0);
            m.put("moraleAdded", 0);
            m.put("efficiencyAdded", 0);
            m.put("added", 0);
            m.put("addedAvg", 0);
            m.put("msgCount", 0);
            m.put("readMsg", 1);
            m.putArray("message");
            m.put("away", 0);
            m.put("actioned", 0);
            m.put("action", "");
            m.put("actionExp", "");
            m.put("workingOn", 0);
            m.put("attention", 10);
            m.put("attenWarning", 8);
            m.put("listen", 5);
            m.putObject("effect");
            m.putObject("envEffect");
            m.putObject("actEffect");
            m.put("evaluated", 0);
            m.put("evalResult", "");
            m.put("touched", 0);
            m.put("maxMorale", 80);
            m.put("trend", 0);
            m.put("trendLast", 0);
        }

        c.putArray("history");
        c.putArray("summary");
        c.putObject("current").putObject("teamAvg").put("total", 0);
        c.putObject("orderStatus").put("ordersCompleted", 0);
        c.putObject("bossMsg").put("count", 0);
        c.putObject("bossRev").put("count", 0);
        c.putObject("staffMsg").put("count", 0);
        return c;
    }

    private JsonNode post(JsonNode content, int stepNumber, int dayNumber) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("restkey", KEY);
        body.put("method", "PUT");
        body.put("mod", "ACTION");
        ObjectNode qdata = body.putObject("qdata");
        qdata.put("drillId", "D1");
        qdata.put("drillDetailId", "DD1b");
        qdata.put("stepNumber", stepNumber);
        qdata.put("dayNumber", dayNumber);
        qdata.put("action", "MORE_ACTIONS");
        // the client sends content as a JSON *string*
        qdata.put("content", mapper.writeValueAsString(content));

        MvcResult r = mockMvc.perform(put("/backend/drill/")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(body))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return mapper.readTree(new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    /**
     * The reply is the LATEST drilldetail row for the drill, and the shared fixture already
     * contains DD1b at step 2 / day 3, so a test that posts step 0 would read that stale row
     * instead of its own result. Posting above it keeps the assertions on our own step.
     */
    private static final int STEP = 6;
    private static final int DAY = 6;

    @Test
    @DisplayName("a fresh order (day only, no assignedTo) is worked through all five stages")
    void freshOrderIsAssignedAndWorked() throws Exception {
        JsonNode content = post(initialContent(), STEP, DAY).get("content");
        JsonNode one = content.get("orders").get("1");

        // one member per stage picked it up: five log entries, one per assigned stage
        JsonNode log = one.path("log");
        for (int stage = 1; stage <= 5; stage++) {
            assertThat(log.path(String.valueOf(stage)).path("assignedTo").asText())
                    .as("stage " + stage + " must be assigned to its member")
                    .isEqualTo(String.valueOf(stage));
            assertThat(log.path(String.valueOf(stage)).path("assignedDay").asInt()).isEqualTo(DAY);
            assertThat(log.path(String.valueOf(stage)).path("completedDay").asInt()).isEqualTo(DAY);
        }

        assertThat(one.path("state").asInt()).as("order reached the last stage").isEqualTo(5);
        assertThat(one.path("workNeeded").asDouble()).isZero();
        assertThat(one.path("assignedTo").asText()).as("cleared when the stage completed").isEmpty();

        // every member's day was consumed
        for (int i = 0; i < TASKS.length; i++) {
            assertThat(content.get("members").get(String.valueOf(i + 1)).path("workAvailable").asDouble())
                    .as("member " + (i + 1) + " spent its work").isZero();
        }
    }

    @Test
    @DisplayName("ordersCompleted, the week counters and the team snapshot all move")
    void progressPropagates() throws Exception {
        JsonNode content = post(initialContent(), STEP, DAY).get("content");

        int completed = content.get("orderStatus").path("ordersCompleted").asInt();
        assertThat(completed).as("ordersCompleted must move off zero").isGreaterThanOrEqualTo(1);

        // team progress: weekSummary replays completed deals into the week counters
        boolean weekMoved = false;
        for (JsonNode w : content.get("week")) {
            if (w.path("order").asInt() >= 1 && w.path("value").asDouble() > 0) weekMoved = true;
        }
        assertThat(weekMoved).as("a week counter recorded the completed order").isTrue();

        // and the snapshot the report charts carries the same count
        JsonNode summary = content.get("summary");
        assertThat(summary.size()).isGreaterThan(0);
        assertThat(summary.get(summary.size() - 1).path("orderDone").asInt()).isEqualTo(completed);
    }
    /**
     * `task_work` is a 0-based array too, and it is the counter that decides whether a completed
     * stage may advance: PHP requires {@code worked >= passingRate} before the stage passes, and
     * discards the order ({@code state = -1}) until that many have been produced.
     *
     * <p>Because the counter lives in a 0-based array it was decoded as a {@code List} and reset to
     * empty every step, so {@code worked} never accumulated: with the schema default
     * {@code passingRate = 1} nothing showed, but as soon as a task is configured with 2 or more,
     * every order died on its first completion and progress stopped dead.
     */
    @Test
    @DisplayName("task_work accumulates across steps, so passingRate > 1 can eventually be met")
    void taskWorkAccumulatesAcrossSteps() throws Exception {
        ObjectNode content = initialContent();
        // passingRate is read from content.task_eff (built by init from taskefficiency), not the DB
        for (JsonNode te : content.get("task_eff")) {
            ((ObjectNode) te).put("passingRate", 3.00);
        }
        // three orders: the first two are discarded while the stage fills up
        ObjectNode orders = (ObjectNode) content.get("orders");
        orders.putObject("2").put("day", 1);
        orders.putObject("3").put("day", 1);
        ((ObjectNode) content.get("control")).put("totalOrders", 4);   // PHP iterates d < totalOrders

        JsonNode body = post(content, STEP, DAY);
        content = (ObjectNode) body.get("content");
        int step = body.get("stepnumber").asInt();
        int day = body.get("daynumber").asInt();
        assertThat(content.path("task_work").path(0).path("worked").asInt())
                .as("first completion of stage 1").isEqualTo(1);

        body = post(content, step, day);
        content = (ObjectNode) body.get("content");
        step = body.get("stepnumber").asInt();
        day = body.get("daynumber").asInt();
        assertThat(content.path("task_work").path(0).path("worked").asInt())
                .as("the counter must carry over from the previous step, not restart at 1")
                .isEqualTo(2);

        body = post(content, step, day);
        content = (ObjectNode) body.get("content");

        assertThat(content.path("task_work").path(0).path("worked").asInt())
                .as("at passingRate the counter resets").isZero();

        // The third order passed stage 1 and moved on to stage 2 (it is then discarded there, since
        // every stage in this fixture also demands three completions — that is the PHP rule, not a
        // defect). Evidence of the pass: stage 1 completed AND stage 2 was assigned.
        boolean passedStage1 = false;
        for (JsonNode o : content.get("orders")) {
            if (o.path("log").path("1").path("completedDay").asInt() >= 1 && o.path("log").has("2")) {
                passedStage1 = true;
            }
        }
        assertThat(passedStage1).as("an order must pass stage 1 once passingRate is met").isTrue();
    }
}
