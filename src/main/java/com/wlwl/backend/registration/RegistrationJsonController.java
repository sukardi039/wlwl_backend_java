package com.wlwl.backend.registration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Port of the static file <b>backend/registration.json</b> — the deployment-identity
 * document the Vue login screen reads before anything else.
 *
 * <p>This file is easy to miss when porting, because it was never source code: the PHP
 * repository's {@code .gitignore} lists {@code registration.json} on its second line,
 * next to {@code etc/db.inc}. IIS served it from the webroot because it sat there, so
 * the Vue client could fetch it, while git deliberately kept it out of the repo. Each
 * deployment supplied its own copy — the company name and registration numbers differ
 * per customer.
 *
 * <p>The client ({@code src/views/Login.vue}) needs exactly these seven keys; it calls
 * {@code getReg()} on {@code created()}, then {@code displayAll(data)} sets
 * {@code document.title = data.company} and renders the footer:
 *
 * <pre>
 *   {{ida.company}} {{ida.reg}} &lt;a :href="ida.url"&gt;{{ida.ida}}&lt;/a&gt;
 *   &lt;img :src="ida.polreg" width="10" /&gt; &lt;a :href="ida.polurl"&gt;{{ida.pol}}&lt;/a&gt;
 * </pre>
 *
 * <p>Resolution order, deliberately mirroring "an operator dropped a file into the
 * webroot":
 *
 * <ol>
 *   <li>{@code wlwl.registration.file} ({@code WLWL_REGISTRATION_FILE}) — the
 *       deployment's own file, equivalent to the gitignored one. If it is configured
 *       but unreadable the answer is <b>404</b>, not the bundled placeholder: a
 *       deployment that names a file and has it missing should fail visibly rather
 *       than silently advertise the wrong company.</li>
 *   <li>Otherwise the bundled {@code registration.json} on the classpath.</li>
 * </ol>
 *
 * <p>The body is served as the raw bytes of the file, never re-serialised: whatever the
 * operator deploys is what the browser gets, including key order.
 *
 * <p>Note the path collides with {@link com.wlwl.backend.rpc.BackendGateway}'s
 * {@code /backend/{module}} mapping. The literal pattern is more specific than the
 * template, so this controller wins and the gateway never treats {@code
 * registration.json} as an RPC module (which would 404, as it is not in the registry).
 */
@RestController
public class RegistrationJsonController {

    private static final Logger log = LoggerFactory.getLogger(RegistrationJsonController.class);

    /** Same name the PHP deployment put in the webroot. */
    private static final String BUNDLED_RESOURCE = "registration.json";

    private final String configuredPath;
    private final byte[] bundled;

    public RegistrationJsonController(@Value("${wlwl.registration.file:}") String configuredPath)
            throws IOException {
        this.configuredPath = configuredPath == null ? "" : configuredPath.trim();
        this.bundled = readBundled();
        if (this.configuredPath.isEmpty()) {
            log.info("registration.json: serving the bundled placeholder; set WLWL_REGISTRATION_FILE "
                    + "to the deployment's own file (the PHP kept it gitignored, outside the repo)");
        } else {
            log.info("registration.json: serving external file '{}'", this.configuredPath);
        }
    }

    private static byte[] readBundled() throws IOException {
        ClassPathResource resource = new ClassPathResource(BUNDLED_RESOURCE);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        }
    }

    @GetMapping(path = "/backend/registration.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> registrationJson() throws IOException {
        if (!configuredPath.isEmpty()) {
            Path path = Path.of(configuredPath);
            if (!Files.isReadable(path)) {
                // PHP: IIS answered 404 for a file that was not in the webroot, and the
                // client's `if (res.status === 200)` simply skipped displayAll().
                log.error("registration.json: wlwl.registration.file='{}' is not readable — answering 404", path);
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Files.readAllBytes(path));
        }

        if (bundled == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(bundled);
    }
}
