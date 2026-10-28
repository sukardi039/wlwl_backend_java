package com.wlwl.backend.images;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wlwl.backend.db.PhpUniqid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Port of <b>images/upload.php</b> — the staff-photo upload endpoint.
 *
 * <p>This is the one backend endpoint that is NOT part of the RPC contract, and it
 * cannot be routed through {@link com.wlwl.backend.rpc.BackendGateway} because that
 * gateway parses a JSON (or gzipped JSON) body and this request is multipart. The
 * PHP also bypasses etc/restdx.inc entirely: it includes etc/db.inc and reads the
 * restkey from the RAW QUERY STRING.
 *
 * <pre>
 *   $sql = "update restsession set usedOn = now()
 *            where restkey = '".$_SERVER['QUERY_STRING']."' and deletedOn is null";
 *   if (mysqli_affected_rows($conn)) {
 *     if (is_uploaded_file($_FILES['upFile']['tmp_name'])) {
 *       ... $par from `select * from params` ...
 *       $img_name  = uniqid().'.'.pathinfo($_FILES['upFile']['name'], PATHINFO_EXTENSION);
 *       $upload_dir = $par['fileLocation'].$img_name;
 *       if (move_uploaded_file($tmp_file, $upload_dir)) { 200 {"fileName": $img_name} }
 *       else                                            { 204 }
 *     }
 *   }
 * </pre>
 *
 * Things that are load-bearing, and why each is reproduced rather than tidied:
 *
 * <ul>
 *   <li><b>Auth is the query string, not the body.</b> The client sends
 *       {@code POST /backend/images/upload.php?<restkey>}. Reading the key from the
 *       envelope would authenticate a request the PHP rejects.</li>
 *   <li><b>`usedOn = now()` has one-second resolution, and
 *       {@code mysqli_affected_rows} counts CHANGED rows.</b> Two uploads inside the
 *       same second therefore make the second one match zero changed rows, and the
 *       PHP answers an EMPTY 200 without storing anything. Reproduced deliberately —
 *       see {@link #touchChangedRows}.</li>
 *   <li><b>The stored name is {@code uniqid() + '.' + extension}</b>, never the client
 *       filename. (The sibling images/post.php uses {@code uniqid() + '_' + name}; the
 *       two differ, and post.php is unreachable — see {@link ImagesModule}.)</li>
 *   <li><b>The extension keeps its case and is everything after the last dot</b>
 *       ({@code photo.JPG -> JPG}, {@code noext -> ""}).</li>
 *   <li><b>Failure to store answers 204, not 500</b> — {@code move_uploaded_file}
 *       returns false and the PHP merely sets the code.</li>
 * </ul>
 *
 * One deliberate divergence, documented in PORTING_NOTES §31: when neither
 * {@code wlwl.upload.dir} nor {@code params.fileLocation} yields a directory, the PHP
 * would build a bare relative path and try to write into its own working directory.
 * This port refuses and answers 204 instead of scattering files into the JVM's CWD.
 */
@RestController
public class ImageUploadController {

    private static final Logger log = LoggerFactory.getLogger(ImageUploadController.class);

    private static final String TOUCH_CHANGED_SQL =
            "update restsession set usedOn = ? where restkey = ? and deletedOn is null "
                    + "and (usedOn is null or usedOn <> ?)";

    /** MySQL renders {@code now()} into a varchar as "YYYY-MM-DD HH:MM:SS". */
    private static final DateTimeFormatter SECOND_PRECISION =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    /** wlwl.upload.dir / WLWL_UPLOAD_DIR; empty means "ask params.fileLocation". */
    private final String dirOverride;

    public ImageUploadController(JdbcTemplate jdbc, ObjectMapper mapper,
                                 @Value("${wlwl.upload.dir:}") String dirOverride) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.dirOverride = dirOverride == null ? "" : dirOverride;
    }

    @PostMapping(path = "/backend/images/upload.php",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void upload(HttpServletRequest request,
                       HttpServletResponse response,
                       @RequestParam(name = "upFile", required = false) MultipartFile upFile)
            throws IOException {

        // ---- auth: raw query string, exactly $_SERVER['QUERY_STRING']
        if (!touchChangedRows(request.getQueryString())) {
            // PHP: the whole body is skipped. Nothing is written; the status stays 200.
            return;
        }

        // ---- is_uploaded_file($_FILES['upFile']['tmp_name'])
        if (upFile == null || upFile.isEmpty()) {
            return;
        }

        String dir = uploadDir();
        if (dir.isEmpty()) {
            log.error("images/upload.php: no upload directory (wlwl.upload.dir unset and "
                    + "params.fileLocation missing) — refusing, declaring a failed move");
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }

        String fileName = PhpUniqid.next() + "." + extensionOf(upFile.getOriginalFilename());
        Path target = Path.of(dir + fileName);

        try (InputStream in = upFile.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            // move_uploaded_file() returns false -> 204. Never a 500.
            log.warn("images/upload.php: could not store '{}': {}", target, e.toString());
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }

        response.setContentType("application/json");
        Map<String, String> body = Map.of("fileName", fileName);
        mapper.writeValue(response.getWriter(), body);
    }

    /**
     * PHP's gate: the UPDATE must CHANGE at least one row.
     *
     * <p>{@code usedOn = now()} is second-resolution, so re-uploading within the same
     * second leaves the column unchanged and {@code mysqli_affected_rows} is 0 — the
     * request is then refused silently. The {@code usedOn <> ?} predicate reproduces
     * that instead of counting matched rows (which is what JDBC returns by default).
     */
    private boolean touchChangedRows(String restkey) {
        if (restkey == null || restkey.isEmpty()) {
            return false;   // PHP: where restkey = '' matches nothing
        }
        try {
            // now() comes from the DATABASE, like the PHP's `usedOn = now()`. It is read
            // as a Timestamp and formatted here rather than with MySQL's date_format(),
            // because the parity suite runs on H2 and only the DB clock matters — the
            // one-second resolution is what the quirk below depends on.
            Timestamp dbNow = jdbc.queryForObject("select now()", Timestamp.class);
            String now = dbNow.toLocalDateTime().format(SECOND_PRECISION);
            int changed = jdbc.update(TOUCH_CHANGED_SQL, now, restkey, now);
            return changed > 0;
        } catch (RuntimeException e) {
            // mysqli_query() false -> affected_rows 0 -> silent empty 200
            log.warn("images/upload.php: restsession touch failed", e);
            return false;
        }
    }

    /**
     * {@code $par['fileLocation']}, with the environment override applied first.
     *
     * The PHP concatenates the name straight onto the parameter, so a value without a
     * trailing separator would produce "dirfile.jpg". That is kept for the DB value
     * (it is production's own data), but the override is a deployment setting and gets
     * a separator appended when it lacks one.
     */
    private String uploadDir() {
        if (!dirOverride.isBlank()) {
            return dirOverride.endsWith("/") || dirOverride.endsWith(File.separator)
                    ? dirOverride : dirOverride + File.separator;
        }
        List<String> values = jdbc.queryForList(
                "select `value` from params where `key` = 'fileLocation'", String.class);
        if (values.isEmpty() || values.get(0) == null) {
            return "";
        }
        return values.get(0);
    }

    /**
     * PHP {@code pathinfo($name, PATHINFO_EXTENSION)}: text after the last dot of the
     * basename; "" when there is no dot. Case is preserved.
     */
    static String extensionOf(String originalName) {
        if (originalName == null || originalName.isEmpty()) {
            return "";
        }
        String base = originalName.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        return dot < 0 ? "" : base.substring(dot + 1);
    }
}
