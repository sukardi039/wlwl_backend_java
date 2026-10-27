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
 * Parity tests for drilloverview/post.php.
 *
 * Fixture (pkg1):
 *   Alice  D1 seq1  latest step 2: sales 500, skills 70 vs 50, day 3/10
 *   Bob    D2 seq1  latest step 1: sales 900, skills 90 vs 50, day 2/8
 *   Boom   D3 seq2  NO drilldetail  -> blank record, and does not bump $count
 *
 * D1 also carries an older step 1 with sales 0, to prove only max(stepNumber) is read.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DrilloverviewParityTest {

    private static final String KEY = "K1";

    @Autowired
    private MockMvc mockMvc;

    private ResultActions call(Integer mod, String pkg) throws Exception {
        String body = "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"package\":\"" + pkg + "\""
                + (mod == null ? "" : ",\"mod\":" + mod) + "}";
        return mockMvc.perform(post("/backend/drilloverview/")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("header: 17 columns in the PHP's order, and index flags all of them")
    void headerAndIndex() throws Exception {
        call(null, "pkg1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header").isArray())
                .andExpect(jsonPath("$.header.length()").value(17))
                .andExpect(jsonPath("$.header[0]").value("userName"))
                .andExpect(jsonPath("$.header[16]").value("teamEffImpPct"))
                .andExpect(jsonPath("$.index.userName").value(true))
                .andExpect(jsonPath("$.index.teamEffImpPct").value(true));
    }

    @Test
    @DisplayName("detail: sorted by sales DESC, ranks 1..n assigned; blank record keeps rank 0")
    void ranking() throws Exception {
        call(null, "pkg1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail").isArray())
                .andExpect(jsonPath("$.detail.length()").value(3))
                // Bob has 900 sales -> rank 1; Alice 500 -> rank 2
                .andExpect(jsonPath("$.detail[0].userName").value("Bob"))
                .andExpect(jsonPath("$.detail[0].rank").value(1))
                .andExpect(jsonPath("$.detail[0].sales").value(900))
                .andExpect(jsonPath("$.detail[1].userName").value("Alice"))
                .andExpect(jsonPath("$.detail[1].rank").value(2))
                .andExpect(jsonPath("$.detail[1].sales").value(500))
                // Boom's drill has no drilldetail: blankRecord leaves rank 0 and
                // never triggers a re-rank.
                .andExpect(jsonPath("$.detail[2].userName").value("Boom"))
                .andExpect(jsonPath("$.detail[2].rank").value(0))
                .andExpect(jsonPath("$.detail[2].status").value("notStarted"));
    }

    @Test
    @DisplayName("derived fields: daysLeft, convert, deltas and 2-dp percentages")
    void derivedFields() throws Exception {
        call(null, "pkg1")
                // Alice, latest step only (step 2), NOT the older step 1
                .andExpect(jsonPath("$.detail[1].day").value(3))
                .andExpect(jsonPath("$.detail[1].daysLeft").value(7))
                .andExpect(jsonPath("$.detail[1].timeLeft").value(120))
                .andExpect(jsonPath("$.detail[1].convert").value(5))
                .andExpect(jsonPath("$.detail[1].status").value("running"))
                .andExpect(jsonPath("$.detail[1].teamSkill").value(70))
                .andExpect(jsonPath("$.detail[1].teamSkillImp").value(20))
                .andExpect(jsonPath("$.detail[1].teamSkillImpPct").value(40.0))
                .andExpect(jsonPath("$.detail[1].teamMoraleImp").value(10))
                .andExpect(jsonPath("$.detail[1].teamMoraleImpPct").value(20.0))
                .andExpect(jsonPath("$.detail[1].teamEffImp").value(10))
                .andExpect(jsonPath("$.detail[1].teamEffImpPct").value(25.0))
                // Bob: 8 - 2 days left, 40/50 -> 80%
                .andExpect(jsonPath("$.detail[0].daysLeft").value(6))
                .andExpect(jsonPath("$.detail[0].teamSkillImpPct").value(80.0));
    }

    @Test
    @DisplayName("mod > 0 filters by seq")
    void filterBySeq() throws Exception {
        call(1, "pkg1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail.length()").value(2));
    }

    @Test
    @DisplayName("a seq whose only drill has no detail -> count stays 0 -> 204 (record discarded)")
    void blankOnlyIs204() throws Exception {
        call(2, "pkg1")
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("company with no drills -> 204")
    void emptyCompany() throws Exception {
        call(null, "pkg2")
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("inactive users are excluded by backenduser.active = 1")
    void excludesInactiveUsers() throws Exception {
        // U5 (Erin) is active = 0; give her a drill and confirm she never appears.
        // Not asserting counts of a shared table here — just that the join filter holds.
        call(null, "pkg1")
                .andExpect(jsonPath("$.detail.length()").value(3));
    }
}
