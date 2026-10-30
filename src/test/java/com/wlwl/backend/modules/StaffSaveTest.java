package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StaffSaveTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void insertReturnsGeneratedRowAndListIncludesAllSavedFields() throws Exception {
        ObjectNode data = staff(-1);
        String photo = "09f7ce5d-c351-4bdb-92b0-cf41b7653fbe.png";
        data.put("staffPhoto", photo);
        JsonNode saved = response(save(data, "/backend/staff/").andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].skillsExp").value("Low"))
                .andExpect(jsonPath("$[0].moraleExp").value("Medium"))
                .andExpect(jsonPath("$[0].efficiencyExp").value("High")));
        assertSavedFields(saved.get(0), data);
        // Read the list independently to verify the database write, not just the save response.
        JsonNode listed = list();
        JsonNode row = findByName(listed, data.get("staffName").asText());
        assertThat(row.get("staffid")).isEqualTo(saved.get(0).get("staffid"));
        assertThat(row.get("staffid").asLong()).isPositive();
        assertSavedFields(row, data);
        assertThat(row.get("staffphoto").asText()).isEqualTo(photo);
        assertThat(row.get("skillsvalue").asInt()).isZero();
        assertThat(row.get("newcomer").asInt()).isZero();
    }

    @Test
    void updatePreservesUneditedColumnsAndReturnsArrayEvenWithQueryString() throws Exception {
        ObjectNode data = staff(2);
        data.put("staffName", "Bob renamed").put("skillsProfile", "P4")
                .put("moraleProfile", "P5").put("efficiencyProfile", "missing")
                .put("yearsWorked", 12).put("experience", 8).put("staffPhoto", "default.png");
        save(data, "/backend/staff/?editor").andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].staffid").value(2))
                .andExpect(jsonPath("$[0].skillsExp").value("High"))
                .andExpect(jsonPath("$[0].moraleExp").value("Low"))
                .andExpect(jsonPath("$[0].efficiencyExp").hasJsonPath())
                .andExpect(jsonPath("$[0].efficiencyExp").isEmpty());
        JsonNode row = findByName(list(), "Bob renamed");
        assertSavedFields(row, data);
        assertThat(row.get("skillsvalue").asInt()).isEqualTo(90);
        assertThat(row.get("moralevalue").asInt()).isEqualTo(70);
        assertThat(row.get("efficiencyvalue").asInt()).isEqualTo(60);
        assertThat(jdbc.queryForObject("select staffName from staff where staffId = 1", String.class))
                .isEqualTo("Alice");
    }

    @Test
    void insertAndUpdateSelectByIdNotNameAndBindSqlParameters() throws Exception {
        ObjectNode first = staff(-1).put("staffName", "O'Brien'); delete from staff; --");
        JsonNode created = response(save(first, "/backend/staff/").andExpect(status().isOk()));
        long firstId = created.get(0).get("staffid").asLong();
        assertThat(created.get(0).get("staffname").asText()).isEqualTo(first.get("staffName").asText());
        ObjectNode second = staff(-1);
        JsonNode other = response(save(second, "/backend/staff/").andExpect(status().isOk()));
        long secondId = other.get(0).get("staffid").asLong();
        assertThat(secondId).isNotEqualTo(firstId);
        first.put("staffId", firstId).put("staffName", "Renamed by ID");
        JsonNode updated = response(save(first, "/backend/staff/").andExpect(status().isOk()));
        assertThat(updated.get(0).get("staffid").asLong()).isEqualTo(firstId);
        assertThat(updated.get(0).get("staffname").asText()).isEqualTo("Renamed by ID");
        assertThat(jdbc.queryForObject("select count(*) from staff where staffId in (1, 2)",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void duplicateNamesInLegacyDataDoNotMakeSaveReadBackTheWrongRow() throws Exception {
        // The production schema normally enforces unique names; the test schema permits
        // duplicates to verify that read-back relies only on the generated primary key.
        ObjectNode data = staff(-1).put("staffName", "Alice");
        JsonNode saved = response(save(data, "/backend/staff/").andExpect(status().isOk()));
        assertThat(saved.size()).isEqualTo(1);
        assertThat(saved.get(0).get("staffid").asLong()).isNotEqualTo(1);
        assertThat(saved.get(0).get("staffdescription").asText())
                .isEqualTo(data.get("staffDescription").asText());
    }

    @Test
    void unchangedUpdateStillReturnsSavedRow() throws Exception {
        ObjectNode data = staff(2);
        save(data, "/backend/staff/").andExpect(status().isOk());
        save(data, "/backend/staff/").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].staffid").value(2));
    }

    @Test
    void nonexistentIdReturns204WithoutInserting() throws Exception {
        int before = jdbc.queryForObject("select count(*) from staff", Integer.class);
        save(staff(999999), "/backend/staff/").andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertThat(jdbc.queryForObject("select count(*) from staff", Integer.class)).isEqualTo(before);
    }

    @Test
    void rejectsMalformedInputWithoutWriting() throws Exception {
        for (String field : new String[]{"staffId", "yearsWorked", "experience"}) {
            ObjectNode data = staff(-1).put(field, "not-an-integer");
            save(data, "/backend/staff/").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").isString());
        }
        save(staff(0), "/backend/staff/").andExpect(status().isBadRequest());
        save(staff(-2), "/backend/staff/").andExpect(status().isBadRequest());
        save(staff(-1).put("yearsWorked", "2147483648"), "/backend/staff/")
                .andExpect(status().isBadRequest());
        mvc.perform(post("/backend/staff/").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"K1\",\"method\":\"PUT\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
        assertThat(jdbc.queryForObject("select count(*) from staff", Integer.class)).isEqualTo(2);
    }

    @Test
    void invalidSessionCannotSaveAndListQueryShapeIsPreserved() throws Exception {
        ObjectNode request = mapper.createObjectNode().put("restkey", "invalid").put("method", "PUT");
        request.set("qdata", staff(-1));
        mvc.perform(post("/backend/staff/").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andExpect(content().string(""));
        mvc.perform(post("/backend/staff/?legacy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"K1\",\"method\":\"POST\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isMap());
        assertThat(jdbc.queryForObject("select count(*) from staff", Integer.class)).isEqualTo(2);
    }

    private ObjectNode staff(long id) {
        return mapper.createObjectNode().put("staffId", id).put("staffName", "New staff")
                .put("staffDescription", "Description 'quoted'")
                .put("staffSelfIntroduction", "Introduction")
                .put("staffInterview", "Interview").put("staffPhoto", "default.png")
                .put("skillsProfile", "P1").put("moraleProfile", "P2")
                .put("efficiencyProfile", "P3").put("yearsWorked", "4").put("experience", "3")
                .put("skillsExp", "ignore").put("moraleExp", "ignore").put("efficiencyExp", "ignore")
                .put("skillsValue", 999).put("newComer", 999);
    }

    private ResultActions save(ObjectNode data, String path) throws Exception {
        ObjectNode request = mapper.createObjectNode().put("restkey", "K1").put("method", "PUT");
        request.set("qdata", data);
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(request)));
    }

    private JsonNode list() throws Exception {
        return response(mvc.perform(post("/backend/staff/").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restkey\":\"K1\",\"method\":\"POST\",\"limit\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray()));
    }

    private JsonNode response(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }

    private JsonNode findByName(JsonNode rows, String name) {
        for (JsonNode row : rows) {
            if (row.get("staffname").asText().equals(name)) return row;
        }
        throw new AssertionError("Saved staff not present in POST list");
    }

    private void assertSavedFields(JsonNode row, ObjectNode data) {
        for (String field : new String[]{"staffName", "staffDescription", "staffSelfIntroduction",
                "staffInterview", "staffPhoto", "skillsProfile", "moraleProfile",
                "efficiencyProfile", "yearsWorked", "experience"}) {
            assertThat(row.get(field.toLowerCase(java.util.Locale.ROOT)).asText())
                    .as(field).isEqualTo(data.get(field).asText());
        }
    }
}
