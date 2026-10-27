package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parity tests for drill/ mod=ACTION, case SELECT_STYLE (the only ported branch).
 *
 * Both members pick the same style 'Formal'; the seeded profile row gives +5 skills, +3
 * morale and +6 efficiency each, so `styleMatch` is 14 and the boss approves unanimously
 * (row 1 in the bossreview fixture -> 'Nice pick_e').
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DrillActionSelectStyleTest {

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

    @BeforeEach
    void scenario() {
        jdbc.update("delete from profileimpact where impactClass = 'Formal'");
        jdbc.update("insert into profileimpact (impactClass, desiredStyle, skillsClass, moraleClass, "
                + "efficiencyClass, skillsImpact, moraleImpact, efficiencyImpact, impactMsg) "
                + "values ('Formal', 'Formal', 'P1', 'P2', 'P3', 10, 5, 2, 'styled')");
        jdbc.update("insert into profilesens (skillsClass, moraleClass, efficiencyClass, "
                + "skillsSensitivity, moraleSensitivity, efficiencySensitivity) "
                + "values ('P1', 'P2', 'P3', 0.50, 0.50, 1.00)");
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
    @DisplayName("SELECT_STYLE scores every member, advances the day and moves to MORE_ACTIONS")
    void selectStyle() throws Exception {
        MvcResult r = step("SELECT_STYLE");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        JsonNode body = json(r);
        assertThat(body.get("stepnumber").asInt()).isEqualTo(3);
        assertThat(body.get("daynumber").asInt()).isEqualTo(6);        // the day advanced
        assertThat(body.get("action").asText()).isEqualTo("MORE_ACTIONS"); // day 6 is not a style day

        JsonNode c = body.get("content");
        JsonNode m1 = c.get("members").get("1");
        assertThat(m1.get("skillsValue").asInt()).isEqualTo(65);
        assertThat(m1.get("moraleValue").asInt()).isEqualTo(53);
        assertThat(m1.get("efficiencyValue").asInt()).isEqualTo(76);
        assertThat(m1.get("styleMatch").asInt()).isEqualTo(14);        // == added
        assertThat(m1.get("actioned").asInt()).isZero();               // cleared in the tail
        // both names were cleared for the next step
        assertThat(c.get("control").get("action").asText()).isEmpty();
        assertThat(c.get("control").get("task").asText()).isEmpty();
        assertThat(c.get("control").get("welcome").asInt()).isZero();
        assertThat(c.get("control").get("status").asInt()).isEqualTo(500);
        assertThat(c.get("day").get("5").get("evaluateStyle").asInt()).isZero();

        assertThat(c.get("history").size()).isEqualTo(2);
        assertThat(c.get("history").get(0).get("actType").asText()).isEqualTo("SELECT_STYLE");
        assertThat(c.get("history").get(0).get("act").asText()).isEqualTo("FO");   // styleExp

        // analysis.style counted both members against the desired style
        JsonNode style = c.get("analysis").get("style");
        assertThat(style.get("total").asInt()).isEqualTo(2);
        assertThat(style.get("Formal").get("count").asInt()).isEqualTo(2);
        assertThat(style.get("Formal").get("positive").asInt()).isEqualTo(2);
        assertThat(style.get("desire_intent").get("positive").asInt()).isEqualTo(2);

        // boss reviewed the style: unanimous approval + efficiency up -> '_e'
        assertThat(c.get("summary").size()).isEqualTo(1);
        assertThat(c.get("bossRev").get("count").asInt()).isEqualTo(1);
        // no action row is named 'Formal', so managementAction coerces to 0 and the style
        // row ('Style locked in') is the one that matches
        assertThat(c.get("bossRev").get("description").get(0).asText()).isEqualTo("Style locked in_e");

        assertThat(jdbc.queryForObject(
                "select count(*) from drilldetail where drillId = 'D1'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "select action from drilldetail where drillId = 'D1' and stepNumber = 3", String.class))
                .isEqualTo("MORE_ACTIONS");
        assertThat(jdbc.queryForObject(
                "select drillStatus from drill where drillId = 'D1'", Integer.class)).isEqualTo(500);
    }

    @Test
    @DisplayName("an unported action branch refuses loudly instead of pretending to work")
    void unportedBranch() throws Exception {
        MvcResult r = step("MORE_ACTIONS");
        assertThat(r.getResponse().getStatus()).isEqualTo(501);
        assertThat(r.getResponse().getContentAsString()).contains("MORE_ACTIONS");
    }
}
