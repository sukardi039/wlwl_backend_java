package com.wlwl.backend.rpc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Locks down the RPC contract that the Vue client depends on.
 *
 * Each test here corresponds to one behaviour discovered by reading the PHP, and
 * each one is something a "clean up the API" refactor would quietly break.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendGatewayParityTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String MOD_STYLES = "{\"restkey\":\"K1\",\"method\":\"POST\",\"mod\":\"styles\"}";

    @Test
    @DisplayName("no query string -> response is an ARRAY of rows, with synthetic 'key' column")
    void arrayShapeWhenNoQueryString() throws Exception {
        mockMvc.perform(post("/backend/baseinfo/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MOD_STYLES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].key").value("Analytical"))
                // H2 lowercases unquoted identifiers; MySQL preserves them.
                // The shape/type behaviour under test is unaffected.
                .andExpect(jsonPath("$[0].displaykey").value("AN"));
    }

    @Test
    @DisplayName("query string present -> response collapses to a single OBJECT (last row wins)")
    void objectShapeWhenQueryStringPresent() throws Exception {
        mockMvc.perform(post("/backend/baseinfo/?X")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MOD_STYLES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isMap())
                .andExpect(jsonPath("$.key").value("Driver"));
    }

    @Test
    @DisplayName("empty result set -> 204 No Content, not 200 with []")
    void emptyResultIs204() throws Exception {
        mockMvc.perform(post("/backend/baseinfo/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"K1\",\"method\":\"POST\",\"mod\":\"no-such-class\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("invalid restkey -> EMPTY 200 (the PHP's affected-rows auth gate)")
    void invalidRestkeyIsEmpty200() throws Exception {
        mockMvc.perform(post("/backend/baseinfo/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"WRONG\",\"method\":\"POST\",\"mod\":\"styles\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("missing restkey -> empty 200, identical to an invalid one")
    void missingRestkeyIsEmpty200() throws Exception {
        mockMvc.perform(post("/backend/baseinfo/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"POST\",\"mod\":\"styles\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("unknown module path -> 404 (as IIS does for a missing directory)")
    void unknownModuleIs404() throws Exception {
        mockMvc.perform(get("/backend/no_such_module/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MOD_STYLES))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("verb in the JSON body overrides the HTTP verb")
    void bodyVerbOverridesHttpVerb() throws Exception {
        // Sent as HTTP GET, but the envelope says POST -> the POST branch runs.
        mockMvc.perform(get("/backend/baseinfo/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MOD_STYLES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("module without a get.php returns empty 200 rather than an error")
    void missingVerbFileIsEmpty200() throws Exception {
        mockMvc.perform(post("/backend/baseinfo/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"K1\",\"method\":\"DELETE\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }
}
