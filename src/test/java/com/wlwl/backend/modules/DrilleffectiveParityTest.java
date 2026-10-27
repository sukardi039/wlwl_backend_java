package com.wlwl.backend.modules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parity tests for drilleffective/post.php.
 *
 * Fixture actions (oneInFour, count, positive, name):
 *   Alice D1: salesAction (0, 4, 2)   coachAction (1, 2, 1)
 *   Bob   D2: salesAction (0, 4, 4)   coachAction (1, 0, 0)
 *   Boom  D3: no drilldetail
 *
 * Expected header: userName, coachAction (1-in-4 first), salesAction.
 * Expected detail: Alice, Bob, {userName:Boom}, footer  -> 4 entries, no ranks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DrilleffectiveParityTest {

    private static final String KEY = "K1";

    @Autowired
    private MockMvc mockMvc;

    private ResultActions call(Integer mod, String pkg) throws Exception {
        String body = "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"package\":\"" + pkg + "\""
                + (mod == null ? "" : ",\"mod\":" + mod) + "}";
        return mockMvc.perform(post("/backend/drilleffective/")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("header is data-driven: 1-in-4 actions first, then the rest")
    void header() throws Exception {
        call(null, "pkg1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.length()").value(3))
                .andExpect(jsonPath("$.header[0]").value("userName"))
                .andExpect(jsonPath("$.header[1]").value("coachAction"))
                .andExpect(jsonPath("$.header[2]").value("salesAction"))
                .andExpect(jsonPath("$.index.coachAction").value(true));
    }

    @Test
    @DisplayName("detail = records + APPENDED footer row; there is no rank key at all")
    void recordsAndFooter() throws Exception {
        call(null, "pkg1")
                .andExpect(jsonPath("$.detail.length()").value(4))
                // Alice: 1/2 -> 50, 2/4 -> 50
                .andExpect(jsonPath("$.detail[0].userName").value("Alice"))
                .andExpect(jsonPath("$.detail[0].coachAction").value(50.0))
                .andExpect(jsonPath("$.detail[0].salesAction").value(50.0))
                // Bob: coachAction has count 0 -> EMPTY STRING, not null and not 0
                .andExpect(jsonPath("$.detail[1].userName").value("Bob"))
                .andExpect(jsonPath("$.detail[1].coachAction").value(""))
                .andExpect(jsonPath("$.detail[1].salesAction").value(100.0))
                // ranking is commented out in the PHP
                .andExpect(jsonPath("$.detail[0].rank").doesNotExist())
                // Boom: no detail rows -> blankRecord against a data-driven header
                // yields ONLY userName
                .andExpect(jsonPath("$.detail[2].userName").value("Boom"))
                .andExpect(jsonPath("$.detail[2].coachAction").doesNotExist())
                .andExpect(jsonPath("$.detail[2].salesAction").doesNotExist())
                // footer: company-wide totals, userName is ""
                .andExpect(jsonPath("$.detail[3].userName").value(""))
                .andExpect(jsonPath("$.detail[3].coachAction").value(50.0))   // 1/2
                .andExpect(jsonPath("$.detail[3].salesAction").value(75.0));  // 6/8
    }

    @Test
    @DisplayName("fb exposes the accumulated positive/count totals as objects")
    void fbTotals() throws Exception {
        call(null, "pkg1")
                .andExpect(jsonPath("$.fb.coachAction.positive").value(1))
                .andExpect(jsonPath("$.fb.coachAction.count").value(2))
                .andExpect(jsonPath("$.fb.salesAction.positive").value(6))
                .andExpect(jsonPath("$.fb.salesAction.count").value(8));
    }

    @Test
    @DisplayName("mod > 0 filters by seq, footer still appended")
    void filterBySeq() throws Exception {
        call(1, "pkg1")
                .andExpect(jsonPath("$.detail.length()").value(3));
    }

    @Test
    @DisplayName("company with no drills -> 204")
    void emptyCompany() throws Exception {
        call(null, "pkg2")
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }
}
