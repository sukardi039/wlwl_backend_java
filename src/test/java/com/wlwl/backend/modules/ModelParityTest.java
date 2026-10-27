package com.wlwl.backend.modules;

import com.wlwl.backend.db.MysqlPassword;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parity tests for model/.
 *
 * Fixture: template BasicTpl wants 2 staff, 1 environment, 1 recruit.
 * Model M1 supplies 1 staff, 1 environment, 1 recruit.
 * So the staff list must be padded to 2, while environments/recruits are left alone
 * (they use loose != and already match).
 *
 * @Transactional so the PUT cases roll back and do not disturb the model rows that
 * basicinformation's parity test asserts on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ModelParityTest {

    private static final String KEY = "K1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ResultActions call(String verb, String query, String body) throws Exception {
        String url = "/backend/model/" + (query == null ? "" : "?" + query);
        var req = switch (verb) {
            case "GET" -> get(url);
            case "POST" -> post(url);
            case "PUT" -> put(url);
            default -> throw new IllegalArgumentException(verb);
        };
        return mockMvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---------------------------------------------------------------- GET

    @Test
    @DisplayName("GET: template expanded into blank staff/environment/recruit slots")
    void getTemplate() throws Exception {
        call("GET", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"GET\",\"mod\":\"BasicTpl\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templatename").value("BasicTpl"))
                .andExpect(jsonPath("$.staffs.length()").value(2))
                .andExpect(jsonPath("$.staffs[0].staffName").value(""))
                .andExpect(jsonPath("$.staffs[0].task").value(""))
                .andExpect(jsonPath("$.environments.length()").value(1))
                .andExpect(jsonPath("$.recruits.length()").value(1))
                .andExpect(jsonPath("$.productExp").value(""))
                .andExpect(jsonPath("$.ordersToComplete").value(0))
                .andExpect(jsonPath("$.valueToComplete").value(0));
    }

    @Test
    @DisplayName("GET: falsy mod -> empty 200 (the PHP's `if ($mod)` never runs)")
    void getWithoutMod() throws Exception {
        call("GET", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"GET\"}")
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("GET: unknown template -> 204")
    void getUnknownTemplate() throws Exception {
        call("GET", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"GET\",\"mod\":\"Nope\"}")
                .andExpect(status().isNoContent());
    }

    // --------------------------------------------------------------- POST

    @Test
    @DisplayName("POST: staff list padded to the template count; matching lists untouched")
    void postRepairsStaffOnly() throws Exception {
        call("POST", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":\"BasicTpl\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                // H2 lowercases the column name; production MySQL keeps modelContent.
                .andExpect(jsonPath("$[0].modelcontent.staffs.length()").value(2))
                .andExpect(jsonPath("$[0].modelcontent.staffs[0].staffName").value("A"))
                // the pad is a default record
                .andExpect(jsonPath("$[0].modelcontent.staffs[1].staffName").value(""))
                .andExpect(jsonPath("$[0].modelcontent.staffs[1].task").value(""))
                // environments matched (1 == 1) so the block was SKIPPED: original kept
                .andExpect(jsonPath("$[0].modelcontent.environments.length()").value(1))
                .andExpect(jsonPath("$[0].modelcontent.environments[0].environmentName")
                        .value("Off"))
                .andExpect(jsonPath("$[0].modelcontent.recruits[0].staffName").value("R"))
                // untouched keys survive the re-encode
                .andExpect(jsonPath("$[0].modelcontent.nodes[0]").value(1))
                .andExpect(jsonPath("$[0].modelcontent.label").value("x"));
    }

    @Test
    @DisplayName("POST: query string is the modelId and collapses the response to an object")
    void postByQueryString() throws Exception {
        call("POST", "M1", "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":\"BasicTpl\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isMap())
                .andExpect(jsonPath("$.modelid").value("M1"));
    }

    @Test
    @DisplayName("POST: unknown modelId -> 204")
    void postUnknownModelId() throws Exception {
        call("POST", "NOPE", "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":\"BasicTpl\"}")
                .andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------- PUT

    @Test
    @DisplayName("PUT: update in place, content re-decoded for the response")
    void putUpdate() throws Exception {
        call("PUT", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"qdata\":{"
                + "\"modelId\":\"M1\",\"modelName\":\"Renamed\",\"modelTemplate\":\"BasicTpl\","
                + "\"modelProduct\":\"W1\",\"modelContent\":\"{\\\"a\\\":1}\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].modelname").value("Renamed"))
                .andExpect(jsonPath("$[0].modelcontent.a").value(1));

        String stored = jdbc.queryForObject(
                "select modelName from model where modelId = 'M1'", String.class);
        assertThat(stored).isEqualTo("Renamed");
    }

    @Test
    @DisplayName("PUT: no modelId -> insert with a 13-hex uniqid()")
    void putInsert() throws Exception {
        String json = call("PUT", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"qdata\":{"
                + "\"modelName\":\"Fresh\",\"modelTemplate\":\"BasicTpl\",\"modelProduct\":\"W1\","
                + "\"modelContent\":\"{}\"}}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String id = com.jayway.jsonpath.JsonPath.read(json, "$[0].modelid");
        assertThat(id).matches("[0-9a-f]{13}");
    }

    @Test
    @DisplayName("PUT: modelContent sent as an OBJECT (not JSON text) -> 500, like the PHP fatal")
    void putContentAsObjectFails() throws Exception {
        call("PUT", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"qdata\":{"
                + "\"modelId\":\"M1\",\"modelName\":\"X\",\"modelTemplate\":\"BasicTpl\","
                + "\"modelProduct\":\"W1\",\"modelContent\":{\"a\":1}}}")
                .andExpect(status().isInternalServerError());
    }
}
