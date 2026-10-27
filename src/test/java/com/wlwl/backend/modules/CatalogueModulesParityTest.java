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
 * Parity tests for the eight catalogue modules, which share one shape:
 * list -> array/object by query string -> 204 when empty, and PUT = upsert + re-read.
 *
 * @Transactional so inserts/updates roll back; several of these assert exact counts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CatalogueModulesParityTest {

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

    private ResultActions list(String module) throws Exception {
        return call(module, "POST", null,
                "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\"}");
    }

    // ------------------------------------------------------- baseinfo-backed

    @Test
    @DisplayName("environmental: lists baseinfo 'environment' rows left-joined to environment")
    void environmentalList() throws Exception {
        list("environmental")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].baseclass").value("environment"))
                .andExpect(jsonPath("$[0].name").value("Offshore"))
                .andExpect(jsonPath("$[0].environmentname").value("Offshore"));
    }

    @Test
    @DisplayName("environmental PUT updates in place and re-reads by the baseinfo name")
    void environmentalUpdate() throws Exception {
        call("environmental", "PUT", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                // BOTH keys are needed: the UPDATE matches on environmentName, but the
                // re-read uses qdata.name (and answers 204 without it).
                + "\"qdata\":{\"name\":\"Offshore\",\"environmentName\":\"Offshore\","
                + "\"environmentDescription\":\"Rough seas\",\"orderImpact\":3,"
                + "\"moraleImpact\":-2,\"duration\":1.5,\"applyAll\":1,\"randomOne\":0,"
                + "\"lowMorale\":1,\"lowEfficiency\":1,\"highEfficiency\":0}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].environmentdescription").value("Rough seas"))
                .andExpect(jsonPath("$[0].orderimpact").value(3))
                .andExpect(jsonPath("$[0].moraleimpact").value(-2));
    }

    @Test
    @DisplayName("environmental PUT insert only comes back if baseinfo has the name (INNER join re-read)")
    void environmentalInsertNeedsBaseinfo() throws Exception {
        jdbc.update("insert into baseinfo (baseclass, name, displayKey) "
                + "values ('environment', 'Forest', 'FD')");

        call("environmental", "PUT", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                + "\"qdata\":{\"name\":\"Forest\",\"environmentDescription\":\"Woods\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].environmentname").value("Forest"));
    }

    @Test
    @DisplayName("template: lists the baseinfo 'template' rows")
    void templateList() throws Exception {
        list("template")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].templatename").value("BasicTpl"))
                .andExpect(jsonPath("$[0].numberofstaff").value(2));
    }

    @Test
    @DisplayName("product: excludes rows whose productShortName is empty")
    void productList() throws Exception {
        // Ghost exists in baseinfo but has productShortName NULL -> excluded by the
        // `productShortName <> ''` predicate (NULL comparisons are never true).
        list("product")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productshortname").value("W1"));
    }

    @Test
    @DisplayName("style: baseclass 'styles' is shared with the driver-style profile rows")
    void styleList() throws Exception {
        // Analytical + Driver (no style half) + Formal (has one) = 3 rows
        list("style")
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].stylename").value("Formal"));
    }

    @Test
    @DisplayName("task: lists baseinfo 'tasks'")
    void taskList() throws Exception {
        list("task")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].taskname").value("Inspect"));
    }

    // ------------------------------------------------------- table-backed

    @Test
    @DisplayName("agent: lists agentlicense_view, one row per agent with a licence")
    void agentList() throws Exception {
        list("agent")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].agentname").value("Acme Agent"));
    }

    @Test
    @DisplayName("agent PUT updates the agent and re-reads the view")
    void agentUpdate() throws Exception {
        call("agent", "PUT", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                + "\"qdata\":{\"agentId\":\"a1\",\"agentName\":\"Acme Renamed\","
                // a real date: '' cannot be stored in a DATE column under strict mode,
                // which is a 500 both here and in the PHP
                + "\"availableTill\":\"2027-01-01\",\"active\":1}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].agentname").value("Acme Renamed"));
    }

    @Test
    @DisplayName("licensebatch: mod filters on agentId; PUT always inserts, never updates")
    void licensebatch() throws Exception {
        call("licensebatch", "POST", null,
                "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":\"a1\"}")
                .andExpect(jsonPath("$.length()").value(2));

        call("licensebatch", "PUT", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                + "\"qdata\":{\"agentId\":\"a1\",\"batchDesc\":\"New batch\","
                + "\"issueDate\":\"2026-02-01\",\"availableTill\":\"2027-02-01\","
                + "\"licenseVolume\":25}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        Integer rows = jdbc.queryForObject(
                "select count(*) from licensebatch where agentId = 'a1'", Integer.class);
        assertThat(rows).isEqualTo(3);
    }

    @Test
    @DisplayName("package: lists every company; PUT updates by packageId")
    void packageModule() throws Exception {
        list("package")
                .andExpect(jsonPath("$.length()").value(4));

        call("package", "PUT", null, "{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                + "\"qdata\":{\"packageId\":\"pkg1\",\"companyName\":\"Acme Renamed\","
                + "\"agentId\":\"a1\",\"runOne\":\"M1\",\"runTwo\":\"\",\"runThree\":\"\","
                + "\"availableFrom\":\"2026-01-01\",\"availableTill\":\"2099-12-31\","
                + "\"commonPW\":\"s3cret\",\"lang\":\"en\",\"companyType\":\"direct\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].companyname").value("Acme Renamed"))
                // PUT is how commonPW (the shared company password) is set
                .andExpect(jsonPath("$[0].commonpw").value("s3cret"))
                .andExpect(jsonPath("$[0].runone").value("M1"));
    }

    @Test
    @DisplayName("limit is applied by the base class")
    void limit() throws Exception {
        call("package", "POST", null,
                "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"limit\":2}")
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("empty result -> 204")
    void emptyIs204() throws Exception {
        call("licensebatch", "POST", null,
                "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":\"nobody\"}")
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }
}
