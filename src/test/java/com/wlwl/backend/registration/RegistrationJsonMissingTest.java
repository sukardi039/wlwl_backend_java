package com.wlwl.backend.registration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A deployment that names a registration file which is not there must fail visibly.
 *
 * <p>Falling back to the bundled placeholder would be worse than a 404: the login page
 * would silently display the wrong company name and registration numbers. The PHP had
 * the same property for a different reason — IIS simply 404'd a file that was not in the
 * webroot, and the client's {@code if (res.status === 200)} skipped {@code displayAll()}.
 */
@SpringBootTest(properties =
        "wlwl.registration.file=/nonexistent/registration.json")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationJsonMissingTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("configured-but-missing file answers 404 instead of the placeholder")
    void missingConfiguredFileIs404() throws Exception {
        mockMvc.perform(get("/backend/registration.json"))
                .andExpect(status().isNotFound());
    }
}
