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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parity tests for simulator/.
 *
 * The INITIALISATION branch uses rand(), so those assertions check the ranges the PHP
 * rolls between (inclusive) rather than exact numbers. The ACTION branch is fully
 * deterministic and is asserted exactly.
 *
 * Fixture: staff 2 = (P1 skills 10-90, P2 morale 20-80, P3 efficiency 30-100), and one
 * impact row for the P1/P2/P3 triple with sensitivities 2/2/1 and impacts 5/4/3.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SimulatorParityTest {

    private static final String KEY = "K1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ResultActions call(String verb, String query, String body) throws Exception {
        String url = "/backend/simulator/" + (query == null ? "" : "?" + query);
        var req = "PUT".equals(verb) ? put(url) : post(url);
        return mockMvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** One impact row + its sensitivity row for the staff-2 profile triple. */
    private void seedImpact() {
        jdbc.update("insert into profileimpact (impactClass, skillsClass, moraleClass, "
                + "efficiencyClass, skillsImpact, moraleImpact, efficiencyImpact) "
                + "values ('Plan', 'P1', 'P2', 'P3', 5, 4, 3)");
        jdbc.update("insert into profilesens (skillsClass, moraleClass, efficiencyClass, "
                + "skillsSensitivity, moraleSensitivity, efficiencySensitivity) "
                + "values ('P1', 'P2', 'P3', 2, 2, 1)");
    }

    private String putBody(String sessionId, int seq, String lastAction) {
        return "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"mod\":\"" + sessionId + "\","
                + "\"qdata\":{\"sessionId\":\"" + sessionId + "\",\"seqNumber\":" + seq + ","
                + "\"productName\":\"W1\",\"productExp\":\"Widget\","
                + "\"taskName\":\"Inspect\",\"taskExp\":\"TI\",\"staffId\":2,"
                + "\"staffName\":\"Bob\",\"skillsValue\":50,\"moraleValue\":50,"
                + "\"efficiencyValue\":50,\"skillsWeight\":0.5,\"moraleWeight\":0.5"
                + (lastAction == null ? "" : ",\"lastAction\":\"" + lastAction + "\","
                + "\"actionExp\":\"Plan it\"") + "}}";
    }

    // ---------------------------------------------------------------- POST

    @Test
    @DisplayName("POST: falsy mod -> 406; unknown session -> 204")
    void postGates() throws Exception {
        call("POST", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\"}")
                .andExpect(status().isNotAcceptable())
                .andExpect(content().string(""));

        call("POST", null,
                "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":\"nosuch\"}")
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("POST always answers with an ARRAY, newest step first")
    void postIsAlwaysAnArray() throws Exception {
        call("PUT", null, putBody("sim1", 0, null)).andExpect(status().isOk());
        call("PUT", null, putBody("sim1", 1, "Plan")).andExpect(status().isOk());

        // even WITH a query string, post.php appends unconditionally -> no object switch
        call("POST", "anything",
                "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":\"sim1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                // order by seqNumber DESC
                .andExpect(jsonPath("$[0].seqnumber").value(2));
    }

    // ----------------------------------------------------------------- PUT

    @Test
    @DisplayName("PUT init: rolls skills/morale/efficiency inside the profile ranges")
    void initialiseRollsRanges() throws Exception {
        String body = "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"mod\":\"sim2\","
                + "\"qdata\":{\"sessionId\":\"sim2\",\"seqNumber\":0,"
                + "\"productName\":\"W1\",\"taskName\":\"Inspect\",\"staffId\":2,"
                + "\"staffName\":\"Bob\",\"skillsValue\":-1,"
                + "\"productExp\":\"Widget\",\"taskExp\":\"TI\"}}";

        call("PUT", null, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seqnumber").value(1))
                // weights come from taskefficiency (W1/Inspect)
                .andExpect(jsonPath("$[0].skillsweight").value("0.50"))
                .andExpect(jsonPath("$[0].moraleweight").value("0.50"))
                .andExpect(jsonPath("$[0].skillsvalue").isNumber())
                .andExpect(jsonPath("$[0].moralevalue").isNumber())
                .andExpect(jsonPath("$[0].efficiencyvalue").isNumber());

        var row = jdbc.queryForMap("select * from simulator where sessionId = 'sim2'");
        int skills = ((Number) row.get("skillsvalue")).intValue();
        int morale = ((Number) row.get("moralevalue")).intValue();
        int eff = ((Number) row.get("efficiencyvalue")).intValue();
        assertThat(skills).isBetween(10, 90);     // P1 min/max
        assertThat(morale).isBetween(20, 80);     // P2 min/max
        // not P3's 30..100: eff is round(skills*0.5 + morale*0.5), so it can be as low as 15
        assertThat(eff).isBetween(15, 85);
        // efficiencyValue = skills*wSkills + morale*wMorale
        // (int) cast matters: Math.round returns long, and Integer.equals(Long) is false
        assertThat(eff).isEqualTo((int) Math.round(skills * 0.5 + morale * 0.5));
    }

    @Test
    @DisplayName("PUT init: skillsValue !== -1 uses the client's values verbatim")
    void initialiseUsesGivenValues() throws Exception {
        call("PUT", null, putBody("sim3", 0, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skillsvalue").value(50))
                .andExpect(jsonPath("$[0].moralevalue").value(50))
                // 50*0.5 + 50*0.5 = 50 — efficiency is NOT rolled in this branch
                .andExpect(jsonPath("$[0].efficiencyvalue").value(50));
    }

    @Test
    @DisplayName("PUT action: deterministic delta maths against impactprofile_view")
    void applyActionMaths() throws Exception {
        seedImpact();

        call("PUT", null, putBody("sim4", 0, "Plan"))
                .andExpect(status().isOk())
                // skillsAdd = round(2*5) = 10   -> 60
                // moraleAdd = round(2*4) =  8   -> 58
                // effAdd    = round((10*.5 + 8*.5)*1 + 3) = 12 -> 62
                .andExpect(jsonPath("$[0].skillsaddedvalue").value(10))
                .andExpect(jsonPath("$[0].moraleaddedvalue").value(8))
                .andExpect(jsonPath("$[0].efficiencyaddedvalue").value(12))
                .andExpect(jsonPath("$[0].skillsvalue").value(60))
                .andExpect(jsonPath("$[0].moralevalue").value(58))
                .andExpect(jsonPath("$[0].efficiencyvalue").value(62))
                // modValue = (60+58+62) - (50+50+50) = 180 - 150 = 30
                .andExpect(jsonPath("$[0].modvalue").value(30))
                .andExpect(jsonPath("$[0].lastskillsvalue").value(50))
                .andExpect(jsonPath("$[0].lastaction").value("Plan"));
    }

    @Test
    @DisplayName("PUT: mod must equal qdata.sessionId, else nothing happens (empty 200)")
    void putSessionMismatch() throws Exception {
        String body = "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"mod\":\"other\","
                + "\"qdata\":{\"sessionId\":\"sim5\",\"seqNumber\":0,"
                + "\"productName\":\"W1\",\"taskName\":\"Inspect\",\"staffId\":2,"
                + "\"skillsValue\":10}}";
        call("PUT", null, body)
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        assertThat(jdbc.queryForObject(
                "select count(*) from simulator where sessionId = 'sim5'", Integer.class))
                .isZero();
    }
}
