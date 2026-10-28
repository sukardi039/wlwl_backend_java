package com.wlwl.backend.images;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parity tests for images/upload.php — the staff-photo upload.
 *
 * The behaviours under test are the ones that make this endpoint unlike every other
 * module: auth from the RAW query string, a second-resolution {@code now()} that
 * silently refuses a second upload in the same second, {@code uniqid() + '.' + ext}
 * naming, and 204 (never 500) when the file cannot be stored.
 *
 * the fixture's restsession row is {@code ('s1','u1','K1',...)} (§21), so K1 is a live
 * key and anything else is not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImageUploadParityTest {

    /** uniqid() is 13 lowercase hex chars; the extension keeps the client's case. */
    private static final String NAME_PATTERN = "^[0-9a-f]{13}\\.JPG$";

    static final Path UPLOAD_DIR;

    static {
        try {
            UPLOAD_DIR = Files.createTempDirectory("wlwl-upload-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void uploadDir(DynamicPropertyRegistry registry) {
        registry.add("wlwl.upload.dir", () -> UPLOAD_DIR.toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void resetSession() {
        // The tests share one in-memory database and the endpoint's auth is a
        // second-resolution "did usedOn change" check, so one test's upload can silence
        // the next one. Start each test from an old timestamp: the touch then always
        // changes a row and auth succeeds. The same-second quirk is exercised explicitly
        // by sameSecondSecondUploadIsRefused.
        jdbc.update("update restsession set usedOn = '2020-01-01 00:00:00' where restkey = 'K1'");
    }

    private static MockMultipartFile photo(String clientName) {
        return new MockMultipartFile("upFile", clientName, "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3});
    }

    private static long stored() throws IOException {
        try (Stream<Path> files = Files.list(UPLOAD_DIR)) {
            return files.count();
        }
    }

    @Test
    @DisplayName("valid key: stores uniqid()+'.'+ext, never the client's filename")
    void storesWithUniqidAndExtension() throws Exception {
        String body = mvc.perform(multipart("/backend/images/upload.php?K1").file(photo("holiday.JPG")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value(org.hamcrest.Matchers.matchesPattern(NAME_PATTERN)))
                .andReturn().getResponse().getContentAsString();

        String name = body.replaceAll(".*\"fileName\":\"([^\"]+)\".*", "$1");
        assertThat(name).doesNotContain("holiday");        // client name is discarded
        assertThat(UPLOAD_DIR.resolve(name)).exists();
    }

    @Test
    @DisplayName("second upload in the SAME SECOND is silently refused (changed-rows quirk)")
    void sameSecondSecondUploadIsRefused() throws Exception {
        long before = stored();

        // Pre-set usedOn to the value the controller is about to write: now() has
        // one-second resolution, so the UPDATE changes nothing and mysqli_affected_rows
        // would be 0. Deterministic version of "two uploads within one second".
        jdbc.update("update restsession set usedOn = ? where restkey = 'K1'", nowString());

        MvcResult result = mvc.perform(multipart("/backend/images/upload.php?K1").file(photo("b.jpg")))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(stored()).isEqualTo(before);            // nothing was written
    }

    @Test
    @DisplayName("unknown restkey: empty 200 and no file, like the PHP's skipped body")
    void unknownKeyWritesNothing() throws Exception {
        long before = stored();

        MvcResult result = mvc.perform(multipart("/backend/images/upload.php?NOT-A-KEY")
                        .file(photo("x.jpg")))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(stored()).isEqualTo(before);
    }

    @Test
    @DisplayName("no restkey at all: empty 200")
    void noKeyWritesNothing() throws Exception {
        MvcResult result = mvc.perform(multipart("/backend/images/upload.php").file(photo("x.jpg")))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("valid key but no upFile part: empty 200 (is_uploaded_file false)")
    void missingFilePart() throws Exception {
        MvcResult result = mvc.perform(multipart("/backend/images/upload.php?K1")
                        .param("other", "value"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("RPC path /backend/images/ is an empty 200, not a 404")
    void moduleIsRegistered() throws Exception {
        MvcResult result = mvc.perform(get("/backend/images/"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("extension edge cases, PHP pathinfo semantics")
    void extensionRules() {
        assertThat(ImageUploadController.extensionOf("photo.JPG")).isEqualTo("JPG");
        assertThat(ImageUploadController.extensionOf("archive.tar.gz")).isEqualTo("gz");
        assertThat(ImageUploadController.extensionOf("noext")).isEmpty();
        assertThat(ImageUploadController.extensionOf(".hidden")).isEqualTo("hidden");
        assertThat(ImageUploadController.extensionOf("C:\\pics\\a.png")).isEqualTo("png");
        assertThat(ImageUploadController.extensionOf(null)).isEmpty();
    }

    private String nowString() {
        java.sql.Timestamp ts = jdbc.queryForObject("select now()", java.sql.Timestamp.class);
        return ts.toLocalDateTime()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
