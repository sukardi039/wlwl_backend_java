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

/**
 * Parity tests for drill/ mod=ACTION, case MORE_ACTIONS.
 *
 * <p>Member 1 acts with 'Advise' (a plain action: parentAction 'Plan', duration/away/
 * effectiveDays 0, affectedIf 'X'), member 2 sits idle. The numbers are the same ones
 * DrillActionSelectStyleTest asserts for the identical profile row (+5 skills, +3 morale,
 * +6 efficiency), so the two branches are directly comparable.
 *
 * <p>What these tests are really guarding is the bookkeeping around the impact — the
 * things that only exist in this branch: the action-use ledger, the away/awayFor pair,
 * the Monday-adjusted `effect` expiry, the attention counters, and the history entries.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DrillMoreActionsTest {

    private static final String KEY = "K1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    /**
     * @param leaderAction  content.control.action — the action row loaded before the loop
     * @param member1Action member 1's action; empty means member 1 does not act
     * @param member2Action member 2's action; empty means member 2 does not act
     */
    private static String blob(String leaderAction, String member1Action, String member2Action) {
        return "{\"control\":{\"maxMembers\":\"2\",\"status\":500,\"action\":\"" + leaderAction + "\","
                + "\"task\":\"Inspect\",\"taskExp\":\"TI\",\"welcome\":1,\"numberOfDays\":10,"
                + "\"totalOrders\":0},"
                + "\"ordersToComplete\":\"8\",\"orders\":{},"
                + "\"day\":{\"5\":{\"evaluateStyle\":0,\"week\":1,\"weekday\":5,"
                + "\"event\":{\"count\":0},\"newOrders\":\"3\"},"
                + "\"6\":{\"evaluateStyle\":0,\"week\":2,\"weekday\":1,"
                + "\"event\":{\"count\":0},\"newOrders\":\"3\"}},"
                + "\"week\":{\"1\":{\"weekNumber\":1},\"2\":{\"weekNumber\":2}},"
                + "\"used\":{\"actions\":{}},"
                + "\"members\":{\"1\":{\"staffName\":\"1\",\"staffExp\":\"Ann\",\"task\":\"Inspect\","
                + "\"taskExp\":\"TI\",\"style\":\"Formal\",\"styleExp\":\"FO\",\"skillsValue\":\"60\","
                + "\"moraleValue\":\"50\",\"efficiencyValue\":\"70\",\"skillsAdded\":0,\"moraleAdded\":0,"
                + "\"efficiencyAdded\":0,\"added\":0,\"addedAvg\":0,\"msgCount\":0,\"readMsg\":1,"
                + "\"message\":[],\"away\":0,\"actioned\":" + (member1Action.isEmpty() ? 0 : 1) + ","
                + "\"action\":\"" + member1Action + "\",\"actionExp\":\"" + (member1Action.isEmpty() ? "" : "AD") + "\",\"workingOn\":0,"
                + "\"attention\":10,\"attenWarning\":8,\"listen\":5,\"effect\":{},\"envEffect\":{},"
                + "\"actEffect\":{},\"evaluated\":0,\"evalResult\":\"\",\"touched\":0,\"maxMorale\":80,"
                + "\"trend\":0,\"trendLast\":0},"
                + "\"2\":{\"staffName\":\"2\",\"staffExp\":\"Bob\",\"task\":\"Inspect\","
                + "\"taskExp\":\"TI\",\"style\":\"Formal\",\"styleExp\":\"FO\",\"skillsValue\":\"40\","
                + "\"moraleValue\":\"30\",\"efficiencyValue\":\"50\",\"skillsAdded\":0,\"moraleAdded\":0,"
                + "\"efficiencyAdded\":0,\"added\":0,\"addedAvg\":0,\"msgCount\":0,\"readMsg\":1,"
                + "\"message\":[],\"away\":0,\"actioned\":" + (member2Action.isEmpty() ? 0 : 1) + ","
                + "\"action\":\"" + member2Action + "\",\"actionExp\":\"\",\"workingOn\":0,"
                + "\"attention\":10,\"attenWarning\":8,\"listen\":5,\"effect\":{},\"envEffect\":{},"
                + "\"actEffect\":{},\"evaluated\":0,\"evalResult\":\"\",\"touched\":0,\"maxMorale\":80,"
                + "\"trend\":0,\"trendLast\":0}},"
                + "\"task_eff\":[{\"taskName\":\"Inspect\",\"taskDisplayName\":\"Inspect\","
                + "\"skillsWeight\":0.50,\"moraleWeight\":0.50,\"duration\":1.00},"
                + "{\"taskName\":\"Drive\",\"taskDisplayName\":\"Drive\","
                + "\"skillsWeight\":0.80,\"moraleWeight\":0.20,\"duration\":1.00}],"
                + "\"history\":[],\"summary\":[],\"current\":{\"teamAvg\":{\"total\":0}},"
                + "\"orderStatus\":{\"ordersCompleted\":0},"
                + "\"bossMsg\":{\"count\":0},\"bossRev\":{\"count\":0},\"staffMsg\":{\"count\":0}}";
    }

    @BeforeEach
    void scenario() {
        // the impact profile both actions resolve through, matching the SELECT_STYLE test
        jdbc.update("delete from profileimpact where impactClass in ('Advise','Hidden','Coach')");
        for (String name : new String[]{"Advise", "Hidden", "Coach"}) {
            // *Impact columns drive cal_impacts (the member's own action); *Affect columns
            // drive cal_affect (the knock-on). They are different columns, which is easy to
            // miss: a row with only *Impact set makes the bystander effect a silent zero.
            jdbc.update("insert into profileimpact (impactClass, desiredStyle, skillsClass, "
                    + "moraleClass, efficiencyClass, skillsImpact, moraleImpact, efficiencyImpact, "
                    + "skillsAffect, moraleAffect, efficiencyAffect, impactMsg) "
                    + "values (?, 'Formal', 'P1', 'P2', 'P3', 10, 5, 2, 10, 5, 2, 'msg')", name);
        }
        // 'Coach' is a real action row so pass 3 can resolve its parentAction/duration;
        // without it the PHP behaves as if the client sent an unknown action.
        jdbc.update("delete from action where actionName = 'Coach'");
        jdbc.update("insert into action (actionName, parentAction, duration, away, effectiveDays, "
                + "perWeek, checkTrend, maximumUse, show) values ('Coach', 'Coach', 1, 0, 0, 0, 0, 9999, 1)");
        jdbc.update("delete from profilesens where skillsClass = 'P1'");
        jdbc.update("insert into profilesens (skillsClass, moraleClass, efficiencyClass, "
                + "skillsSensitivity, moraleSensitivity, efficiencySensitivity) "
                + "values ('P1', 'P2', 'P3', 0.50, 0.50, 1.00)");
    }

    private MvcResult step(String leaderAction, String member1Action, String member2Action)
            throws Exception {
        String escaped = blob(leaderAction, member1Action, member2Action).replace("\"", "\\\"");
        String body = "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"mod\":\"ACTION\","
                + "\"qdata\":{\"drillId\":\"D1\",\"drillDetailId\":\"DD1b\",\"stepNumber\":2,"
                + "\"dayNumber\":5,\"action\":\"MORE_ACTIONS\",\"content\":\"" + escaped + "\"}}";
        return mockMvc.perform(put("/backend/drill/")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return mapper.readTree(new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("MORE_ACTIONS applies the impact and keeps the action bookkeeping")
    void impactAndBookkeeping() throws Exception {
        MvcResult r = step("Advise", "Advise", "");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        JsonNode body = json(r);
        assertThat(body.get("stepnumber").asInt()).isEqualTo(3);
        assertThat(body.get("daynumber").asInt()).isEqualTo(6);
        assertThat(body.get("action").asText()).isEqualTo("MORE_ACTIONS");

        JsonNode c = body.get("content");
        JsonNode m1 = c.get("members").get("1");

        // the same profile row as SELECT_STYLE: +5 skills, +3 morale, +6 efficiency
        assertThat(m1.get("skillsValue").asInt()).isEqualTo(65);
        assertThat(m1.get("moraleValue").asInt()).isEqualTo(53);
        assertThat(m1.get("efficiencyValue").asInt()).isEqualTo(76);

        // the action-use ledger counted the leader's parentAction group
        assertThat(c.get("used").get("actions").get("Plan").asInt()).isEqualTo(1);

        // away is set to 0 + 0 + 1 and then decays by one in pass 4
        assertThat(m1.get("away").asInt()).isZero();
        assertThat(m1.get("awayFor").asText()).isEqualTo("Advise");

        // effect[parentAction] = day + away + effectiveDays + 1, then +1 for the Monday
        // rollover: 5 + 0 + 0 + 1 = 6, weekday 5 means (0+0+1+5) > 5 so it becomes 7
        assertThat(m1.get("effect").get("Plan").asInt()).isEqualTo(7);

        // the acting member's counters are reset to the tolerances, the idle one decays
        assertThat(m1.get("attention").asInt()).isEqualTo(10);
        assertThat(m1.get("attenWarning").asInt()).isEqualTo(8);
        JsonNode m2 = c.get("members").get("2");
        assertThat(m2.get("attention").asInt()).isEqualTo(9);
        assertThat(m2.get("attenWarning").asInt()).isEqualTo(7);
        assertThat(m2.get("skillsValue").asInt()).isEqualTo(40);   // untouched

        // one history entry, for member 1 only
        assertThat(c.get("history").size()).isEqualTo(1);
        assertThat(c.get("history").get(0).get("act").asText()).isEqualTo("AD");   // actionExp
        assertThat(c.get("history").get(0).get("actType").asText()).isEqualTo("MORE_ACTIONS");
        assertThat(c.get("history").get(0).get("staffExp").asText()).isEqualTo("Ann");

        // pass 4 computed the work each member can do from their efficiency
        assertThat(m1.get("workAvailable").asDouble()).isEqualTo(0.76);

        // the tail recorded the new step and cleared the action for the next one
        assertThat(c.get("control").get("action").asText()).isEmpty();
        assertThat(m1.get("actioned").asInt()).isZero();
    }

    @Test
    @DisplayName("the misspelled noInmpact guard means a noImpact action still lands")
    void noImpactTypoDoesNotBlockTheImpact() throws Exception {
        // PHP reads $action['noInmpact'] (m), a key that never exists, so !null is true.
        jdbc.update("update action set noImpact = 1 where actionName = 'Hidden'");

        MvcResult r = step("Hidden", "Hidden", "");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        JsonNode c = json(r).get("content");
        // the impact was applied despite noImpact = 1
        assertThat(c.get("members").get("1").get("skillsValue").asInt()).isEqualTo(65);
        // ...while the CORRECTLY spelled flag suppressed the boss review after the loop
        assertThat(c.get("bossRev").get("count").asInt()).isZero();
    }

    @Test
    @DisplayName("a bystander takes the knock-on effect and gets its own history entry")
    void bystanderAffected() throws Exception {
        // affectedIf '><' fires regardless of sign; affectedWhen '1' compares skills
        jdbc.update("update action set affectedWhen = '1', affectedIf = '><' where actionName = 'Coach'");

        MvcResult r = step("Coach", "Coach", "");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        JsonNode c = json(r).get("content");
        JsonNode m2 = c.get("members").get("2");

        // cal_affect ran on member 2: their own numbers moved and they are in history
        assertThat(m2.get("skillsValue").asInt()).isNotEqualTo(40);
        assertThat(c.get("history").size()).isEqualTo(2);
        assertThat(c.get("history").get(1).get("staffExp").asText()).isEqualTo("Bob");
        // and member 2 still did not act
        assertThat(m2.get("actioned").asInt()).isZero();
    }

    @Test
    @DisplayName("an evaluate action records the verdict and leaves the member's stats alone")
    void evaluateBranch() throws Exception {
        // parentAction 'evaluate' is what sends cal_impacts down the evaluation branch.
        // The member's action string carries the task under evaluation after "evaluate-".
        jdbc.update("delete from action where actionName = 'evaluate-Drive'");
        jdbc.update("insert into action (actionName, parentAction, duration, away, effectiveDays, "
                + "perWeek, checkTrend, maximumUse, show) "
                + "values ('evaluate-Drive', 'evaluate', 1, 0, 0, 0, 0, 9999, 1)");

        MvcResult r = step("Advise", "evaluate-Drive", "");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        JsonNode c = json(r).get("content");
        JsonNode m1 = c.get("members").get("1");

        // cal_swap ran on a COPY, so the evaluation itself changed nothing
        assertThat(m1.get("skillsValue").asInt()).isEqualTo(60);
        assertThat(m1.get("moraleValue").asInt()).isEqualTo(50);
        assertThat(m1.get("efficiencyValue").asInt()).isEqualTo(70);

        // ...but the verdict was recorded, and the action was consumed
        assertThat(m1.get("evaluated").asInt()).isEqualTo(1);
        assertThat(m1.get("evaluatedFor").asText()).isEqualTo("Drive");
        assertThat(m1.get("action").asText()).isEmpty();
        assertThat(m1.get("evalResult").asText()).startsWith("Drive: ");
        assertThat(m1.get("msgCount").asInt()).isEqualTo(1);

        // the evaluation message is queued for the staff message list
        assertThat(c.get("staffMsg").get("count").asInt()).isEqualTo(1);
        // numeric keys => PHP json_encode emits an ARRAY, not an object
        assertThat(c.get("staffMsg").get("description").get(0).asText()).startsWith("Drive: ");
        assertThat(c.get("staffMsg").get("staffExp").get(0).asText()).isEqualTo("Ann");
    }
}
