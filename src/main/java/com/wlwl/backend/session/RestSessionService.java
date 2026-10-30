package com.wlwl.backend.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The entire authentication mechanism, ported literally.
 *
 * PHP (etc/restdx.inc):
 *   $sql = "update restsession set usedOn = '".date("Y-m-d H:i:s.").gettimeofday()["usec"]."'
 *           where restkey = '".$restkey."' and deletedOn is null";
 *   if (mysqli_affected_rows($conn)) { ...dispatch... }
 *
 * So authentication is "did this UPDATE match a live session row", and the
 * session's usedOn column doubles as a last-seen timestamp. restsession.usedOn
 * is varchar(100) in the schema, which is why this writes a STRING and not a
 * datetime — mapping it to a temporal type would silently change the stored
 * format and break anything that compares it.
 *
 * Note also that gettimeofday()["usec"] is an int and PHP does NOT zero-pad it,
 * so a real value can be "2026-10-26 15:47:01.4231" with four digits. That
 * quirk is reproduced deliberately.
 */
@Service
public class RestSessionService {

    private static final Logger log = LoggerFactory.getLogger(RestSessionService.class);

    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String TOUCH_SQL =
            "update restsession set usedOn = ? where restkey = ? and deletedOn is null";

    private final JdbcTemplate jdbc;

    public RestSessionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true when at least one live session row matched, i.e. the key is valid.
     */
    public boolean touch(String restkey) {
        if (restkey == null || restkey.isEmpty()) return false;
        try {
            int rows = jdbc.update(TOUCH_SQL, usedOnNow(), restkey);
            return rows > 0;
        } catch (RuntimeException e) {
            // PHP: mysqli_query returns false, affected_rows is 0 -> no output, empty 200.
            log.warn("restsession touch failed for a presented key", e);
            return false;
        }
    }

    /**
     * New HTTP APIs validate the session separately from updating its timestamp.
     * Unlike the legacy RPC gate, database errors propagate to the HTTP error handler.
     */
    public boolean authenticate(String restkey) {
        if (restkey == null || restkey.isBlank()) return false;
        Integer matches = jdbc.queryForObject(
                "select count(*) from restsession where restkey = ? and deletedOn is null",
                Integer.class, restkey);
        if (matches == null || matches == 0) return false;
        jdbc.update(TOUCH_SQL, usedOnNow(), restkey);
        return true;
    }

    /** e.g. 2026-10-26 15:47:01.4231 — the trailing microseconds are NOT zero-padded. */
    static String usedOnNow() {
        LocalDateTime now = LocalDateTime.now();
        int micros = now.getNano() / 1_000;
        return now.format(DATE_PART) + "." + micros;
    }
}
