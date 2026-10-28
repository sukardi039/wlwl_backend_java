package com.wlwl.backend.modules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parity tests for basicinformation/post.php — the startup aggregate.
 *
 * Every assertion here comes from a specific PHP line, and most of them are about
 * STRUCTURE rather than values: in PHP the difference between `$a['x'] = []` and
 * "the key was never set", or between an array literal and an associative one,
 * changes the JSON the client parses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BasicinformationParityTest {

    private static final String CALL =
            "{\"restkey\":\"K1\",\"method\":\"POST\"}";

    @Autowired
    private MockMvc mockMvc;

    private org.springframework.test.web.servlet.ResultActions call() throws Exception {
        return mockMvc.perform(post("/backend/basicinformation/")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CALL));
    }

    @Test
    @DisplayName("whole aggregate is a single 200 JSON object keyed by section")
    void aggregateShape() throws Exception {
        call().andExpect(status().isOk())
                .andExpect(jsonPath("$").isMap())
                .andExpect(jsonPath("$.base_ref").isMap())
                .andExpect(jsonPath("$.base_lst.styles").isArray());
    }

    @Test
    @DisplayName("baseinfo nests value-by-index inside a class level: base_ref[class][displayKey] = name")
    void baseinfoNesting() throws Exception {
        call().andExpect(jsonPath("$.base_ref.styles.AN").value("Analytical"))
                .andExpect(jsonPath("$.base_ref.styles.DR").value("Driver"))
                .andExpect(jsonPath("$.base_lst.styles[0]").value("AN"))
                .andExpect(jsonPath("$.base_lst.styles[1]").value("DR"))
                // reverse map, keyed by display name
                .andExpect(jsonPath("$.base_exp.styles.Driver").value("DR"))
                // detail rows stay row objects
                .andExpect(jsonPath("$.base_det.styles[0].name").value("Analytical"));
    }

    @Test
    @DisplayName("class_exp is FLAT (no class level) while class_ref is nested — easy to get wrong")
    void classExpIsFlat() throws Exception {
        call().andExpect(jsonPath("$.class_exp.Analytical").value("AN"))
                // and nesting it would be a bug:
                .andExpect(jsonPath("$.class_ref.styles.AN").value("Analytical"))
                .andExpect(jsonPath("$.act_ref.analyse.AN").value("Analytical"))
                .andExpect(jsonPath("$.baseclass_lst.baseclass").isArray());
    }

    @Test
    @DisplayName("missing profile class -> JSON null for efficiencyExp (PHP has no isset guard)")
    void staffExpansionNulls() throws Exception {
        // staffId is a bigint in production, so the key is numeric (PHP emits an object
        // because the int keys are not a 0-based sequence).
        call().andExpect(jsonPath("$.staff_det.staff['1'].skillsExp").value("Low"))
                .andExpect(jsonPath("$.staff_det.staff['1'].moraleExp").value("Medium"))
                .andExpect(jsonPath("$.staff_det.staff['1'].efficiencyExp").doesNotExist())
                // the key exists with a null value; JSON null is not "absent"
                .andExpect(jsonPath("$.staff_det.staff['1'].efficiencyExp").hasJsonPath())
                .andExpect(jsonPath("$.staff_ref.staff.Alice").value(1));
    }

    @Test
    @DisplayName("product join overwrites displayKey/name with productShortName; null short names excluded")
    void productOverwrite() throws Exception {
        call().andExpect(jsonPath("$.product_lst.product.length()").value(1))
                .andExpect(jsonPath("$.product_lst.product[0]").value("W1"))
                .andExpect(jsonPath("$.product_ref.product.W1").value("W1"))
                // H2 lowercases unquoted identifiers, so the overwritten column
                // reports as "displaykey" here; production MySQL keeps "displayKey".
                .andExpect(jsonPath("$.product_det.product[0].displaykey").value("W1"));
    }

    @Test
    @DisplayName("template_lst carries two PARALLEL arrays of equal length")
    void templateParallelLists() throws Exception {
        call().andExpect(jsonPath("$.template_lst.template[0]").value("BT"))
                .andExpect(jsonPath("$.template_lst.templateName[0]").value("A basic template"))
                .andExpect(jsonPath("$.template_lst.template.length()").value(1))
                .andExpect(jsonPath("$.template_lst.templateName.length()").value(1));
    }

    @Test
    @DisplayName("action: show>0 splits mainAction vs subAction; show=0 only appears in action_det.action")
    void actionSplitting() throws Exception {
        call().andExpect(jsonPath("$.action_lst.mainAction[0]").value("PL"))
                .andExpect(jsonPath("$.action_lst.subAction.Plan[0]").value("AD"))
                // all three rows, including the hidden one
                .andExpect(jsonPath("$.action_det.action.length()").value(3))
                // typeExp resolved from actiontype
                .andExpect(jsonPath("$.action_det.action[0].typeExp").value("Primary"))
                .andExpect(jsonPath("$.action_det.subAction.Plan[0].typeExp").value("Secondary"));
    }

    @Test
    @DisplayName("actiontype_ref is keyed by description, actiontype_exp by code (opposite directions)")
    void actionTypeBothDirections() throws Exception {
        call().andExpect(jsonPath("$.actionType_ref.actionType.Primary").value(1))
                .andExpect(jsonPath("$.actionType_exp.actionType.1").value("Primary"))
                .andExpect(jsonPath("$.actionType_lst.actionType[0]").value("Primary"));
    }

    @Test
    @DisplayName("modelContent is JSON-decoded and re-embedded as an object, not left as a string")
    void modelContentDecoded() throws Exception {
        call().andExpect(jsonPath("$.model_det.model[0].modelcontent").isMap())
                .andExpect(jsonPath("$.model_det.model[0].modelcontent.nodes[0]").value(1))
                .andExpect(jsonPath("$.model_det.model[0].modelcontent.label").value("x"))
                .andExpect(jsonPath("$.model_ref.model['Model One']").value("M1"));
    }

    @Test
    @DisplayName("params: numeric-looking key is coerced to an int array key")
    void paramsNumericKey() throws Exception {
        call().andExpect(jsonPath("$.params_ref.params.timezone").value("Asia/Jakarta"))
                .andExpect(jsonPath("$.params_ref.params['123']").value("numeric-key"))
                .andExpect(jsonPath("$.bossmessage_ref.msg.WELCOME").value("Welcome aboard"));
    }

    @Test
    @DisplayName("company active<2 only; livepackage_* only when today is inside the window")
    void livePackageFiltering() throws Exception {
        call().andExpect(jsonPath("$.package_lst.package.length()").value(2))
                .andExpect(jsonPath("$.package_lst.package[0]").value("Acme"))
                // pkg3 is active=2 -> excluded
                .andExpect(jsonPath("$.package_ref.package.Deleted").doesNotExist())
                // pkg2 expired -> not live
                .andExpect(jsonPath("$.livepackage_lst.package.length()").value(1))
                .andExpect(jsonPath("$.livepackage_lst.package[0]").value("Acme"))
                // livepackage_exp is never written by the PHP
                .andExpect(jsonPath("$.livepackage_exp").doesNotExist());
    }

    @Test
    @DisplayName("agent license view: same live-window filter, but agent_lst keeps everyone")
    void agentLiveFiltering() throws Exception {
        // agentlicense_view groups per BATCH (a1 has two) and LEFT JOINs (a2 has none),
        // so agent_lst is a plain append list and repeats the agent name per batch.
        call().andExpect(jsonPath("$.agent_lst.agent.length()").value(3))
                // "live" is really just availableTill >= today: the PHP also reads
                // $row['availableFrom'], which agentlicense_view does not expose, so
                // null <= today makes that first half always true. a1 is live (twice,
                // one row per batch); a2 expired in 2021.
                .andExpect(jsonPath("$.liveagent_lst.agent.length()").value(2))
                .andExpect(jsonPath("$.liveagent_lst.agent[0]").value("Acme Agent"))
                .andExpect(jsonPath("$.agent_ref.agent['Acme Agent']").value("a1"));
    }

    @Test
    @DisplayName("task_eff nests the whole ROW two levels deep")
    void taskEffNestsRow() throws Exception {
        // The row is echoed verbatim by `select *`, so this is also a check that the
        // fixture's columns match production: `target` exists, `efficiency` does not.
        call().andExpect(jsonPath("$.task_eff.W1.Inspect.target").value("12.50"))
                .andExpect(jsonPath("$.task_eff.W1.Inspect.taskname").value("Inspect"));
    }

    @Test
    @DisplayName("no get.php for this module -> GET returns empty 200, like the PHP include warning")
    void getVerbIsEmpty() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/backend/basicinformation/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"K1\",\"method\":\"GET\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    /**
     * Not an assertion — writes the whole payload to target/basicinformation-sample.json
     * so it can be diffed against a response captured from the PHP app. This is the
     * first artefact the golden-master harness needs.
     */
    @Test
    @DisplayName("dump sample payload for golden-master comparison")
    void dumpSamplePayload() throws Exception {
        String body = call().andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("target", "basicinformation-sample.json"), body);
        org.assertj.core.api.Assertions.assertThat(body).contains("\"base_ref\"");
    }
}
