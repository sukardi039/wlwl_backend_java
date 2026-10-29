package com.wlwl.backend.registration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A deployment's own file (equivalent to the gitignored {@code registration.json} the PHP
 * kept in its webroot) must win over the bundled placeholder, byte for byte.
 *
 * <p>The fixture lives in {@code src/test/resources} and reaches this test through
 * {@code target/test-classes}, so the path is a real file on disk — the same code path a
 * production deployment uses.
 */
@SpringBootTest(properties =
        "wlwl.registration.file=target/test-classes/registration-override.json")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationJsonOverrideTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("WLWL_REGISTRATION_FILE wins over the bundled placeholder")
    void externalFileIsAuthoritative() throws Exception {
        mockMvc.perform(get("/backend/registration.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company").value("Override Company"))
                .andExpect(jsonPath("$.reg").value("REG-999"))
                .andExpect(jsonPath("$.ida").value("IDA-123"))
                .andExpect(jsonPath("$.pol").value("Privacy Policy"));
    }

    @Test
    @DisplayName("the file's own key order is preserved (raw bytes, not re-serialised)")
    void byteOrderPreserved() throws Exception {
        mockMvc.perform(get("/backend/registration.json"))
                .andExpect(status().isOk())
                // 'company' is the first key in the fixture; Jackson on a Map or a
                // re-serialisation could reorder it.
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("{\n  \"company\"")));
    }
}
