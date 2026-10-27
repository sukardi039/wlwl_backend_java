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
 * Parity tests for drill/ mod=ENVIRONMENT (drill/environment.php).
 *
 * The step-4 content blob below is shaped like a real one: two members, an 'Offshore'
 * event scheduled on day 5, empty history/summary. With the profile row seeded here the
 * maths is hand-checkable (see DrillImpactMathTest for the unit-level version):
 *   member 1 60/50/70 -> 65/53/76,  member 2 40/30/50 -> 45/33/56.
 * With an event targeted at member 1, member 2 takes the AFFECT columns instead:
 *   40/30/50 -> 38/29/45.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DrillEnvironmentParityTest {

    private static final String KEY = "K1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    /** stepNumber 2 is the row the client is sitting on; the reply is step 3. */
    private static String blob(String targetedStaff, String member1Name, String member2Name,
                               String day1Event, String day2Event) {
        return "{\"control\":{\"maxMembers\":\"2\",\"status\":500,\"actionExp\":\"\",\"action\":\"\"},"
                + "\"day\":{\"5\":{\"event\":" + day1Event + ",\"evaluateStyle\":1,\"weekday\":5,"
                + "\"week\":1,\"newOrders\":\"3\"},"
                + "\"6\":{\"event\":" + day2Event + ",\"evaluateStyle\":0,\"weekday\":1,"
                + "\"week\":2,\"newOrders\":\"3\"}},"
                + "\"members\":{\"1\":{\"staffName\":\"" + member1Name + "\",\"staffExp\":\"Ann\","
                + "\"task\":\"Inspect\",\"skillsValue\":60,\"moraleValue\":50,\"efficiencyValue\":70,"
                + "\"skillsAdded\":0,\"moraleAdded\":0,\"efficiencyAdded\":0,\"added\":0,"
                + "\"addedAvg\":0,\"msgCount\":0,\"readMsg\":1,\"message\":[],\"trend\":0,"
                + "\"newComer\":\"0\",\"oldTimer\":\"0\",\"away\":0,\"listen\":5},"
                + "\"2\":{\"staffName\":\"" + member2Name + "\",\"staffExp\":\"Bob\","
                + "\"task\":\"Inspect\",\"skillsValue\":40,\"moraleValue\":30,\"efficiencyValue\":50,"
                + "\"skillsAdded\":0,\"moraleAdded\":0,\"efficiencyAdded\":0,\"added\":0,"
                + "\"addedAvg\":0,\"msgCount\":0,\"readMsg\":1,\"message\":[],\"trend\":0,"
                + "\"newComer\":\"0\",\"oldTimer\":\"0\",\"away\":0,\"listen\":5}},"
                + "\"history\":[],\"summary\":[],\"current\":{\"teamAvg\":{\"total\":0}},"
                + "\"orderStatus\":{\"ordersCompleted\":3},"
                + "\"bossMsg\":{\"count\":0},\"bossRev\":{\"count\":0},\"staffMsg\":{\"count\":0}}";
    }

    private static final String EVENT_OFFSHORE =
            "{\"count\":1,\"eventName\":[\"Offshore\"],\"eventExp\":[\"OF\"],"
                    + "\"staffName\":[\"\"],\"staffExp\":[\"\"],\"description\":[\"Rot\"],"
                    + "\"criteria\":[0]}";

    private static final String NO_EVENT = "{\"count\":0}";

    @BeforeEach
    void scenario() {
        jdbc.update("delete from profileimpact where impactClass = 'Offshore'");
        jdbc.update("insert into profileimpact (impactClass, desiredStyle, skillsClass, moraleClass, "
                + "efficiencyClass, skillsImpact, moraleImpact, efficiencyImpact, impactMsg, "
                + "skillsAffect, moraleAffect, efficiencyAffect, affectMsg) "
                + "values ('Offshore', '2-coach', 'P1', 'P2', 'P3', 10, 5, 2, 'bad', -4, -2, -3, 'ouch')");
        jdbc.update("insert into profilesens (skillsClass, moraleClass, efficiencyClass, "
                + "skillsSensitivity, moraleSensitivity, efficiencySensitivity) "
                + "values ('P1', 'P2', 'P3', 0.50, 0.50, 1.00)");
        jdbc.update("update environment set environmentDescription = 'Rot', orderImpact = 1, "
                + "duration = 2, effectiveDays = 0, resignAction = '', resignCheck = 0, "
                + "setAway = 0, newComerImpact = 0, oldTimerImpact = 0 where environmentName = 'Offshore'");
        // a clock the endpoint can report on
        jdbc.update("update drill set expireOn = timestampadd(MINUTE, 20, now()) where drillId = 'D1'");
        jdbc.update("insert into params_int (\"key\", \"value\") values ('resign_embargo_min_days', '7')");
        jdbc.update("insert into params_int (\"key\", \"value\") values ('resign_embargo_max_days', '7')");
    }

    private MvcResult step(String blobJson, int dayNumber, int stepNumber) throws Exception {
        String escaped = blobJson.replace("\\", "\\\\").replace("\"", "\\\"");
        String body = "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"mod\":\"ENVIRONMENT\","
                + "\"qdata\":{\"drillId\":\"D1\",\"drillDetailId\":\"DD1b\",\"stepNumber\":" + stepNumber
                + ",\"dayNumber\":" + dayNumber + ",\"action\":\"MORE_ACTIONS\","
                + "\"content\":\"" + escaped + "\"}}";
        return mockMvc.perform(put("/backend/drill/")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return mapper.readTree(new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an untargeted event hits every member, appends history, summarises and answers 200")
    void passiveEvent() throws Exception {
        MvcResult r = step(blob("", "1", "2", EVENT_OFFSHORE, NO_EVENT), 5, 2);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        JsonNode body = json(r);
        assertThat(body.get("stepnumber").asInt()).isEqualTo(3);      // client's step + 1
        assertThat(body.get("daynumber").asInt()).isEqualTo(5);
        assertThat(body.get("action").asText()).isEqualTo("MORE_ACTIONS");
        assertThat(body.get("action").asText()).isNotEqualTo("FAILED");
        assertThat(body.get("timeNow").asText()).isNotEmpty();

        JsonNode c = body.get("content");
        assertThat(c.get("control").get("actionExp").asText()).isEqualTo("OF");
        assertThat(c.get("control").get("status").asInt()).isEqualTo(500);
        assertThat(c.get("control").get("remainingMinutes").asText()).matches("\\d+:\\d{2}");

        // member 1: 60/50/70 -> 65/53/76 (impact 10x0.5, morale 5x0.5 -> round(2.5)=3)
        JsonNode m1 = c.get("members").get("1");
        assertThat(m1.get("skillsValue").asInt()).isEqualTo(65);
        assertThat(m1.get("moraleValue").asInt()).isEqualTo(53);
        assertThat(m1.get("efficiencyValue").asInt()).isEqualTo(76);
        assertThat(m1.get("touched").asInt()).isEqualTo(1);
        // member 2: 40/30/50 -> 45/33/56
        JsonNode m2 = c.get("members").get("2");
        assertThat(m2.get("skillsValue").asInt()).isEqualTo(45);
        assertThat(m2.get("moraleValue").asInt()).isEqualTo(33);
        assertThat(m2.get("efficiencyValue").asInt()).isEqualTo(56);

        // one history line per member, carrying the environment's display key
        assertThat(c.get("history").size()).isEqualTo(2);
        assertThat(c.get("history").get(0).get("actType").asText()).isEqualTo("MORE_ACTIONS");
        assertThat(c.get("history").get(0).get("act").asText()).isEqualTo("OF");
        assertThat(c.get("history").get(0).get("step").asInt()).isEqualTo(2);

        // the summary snapshot was appended and the event stack was consumed
        assertThat(c.get("summary").size()).isEqualTo(1);
        assertThat(c.get("summary").get(0).get("phase").asText()).isEqualTo("ENVIRONMENT");
        assertThat(c.get("summary").get(0).get("team").get("skills").asInt()).isEqualTo(110);
        // The PHP shifts entries down but never clears index 0 when there was only one:
        // it writes a blank at index $count and decrements the counter, so the strings stay.
        assertThat(c.get("day").get("5").get("event").get("count").asInt()).isZero();
        assertThat(c.get("day").get("5").get("event").get("eventName").get(0).asText())
                .isEqualTo("Offshore");
        assertThat(c.get("day").get("5").get("event").get("eventName").get(1).asText()).isEmpty();

        // DB side: the client's row was rewritten, a new step was appended, status is 500
        assertThat(jdbc.queryForObject(
                "select count(*) from drilldetail where drillId = 'D1'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "select action from drilldetail where drillId = 'D1' and stepNumber = 3", String.class))
                .isEqualTo("MORE_ACTIONS");
        assertThat(jdbc.queryForObject(
                "select drillStatus from drill where drillId = 'D1'", Integer.class)).isEqualTo(500);
        assertThat(jdbc.queryForObject(
                "select endOn from drill where drillId = 'D1'", Object.class)).isNull();
    }

    @Test
    @DisplayName("a targeted event affects that member and AFFECTs the rest")
    void targetedEvent() throws Exception {
        String day5 = EVENT_OFFSHORE.replace("[\"\"]", "[\"1\"]");   // staffName: member 1
        MvcResult r = step(blob("1", "1", "2", day5, NO_EVENT), 5, 2);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        JsonNode c = json(r).get("content");
        JsonNode m1 = c.get("members").get("1");
        JsonNode m2 = c.get("members").get("2");

        // the target takes the impact columns, exactly as in the untargeted case
        assertThat(m1.get("skillsValue").asInt()).isEqualTo(65);
        assertThat(m1.get("efficiencyValue").asInt()).isEqualTo(76);
        // ...and gets the combo's effective-days marker: 5 + 0 + 1, +1 for crossing a week
        assertThat(m1.get("envEffect").get("Offshore").asInt()).isEqualTo(7);

        // the bystander takes skillsAffect -4 / moraleAffect -2 instead. Morale 29 lands in
        // the 0..30 skillseffect band (0.50), so efficiency gains 1: round(-2*0.5 + 2).
        assertThat(m2.get("skillsValue").asInt()).isEqualTo(38);
        assertThat(m2.get("moraleValue").asInt()).isEqualTo(29);
        assertThat(m2.get("efficiencyValue").asInt()).isEqualTo(51);

        // both still get a history line
        assertThat(c.get("history").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("an empty team ends the drill: 750 / FAILED, remainingMinutes 0.00, endOn stamped")
    void failsWhenNobodyLeft() throws Exception {
        MvcResult r = step(blob("", "", "", NO_EVENT, NO_EVENT), 5, 2);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        JsonNode body = json(r);
        assertThat(body.get("action").asText()).isEqualTo("FAILED");
        JsonNode control = body.get("content").get("control");
        assertThat(control.get("status").asInt()).isEqualTo(750);
        assertThat(control.get("remainingMinutes").asText()).isEqualTo("0.00");

        assertThat(jdbc.queryForObject(
                "select drillStatus from drill where drillId = 'D1'", Integer.class)).isEqualTo(750);
        assertThat(jdbc.queryForObject(
                "select endOn from drill where drillId = 'D1'", Object.class)).isNotNull();
        assertThat(jdbc.queryForObject(
                "select completedDrill from drillcontrol where drillControlId = 'DC1'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("resignCheck removes the member and books the embargo; a resign action is scheduled")
    void resignation() throws Exception {
        jdbc.update("update environment set resignCheck = 1, resignAction = 'Offshore', "
                + "effectiveDays = 1 where environmentName = 'Offshore'");
        String day5 = EVENT_OFFSHORE.replace("[\"\"]", "[\"1\"]");

        MvcResult r = step(blob("1", "1", "2", day5, NO_EVENT), 5, 2);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        JsonNode c = json(r).get("content");
        JsonNode m1 = c.get("members").get("1");
        assertThat(m1.get("staffName").asText()).isEmpty();
        assertThat(m1.get("staffExp").asText()).isEmpty();
        assertThat(m1.get("away").asInt()).isEqualTo(7);          // rand(7,7)
        assertThat(m1.get("msgCount").asInt()).isZero();
        assertThat(m1.get("readMsg").asInt()).isEqualTo(1);
        assertThat(m1.get("listen").asText()).isEqualTo("5");     // back to the tolerance
        // member 2 is untouched
        assertThat(c.get("members").get("2").get("staffName").asText()).isEqualTo("2");
        // day 5 has no evaluateStyle-0 at day 8 -> grace 4 -> the resignation lands on day 9
        JsonNode ev9 = c.get("day").get("9").get("event");
        assertThat(ev9.get("count").asInt()).isEqualTo(1);
        assertThat(ev9.get("staffName").get(0).asText()).isEqualTo("1");
    }

    @Test
    @DisplayName("ACTION is still loud; an unknown drill still gets a row written (no FK guard)")
    void dispatch() throws Exception {
        mockMvc.perform(put("/backend/drill/").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"mod\":\"ACTION\"}"))
                .andExpect(status().isNotImplemented());

        // Unknown drill: drilldetail has no FK to drill, so the PHP's insert succeeds and
        // the re-read by drillId finds it — the endpoint answers 200, not 204.
        String day5 = EVENT_OFFSHORE.replace("[\"\"]", "[\"1\"]");
        String escaped = blob("1", "1", "2", day5, NO_EVENT).replace("\"", "\\\"");
        mockMvc.perform(put("/backend/drill/").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"mod\":\"ENVIRONMENT\","
                                + "\"qdata\":{\"drillId\":\"NOPE\",\"drillDetailId\":\"X\",\"stepNumber\":2,"
                                + "\"dayNumber\":5,\"action\":\"MORE_ACTIONS\",\"content\":\""
                                + escaped + "\"}}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "select count(*) from drilldetail where drillId = 'NOPE'", Integer.class)).isEqualTo(1);
    }
}
