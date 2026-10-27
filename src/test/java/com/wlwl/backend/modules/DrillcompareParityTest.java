package com.wlwl.backend.modules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parity tests for drillcompare/.
 *
 * Fixture: pkg1 has one trainee (U1/Alice, instructor = 0) with a single drill at seq 1.
 * U2/Bob is instructor = 1 and must therefore be excluded; U6 is also instructor = 1.
 * pkg1 has no seq 3 and no seq 2 drill for a trainee, so the end-ladder falls all the way
 * back to seq 1 — meaning startEff == endEff. That is the interesting case: the fallback.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DrillcompareParityTest {

    private static final String KEY = "K1";

    @Autowired
    private MockMvc mockMvc;

    private org.springframework.test.web.servlet.ResultActions call(String pkg) throws Exception {
        return mockMvc.perform(post("/backend/drillcompare/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"restkey\":\"" + KEY + "\",\"method\":\"POST\","
                        + "\"package\":\"" + pkg + "\"}"));
    }

    @Test
    @DisplayName("drillcompare: trainees only, seq1 vs fallback seq1, exactly three fields")
    void comparesStartAndEnd() throws Exception {
        call("pkg1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                // Bob (instructor = 1) and Boom (instructor = 1) are excluded
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userName").value("Alice"))
                .andExpect(jsonPath("$[0].startEff").isNumber())
                // 3 -> 2 -> 1 ladder: no seq 3 / seq 2 trainee drill exists, so endEff
                // comes from the same seq-1 drill
                .andExpect(jsonPath("$[0].endEff").isNumber())
                .andExpect(jsonPath("$[0].length()").value(3));
    }

    @Test
    @DisplayName("drillcompare start and end agree when only one drill exists")
    void singleDrillStartEqualsEnd() throws Exception {
        String body = call("pkg1").andReturn().getResponse().getContentAsString();
        String start = body.replaceAll(".*\"startEff\":([^,]+),.*", "$1");
        String end = body.replaceAll(".*\"endEff\":([^,}]+).*", "$1");
        org.assertj.core.api.Assertions.assertThat(end.trim()).isEqualTo(start.trim());
    }

    @Test
    @DisplayName("drillcompare: no seq-1 trainee drills for the package -> 204")
    void emptyPackageIs204() throws Exception {
        call("nosuchpackage").andExpect(status().isNoContent());
    }
}
