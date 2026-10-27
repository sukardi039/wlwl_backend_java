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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parity tests for drill/ mod=INIT (drill/init.php + commonLib::init_staff_ana).
 *
 * The fixture is deterministic on purpose:
 *   - product range min = max = 400  -> orderValue is always 400000 (no rand)
 *   - environment happenOn = 7       -> no rand(2, numberOfDays-5)
 *   - staff slot 1 has all three *Init values (33/40/48) -> no rand
 *   - staff slot 2 points at a staffId the view cannot resolve, so every rand() degrades
 *     to rand(0,0) exactly as PHP does with NULL bounds -> 2 / 2 / 10 after max()
 *
 * Content shape is taken from the two real blobs (2023-12 and 2026-08), which is also why
 * the assertions distinguish INT from STRING: PHP only turns a value into a JSON number
 * when it computed it (totalOrders, orders[].day, manpower.pos); everything copied out of
 * a row keeps whatever type the row gave it (skillsValue "30" comes from the model blob).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DrillInitParityTest {

    private static final String KEY = "K1";

    /** Shaped like a real model/modelContent: 2 staff, 1 environment, 1 recruit. */
    private static final String MODEL_CONTENT = """
            {"productExp":"引领一号","ordersToComplete":"8",
             "staffs":[
               {"staffName":"2","task":"1-scout","staffExp":"杜仲","taskExp":"客户拓展",
                "skillsInit":"30","moraleInit":"40","efficiencyInit":"48"},
               {"staffName":"3","task":"2-evaluate","staffExp":"半","taskExp":"需求分析",
                "skillsInit":"","moraleInit":"","efficiencyInit":""}],
             "environments":[{"environmentName":"Offshore","environmentExp":"_u.e_badprod","happenOn":"7"}],
             "recruits":[{"staffName":"28","staffExp":"云华"}]}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void scenario() {
        // The base fixture is deliberately bland; INIT needs a real template + product.
        jdbc.update("update product set ordersPerDay = 3, minOrderValue = 400, maxOrderValue = 400 "
                + "where productShortName = 'W1'");
        jdbc.update("update template set numberOfStaff = 2, numberOfDays = 10, duration = 20, "
                + "environmentEvents = 1, recruits = 1, report = 1 where templateName = 'BasicTpl'");
        jdbc.update("update environment set environmentDescription = 'Offshore rot', "
                + "orderImpact = 1, duration = 2 where environmentName = 'Offshore'");
        jdbc.update("update model set modelContent = ? where modelId = 'M1'", MODEL_CONTENT);
        // D3 is the only drill with NO drilldetail rows -> the branch that builds content.
        // It must also be UNSTARTED: init.php's clock update is guarded by `startOn is null`.
        jdbc.update("update drill set modelProduct = 'W1', startOn = null, expireOn = null "
                + "where drillId = 'D3'");
    }

    private MvcResult init(String qdata) throws Exception {
        String body = "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"mod\":\"INIT\""
                + (qdata == null ? "" : ",\"qdata\":" + qdata) + "}";
        return mockMvc.perform(put("/backend/drill/")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    /** Decode as UTF-8: the response has no charset parameter, like the PHP's. */
    private JsonNode json(MvcResult r) throws Exception {
        return mapper.readTree(new String(r.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8));
    }

    private JsonNode initContent() throws Exception {
        MvcResult r = init("{\"drillId\":\"D3\",\"seq\":2,\"modelProduct\":\"W1\"}");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return json(r).get("content");
    }

    @Test
    @DisplayName("INIT builds the drill document, stores step 0 and starts the clock")
    void buildsAndStarts() throws Exception {
        MvcResult r = init("{\"drillId\":\"D3\",\"seq\":2,\"modelProduct\":\"W1\"}");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        JsonNode body = json(r);
        // The drilldetail row itself, plus the synthetic keys the PHP appends.
        assertThat(body.get("drillid").asText()).isEqualTo("D3");
        assertThat(body.get("stepnumber").asInt()).isZero();
        assertThat(body.get("daynumber").asInt()).isZero();
        assertThat(body.get("action").asText()).isEqualTo("INTRODUCTION");
        assertThat(body.get("drilldetailid").asText()).hasSize(13);   // uniqid() width
        assertThat(body.get("eTime").asText()).isEqualTo("20:00");
        assertThat(body.get("timeNow").asText()).isNotEmpty();
        assertThat(body.get("content").isObject()).isTrue();

        // Side effects: the clock and the control pointer.
        assertThat(jdbc.queryForObject("select drillStatus from drill where drillId = 'D3'", Integer.class))
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("select startOn from drill where drillId = 'D3'", Object.class))
                .isNotNull();
        assertThat(jdbc.queryForObject("select expireOn from drill where drillId = 'D3'", Object.class))
                .isNotNull();
        assertThat(jdbc.queryForObject(
                "select currentDrill from drillcontrol where drillControlId = 'DC3'", String.class))
                .isEqualTo("2");
        assertThat(jdbc.queryForObject(
                "select drillStartedOn from drillcontrol where drillControlId = 'DC3'", Object.class))
                .isNotNull();
        assertThat(jdbc.queryForObject(
                "select count(*) from drilldetail where drillId = 'D3'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("control / week / orders: orderValue, week targets and the 5-day calendar")
    void controlAndCalendar() throws Exception {
        JsonNode c = initContent();
        JsonNode control = c.get("control");

        assertThat(c.get("orderValue").asLong()).isEqualTo(400_000L);          // rand(400,400)*1000
        assertThat(c.get("valueToComplete").asLong()).isEqualTo(3_200_000L);   // * ordersToComplete 8
        assertThat(control.get("funnelType").asText()).isEqualTo("1");         // params_int is varchar
        assertThat(control.get("totalOrders").asInt()).isEqualTo(30);          // 10 days * 3
        assertThat(control.get("status").asInt()).isEqualTo(200);
        assertThat(control.get("welcome").asInt()).isEqualTo(1);
        assertThat(control.get("countTime").asBoolean()).isFalse();
        assertThat(control.get("eTime").asText()).isEqualTo("20:00");
        assertThat(control.get("numberOfDays").asInt()).isEqualTo(10);
        assertThat(control.get("maxMembers").asInt()).isEqualTo(2);

        // 10 days = 2 weeks; target_method 1 distributes the 8 orders evenly.
        assertThat(c.get("week").size()).isEqualTo(2);
        assertThat(c.get("week").get("1").get("orderTarget").asInt()).isEqualTo(4);
        assertThat(c.get("week").get("1").get("weekOrderTarget").asInt()).isEqualTo(4);
        assertThat(c.get("week").get("1").get("valueTarget").asLong()).isEqualTo(1_600_000L);
        assertThat(c.get("week").get("2").get("orderTarget").asInt()).isEqualTo(8);
        assertThat(c.get("week").get("2").get("weekOrderTarget").asInt()).isEqualTo(4);

        // weekday 1..5 then wraps, and week 1 starts on day 1.
        assertThat(c.get("day").get("1").get("weekday").asInt()).isEqualTo(1);
        assertThat(c.get("day").get("1").get("evaluateStyle").asInt()).isEqualTo(1);
        assertThat(c.get("day").get("5").get("weekday").asInt()).isEqualTo(5);
        assertThat(c.get("day").get("5").get("evaluateStyle").asInt()).isZero();
        assertThat(c.get("day").get("6").get("weekday").asInt()).isEqualTo(1);
        assertThat(c.get("day").get("6").get("week").asInt()).isEqualTo(2);
        assertThat(c.get("day").get("6").get("evaluateStyle").asInt()).isEqualTo(1);

        // Every day got 3 new orders; the order ledger is keyed 1..30, each on its day.
        assertThat(c.get("day").get("1").get("newOrders").asInt()).isEqualTo(3);
        assertThat(c.get("orders").size()).isEqualTo(30);
        assertThat(c.get("orders").get("1").get("day").asInt()).isEqualTo(1);
        assertThat(c.get("orders").get("30").get("day").asInt()).isEqualTo(10);
        assertThat(c.get("summary").isArray()).isTrue();
        assertThat(c.get("used").get("actions").isArray()).isTrue();
    }

    @Test
    @DisplayName("environment lands on its day, keeps criteria on $when, and scales new orders")
    void environmentEvents() throws Exception {
        JsonNode c = initContent();
        JsonNode day7 = c.get("day").get("7");

        assertThat(day7.get("event").get("count").asInt()).isEqualTo(1);
        assertThat(day7.get("event").get("eventName").get(0).asText()).isEqualTo("Offshore");
        assertThat(day7.get("event").get("eventExp").get(0).asText()).isEqualTo("OF");   // baseinfo displayKey
        assertThat(day7.get("event").get("description").get(0).asText()).isEqualTo("Offshore rot");
        assertThat(day7.get("event").get("staffName").get(0).asText()).isEmpty();
        assertThat(day7.get("event").get("criteria").get(0).asInt()).isZero();
        // orderImpact 1 keeps the count; a duration-2 event also touches the next day
        assertThat(day7.get("newOrders").asInt()).isEqualTo(3);
        assertThat(c.get("day").get("8").get("newOrders").asInt()).isEqualTo(3);
        assertThat(c.get("day").get("8").get("event").get("count").asInt()).isZero();
        // days with no event keep the plain product value
        assertThat(c.get("day").get("9").get("newOrders").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("members: style, per-member history, and the rand()-from-view fallback")
    void members() throws Exception {
        JsonNode c = initContent();
        JsonNode m1 = c.get("members").get("1");
        JsonNode m2 = c.get("members").get("2");

        assertThat(m1.get("state").asText()).isEqualTo("blink");      // slot 1 blinks
        assertThat(m2.get("state").asText()).isEqualTo("halt");
        assertThat(m1.get("introduced").asInt()).isZero();
        assertThat(m1.get("style").asText()).isEqualTo("Formal");
        assertThat(m1.get("styleExp").asText()).isEqualTo("FO");
        // *Init values are copied verbatim and stay strings (they come from the model blob)
        assertThat(m1.get("skillsValue").asText()).isEqualTo("30");
        assertThat(m1.get("moraleValue").asText()).isEqualTo("40");
        assertThat(m1.get("efficiencyValue").asText()).isEqualTo("48");
        assertThat(m1.get("initSkillsValue").asText()).isEqualTo("30");
        // tolerances come from params_int (varchar)
        assertThat(m1.get("attention").asText()).isEqualTo("10");
        assertThat(m1.get("attenWarning").asText()).isEqualTo("8");
        assertThat(m1.get("listen").asText()).isEqualTo("5");
        assertThat(m1.get("trend").asInt()).isZero();

        // slot 2: staffId 3 has no staffprofile_view row -> rand(0,0) then max(...,2|10)
        assertThat(m2.get("skillsValue").asInt()).isEqualTo(2);
        assertThat(m2.get("moraleValue").asInt()).isEqualTo(2);
        assertThat(m2.get("efficiencyValue").asInt()).isEqualTo(10);
        assertThat(m2.get("newComer").isNull()).isTrue();

        // one history line per named member, all at step 0 / day 0 / INIT
        assertThat(c.get("history").size()).isEqualTo(2);
        assertThat(c.get("history").get(0).get("staff").asText()).isEqualTo("2");
        assertThat(c.get("history").get(0).get("actType").asText()).isEqualTo("INIT");
        assertThat(c.get("history").get(0).get("skills").asText()).isEqualTo("30");

        // manpower: numberOfStaff / numberOfTasks = 2 / 1
        assertThat(c.get("manpower").get("Inspect").get("pos").asInt()).isEqualTo(2);
        assertThat(c.get("manpower").get("Inspect").get("mbr").asInt()).isZero();
        assertThat(c.get("manpowerTeam").get("pos").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("analysis skeleton: staff/action/style counters and the report headings")
    void analysisSkeleton() throws Exception {
        JsonNode a = initContent().get("analysis");

        // Only 'Plan' is show=1 AND its own parent -> the only main action.
        assertThat(a.get("action").size()).isEqualTo(1);
        JsonNode plan = a.get("action").get("Plan");
        assertThat(plan.get("name").asText()).isEqualTo("Plan");
        assertThat(plan.get("count").asInt()).isZero();
        assertThat(plan.get("oneInFour").asInt()).isEqualTo(1);       // action."1In4"
        assertThat(plan.get("skillsAdded").asInt()).isZero();
        assertThat(plan.get("moraleAdded").asInt()).isZero();

        // init_staff_ana ran for both member slots, keyed by staff name.
        assertThat(a.get("staff").size()).isEqualTo(2);
        assertThat(a.get("staff").get("2").get("Plan").get("count").asInt()).isZero();
        assertThat(a.get("staff").get("2").get("staffExp").asText()).isEqualTo("杜仲");
        assertThat(a.get("staff").get("2").get("total").asInt()).isZero();
        assertThat(a.get("staff").get("3").get("staffExp").asText()).isEqualTo("半");

        JsonNode style = a.get("style");
        assertThat(style.get("Formal").get("skillsAdded").asInt()).isZero();
        assertThat(style.get("total").asInt()).isZero();
        assertThat(style.get("desire_actual").get("positive").asInt()).isZero();
        assertThat(style.get("desire_intent").get("count").asInt()).isZero();
        assertThat(style.get("intent_actual").get("negative").asInt()).isZero();
        assertThat(a.get("action_style").get("Formal").get("count").asInt()).isZero();
        assertThat(a.get("action_style").get("total").asInt()).isZero();

        // drill -> drillcontrol -> backenduser
        assertThat(a.get("reportUser").asText()).isEqualTo("Boom");
        assertThat(a.get("reportDate").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    @DisplayName("INIT is idempotent: a second call returns the stored row and appends nothing")
    void idempotent() throws Exception {
        JsonNode first = initContent();
        String firstId = jdbc.queryForObject(
                "select drillDetailId from drilldetail where drillId = 'D3'", String.class);

        MvcResult r = init("{\"drillId\":\"D3\",\"seq\":2,\"modelProduct\":\"W1\"}");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode second = json(r);

        assertThat(second.get("drilldetailid").asText()).isEqualTo(firstId);
        assertThat(second.get("content").get("control").get("totalOrders").asInt()).isEqualTo(30);
        assertThat(first.get("control").get("totalOrders").asInt()).isEqualTo(30);
        assertThat(jdbc.queryForObject(
                "select count(*) from drilldetail where drillId = 'D3'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("unknown drill -> 404, no body; known drill without a product -> 204")
    void failurePaths() throws Exception {
        MvcResult unknown = init("{\"drillId\":\"NOPE\",\"seq\":1,\"modelProduct\":\"W1\"}");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(unknown.getResponse().getContentAsString()).isEmpty();

        // no drillId at all: the PHP never even queries the drill
        MvcResult noId = init("{\"seq\":1}");
        assertThat(noId.getResponse().getStatus()).isEqualTo(404);
        assertThat(noId.getResponse().getContentAsString()).isEmpty();

        // drill exists, every join succeeds except the product lookup -> 204, no body
        MvcResult noProduct = init("{\"drillId\":\"D3\",\"seq\":2,\"modelProduct\":\"GHOST\"}");
        assertThat(noProduct.getResponse().getStatus()).isEqualTo(204);
        assertThat(noProduct.getResponse().getContentAsString()).isEmpty();
        assertThat(jdbc.queryForObject(
                "select count(*) from drilldetail where drillId = 'D3'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("dispatch: ACTION and ENVIRONMENT are still loud, unknown mods echo 'No Mod'")
    void dispatch() throws Exception {
        mockMvc.perform(put("/backend/drill/").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"mod\":\"ACTION\"}"))
                .andExpect(status().isNotImplemented());

        mockMvc.perform(put("/backend/drill/").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"mod\":\"BOGUS\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("No Mod"));
    }
}
