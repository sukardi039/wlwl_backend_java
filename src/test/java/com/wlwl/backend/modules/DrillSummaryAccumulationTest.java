package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * The drill report is built entirely on the client from {@code content.summary} (the weekly chart
 * walks {@code summary.length}; {@code summary[0]} is the baseline the evaluation compares against)
 * and {@code content.history}.
 *
 * <p>Those are 0-based arrays, so PHP encodes them as JSON arrays. The port decoded JSON arrays into
 * {@code java.util.List} while its mutating helpers only reuse {@code PhpArray}, so
 * {@code cont.child("summary")} silently started from empty every step: a finished drill kept one
 * entry, {@code summary[0]} became the LAST step, every improvement delta computed as zero, and the
 * chart had a single point ("End" only). Same for history, bossReview, debug and log.action.
 *
 * <p>This drives the real client loop: the returned content is posted back, exactly as
 * {@code Drill.vue} does, so every step's content round-trips through JSON.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DrillSummaryAccumulationTest {

    private static final String KEY = "K1";

    /**
     * The reply is the latest drilldetail row for the drill, and the shared fixture already seeds
     * DD1b at step 2 / day 3 — posting below that would assert against the seeded row instead of
     * our own step.
     */
    private static final int STEP = 6;
    private static final int DAY = 6;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    /** Client-shaped JSON: summary/history are real arrays, one history row already present. */
    private ObjectNode initialContent() {
        ObjectNode c = mapper.createObjectNode();

        ObjectNode control = c.putObject("control");
        control.put("maxMembers", 1);
        control.put("status", 500);
        control.put("action", "Advise");
        control.put("actionExp", "AD");
        control.put("task", "Inspect");
        control.put("taskExp", "TI");
        control.put("welcome", 1);
        control.put("numberOfDays", 10);
        control.put("totalOrders", 0);
        c.put("ordersToComplete", 8);
        c.put("orderValue", 100);
        c.putObject("orders");

        ObjectNode days = c.putObject("day");
        ObjectNode weeks = c.putObject("week");
        for (int d = 1; d <= 12; d++) {
            int week = (d - 1) / 5 + 1;
            ObjectNode day = days.putObject(String.valueOf(d));
            day.put("evaluateStyle", 0);
            day.put("week", week);
            day.put("weekday", (d - 1) % 5 + 1);
            day.putObject("event").put("count", 0);
            day.put("newOrders", 0);
            weeks.putObject(String.valueOf(week)).put("weekNumber", week);
        }

        c.putObject("used").putObject("actions");
        c.putArray("task_eff");

        ObjectNode m = c.putObject("members").putObject("1");
        m.put("staffName", "1");
        m.put("staffExp", "Ann");
        m.put("task", "Inspect");
        m.put("taskExp", "TI");
        m.put("style", "Formal");
        m.put("styleExp", "FO");
        m.put("skillsValue", 60);
        m.put("moraleValue", 50);
        m.put("efficiencyValue", 70);
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

        // one history row from an earlier step, to prove it survives the next steps
        ObjectNode h = c.putArray("history").addObject();
        h.put("step", 1);
        h.put("day", 1);
        h.put("staff", "Ann");
        h.put("staffExp", "Ann");
        h.put("actType", "INIT");
        h.put("act", "");
        h.put("skills", 60);
        h.put("morale", 50);
        h.put("eff", 70);
        h.put("msg", "");

        c.putArray("summary");
        c.putObject("current").putObject("teamAvg").put("total", 0);
        c.putObject("orderStatus").put("ordersCompleted", 0);
        c.putObject("bossMsg").put("count", 0);
        c.putObject("bossRev").put("count", 0);
        c.putObject("staffMsg").put("count", 0);
        return c;
    }

    private JsonNode post(JsonNode content, int stepNumber, int dayNumber, String action)
            throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("restkey", KEY);
        body.put("method", "PUT");
        body.put("mod", "ACTION");
        ObjectNode qdata = body.putObject("qdata");
        qdata.put("drillId", "D1");
        qdata.put("drillDetailId", "DD1b");
        qdata.put("stepNumber", stepNumber);
        qdata.put("dayNumber", dayNumber);
        qdata.put("action", action);
        qdata.put("content", mapper.writeValueAsString(content));

        MvcResult r = mockMvc.perform(put("/backend/drill/")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(body))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return mapper.readTree(new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("summary accumulates across steps instead of keeping only the last one")
    void summaryAccumulatesAcrossSteps() throws Exception {
        JsonNode content = initialContent();
        int step = STEP;
        int day = DAY;

        // the real sequence: INTRODUCTION at step 0, then MORE_ACTIONS ever after
        JsonNode body = post(content, step, day, "INTRODUCTION");
        content = body.get("content");
        step = body.get("stepnumber").asInt();
        day = body.get("daynumber").asInt();

        for (int i = 0; i < 2; i++) {
            body = post(content, step, day, "MORE_ACTIONS");
            content = body.get("content");
            step = body.get("stepnumber").asInt();
            day = body.get("daynumber").asInt();
        }

        JsonNode summary = content.get("summary");
        assertThat(summary.size())
                .as("three steps must leave three summary entries, not one")
                .isEqualTo(3);
        assertThat(summary.get(0).path("phase").asText()).isEqualTo("INTRODUCTION");
        assertThat(summary.get(1).path("phase").asText()).isEqualTo("MORE_ACTIONS");
        assertThat(summary.get(2).path("phase").asText()).isEqualTo("MORE_ACTIONS");

        // the report's baseline is summary[0]; it must stay the FIRST snapshot, not the last
        assertThat(summary.get(0).path("step").asInt())
                .isLessThan(summary.get(2).path("step").asInt());
    }

    @Test
    @DisplayName("history, bossReview and debug keep their earlier entries too")
    void otherListsAlsoSurvive() throws Exception {
        JsonNode content = initialContent();
        int step = STEP;
        int day = DAY;

        for (int i = 0; i < 3; i++) {
            JsonNode body = post(content, step, day, "MORE_ACTIONS");
            content = body.get("content");
            step = body.get("stepnumber").asInt();
            day = body.get("daynumber").asInt();
        }

        JsonNode history = content.get("history");
        assertThat(history.size()).as("the INIT row from step 1 survives").isGreaterThanOrEqualTo(1);
        assertThat(history.get(0).path("actType").asText()).isEqualTo("INIT");

        // dbg() writes here every step; with the decoder bug it held only the current step's lines
        assertThat(content.path("debug").size()).isGreaterThan(0);
        assertThat(content.path("bossReview").size()).isGreaterThanOrEqualTo(1);
    }
}
