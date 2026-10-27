package com.wlwl.backend.modules;

import com.jayway.jsonpath.JsonPath;
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
 * Parity tests for license/, packageremove/, controldrill/, registerdemo/ and
 * consoleuser/ — the last five modules that run against real production tables.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminModulesParityTest {

    private static final String KEY = "K1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ResultActions call(String module, String verb, String query, String body)
            throws Exception {
        String url = "/backend/" + module + "/" + (query == null ? "" : "?" + query);
        var req = switch (verb) {
            case "GET" -> get(url);
            case "PUT" -> put(url);
            default -> post(url);
        };
        return mockMvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String env(String extra) {
        return "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\"" + extra + "}";
    }

    // --------------------------------------------------------------- license

    @Test
    @DisplayName("license: license_view carries batchDesc; mod filters batch OR package")
    void licenseModule() throws Exception {
        call("license", "POST", null, env("")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].batchdesc").value("Batch One"));

        // batchId match -> the three B1 licences
        call("license", "POST", null, env(",\"mod\":\"B1\""))
                .andExpect(jsonPath("$.length()").value(3));

        // packageId match -> pkg1 has L1 and L3 only
        call("license", "POST", null, env(",\"mod\":\"pkg1\""))
                .andExpect(jsonPath("$.length()").value(2));
    }

    // --------------------------------------------------------- packageremove

    @Test
    @DisplayName("packageremove: deletes expired companies and answers 204 unconditionally")
    void packageremove() throws Exception {
        Integer before = jdbc.queryForObject("select count(*) from company", Integer.class);
        assertThat(before).isEqualTo(4);

        call("packageremove", "POST", null, env(""))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // pkg2 expired in 2021; pkg1/pkg3/pkg5 run to 2099
        assertThat(jdbc.queryForObject("select count(*) from company", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "select count(*) from company where packageId = 'pkg2'", Integer.class))
                .isZero();
    }

    // ---------------------------------------------------------- controldrill

    @Test
    @DisplayName("controldrill POST: company row plus the three model-name lookups")
    void controldrillPost() throws Exception {
        call("controldrill", "POST", null, env(",\"package\":\"pkg1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].packageid").value("pkg1"))
                .andExpect(jsonPath("$[0].runOneName").value("Model One"))
                // runTwo is NULL -> the PHP's $model[''] = '' seeding yields ""
                .andExpect(jsonPath("$[0].runTwoName").value(""));
    }

    @Test
    @DisplayName("controldrill PUT: mod selects which activation column is set")
    void controldrillActivation() throws Exception {
        call("controldrill", "PUT", null,
                "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"mod\":1,"
                        + "\"package\":\"pkg1\",\"qdata\":{}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].activeone").value(1));

        assertThat(jdbc.queryForObject(
                "select activeOne from company where packageId = 'pkg1'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("controldrill PUT mod=0 re-pushes commonPW to instructors=0 only")
    void controldrillPasswordReset() throws Exception {
        // give a non-instructor a bespoke password first
        jdbc.update("update backenduser set loginPassword = ? where userId = 'U1'",
                MysqlPassword.encode("bespoke"));
        String instructorBefore = jdbc.queryForObject(
                "select loginPassword from backenduser where userId = 'U6'", String.class);

        call("controldrill", "PUT", null,
                "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"mod\":0,"
                        + "\"package\":\"pkg1\",\"qdata\":{}}")
                .andExpect(status().isOk());

        // instructor = 0 in pkg1: overwritten with PASSWORD(company.commonPW)
        assertThat(jdbc.queryForObject(
                "select loginPassword from backenduser where userId = 'U1'", String.class))
                .isEqualTo(MysqlPassword.encode("123456"));
        // instructor = 1: untouched — this is why the WHERE clause matters
        assertThat(jdbc.queryForObject(
                "select loginPassword from backenduser where userId = 'U6'", String.class))
                .isEqualTo(instructorBefore);
    }

    @Test
    @DisplayName("controldrill PUT: unknown activation slot -> 500 ($act[$mod] undefined)")
    void controldrillBadSlot() throws Exception {
        call("controldrill", "PUT", null,
                "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\",\"mod\":9,"
                        + "\"package\":\"pkg1\",\"qdata\":{}}")
                .andExpect(status().isInternalServerError());
    }

    // ---------------------------------------------------------- registerdemo

    @Test
    @DisplayName("registerdemo: anything but PUT answers 'No method specified!'")
    void registerdemoVerb() throws Exception {
        call("registerdemo", "GET", null, env(""))
                .andExpect(status().isOk())
                .andExpect(content().string("No method specified!"));
        call("registerdemo", "POST", null, env(""))
                .andExpect(status().isOk())
                .andExpect(content().string("No method specified!"));
    }

    @Test
    @DisplayName("registerdemo PUT: instructor registration needs no licence seat")
    void registerdemoInstructor() throws Exception {
        String json = call("registerdemo", "PUT", null, "{\"qdata\":{"
                + "\"userId\":\"\",\"userName\":\"New Teach\",\"loginName\":\"newteach\","
                + "\"loginPassword\":\"pw123\",\"level\":1,\"active\":1,"
                + "\"companyName\":\"pkg1\",\"instructor\":1}}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String id = JsonPath.read(json, "$[0].userid");
        assertThat(id).matches("[0-9a-f]{13}");
        // password stored as the MySQL 4.1 hash, not plaintext
        assertThat(jdbc.queryForObject(
                "select loginPassword from backenduser where userId = ?", String.class, id))
                .isEqualTo(MysqlPassword.encode("pw123"));
    }

    @Test
    @DisplayName("registerdemo PUT: duplicate loginName -> 406, empty body")
    void registerdemoDuplicate() throws Exception {
        call("registerdemo", "PUT", null, "{\"qdata\":{"
                + "\"userName\":\"Dupe\",\"loginName\":\"alice\",\"loginPassword\":\"x\","
                + "\"level\":1,\"companyName\":\"pkg1\",\"instructor\":1}}")
                .andExpect(status().isNotAcceptable())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("registerdemo PUT: level 0 with no available batch -> 405")
    void registerdemoNoSeat() throws Exception {
        // pkg5's agent 'zz' has no licence batch at all
        call("registerdemo", "PUT", null, "{\"qdata\":{"
                + "\"userName\":\"NoSeat\",\"loginName\":\"noseat\",\"loginPassword\":\"x\","
                + "\"level\":0,\"companyName\":\"pkg5\",\"instructor\":0}}")
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("registerdemo PUT: level 0 with seats allocates from the agent's batch")
    void registerdemoAllocatesSeat() throws Exception {
        String json = call("registerdemo", "PUT", null, "{\"qdata\":{"
                + "\"userName\":\"Seated\",\"loginName\":\"seated\",\"loginPassword\":\"x\","
                + "\"level\":0,\"companyName\":\"pkg1\",\"instructor\":0}}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String id = JsonPath.read(json, "$[0].userid");
        // B1 had 10 seats / 3 used, so the seat comes from B1
        assertThat(jdbc.queryForObject(
                "select batchId from license where userId = ?", String.class, id))
                .isEqualTo("B1");
    }

    // ---------------------------------------------------------- consoleuser

    @Test
    @DisplayName("consoleuser: latest step per started drill, content re-encoded in place")
    void consoleuserFeed() throws Exception {
        call("consoleuser", "POST", null, env(",\"package\":\"pkg1\""))
                .andExpect(status().isOk())
                // D3 (seq 2) has NO drilldetail, so it contributes no row at all.
                .andExpect(jsonPath("$.length()").value(2))
                // always an array, even though a query string is absent
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].drillid").value("D1"))
                // content is the decoded+re-encoded JSON blob, not a string
                .andExpect(jsonPath("$[0].content.control.status").value("running"))
                .andExpect(jsonPath("$[0].content.orderStatus.ordersCompleted").value(5))
                // and the aliased drillControlId is present
                .andExpect(jsonPath("$[0].drillcontrolid").value("DC1"));
    }

    @Test
    @DisplayName("consoleuser: mod filters by seq")
    void consoleuserSeqFilter() throws Exception {
        call("consoleuser", "POST", null, env(",\"package\":\"pkg1\",\"mod\":1"))
                .andExpect(jsonPath("$.length()").value(2));

        // seq 2 is D3, which never got a drilldetail row -> nothing to emit -> 204
        call("consoleuser", "POST", null, env(",\"package\":\"pkg1\",\"mod\":2"))
                .andExpect(status().isNoContent());
    }
}
