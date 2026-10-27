package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parity tests for drill/post.php — the POST verb ({@code POST /backend/drill/?<userId>}).
 *
 * The query string is the userId (PHP splices $_SERVER['QUERY_STRING'] into the SQL), and
 * the call bootstraps the user's drillcontrol row plus one drill per company run slot.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DrillPostParityTest {

    private static final String KEY = "K1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private MvcResult get(String queryString) throws Exception {
        return mockMvc.perform(post("/backend/drill/?" + queryString)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"" + KEY + "\",\"method\":\"POST\"}"))
                .andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return mapper.readTree(new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    /** A general user with a live company and no drillcontrol row yet. */
    private void freshUser() {
        jdbc.update("insert into backenduser (userId, userName, loginName, level, active, companyName) "
                + "values ('U8', 'Fresh', 'fresh', 0, 1, 'pkg1')");
    }

    @Test
    @DisplayName("first contact creates the control row and one drill per configured run slot")
    void createsOnFirstContact() throws Exception {
        freshUser();

        MvcResult r = get("U8");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        JsonNode body = json(r);
        // the user object is the JOINED backenduser+company row
        assertThat(body.get("user").get("username").asText()).isEqualTo("Fresh");
        assertThat(body.get("user").get("runone").asText()).isEqualTo("M1");     // company's slot
        assertThat(body.get("user").get("companyname").asText()).isEqualTo("Acme"); // company wins

        JsonNode control = body.get("control");
        assertThat(control.get("userid").asText()).isEqualTo("U8");
        String controlId = control.get("drillcontrolid").asText();
        assertThat(controlId).hasSize(13);                    // uniqid()

        // pkg1 only configures runOne, so exactly one drill appears, seq 1, status 100
        assertThat(body.get("runs").size()).isEqualTo(1);
        JsonNode run = body.get("runs").get(0);
        assertThat(run.get("run").asText()).isEqualTo("runOne");
        assertThat(run.get("seq").asInt()).isEqualTo(1);
        assertThat(run.get("drillstatus").asInt()).isEqualTo(100);
        assertThat(run.get("modelname").asText()).isEqualTo("Model One");
        assertThat(run.get("modeltemplate").asText()).isEqualTo("BasicTpl");
        assertThat(run.get("templatename").asText()).isEqualTo("BasicTpl");
        assertThat(run.get("modelproduct").asText()).isEqualTo("W1");

        assertThat(jdbc.queryForObject(
                "select count(*) from drill where drillControlId = ?", Integer.class, controlId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a second call reuses the control row and creates no extra drills")
    void isIdempotent() throws Exception {
        freshUser();
        String first = json(get("U8")).get("control").get("drillcontrolid").asText();

        JsonNode second = json(get("U8"));
        assertThat(second.get("control").get("drillcontrolid").asText()).isEqualTo(first);
        assertThat(second.get("runs").size()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from drill where drillControlId = ?", Integer.class, first))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an existing control is returned as-is, with its drills")
    void existingControl() throws Exception {
        // U1 already owns DC1, which the fixture populates with drill D1
        JsonNode body = json(get("U1"));
        assertThat(body.get("control").get("drillcontrolid").asText()).isEqualTo("DC1");
        assertThat(body.get("runs").size()).isEqualTo(1);
        assertThat(body.get("runs").get(0).get("drillid").asText()).isEqualTo("D1");
    }

    @Test
    @DisplayName("no runs -> the response has no 'runs' key at all, not an empty array")
    void noRunsKey() throws Exception {
        freshUser();
        jdbc.update("insert into drillcontrol (drillControlId, userId) values ('DCX', 'U8')");

        JsonNode body = json(get("U8"));
        assertThat(body.get("control").get("drillcontrolid").asText()).isEqualTo("DCX");
        assertThat(body.has("runs")).isFalse();
    }

    @Test
    @DisplayName("204 for unknown users, instructors, inactive users and dead companies")
    void rejections() throws Exception {
        assertThat(get("nobody").getResponse().getStatus()).isEqualTo(204);       // no such user
        assertThat(get("U2").getResponse().getStatus()).isEqualTo(204);           // level = 1
        assertThat(get("U5").getResponse().getStatus()).isEqualTo(204);           // inactive user
        assertThat(get("U3").getResponse().getStatus()).isEqualTo(204);           // inactive company
        assertThat(get("").getResponse().getStatus()).isEqualTo(204);             // no query string
    }

    @Test
    @DisplayName("PUT still dispatches on mod (POST handler does not shadow it)")
    void putStillWorks() throws Exception {
        mockMvc.perform(post("/backend/drill/").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"K1\",\"method\":\"PUT\",\"mod\":\"BOGUS\"}"))
                .andExpect(status().isOk());
    }
}
