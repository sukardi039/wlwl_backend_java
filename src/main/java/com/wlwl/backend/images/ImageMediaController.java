package com.wlwl.backend.images;

import com.wlwl.backend.session.RestSessionService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

@RestController
public class ImageMediaController {

    private final ImageMediaService images;
    private final RestSessionService sessions;

    public ImageMediaController(ImageMediaService images, RestSessionService sessions) {
        this.images = images;
        this.sessions = sessions;
    }

    @PostMapping(path = "/backend/media/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageMediaService.UploadedImage> upload(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(name = "file") MultipartFile file) throws IOException {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                || !sessions.authenticate(authorization.substring(7).trim())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "A valid session key is required in Authorization: Bearer <restkey>");
        }
        var image = images.store(file);
        return ResponseEntity.created(URI.create(image.url()))
                .cacheControl(CacheControl.noStore()).body(image);
    }

    @GetMapping("/backend/media/images/{id}")
    public ResponseEntity<Resource> get(@PathVariable String id) throws IOException {
        var image = images.load(id);
        CacheControl cache = image.immutable()
                ? CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()
                : CacheControl.maxAge(Duration.ofHours(1)).cachePublic();
        return ResponseEntity.ok().contentType(image.contentType())
                .contentLength(image.size()).lastModified(image.lastModified())
                .cacheControl(cache).header("X-Content-Type-Options", "nosniff")
                .body(image.resource());
    }
}
