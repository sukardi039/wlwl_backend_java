package com.wlwl.backend.modules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parity tests for drill/ mod=TIMEOUT (drill/timeout.php + competency.php::saveCompetency).
 *
 * The content below is shaped like the real 2026-08 terminal blob. With
 *   skills 53 -> 66, morale 43 -> 93, efficiency 58 -> 71,
 *   desire positives 17/20, sales 500000 vs target 400000
 * the scores are 7, 9, 6, 9, 10 = 41, which is what the test pins.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DrillTimeoutParityTest {

    private static final String KEY = "K1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private static String blob(int status) {
        return "{\"control\":{\"status\":" + status + "},"
                + "\"summary\":[{\"teamAvg\":{\"skills\":53,\"morale\":43,\"efficiency\":58}}],"
                + "\"current\":{\"teamAvg\":{\"skills\":66,\"morale\":93,\"efficiency\":71}},"
                + "\"analysis\":{\"style\":{\"desire_intent\":{\"count\":10,\"positive\":8},"
                + "\"desire_actual\":{\"count\":10,\"positive\":9}}},"
                + "\"valueToComplete\":400000,\"orderStatus\":{\"ordersCompleted\":10},"
                + "\"orderValue\":50000}";
    }

    private ResultActions timeout(int status) throws Exception {
        String body = "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"mod\":\"TIMEOUT\","
                + "\"qdata\":{\"drillId\":\"D1\",\"drillDetailId\":\"DD1b\","
                + "\"stepNumber\":41,\"dayNumber\":33,\"content\":\""
                + blob(status).replace("\"", "\\\"") + "\"}}";
        return mockMvc.perform(put("/backend/drill/")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("timeout after the target was met -> COMPLETED / 900, competency saved")
    void completesWhenTargetMet() throws Exception {
        timeout(600)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("COMPLETED"))
                // stepNumber comes from the client and is incremented
                .andExpect(jsonPath("$.stepnumber").value(42))
                // dayNumber is NOT incremented (that is action.php's job)
                .andExpect(jsonPath("$.daynumber").value(33))
                .andExpect(jsonPath("$.content.control.status").value(900))
                .andExpect(jsonPath("$.content.competency.totalCompetency").value(41))
                .andExpect(jsonPath("$.content.avg_competency.totalCompetency").value(0));

        assertThat(jdbc.queryForObject(
                "select drillStatus from drill where drillId = 'D1'", Integer.class))
                .isEqualTo(900);
        assertThat(jdbc.queryForObject(
                "select endOn from drill where drillId = 'D1'", Object.class)).isNotNull();
        assertThat(jdbc.queryForObject(
                "select totalCompetency from competency where userId = 'U1'", Integer.class))
                .isEqualTo(41);
    }

    @Test
    @DisplayName("timeout without the target -> TIME_OUT / 800, no endOn")
    void timesOutWhenTargetMissed() throws Exception {
        timeout(500)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("TIME_OUT"))
                .andExpect(jsonPath("$.content.control.status").value(800));

        // NOT null: the PHP guards endOn on `if ($stat > 600)` and 800 (timed out) is also
        // > 600, so a timeout stamps endOn exactly like a completion does.
        assertThat(jdbc.queryForObject(
                "select endOn from drill where drillId = 'D1'", Object.class)).isNotNull();
    }

    @Test
    @DisplayName("a second timeout call returns the same terminal row and appends nothing")
    void isIdempotent() throws Exception {
        timeout(600).andExpect(status().isOk());
        Integer afterFirst = jdbc.queryForObject(
                "select count(*) from drilldetail where drillId = 'D1'", Integer.class);

        timeout(600)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("COMPLETED"));

        assertThat(jdbc.queryForObject(
                "select count(*) from drilldetail where drillId = 'D1'", Integer.class))
                .isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("drill mod dispatch: unported mods are loud, unknown mods echo 'No Mod'")
    void dispatch() throws Exception {
        mockMvc.perform(put("/backend/drill/").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"mod\":\"ACTION\"}"))
                .andExpect(status().isNotImplemented());

        mockMvc.perform(put("/backend/drill/").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"mod\":\"BOGUS\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("No Mod"));

        // falsy mod: PHP's `if ($mod)` never enters the switch
        mockMvc.perform(put("/backend/drill/").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("RESET deletes the drillControl and answers 204 — the body only appears on FAILURE")
    void resetDeletes() throws Exception {
        // Read the PHP carefully: it deletes, re-selects, and echoes
        // {'status':'notdone'} only if the row is STILL THERE. A successful delete falls
        // into the else branch and returns 204 with no body.
        mockMvc.perform(put("/backend/drill/").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                                + "\"mod\":\"RESET\",\"qdata\":{\"drillControlId\":\"DC1\"}}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(jdbc.queryForObject(
                "select count(*) from drillcontrol where drillControlId = 'DC1'", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("RESET without a drillControlId emits nothing at all")
    void resetWithoutId() throws Exception {
        mockMvc.perform(put("/backend/drill/").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                                + "\"mod\":\"RESET\",\"qdata\":{}}"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("UPDATENAME renames the user and returns the fresh row")
    void updateName() throws Exception {
        mockMvc.perform(put("/backend/drill/").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                                + "\"mod\":\"UPDATENAME\",\"qdata\":{\"userName\":\"Renamed\","
                                + "\"userId\":\"U1\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("Renamed"));

        assertThat(jdbc.queryForObject(
                "select userName from backenduser where userId = 'U1'", String.class))
                .isEqualTo("Renamed");
    }
}
