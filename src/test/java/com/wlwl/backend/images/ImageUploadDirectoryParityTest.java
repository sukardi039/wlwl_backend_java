package com.wlwl.backend.images;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Where images/upload.php puts the file when {@code WLWL_UPLOAD_DIR} is NOT set.
 *
 * The PHP reads {@code params.fileLocation}; this class proves the port does the same,
 * and that a missing directory answers 204 rather than throwing. The second case is a
 * deliberate divergence from the PHP (which would attempt a bare relative write into its
 * own working directory) — see PORTING_NOTES §31.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImageUploadDirectoryParityTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void noFileLocationParam() {
        jdbc.update("delete from params where \"key\" = 'fileLocation'");
        // Auth is "did usedOn change" at second resolution and the tests share one
        // in-memory database, so pin the session to an old value before each test.
        jdbc.update("update restsession set usedOn = '2020-01-01 00:00:00' where restkey = 'K1'");
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile("upFile", "a.png", "image/png", new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("no override and no params.fileLocation -> 204, never a 500")
    void missingDirectoryIs204() throws Exception {
        mvc.perform(multipart("/backend/images/upload.php?K1").file(photo()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("params.fileLocation is used verbatim when no override is set")
    void usesParamsFileLocation() throws Exception {
        Path dir = Files.createTempDirectory("wlwl-param-upload");
        // PHP concatenates the name straight onto the value, so a trailing separator is
        // part of the setting — exactly as production stores it.
        jdbc.update("insert into params (\"key\", \"value\") values ('fileLocation', ?)",
                dir + java.io.File.separator);

        MvcResult result = mvc.perform(multipart("/backend/images/upload.php?K1").file(photo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value(org.hamcrest.Matchers.matchesPattern(
                        "^[0-9a-f]{13}\\.png$")))
                .andReturn();

        String name = result.getResponse().getContentAsString()
                .replaceAll(".*\"fileName\":\"([^\"]+)\".*", "$1");
        assertThat(dir.resolve(name)).exists();
    }

    @Test
    @DisplayName("a directory that cannot be written answers 204 (move_uploaded_file false)")
    void unwritableDirectoryIs204() throws Exception {
        Path dir = Files.createTempDirectory("wlwl-missing-upload").resolve("does-not-exist");
        jdbc.update("insert into params (\"key\", \"value\") values ('fileLocation', ?)",
                dir + java.io.File.separator);

        mvc.perform(multipart("/backend/images/upload.php?K1").file(photo()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("the stored directory really receives bytes, not an empty file")
    void storesBytes() throws Exception {
        Path dir = Files.createTempDirectory("wlwl-bytes-upload");
        jdbc.update("insert into params (\"key\", \"value\") values ('fileLocation', ?)",
                dir + java.io.File.separator);

        MvcResult result = mvc.perform(multipart("/backend/images/upload.php?K1")
                        .file(new MockMultipartFile("upFile", "b.png", "image/png",
                                new byte[]{9, 8, 7, 6})))
                .andExpect(status().isOk())
                .andReturn();

        String name = result.getResponse().getContentAsString()
                .replaceAll(".*\"fileName\":\"([^\"]+)\".*", "$1");
        Path stored = dir.resolve(name);
        assertThat(stored).exists();
        assertThat(Files.readAllBytes(stored)).containsExactly(9, 8, 7, 6);
    }

    @SuppressWarnings("unused")
    private static void cleanup(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) Files.deleteIfExists(p);
        }
    }
}
