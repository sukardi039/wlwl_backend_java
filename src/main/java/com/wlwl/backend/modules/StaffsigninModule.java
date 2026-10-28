package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wlwl.backend.db.MysqlPassword;
import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.db.PhpUniqid;
import com.wlwl.backend.rpc.ModuleHandler;
import com.wlwl.backend.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Port of staffsignin/ (8 files, 352 LOC) — the live login surface.
 *
 * This module is structurally different from every restdx module:
 *
 *  1. Its index.php does NOT include etc/restdx.inc. There is no restkey auth gate,
 *     which is correct — you cannot present a session key before you have one. The
 *     gateway already treats "staffsignin" as a PUBLIC_MODULE.
 *  2. It dispatches on the HTTP verb, with ?method= as an override, and has TWO
 *     extra cases that are not HTTP verbs: GETAUTH and CHKAUTH. The body's "method"
 *     field is ignored here (unlike restdx, where it wins).
 *  3. It writes its own JSON by hand and sets status codes 200/400/404/405/406/500/502.
 *
 * Files and what is live:
 *   index.php      router                          -> allowsQueryStringMethodOverride()
 *   get.php        session lookup by sessionId     -> onGet
 *   post.php       login (creates restsession)     -> onPost
 *   put.php        updates the "tasks" table       -> onPut   [FOREIGN/DEAD, see note]
 *   delete.php     logout (soft-delete session)    -> onDelete
 *   getauth.php    email/OTP handshake webhook     -> onGetAuth
 *   chkauth.php    OTP verification webhook        -> onCheckAuth
 *   post_xxx.php   login against wxuser/studentlist-> NOT PORTED [DEAD, see note]
 *
 * PASSWORD() BLOCKER — SOLVED WITHOUT A DATA MIGRATION
 * ----------------------------------------------------
 * post.php authenticates with `b.loginPassword = password('...')`, and MySQL removed
 * PASSWORD() in 8.0. Rather than reset every password, {@link MysqlPassword} computes
 * the identical MySQL 4.1 hash in Java and binds it as a parameter. The SQL becomes
 * `loginPassword = ?`, producing exactly the result set MySQL's own function would
 * have produced. No schema change, no locked-out users.
 *
 * KNOWN DEAD / FOREIGN CODE (flagged for a written ruling, ported literally)
 * --------------------------------------------------------------------------
 *  - put.php operates on a "tasks" table that does not exist in the wlwl schema. It
 *    looks copy-pasted from a to-do app. It IS reachable via PUT to this module. As in
 *    the PHP, the first statement against the missing table throws and the request
 *    becomes a 500. Implemented literally so behaviour matches if the table appears.
 *  - post_xxx.php queries wxuser/studentlist — a different product's tables (WeChat
 *    parent login). index.php never includes it. NOT ported; see the ledger.
 */
@Component
public class StaffsigninModule implements ModuleHandler {

    private static final Logger log = LoggerFactory.getLogger(StaffsigninModule.class);

    /** PHP defaults, used when neither a Spring property nor an env var is set. */
    private static final String DEFAULT_GETAUTH_URL =
            "https://workflow.innovacityconsulting.com/webhook/79e4c06f-18e7-4754-ab64-7dc572110a66";
    private static final String DEFAULT_CHKAUTH_URL =
            "https://workflow.innovacityconsulting.com/webhook/b40a32bf-fe75-448f-a625-e719282dd219";

    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String DIGITS = "0123456789";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final HttpClient http;

    private final String getauthUrl;
    private final String chkauthUrl;

    public StaffsigninModule(JdbcTemplate jdbc,
                             ObjectMapper mapper,
                             @Value("${wlwl.getauth.url:}") String getauthUrl,
                             @Value("${wlwl.chkauth.url:}") String chkauthUrl,
                             @Value("${wlwl.webhook.connect-timeout-ms:5000}") long connectTimeoutMs,
                             @Value("${wlwl.webhook.timeout-ms:15000}") long timeoutMs) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.getauthUrl = firstNonEmpty(getauthUrl, System.getenv("GETAUTH_SERVICE_URL"), DEFAULT_GETAUTH_URL);
        this.chkauthUrl = firstNonEmpty(chkauthUrl, System.getenv("CHKAUTH_SERVICE_URL"), DEFAULT_CHKAUTH_URL);
        // PHP: SetTimeouts(5000 resolve, 5000 connect, 15000 send, 15000 receive)
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    private final Duration timeout;

    @Override
    public String module() {
        return "staffsignin";
    }

    /** index.php honours ?method=; restdx modules do not. */
    @Override
    public boolean allowsQueryStringMethodOverride() {
        return true;
    }

    // ------------------------------------------------------------------ GET
    // get.php: look up a session by sessionId. The query string IS the sessionId.

    @Override
    public void onGet(RpcContext ctx) throws Exception {
        String qs = ctx.queryString();
        String sql = "select * from restsession where deletedOn is null";
        List<Object> args = new java.util.ArrayList<>();

        if (!qs.isEmpty()) {
            // PHP: ... and sessionId = "$_SERVER[QUERY_STRING]"   (double-quoted literal)
            sql = sql + " and sessionId = ?";
            args.add(qs);
        } else {
            // PHP replaces the WHOLE statement in the else branch, not just appends.
            // 'xxxx' is a sentinel that matches nothing, so the no-query-string case
            // can never return rows. Reproduced so the array branch stays unreachable,
            // exactly as in production.
            sql = "select * from restsession where deletedOn is null and sessionId = 'xxxx'";
        }

        List<Map<String, Object>> sessions = PhpRows.query(jdbc, sql, args.toArray());

        // NOTE: when there are no rows the PHP echoes NOTHING and leaves the default
        // 200 in place — it does not send 204. Different from every other module.
        if (sessions.isEmpty()) {
            ctx.status(200);
            return;
        }

        PhpArray listOrObject = new PhpArray();
        boolean asList = qs.isEmpty();

        for (Map<String, Object> ros : sessions) {
            List<Map<String, Object>> users = PhpRows.query(jdbc,
                    "select b.*, c.lang from backenduser b, company c "
                            + "where c.packageId = b.companyName and b.userId = ?",
                    PhpRows.get(ros, "userId"));

            // PHP: $row = mysqli_fetch_assoc($resuls)  -> null when no user matched,
            // and then $row['sessionid'] = ... auto-vivifies an ARRAY from null. So a
            // dangling session row yields an object holding ONLY the four synthetic
            // keys, not null and not an empty object.
            PhpArray row = users.isEmpty() ? new PhpArray() : PhpArray.of(users.get(0));

            // PhpRows.set, not put: loginPassword and email already exist as row keys,
            // and a blind put would ADD a camelCase duplicate next to the driver's
            // lowercased key — leaving the password HASH visible in the JSON under H2.
            // set() replaces in place, like $row['loginPassword'] = "" in PHP.
            row.set("sessionid", PhpRows.get(ros, "sessionId"));
            row.set("restkey", PhpRows.get(ros, "restkey"));
            row.set("loginPassword", "");
            row.set("email", "");

            if (asList) {
                listOrObject.append(row);
            } else {
                // PHP: $jsonarray = $row;  -> last row wins, as a single object
                listOrObject = row;
            }
        }

        ctx.json(listOrObject.toJsonNode());
    }

    // ----------------------------------------------------------------- POST
    // post.php: login. Creates a restsession row and returns it with the new keys.

    @Override
    public void onPost(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String username = text(env, "username");
        String password = text(env, "password");

        int err = 200;

        // PHP: ... and b.loginPassword = password('...')
        // MySQL 8 dropped PASSWORD(); computing the identical hash here keeps the
        // SQL a plain equality against the stored value. See MysqlPassword.
        String hashed = MysqlPassword.encode(password);

        List<Map<String, Object>> found = PhpRows.query(jdbc,
                "select b.*, c.lang from backenduser b, company c "
                        + "where b.companyName = c.packageId and b.active = 1 "
                        + "and b.loginName = ? and b.loginPassword = ?",
                username, hashed);

        if (!found.isEmpty()) {
            Map<String, Object> row = found.get(0);

            // License gate applies to general users only (level == 0).
            if (number(PhpRows.get(row, "level")) == 0) {
                String userId = text(PhpRows.get(row, "userId"));
                String companyName = text(PhpRows.get(row, "companyName"));

                List<Map<String, Object>> lic = PhpRows.query(jdbc,
                        "select * from license where userId = ? and packageId = ?",
                        userId, companyName);
                if (lic.isEmpty()) {
                    err = 406;
                } else if (!companyName.isEmpty()
                        && number(PhpRows.get(row, "instructor")) == 0) {
                    List<Map<String, Object>> comp = PhpRows.query(jdbc,
                            "select * from company where packageId = ? and active > 0",
                            companyName);
                    if (comp.isEmpty()) {
                        err = 405;
                    }
                }
            }

            if (err == 200) {
                String restkey = PhpUniqid.next();
                String sessionId = PhpUniqid.next();
                jdbc.update("insert into restsession (sessionId, userId, restkey, createdOn) "
                                + "values (?, ?, ?, now())",
                        sessionId, PhpRows.get(row, "userId"), restkey);

                PhpRows.set(row, "restkey", restkey);
                PhpRows.set(row, "sessionid", sessionId);
                PhpRows.set(row, "loginPassword", "");
                PhpRows.set(row, "email", "");

                ctx.json(PhpArray.of(row).toJsonNode());
                return;
            }
        } else {
            err = 404;
        }

        // PHP: if ($err != 200) { http_response_code($err); }  -> status only, no body
        ctx.status(err);
    }

    // ------------------------------------------------------------------ PUT
    // put.php: updates a "tasks" table that is NOT part of this database.
    //
    // Ported literally rather than dropped, because "nothing silently omitted" is a
    // hard requirement. The statement against the missing table throws, which the
    // gateway turns into a 500 — matching the PHP's fatal mysqli_fetch_assoc(false).
    //
    // RULED 2026-10-28: keep it. The 500 is the honest answer for a request the client
    // can never make (the Vue app sends only POST/GET/DELETE/GETAUTH/CHKAUTH here), and
    // if a "tasks" table ever appears the branch starts working instead of lying.
    // Asserted by StaffsigninParityTest.putHitsMissingTable.

    @Override
    public void onPut(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String text = text(env, "text");
        String day = text(env, "day");
        int reminder = truthy(env, "reminder") ? 1 : 0;
        String id = text(env, "id");

        jdbc.update("update tasks set text = ?, day = ?, reminder = ? where id = ?",
                text, day, reminder, id);
        List<Map<String, Object>> rows =
                PhpRows.query(jdbc, "select * from tasks where id = ?", id);
        Map<String, Object> row = rows.isEmpty() ? null : rows.get(0);

        if (row != null && id.equals(text(PhpRows.get(row, "id")))) {
            // PHP: $row['reminder'] = $row['reminder'] ? true : false;  -> JSON boolean
            PhpRows.set(row, "reminder", number(PhpRows.get(row, "reminder")) != 0);
            ctx.json(PhpArray.of(row).toJsonNode());
        } else {
            ctx.status(404);
        }
    }

    // --------------------------------------------------------------- DELETE
    // delete.php: logout. Soft-deletes the session row. ALWAYS 200, even when no
    // row matched — the PHP sets the status unconditionally.

    @Override
    public void onDelete(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String userId = text(env, "userId");
        String sessionId = text(env, "sessionid");

        try {
            jdbc.update("update restsession set deletedOn = now() "
                    + "where sessionId = ? and userId = ?", sessionId, userId);
        } catch (RuntimeException e) {
            // PHP would emit a warning and still fall through to http_response_code(200).
            log.warn("staffsignin delete: restsession update failed", e);
        }
        ctx.status(200);
    }

    // -------------------------------------------------------------- GETAUTH
    // getauth.php: generate an 8-char key, hand key+email to the webhook.

    @Override
    public void onGetAuth(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String userId = text(env, "userId");

        if (userId.isEmpty()) {
            ctx.statusJson(400, ko("0", "0"));
            return;
        }

        List<Map<String, Object>> users;
        try {
            users = PhpRows.query(jdbc,
                    "select email from backenduser where userId = ? limit 1", userId);
        } catch (RuntimeException e) {
            log.error("getauth database query failed", e);
            ctx.statusJson(500, ko("0", "0"));
            return;
        }

        String email = users.isEmpty() ? "" : text(PhpRows.get(users.get(0), "email"));
        if (email.trim().isEmpty()) {
            ctx.statusJson(200, ko("0", "0"));
            return;
        }

        String key = randomKey();

        HttpResponse<String> resp = webhookGet(getauthUrl, key, "key", "email", email);
        if (resp == null) {
            log.error("getauth webhook request failed");
            ctx.statusJson(502, ko("0", "0"));
            return;
        }

        JsonNode result = parse(resp.body());
        if (result == null || !result.isObject()) {
            log.error("getauth webhook returned an empty or invalid JSON response; HTTP status {}",
                    resp.statusCode());
            ctx.statusJson(502, ko("0", "0"));
            return;
        }
        JsonNode k = result.get("K");
        JsonNode o = result.get("O");
        if (!isScalar(k) || !isScalar(o)) {
            log.error("getauth webhook JSON response is missing scalar K/O fields; HTTP status {}",
                    resp.statusCode());
            ctx.statusJson(502, ko("0", "0"));
            return;
        }

        // PHP: array('K' => (string)$service_result->K, 'O' => $service_result->O)
        // K is CAST to string; O is passed through unchanged, so an integer O stays a
        // JSON number. Both details are reproduced here.
        PhpArray out = new PhpArray();
        out.put("K", k.asText());
        out.put("O", o);
        ctx.json(out.toJsonNode());
    }

    // -------------------------------------------------------------- CHKAUTH
    // chkauth.php: verify key+otp against the webhook and pass its body through raw.

    @Override
    public void onCheckAuth(RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String key = text(env, "key");
        String otp = text(env, "otp");

        // PHP: if (trim($key) === '' || trim($otp) === '') -> 200 {"key": "<key>xxx"}
        if (key.trim().isEmpty() || otp.trim().isEmpty()) {
            ctx.statusJson(200, keyOnly(key));
            return;
        }

        HttpResponse<String> resp = webhookGet(chkauthUrl, key, "key", "otp", otp);
        if (resp == null) {
            log.error("chkauth webhook request failed");
            ctx.statusJson(502, keyOnly(key));
            return;
        }

        JsonNode result = parse(resp.body());
        if (result == null || !result.isObject()) {
            log.error("chkauth webhook returned an empty or invalid JSON response; HTTP status {}",
                    resp.statusCode());
            ctx.statusJson(502, keyOnly(key));
            return;
        }
        if (!isScalar(result.get("key"))) {
            log.error("chkauth webhook JSON response is missing scalar key field; HTTP status {}",
                    resp.statusCode());
            ctx.statusJson(502, keyOnly(key));
            return;
        }

        // PHP: header('Content-type: application/json'); http_response_code(200); echo $response;
        // The upstream BODY is echoed verbatim — not re-encoded. Byte-for-byte.
        ctx.rawJson(200, resp.body());
    }

    // -------------------------------------------------------------- helpers

    /**
     * PHP's http_build_query(array('key'=>$key,'email'=>$email)) — application/
     * x-www-form-urlencoded, so a space becomes '+'. java.net.URLEncoder matches that.
     */
    private HttpResponse<String> webhookGet(String serviceUrl, String key, String keyName,
                                            String valueName, String value) {
        String separator = serviceUrl.contains("?") ? "&" : "?";
        String requestUrl = serviceUrl + separator
                + encode(keyName) + "=" + encode(key)
                + "&" + encode(valueName) + "=" + encode(value);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(requestUrl))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.error("webhook request failed; HTTP status {}", resp.statusCode());
                return null;
            }
            return resp;
        } catch (Exception e) {
            // Stands in for the PHP's `class COM does not exist` / WinHTTP exception.
            log.error("webhook request failed: {}", e.toString());
            return null;
        }
    }

    private static String encode(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    /**
     * getauth.php's key generator: lowercase + uppercase + digit, then five more from
     * the full alphabet, then a Fisher-Yates shuffle over the 8 characters.
     */
    static String randomKey() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String all = LOWER + UPPER + DIGITS;
        StringBuilder sb = new StringBuilder(8);
        sb.append(LOWER.charAt(rnd.nextInt(LOWER.length())));
        sb.append(UPPER.charAt(rnd.nextInt(UPPER.length())));
        sb.append(DIGITS.charAt(rnd.nextInt(DIGITS.length())));
        for (int i = 3; i < 8; i++) sb.append(all.charAt(rnd.nextInt(all.length())));

        char[] key = sb.toString().toCharArray();
        for (int i = key.length - 1; i > 0; i--) {
            int swap = rnd.nextInt(i + 1);
            char c = key[i];
            key[i] = key[swap];
            key[swap] = c;
        }
        return new String(key);
    }

    private JsonNode parse(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** json_encode(array('K'=>'0','O'=>'0')) — K stays a JSON string. */
    private static JsonNode ko(String k, String o) {
        PhpArray a = new PhpArray();
        a.put("K", k);
        a.put("O", o);
        return a.toJsonNode();
    }

    /** json_encode(array('key' => $key . 'xxx')) — the 'xxx' suffix is in the original. */
    private static JsonNode keyOnly(String key) {
        PhpArray a = new PhpArray();
        a.put("key", key + "xxx");
        return a.toJsonNode();
    }

    private static boolean isScalar(JsonNode n) {
        return n != null && (n.isTextual() || n.isNumber() || n.isBoolean());
    }

    private static String firstNonEmpty(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isEmpty()) return c;
        }
        return "";
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static String text(Object v) {
        return v == null ? "" : v.toString();
    }

    private static double number(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0;
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** PHP truthiness of a JSON field: 0, "", "0", false, null and absent are falsy. */
    private static boolean truthy(JsonNode node, String field) {
        if (node == null) return false;
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) return false;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber()) return v.asDouble() != 0;
        String s = v.asText();
        return !s.isEmpty() && !s.equals("0");
    }

}
