package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

/**
 * Parity tests for drill/ mod=ACTION, action INTRODUCTION — the step-0 hand-off.
 *
 * <p>The client sends it from {@code Drill.vue:submitIntroduction()} as
 * {@code PUT /backend/drill/} with verb ACTION and {@code qdata.action = "INTRODUCTION"}
 * (also the path for styles, via {@code submitStyle -> submitIntroduction}).
 *
 * <p><b>action.php has no {@code case "INTRODUCTION"}</b>. The switch matches nothing,
 * {@code $daysNeeded} stays 0, and execution falls straight into the shared tail. That is
 * not a bug to tidy: the tail is what produces the data the report needs, in this order:
 *
 * <ol>
 *   <li>{@code writeSummary()} (action.php:741) stamps {@code summary[n].phase =
 *       "INTRODUCTION"} while {@code $qdata->action} still holds the client's value;</li>
 *   <li>only afterwards (action.php:767) is {@code $qdata->action} reassigned to
 *       SELECT_STYLE or MORE_ACTIONS, which is the value stored in drilldetail.action;</li>
 *   <li>stepNumber and dayNumber are incremented, status ladder recomputed, row inserted.</li>
 * </ol>
 *
 * <p>So the same request must produce a summary entry phased INTRODUCTION while the
 * persisted row is labelled with the NEXT action. {@code ReportResult.vue} finds the chart's
 * first point with {@code findSummary(d, "INTRODUCTION")}, so if the phase were lost the
 * report silently starts at the second week.
 *
 * <p>The fixture reuses the day-5/step-2 content shape from {@link DrillActionSelectStyleTest}
 * (a known-good {@code week}/{@code day}/{@code current} structure); day 6 is not a style
 * day, so the next action resolves to MORE_ACTIONS.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DrillActionIntroductionTest {

    private static final String KEY = "K1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private static String blob() {
        return "{\"control\":{\"maxMembers\":\"2\",\"status\":500,\"action\":\"\",\"actionExp\":\"\","
                + "\"welcome\":1,\"numberOfDays\":10},"
                + "\"ordersToComplete\":\"8\","
                + "\"day\":{\"5\":{\"evaluateStyle\":1,\"week\":1,\"weekday\":5,"
                + "\"event\":{\"count\":0},\"newOrders\":\"3\"},"
                + "\"6\":{\"evaluateStyle\":0,\"week\":2,\"weekday\":1,"
                + "\"event\":{\"count\":0},\"newOrders\":\"3\"}},"
                + "\"week\":{\"1\":{\"weekNumber\":1},\"2\":{\"weekNumber\":2}},"
                + "\"members\":{\"1\":{\"staffName\":\"1\",\"staffExp\":\"Ann\",\"task\":\"Inspect\","
                + "\"style\":\"Formal\",\"styleExp\":\"FO\",\"skillsValue\":\"60\",\"moraleValue\":\"50\","
                + "\"efficiencyValue\":\"70\",\"skillsAdded\":0,\"moraleAdded\":0,\"efficiencyAdded\":0,"
                + "\"added\":0,\"addedAvg\":0,\"msgCount\":0,\"readMsg\":1,\"message\":[],\"away\":0,"
                + "\"actioned\":0,\"actionExp\":\"\"},"
                + "\"2\":{\"staffName\":\"2\",\"staffExp\":\"Bob\",\"task\":\"Inspect\","
                + "\"style\":\"Formal\",\"styleExp\":\"FO\",\"skillsValue\":\"40\",\"moraleValue\":\"30\","
                + "\"efficiencyValue\":\"50\",\"skillsAdded\":0,\"moraleAdded\":0,\"efficiencyAdded\":0,"
                + "\"added\":0,\"addedAvg\":0,\"msgCount\":0,\"readMsg\":1,\"message\":[],\"away\":0,"
                + "\"actioned\":0,\"actionExp\":\"\"}},"
                + "\"history\":[],\"summary\":[],\"current\":{\"teamAvg\":{\"total\":0}},"
                + "\"orderStatus\":{\"ordersCompleted\":3},"
                + "\"bossMsg\":{\"count\":0},\"bossRev\":{\"count\":0},\"staffMsg\":{\"count\":0}}";
    }

    private MvcResult step(String action) throws Exception {
        String escaped = blob().replace("\"", "\\\"");
        String body = "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"mod\":\"ACTION\","
                + "\"qdata\":{\"drillId\":\"D1\",\"drillDetailId\":\"DD1b\",\"stepNumber\":2,"
                + "\"dayNumber\":5,\"action\":\"" + action + "\",\"content\":\"" + escaped + "\"}}";
        return mockMvc.perform(put("/backend/drill/")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return mapper.readTree(new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("INTRODUCTION is accepted (200) instead of the 501 the port used to answer")
    void introductionIsHandled() throws Exception {
        MvcResult r = step("INTRODUCTION");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("the summary entry is phased INTRODUCTION — that is the report's first chart point")
    void summaryIsPhasedIntroduction() throws Exception {
        JsonNode c = json(step("INTRODUCTION")).get("content");

        JsonNode summary = c.get("summary");
        assertThat(summary.size()).isEqualTo(1);
        assertThat(summary.get(0).get("phase").asText()).isEqualTo("INTRODUCTION");
        assertThat(summary.get(0).get("step").asInt()).isEqualTo(2);
        assertThat(summary.get(0).get("day").asInt()).isEqualTo(5);

        // both named members are in the snapshot, and (like the PHP) a non-action phase
        // records styleExp rather than actionExp
        JsonNode ann = summary.get(0).get("member").get("1");
        assertThat(ann.get("staff").asText()).isEqualTo("Ann");
        assertThat(ann.get("action").asText()).isEqualTo("FO");
        assertThat(summary.get(0).get("member").get("2").get("staff").asText()).isEqualTo("Bob");
    }

    @Test
    @DisplayName("no per-action work happens: stats unchanged, no history rows")
    void nothingIsAppliedToTheTeam() throws Exception {
        JsonNode c = json(step("INTRODUCTION")).get("content");

        JsonNode m1 = c.get("members").get("1");
        assertThat(m1.get("skillsValue").asInt()).isEqualTo(60);   // untouched
        assertThat(m1.get("moraleValue").asInt()).isEqualTo(50);
        assertThat(m1.get("efficiencyValue").asInt()).isEqualTo(70);
        assertThat(m1.get("actioned").asInt()).isZero();           // cleared by the tail

        // SELECT_STYLE/MORE_ACTIONS append history; INTRODUCTION must not
        assertThat(c.get("history").size()).isZero();
    }

    @Test
    @DisplayName("the tail still advances the step and stores the NEXT action on the row")
    void tailRunsAndStoresNextAction() throws Exception {
        JsonNode body = json(step("INTRODUCTION"));

        assertThat(body.get("stepnumber").asInt()).isEqualTo(3);        // 2 -> 3
        assertThat(body.get("daynumber").asInt()).isEqualTo(6);         // 5 -> 6
        // action.php:767 reassigns $qdata->action AFTER writeSummary, so the persisted
        // row is labelled with the next action, not INTRODUCTION
        assertThat(body.get("action").asText()).isEqualTo("MORE_ACTIONS");
        assertThat(body.get("content").get("control").get("welcome").asInt()).isZero();
        assertThat(body.get("content").get("control").get("status").asInt()).isEqualTo(500);

        assertThat(jdbc.queryForObject(
                "select count(*) from drilldetail where drillId = 'D1'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "select action from drilldetail where drillId = 'D1' and stepNumber = 3", String.class))
                .isEqualTo("MORE_ACTIONS");
        assertThat(jdbc.queryForObject(
                "select dayNumber from drilldetail where drillId = 'D1' and stepNumber = 3", Integer.class))
                .isEqualTo(6);
    }

    @Test
    @DisplayName("an unknown action is still refused with 501 and its name")
    void unknownActionStillRefused() throws Exception {
        MvcResult r = step("NOT_A_REAL_ACTION");
        assertThat(r.getResponse().getStatus()).isEqualTo(501);
        assertThat(r.getResponse().getContentAsString()).contains("NOT_A_REAL_ACTION");
    }
}
