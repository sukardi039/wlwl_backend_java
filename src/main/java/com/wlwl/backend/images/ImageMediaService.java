package com.wlwl.backend.images;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ImageMediaService {

    static final long MAX_BYTES = 2 * 1024 * 1024;
    static final long MAX_PIXELS = 16_000_000;
    static final int MAX_DIMENSION = 8192;
    private static final Pattern IMAGE_ID = Pattern.compile(
            "(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:jpg|png)"
                    + "|[0-9a-f]{13}\\.(?i:jpg|jpeg|png|gif))");

    private final JdbcTemplate jdbc;
    private final String directory;

    public ImageMediaService(JdbcTemplate jdbc, @Value("${wlwl.upload.dir:}") String directory) {
        this.jdbc = jdbc;
        this.directory = directory;
    }

    public UploadedImage store(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An image file is required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image exceeds 2 MiB");
        }

        BufferedImage image;
        String format;
        try (InputStream input = file.getInputStream();
             ImageInputStream stream = new MemoryCacheImageInputStream(input)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw unsupportedImage();
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("png")) throw unsupportedImage();
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Image must be at most 8192 pixels per side and 16000000 pixels total");
                }
                image = reader.read(0);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image cannot be decoded", e);
            } finally {
                reader.dispose();
            }
        }

        // Re-encode decoded pixels: never publish the original untrusted file or its metadata.
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, encoded)) {
            throw new IOException("No image encoder available for " + format);
        }
        if (encoded.size() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Normalized image exceeds 2 MiB; resize or compress the image");
        }
        String extension = format.equals("jpeg") ? "jpg" : "png";
        String id = UUID.randomUUID() + "." + extension;
        Path root = root();
        Files.createDirectories(root);
        Path temporary = Files.createTempFile(root, ".image-", ".tmp");
        try {
            Files.write(temporary, encoded.toByteArray(), StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary, root.resolve(id));
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new UploadedImage(id, id, "/backend/media/images/" + id,
                format.equals("jpeg") ? "image/jpeg" : "image/png",
                encoded.size(), image.getWidth(), image.getHeight());
    }

    public StoredImage load(String id) throws IOException {
        if (!IMAGE_ID.matcher(id).matches()) throw notFound();
        Path path = root().resolve(id);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw notFound();
        MediaType type;
        try (InputStream input = Files.newInputStream(path);
             ImageInputStream stream = new MemoryCacheImageInputStream(input)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw notFound();
            ImageReader reader = readers.next();
            try {
                type = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                    case "jpeg" -> MediaType.IMAGE_JPEG;
                    case "png" -> MediaType.IMAGE_PNG;
                    case "gif" -> MediaType.IMAGE_GIF;
                    default -> throw notFound();
                };
            } finally {
                reader.dispose();
            }
        }
        return new StoredImage(new FileSystemResource(path), type, Files.size(path),
                Files.getLastModifiedTime(path).toMillis(), id.indexOf('-') >= 0);
    }

    private Path root() {
        String configured = directory;
        if (configured.isBlank()) {
            var values = jdbc.queryForList(
                    "select `value` from params where `key` = 'fileLocation'", String.class);
            configured = values.isEmpty() ? null : values.get(0);
        }
        if (configured == null || configured.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Image storage is not configured; set WLWL_UPLOAD_DIR");
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static ResponseStatusException unsupportedImage() {
        return new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Only decoded JPEG and PNG images are accepted");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found");
    }

    public record UploadedImage(String id, String fileName, String url, String contentType,
                                long size, int width, int height) {}

    public record StoredImage(FileSystemResource resource, MediaType contentType, long size,
                              long lastModified, boolean immutable) {}
}
