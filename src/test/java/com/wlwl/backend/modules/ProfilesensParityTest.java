package com.wlwl.backend.modules;

import com.jayway.jsonpath.JsonPath;
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
 * Parity tests for profilesens/.
 *
 * Seeded on first read (whole table empty -> 2x2x2 = 8 rows). post.php enriches each
 * row with three Exp (display name) AND three Prf (baseinfo displayKey) fields;
 * put.php enriches with ONLY the Exp fields. That asymmetry is deliberate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProfilesensParityTest {

    private static final String KEY = "K1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ResultActions post_(String query, String body) throws Exception {
        String url = "/backend/profilesens/" + (query == null ? "" : "?" + query);
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String seed() throws Exception {
        return post_(null, "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\"}")
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("POST seeds 8 combinations and adds both Exp and Prf expansions")
    void seedsWithPrf() throws Exception {
        post_(null, "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[0].skillsclass").value("P1"))
                // synthetic fields keep the PHP's camelCase
                .andExpect(jsonPath("$[0].skillsExp").value("Low"))
                // Prf comes from baseinfo.displayKey, not from profileclass
                .andExpect(jsonPath("$[0].skillsPrf").value("SK"))
                .andExpect(jsonPath("$[0].moralePrf").value("MO"))
                .andExpect(jsonPath("$[0].efficiencyPrf").value("EF"))
                .andExpect(jsonPath("$[0].skillssensitivity").value("0.00"));
    }

    @Test
    @DisplayName("POST does not re-seed")
    void seedsOnlyOnce() throws Exception {
        seed();
        seed();
        assertThat(jdbc.queryForObject("select count(*) from profilesens", Integer.class))
                .isEqualTo(8);
    }

    @Test
    @DisplayName("POST: query string is the key -> single object")
    void postByKey() throws Exception {
        Integer key = JsonPath.read(seed(), "$[0].profilesensitivitykey");
        post_("" + key, "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isMap())
                .andExpect(jsonPath("$.profilesensitivitykey").value(key));
    }

    @Test
    @DisplayName("POST: unknown key -> 204 (rows exist, but none match)")
    void postUnknownKey() throws Exception {
        seed();
        post_("99999", "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\"}")
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("PUT writes sensitivities and adds only the Exp fields — no Prf")
    void putWithoutPrf() throws Exception {
        Integer key = JsonPath.read(seed(), "$[0].profilesensitivitykey");

        mockMvc.perform(put("/backend/profilesens/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"qdata\":{"
                                + "\"profileSensitivityKey\":" + key + ","
                                + "\"skillsSensitivity\":4,\"moraleSensitivity\":5,"
                                + "\"efficiencySensitivity\":6}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skillssensitivity").value("4.00"))
                .andExpect(jsonPath("$[0].moralesensitivity").value("5.00"))
                .andExpect(jsonPath("$[0].efficiencyExp").value("High"))
                // put.php does NOT emit the Prf fields
                .andExpect(jsonPath("$[0].skillsPrf").doesNotExist());
    }

    @Test
    @DisplayName("PUT: missing key -> 500")
    void putMissingKey() throws Exception {
        mockMvc.perform(put("/backend/profilesens/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"qdata\":{"
                                + "\"skillsSensitivity\":1,\"moraleSensitivity\":1,"
                                + "\"efficiencySensitivity\":1}}"))
                .andExpect(status().isInternalServerError());
    }
}
