package com.wlwl.backend.images;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageMediaStorageTest {

    @TempDir Path directory;

    @Test
    void createsDirectoryAndJoinsDatabasePathWithoutTrailingSeparator() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Path root = directory.resolve("new-directory");
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of(root.toString()));
        var images = new ImageMediaService(jdbc, "");
        var stored = images.store(photo());
        assertThat(root.resolve(stored.id())).exists();
        assertThat(images.load(stored.id()).size()).isEqualTo(stored.size());
    }

    @Test
    void missingConfigurationFailsExplicitly() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        assertThatThrownBy(() -> new ImageMediaService(jdbc, "").store(photo()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
    }

    @Test
    void failedStorageDoesNotLeavePartialImages() throws Exception {
        Path notDirectory = directory.resolve("regular-file");
        Files.writeString(notDirectory, "not a directory");
        assertThatThrownBy(() -> new ImageMediaService(mock(JdbcTemplate.class),
                notDirectory.toString()).store(photo())).isInstanceOf(IOException.class);
        try (var files = Files.list(directory)) {
            assertThat(files.toList()).containsExactly(notDirectory);
        }
    }

    @Test
    void authenticationDoesNotDependOnUpdateChangedRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("K1"))).thenReturn(1);
        // The default mock UPDATE result is zero, as for an unchanged timestamp.
        assertThat(new com.wlwl.backend.session.RestSessionService(jdbc).authenticate("K1")).isTrue();
    }

    private static MockMultipartFile photo() throws IOException {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output);
        return new MockMultipartFile("file", "image.png", "image/png", output.toByteArray());
    }
}
