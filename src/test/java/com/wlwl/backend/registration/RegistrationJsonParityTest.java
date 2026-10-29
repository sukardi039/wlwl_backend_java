package com.wlwl.backend.registration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Locks down {@code GET /backend/registration.json}, the file the Vue login screen
 * fetches in {@code created()} before it does anything else.
 *
 * <p>Two things could silently break this and are asserted here:
 *
 * <ol>
 *   <li><b>The gateway must not swallow the path.</b> {@code BackendGateway} maps
 *       {@code /backend/{module}}, which also matches {@code /backend/registration.json}.
 *       If the gateway won, the answer would be 404 (no such module) and the login page
 *       would lose its company name and footer without any error.</li>
 *   <li><b>All seven keys must exist</b>, because the template reads them unguarded:
 *       {@code {{ida.company}} {{ida.reg}}} and the two anchors/images use
 *       {@code url}, {@code ida}, {@code polreg}, {@code polurl}, {@code pol}.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationJsonParityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("bundled registration.json is served at /backend/registration.json as JSON")
    void servedWithAllSevenKeys() throws Exception {
        mockMvc.perform(get("/backend/registration.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.company").exists())
                .andExpect(jsonPath("$.reg").exists())
                .andExpect(jsonPath("$.url").exists())
                .andExpect(jsonPath("$.ida").exists())
                .andExpect(jsonPath("$.polreg").exists())
                .andExpect(jsonPath("$.polurl").exists())
                .andExpect(jsonPath("$.pol").exists())
                // displayAll() assigns document.title = data.company, so it must not be null
                .andExpect(jsonPath("$.company").isNotEmpty());
    }

    @Test
    @DisplayName("no auth is required: the login page fetches it before anyone signs in")
    void noRestkeyNeeded() throws Exception {
        // The RPC modules answer an empty 200 without a restkey; this file must answer
        // 200 WITH a body, otherwise the footer stays blank until the user logs in.
        mockMvc.perform(get("/backend/registration.json"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("company")));
    }

    @Test
    @DisplayName("unknown modules still 404 — the literal mapping did not open up /backend/**")
    void gatewayStillAnswers404ForUnknownModules() throws Exception {
        mockMvc.perform(post("/backend/no-such-module").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/backend/registration.json.bak"))
                .andExpect(status().isNotFound());
    }
}
