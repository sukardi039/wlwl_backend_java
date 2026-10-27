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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parity tests for the three catalogue modules that are NOT plain row dumps:
 * action/ (three lookups), taskefficiency/ (lazy seeding + a real SQL bug) and
 * profileclass/ (dynamic table name in PUT).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ActionTaskefficiencyProfileclassParityTest {

    private static final String KEY = "K1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ResultActions call(String module, String verb, String query, String body)
            throws Exception {
        String url = "/backend/" + module + "/" + (query == null ? "" : "?" + query);
        var req = "PUT".equals(verb)
                ? org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url)
                : org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url);
        return mockMvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // -------------------------------------------------------------- action

    @Test
    @DisplayName("action: LEFT JOIN keeps baseinfo rows, and all three lookups are applied")
    void actionList() throws Exception {
        call("action", "POST", null,
                "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].actionname").value("Plan"))
                // typeExp = actiontype[actionType]
                .andExpect(jsonPath("$[0].typeExp").value("Primary"))
                // affectText/affectCode are unguarded lookups -> null for a null column
                .andExpect(jsonPath("$[0].affectText").doesNotExist())
                .andExpect(jsonPath("$[0].affectCode").doesNotExist());
    }

    @Test
    @DisplayName("action PUT updates in place and re-reads through the INNER join")
    void actionUpdate() throws Exception {
        call("action", "PUT", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                + "\"qdata\":{\"name\":\"Plan\",\"actionName\":\"Plan\","
                + "\"actionDescription\":\"Plan it\",\"duration\":2,\"applyAll\":0,"
                + "\"minStaff\":1,\"maxStaff\":4,\"actionType\":1,\"withTask\":1,"
                + "\"swapTask\":0,\"parentAction\":\"Plan\",\"minMemberSize\":1,"
                + "\"affectedWhen\":\"\",\"affectedIf\":\"\",\"away\":0}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actiondescription").value("Plan it"))
                .andExpect(jsonPath("$[0].typeExp").value("Primary"));
    }

    @Test
    @DisplayName("action PUT insert: name comes from qdata.name, then re-read needs baseinfo")
    void actionInsert() throws Exception {
        jdbc.update("insert into baseinfo (baseclass, name, displayKey) "
                + "values ('actions', 'Review', 'RV')");

        call("action", "PUT", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                + "\"qdata\":{\"name\":\"Review\",\"actionDescription\":\"Review it\","
                + "\"duration\":3,\"actionType\":1}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actionname").value("Review"));

        assertThat(jdbc.queryForObject(
                "select actionDescription from action where actionName = 'Review'",
                String.class)).isEqualTo("Review it");
    }

    // -------------------------------------------------------- taskefficiency

    @Test
    @DisplayName("taskefficiency: first read for a product seeds one row per baseinfo task")
    void taskefficiencySeeds() throws Exception {
        call("taskefficiency", "POST", null,
                "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":\"W2\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productname").value("W2"))
                .andExpect(jsonPath("$[0].taskname").value("Inspect"))
                // skillsWeight 0.5, duration 1.0 and taskDisplayName = baseinfo.orgKey
                .andExpect(jsonPath("$[0].skillsweight").value("0.50"))
                .andExpect(jsonPath("$[0].duration").value("1.00"))
                .andExpect(jsonPath("$[0].taskExp").value("TI"));
    }

    @Test
    @DisplayName("taskefficiency: falsy mod -> 406")
    void taskefficiencyNeedsMod() throws Exception {
        call("taskefficiency", "POST", null,
                "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\"}")
                .andExpect(status().isNotAcceptable())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("taskefficiency: a query string hits the non-existent profileImpactKey -> 500")
    void taskefficiencyQueryStringBug() throws Exception {
        // Faithful bug: the PHP appends `and profileImpactKey = '<qs>'` to a table that
        // has no such column, so any query string is an SQL error.
        call("taskefficiency", "POST", "1",
                "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":\"W1\"}")
                .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("taskefficiency PUT updates weights and returns taskExp")
    void taskefficiencyUpdate() throws Exception {
        Integer id = jdbc.queryForObject(
                "select taskEfficiencyId from taskefficiency where productName = 'W1'",
                Integer.class);

        call("taskefficiency", "PUT", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                + "\"qdata\":{\"taskEfficiencyId\":" + id + ",\"skillsWeight\":0.7,"
                + "\"moraleWeight\":0.3,\"duration\":2.5,\"taskDisplayName\":\"Inspect 2\","
                + "\"passingRate\":0.9,\"target\":1.2}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skillsweight").value("0.70"))
                .andExpect(jsonPath("$[0].taskdisplayname").value("Inspect 2"))
                .andExpect(jsonPath("$[0].taskExp").value("TI"));
    }

    // --------------------------------------------------------- profileclass

    @Test
    @DisplayName("profileclass: adds key and profileDisplayName from baseinfo")
    void profileclassList() throws Exception {
        call("profileclass", "POST", null,
                "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].key").value("P1"))
                // synthetic fields keep the PHP's camelCase
                .andExpect(jsonPath("$[0].profileDisplayName").value("SK"));
    }

    @Test
    @DisplayName("profileclass PUT: table name comes from mod; update then re-read by code")
    void profileclassUpdate() throws Exception {
        call("profileclass", "PUT", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                + "\"mod\":\"profileclass\",\"qdata\":{\"key\":\"P1\","
                + "\"profileClassCode\":\"P1\",\"profileName\":\"skills\","
                + "\"profileClassDisplayName\":\"Very Low\",\"profileClassMin\":0,"
                + "\"profileClassMax\":10}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].profileclassdisplayname").value("Very Low"));
    }

    @Test
    @DisplayName("profileclass PUT with a non-identifier mod -> 500 (identifier is not bindable)")
    void profileclassRejectsBadTable() throws Exception {
        call("profileclass", "PUT", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                + "\"mod\":\"profileclass; drop table baseinfo\",\"qdata\":{}}")
                .andExpect(status().isInternalServerError());
    }
}
