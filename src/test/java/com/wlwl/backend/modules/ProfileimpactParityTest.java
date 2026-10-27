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
 * Parity tests for profileimpact/ and profileaffect/.
 *
 * These two are a pair: post.php is byte-identical and both read `profileimpact`;
 * only the columns each put.php writes differ. The matrix is seeded on first read
 * with the cartesian product of the profile classes (2x2x2 = 8 here).
 *
 * @Transactional so the seeded matrix rolls back between tests — otherwise the
 * "seeded exactly once" assertion would depend on test order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProfileimpactParityTest {

    private static final String KEY = "K1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ResultActions call(String module, String verb, String query, String body)
            throws Exception {
        String url = "/backend/" + module + "/" + (query == null ? "" : "?" + query);
        // fully qualified: this class has its own post(...) helper that shadows the
        // static import of MockMvcRequestBuilders.post
        var req = "PUT".equals(verb)
                ? org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url)
                : org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url);
        return mockMvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String post(String module, String mod) throws Exception {
        return call(module, "POST", null,
                "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":\"" + mod + "\"}")
                .andReturn().getResponse().getContentAsString();
    }

    // --------------------------------------------------------------- POST

    @Test
    @DisplayName("POST seeds the full 2x2x2 matrix by impactClass, with Exp expansions")
    void seedsMatrix() throws Exception {
        call("profileimpact", "POST", null,
                "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":\"styles\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[0].impactclass").value("styles"))
                // decimals come back as strings, like mysqli
                .andExpect(jsonPath("$[0].skillsimpact").value("0.00"))
                // Exp fields are synthetic (appended), so they keep the PHP's camelCase
                .andExpect(jsonPath("$[0].skillsExp").value("Low"))
                .andExpect(jsonPath("$[0].moraleExp").value("Medium"))
                .andExpect(jsonPath("$[0].efficiencyExp").value("High"));
    }

    @Test
    @DisplayName("POST does not re-seed an already-populated matrix")
    void seedsOnlyOnce() throws Exception {
        post("profileimpact", "styles");
        post("profileimpact", "styles");

        Integer rows = jdbc.queryForObject(
                "select count(*) from profileimpact where impactClass = 'styles'",
                Integer.class);
        assertThat(rows).isEqualTo(8);
    }

    @Test
    @DisplayName("POST: falsy mod -> 406 with no body")
    void postWithoutMod() throws Exception {
        call("profileimpact", "POST", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\"}")
                .andExpect(status().isNotAcceptable())
                .andExpect(content().string(""));
        call("profileaffect", "POST", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\"}")
                .andExpect(status().isNotAcceptable());
    }

    @Test
    @DisplayName("POST: query string is the profileImpactKey and collapses to an object")
    void postByKey() throws Exception {
        String json = post("profileimpact", "styles");
        Integer key = JsonPath.read(json, "$[0].profileimpactkey");

        call("profileimpact", "POST", String.valueOf(key),
                "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":\"styles\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isMap())
                .andExpect(jsonPath("$.profileimpactkey").value(key));
    }

    // ---------------------------------------------------------------- PUT

    @Test
    @DisplayName("profileimpact PUT writes the Impact columns only")
    void impactPut() throws Exception {
        String json = post("profileimpact", "styles");
        Integer key = JsonPath.read(json, "$[0].profileimpactkey");

        call("profileimpact", "PUT", null, "{\"restkey\":\"" + KEY + "\","
                + "\"method\":\"PUT\",\"qdata\":{\"profileImpactKey\":" + key + ","
                + "\"skillsImpact\":5,\"moraleImpact\":6,\"efficiencyImpact\":7,"
                + "\"impactMsg\":\"hi\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skillsimpact").value("5.00"))
                .andExpect(jsonPath("$[0].moraleimpact").value("6.00"))
                .andExpect(jsonPath("$[0].impactmsg").value("hi"))
                .andExpect(jsonPath("$[0].skillsExp").value("Low"));

        // the Affect columns are untouched by this module
        assertThat(jdbc.queryForObject("select skillsAffect from profileimpact "
                + "where profileImpactKey = ?", String.class, key)).isEqualTo("0.00");
    }

    @Test
    @DisplayName("profileaffect PUT writes the Affect columns on the SAME rows")
    void affectPut() throws Exception {
        String json = post("profileimpact", "styles");
        Integer key = JsonPath.read(json, "$[0].profileimpactkey");

        call("profileaffect", "PUT", null, "{\"restkey\":\"" + KEY + "\","
                + "\"method\":\"PUT\",\"qdata\":{\"profileImpactKey\":" + key + ","
                + "\"skillsAffect\":1,\"moraleAffect\":2,\"efficiencyAffect\":3,"
                + "\"affectMsg\":\"af\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skillsaffect").value("1.00"))
                .andExpect(jsonPath("$[0].affectmsg").value("af"));

        // Impact columns untouched, and no cross-contamination of the message columns
        assertThat(jdbc.queryForObject("select skillsImpact from profileimpact "
                + "where profileImpactKey = ?", String.class, key)).isEqualTo("0.00");
        assertThat(jdbc.queryForObject("select impactMsg from profileimpact "
                + "where profileImpactKey = ?", String.class, key)).isEmpty();
    }

    @Test
    @DisplayName("PUT with a non-numeric key -> 500, matching the PHP's unquoted interpolation")
    void putBadKey() throws Exception {
        call("profileimpact", "PUT", null, "{\"restkey\":\"" + KEY + "\","
                + "\"method\":\"PUT\",\"qdata\":{\"profileImpactKey\":\"abc\","
                + "\"skillsImpact\":1,\"moraleImpact\":1,\"efficiencyImpact\":1}}")
                .andExpect(status().isInternalServerError());
    }
}
