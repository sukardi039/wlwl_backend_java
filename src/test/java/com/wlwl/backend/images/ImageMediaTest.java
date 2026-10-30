package com.wlwl.backend.images;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImageMediaTest {

    private static final Path DIRECTORY = temporaryDirectory();
    private static final String ENDPOINT = "/backend/media/images";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("wlwl.upload.dir", DIRECTORY::toString);
    }

    @BeforeEach
    void resetSession() {
        jdbc.update("update restsession set usedOn = '2020-01-01 00:00:00', deletedOn = null "
                + "where restkey = 'K1'");
    }

    @AfterAll
    static void cleanup() throws IOException {
        try (var files = Files.list(DIRECTORY)) {
            for (Path file : files.toList()) Files.delete(file);
        }
        Files.delete(DIRECTORY);
    }

    @Test
    void uploadAndPublicRetrievalSurviveServiceRecreation() throws Exception {
        byte[] source = image("png");
        JsonNode uploaded = upload(source, "client-name.exe", "application/octet-stream");
        String id = uploaded.get("id").asText();
        assertThat(id).matches("[0-9a-f-]{36}\\.png");
        assertThat(uploaded.get("fileName").asText()).isEqualTo(id);
        assertThat(uploaded.get("contentType").asText()).isEqualTo("image/png");
        assertThat(uploaded.get("width").asInt()).isEqualTo(3);
        assertThat(uploaded.get("height").asInt()).isEqualTo(2);
        assertThat(uploaded.get("url").asText()).isEqualTo(ENDPOINT + "/" + id);
        byte[] stored = Files.readAllBytes(DIRECTORY.resolve(id));
        assertThat(uploaded.get("size").asLong()).isEqualTo(stored.length);
        assertThat(ImageIO.read(DIRECTORY.resolve(id).toFile()).getWidth()).isEqualTo(3);
        mvc.perform(get(uploaded.get("url").asText()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(stored))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
        var recreated = new ImageMediaService(jdbc, DIRECTORY.toString());
        assertThat(recreated.load(id).resource().getContentAsByteArray()).isEqualTo(stored);
    }

    @Test
    void acceptsJpegAndDoesNotTrustClientExtension() throws Exception {
        JsonNode uploaded = upload(image("jpeg"), "wrong.png", "image/png");
        assertThat(uploaded.get("id").asText()).endsWith(".jpg");
        mvc.perform(get(uploaded.get("url").asText()))
                .andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
    }

    @Test
    void repeatedUploadsDoNotDependOnChangedTimestamp() throws Exception {
        String first = upload(image("png"), "a.png", "image/png").get("id").asText();
        String second = upload(image("png"), "a.png", "image/png").get("id").asText();
        assertThat(first).isNotEqualTo(second);
        assertThat(DIRECTORY.resolve(first)).exists();
        assertThat(DIRECTORY.resolve(second)).exists();
    }

    @Test
    void invalidMissingAndDeletedSessionsAreUnauthorized() throws Exception {
        for (String token : new String[]{"", "Bearer invalid", "Basic K1", "Bearer "}) {
            mvc.perform(multipart(ENDPOINT).file(photo(image("png")))
                            .header("Authorization", token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        mvc.perform(multipart(ENDPOINT).file(photo(image("png"))))
                .andExpect(status().isUnauthorized());
        jdbc.update("update restsession set deletedOn = '2026-01-01' where restkey = 'K1'");
        mvc.perform(multipart(ENDPOINT).file(photo(image("png")))
                        .header("Authorization", "Bearer K1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingAndEmptyFileAreBadRequests() throws Exception {
        mvc.perform(multipart(ENDPOINT).header("Authorization", "Bearer K1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(multipart(ENDPOINT).file(photo(new byte[0]))
                        .header("Authorization", "Bearer K1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsFakeImagesAndUnsupportedFormats() throws Exception {
        for (byte[] bytes : new byte[][]{"<svg/>".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                image("gif")}) {
            mvc.perform(multipart(ENDPOINT).file(photo(bytes))
                            .header("Authorization", "Bearer K1"))
                    .andExpect(status().isUnsupportedMediaType());
        }
    }

    @Test
    void enforcesExactByteLimitAndStripsTrailingContent() throws Exception {
        byte[] source = image("png");
        JsonNode uploaded = upload(Arrays.copyOf(source, (int) ImageMediaService.MAX_BYTES),
                "padded.png", "image/png");
        assertThat(uploaded.get("size").asLong()).isLessThan(ImageMediaService.MAX_BYTES);
        mvc.perform(multipart(ENDPOINT)
                        .file(photo(new byte[(int) ImageMediaService.MAX_BYTES + 1]))
                        .header("Authorization", "Bearer K1"))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void rejectsExcessiveDimensionsBeforeDecoding() throws Exception {
        for (int[] dimensions : new int[][]{{8193, 1}, {1, 8193}, {4000, 4001}}) {
            byte[] png = image("png");
            ByteBuffer.wrap(png).putInt(16, dimensions[0]).putInt(20, dimensions[1]);
            CRC32 crc = new CRC32();
            crc.update(png, 12, 17);
            ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
            mvc.perform(multipart(ENDPOINT).file(photo(png))
                            .header("Authorization", "Bearer K1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(
                            "Image must be at most 8192 pixels per side and 16000000 pixels total"));
        }
    }

    @Test
    void acceptsInclusiveDimensionAndPixelLimits() throws Exception {
        for (int[] dimensions : new int[][]{{8192, 1}, {4000, 4000}}) {
            var output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(dimensions[0], dimensions[1],
                    BufferedImage.TYPE_INT_RGB), "png", output);
            JsonNode uploaded = upload(output.toByteArray(), "boundary.png", "image/png");
            assertThat(uploaded.get("width").asInt()).isEqualTo(dimensions[0]);
            assertThat(uploaded.get("height").asInt()).isEqualTo(dimensions[1]);
        }
    }

    @Test
    void malformedImageIsBadRequest() throws Exception {
        byte[] truncated = Arrays.copyOf(image("png"), 33);
        mvc.perform(multipart(ENDPOINT).file(photo(truncated))
                        .header("Authorization", "Bearer K1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void servesExistingPhpFilenamesWithoutChangingStaffPayload() throws Exception {
        Files.write(DIRECTORY.resolve("abcdef1234567.JPG"), image("jpeg"));
        Files.write(DIRECTORY.resolve("abcdef1234568.gif"), image("gif"));
        mvc.perform(get(ENDPOINT + "/abcdef1234567.JPG"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("Cache-Control", "max-age=3600, public"));
        mvc.perform(get(ENDPOINT + "/abcdef1234568.gif"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/gif"));
    }

    @Test
    void missingUnsafeAndNonImageFilesAreNotServed() throws Exception {
        Files.writeString(DIRECTORY.resolve("abcdef1234569.png"), "<svg/>");
        for (String id : new String[]{"missing.png", "abcdef1234567.php", "..",
                "abcdef1234569.png", "00000000-0000-0000-0000-000000000000.png"}) {
            mvc.perform(get(ENDPOINT + "/" + id))
                    .andExpect(status().isNotFound());
        }
    }

    private JsonNode upload(byte[] bytes, String name, String contentType) throws Exception {
        var result = mvc.perform(multipart(ENDPOINT)
                        .file(new MockMultipartFile("file", name, contentType, bytes))
                        .header("Authorization", "Bearer K1"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        JsonNode response = mapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(response.get("url").asText());
        return response;
    }

    private static MockMultipartFile photo(byte[] bytes) {
        return new MockMultipartFile("file", "photo.png", "image/png", bytes);
    }

    private static byte[] image(String format) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), format, output);
        return output.toByteArray();
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("wlwl-media-test-");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
