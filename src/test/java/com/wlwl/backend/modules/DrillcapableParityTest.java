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
 * Parity tests for drillcapable/post.php.
 *
 * Fixture: Alice totalCompetency 300 (D1 seq1), Bob 250 (D2 seq1),
 * Boom has a drill with no detail -> blank record that does not bump $count.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DrillcapableParityTest {

    private static final String KEY = "K1";

    @Autowired
    private MockMvc mockMvc;

    private ResultActions call(Integer mod, String pkg) throws Exception {
        String body = "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"package\":\"" + pkg + "\""
                + (mod == null ? "" : ",\"mod\":" + mod) + "}";
        return mockMvc.perform(post("/backend/drillcapable/")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("fixed 9-column header and index flags")
    void header() throws Exception {
        call(null, "pkg1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.length()").value(9))
                .andExpect(jsonPath("$.header[0]").value("userName"))
                .andExpect(jsonPath("$.header[8]").value("totalCompetency"))
                .andExpect(jsonPath("$.index.totalCompetency").value(true));
    }

    @Test
    @DisplayName("ranked by totalCompetency DESC; blank record keeps rank 0")
    void ranking() throws Exception {
        call(null, "pkg1")
                .andExpect(jsonPath("$.detail.length()").value(3))
                .andExpect(jsonPath("$.detail[0].userName").value("Alice"))
                .andExpect(jsonPath("$.detail[0].totalCompetency").value(300))
                .andExpect(jsonPath("$.detail[0].rank").value(1))
                .andExpect(jsonPath("$.detail[1].userName").value("Bob"))
                .andExpect(jsonPath("$.detail[1].totalCompetency").value(250))
                .andExpect(jsonPath("$.detail[1].rank").value(2))
                // Boom: no drilldetail -> blank record, never re-ranked
                .andExpect(jsonPath("$.detail[2].userName").value("Boom"))
                .andExpect(jsonPath("$.detail[2].rank").value(0))
                .andExpect(jsonPath("$.detail[2].status").value("notStarted"))
                .andExpect(jsonPath("$.detail[2].totalCompetency").value(0));
    }

    @Test
    @DisplayName("all six competency fields come from content.competency")
    void competencyFields() throws Exception {
        call(null, "pkg1")
                .andExpect(jsonPath("$.detail[0].status").value("running"))
                .andExpect(jsonPath("$.detail[0].skillsCompetency").value(80))
                .andExpect(jsonPath("$.detail[0].moraleCompetency").value(70))
                .andExpect(jsonPath("$.detail[0].efficiencyCompetency").value(60))
                .andExpect(jsonPath("$.detail[0].leaderShipCompetency").value(50))
                .andExpect(jsonPath("$.detail[0].driveCompetency").value(40));
    }

    @Test
    @DisplayName("mod > 0 filters by seq")
    void filterBySeq() throws Exception {
        call(1, "pkg1")
                .andExpect(jsonPath("$.detail.length()").value(2));
    }

    @Test
    @DisplayName("seq whose only drill has no detail -> 204")
    void blankOnlyIs204() throws Exception {
        call(2, "pkg1")
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("company with no drills -> 204")
    void emptyCompany() throws Exception {
        call(null, "pkg2").andExpect(status().isNoContent());
    }
}
