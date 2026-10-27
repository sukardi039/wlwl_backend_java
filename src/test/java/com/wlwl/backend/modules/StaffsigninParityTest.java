package com.wlwl.backend.modules;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parity tests for staffsignin/ — the login surface.
 *
 * This module is the one place in the backend that:
 *   - has NO restkey gate (you cannot hold a session key before logging in),
 *   - dispatches on ?method= rather than the body's "method",
 *   - has two pseudo-verbs that are not HTTP verbs (GETAUTH, CHKAUTH),
 *   - authenticates with MySQL's PASSWORD() hash, which MySQL 8 removed.
 *
 * The webhook endpoints are replaced by a local HttpServer so the GETAUTH/CHKAUTH
 * branches are exercised without touching the real workflow service. The URLs are
 * injected with @DynamicPropertySource, which is also the seam production would use.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffsigninParityTest {

    /** Exact raw body the CHKAUTH stub returns, to prove byte-for-byte passthrough. */
    private static final String CHKAUTH_OK_BODY =
            "{\"key\":\"verified\",\"n\":2.50,\"nested\":{\"a\":1}}";

    private static final AtomicReference<String> LAST_GETAUTH_KEY = new AtomicReference<>();

    private static final HttpServer STUB;
    private static final String BASE;

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/getauth", exchange -> {
                String q = exchange.getRequestURI().getQuery();
                String key = param(q, "key");
                String email = param(q, "email");
                LAST_GETAUTH_KEY.set(key);

                if ("boom@example.com".equals(email)) {
                    respond(exchange, 500, "{\"error\":\"boom\"}");
                } else if ("missingk@example.com".equals(email)) {
                    // K is absent -> the PHP's isset($service_result->K) check fails
                    respond(exchange, 200, "{\"O\":1}");
                } else {
                    // O is a NUMBER here, to prove the port does NOT stringify it
                    respond(exchange, 200, "{\"K\":\"abc123\",\"O\":7}");
                }
            });
            STUB.createContext("/chkauth", exchange -> {
                String q = exchange.getRequestURI().getQuery();
                String otp = param(q, "otp");
                if ("bad".equals(otp)) {
                    respond(exchange, 500, "{\"error\":\"bad\"}");
                } else if ("nokey".equals(otp)) {
                    respond(exchange, 200, "{\"nokey\":1}");
                } else {
                    respond(exchange, 200, CHKAUTH_OK_BODY);
                }
            });
            STUB.start();
            BASE = "http://127.0.0.1:" + STUB.getAddress().getPort();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void webhookUrls(DynamicPropertyRegistry registry) {
        registry.add("wlwl.getauth.url", () -> BASE + "/getauth");
        registry.add("wlwl.chkauth.url", () -> BASE + "/chkauth");
        registry.add("wlwl.webhook.connect-timeout-ms", () -> "2000");
        registry.add("wlwl.webhook.timeout-ms", () -> "3000");
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ResultActions call(String verb, String query, String body) throws Exception {
        String url = "/backend/staffsignin/" + (query == null ? "" : "?" + query);
        var req = switch (verb) {
            case "GET" -> get(url);
            case "POST" -> post(url);
            case "PUT" -> put(url);
            case "DELETE" -> delete(url);
            default -> throw new IllegalArgumentException(verb);
        };
        return mockMvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ------------------------------------------------------------- login

    @Test
    @DisplayName("login: valid credentials -> 200, new 13-char restkey/sessionid, password blanked")
    void loginSuccess() throws Exception {
        String json = call("POST", null,
                "{\"username\":\"alice\",\"password\":\"secret\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userid").value("U1"))
                // H2 lowercases identifiers; production MySQL keeps camelCase.
                .andExpect(jsonPath("$.loginpassword").value(""))
                .andExpect(jsonPath("$.email").value(""))
                .andReturn().getResponse().getContentAsString();

        String restkey = com.jayway.jsonpath.JsonPath.read(json, "$.restkey");
        String sessionid = com.jayway.jsonpath.JsonPath.read(json, "$.sessionid");
        assertThat(restkey).matches("[0-9a-f]{13}");
        assertThat(sessionid).matches("[0-9a-f]{13}");

        // the row really was inserted
        Integer rows = jdbc.queryForObject(
                "select count(*) from restsession where restkey = ? and userId = 'U1'",
                Integer.class, restkey);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("login: wrong password -> 404 with an EMPTY body")
    void loginWrongPassword() throws Exception {
        call("POST", null, "{\"username\":\"alice\",\"password\":\"nope\"}")
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("login: unknown user -> 404")
    void loginUnknownUser() throws Exception {
        call("POST", null, "{\"username\":\"ghost\",\"password\":\"secret\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("login: active = 0 -> excluded by the WHERE clause -> 404")
    void loginInactiveUser() throws Exception {
        call("POST", null, "{\"username\":\"erin\",\"password\":\"secret\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("login: general user with no license row -> 406, empty body")
    void loginNoLicense() throws Exception {
        call("POST", null, "{\"username\":\"carol\",\"password\":\"letmein\"}")
                // The PHP sends 406 NOT ACCEPTABLE here (not 422), so that is the contract.
                .andExpect(status().isNotAcceptable())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("login: licensed but company active = 0 -> 405")
    void loginCompanyInactive() throws Exception {
        call("POST", null, "{\"username\":\"dave\",\"password\":\"secret\"}")
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("login: instructor (level != 0) skips both license gates")
    void loginInstructorSkipsLicense() throws Exception {
        call("POST", null, "{\"username\":\"bob\",\"password\":\"hunter2\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restkey").exists());
    }

    @Test
    @DisplayName("login: the BODY's method field is ignored here (unlike restdx modules)")
    void bodyMethodIgnored() throws Exception {
        // "method":"GETAUTH" in the body must NOT switch the branch; only ?method= does.
        call("POST", null,
                "{\"username\":\"alice\",\"password\":\"secret\",\"method\":\"GETAUTH\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restkey").exists());
    }

    // -------------------------------------------------------------- GET

    @Test
    @DisplayName("GET: the raw query string IS the sessionId -> single object, password blanked")
    void getBySessionId() throws Exception {
        call("GET", "sess1", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isMap())
                .andExpect(jsonPath("$.sessionid").value("sess1"))
                .andExpect(jsonPath("$.restkey").value("RK1"))
                .andExpect(jsonPath("$.loginpassword").value(""))
                .andExpect(jsonPath("$.userid").value("U1"));
    }

    @Test
    @DisplayName("GET: no query string -> sentinel sessionId 'xxxx' -> empty 200 (NOT 204)")
    void getWithoutQueryString() throws Exception {
        call("GET", null, "")
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("GET: unknown sessionId -> empty 200")
    void getUnknownSession() throws Exception {
        call("GET", "nosuchsession", "")
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    // ----------------------------------------------------------- DELETE

    @Test
    @DisplayName("DELETE: soft-deletes the session and ALWAYS returns 200")
    void deleteLogsOut() throws Exception {
        jdbc.update("insert into restsession (sessionId, userId, restkey, createdOn) "
                + "values ('delme', 'U1', 'RKX', now())");

        call("DELETE", null, "{\"userId\":\"U1\",\"sessionid\":\"delme\"}")
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        String deletedOn = jdbc.queryForObject(
                "select deletedOn from restsession where sessionId = 'delme'", String.class);
        assertThat(deletedOn).isNotNull();
    }

    @Test
    @DisplayName("DELETE: unknown session still returns 200 (status set unconditionally)")
    void deleteUnknownSession() throws Exception {
        call("DELETE", null, "{\"userId\":\"U1\",\"sessionid\":\"nope\"}")
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------- PUT

    @Test
    @DisplayName("PUT: targets the non-existent 'tasks' table -> 500, as the PHP fatal would")
    void putHitsMissingTable() throws Exception {
        call("PUT", null, "{\"text\":\"x\",\"day\":\"2026-01-01\",\"reminder\":1,\"id\":\"1\"}")
                .andExpect(status().isInternalServerError());
    }

    // ---------------------------------------------------------- GETAUTH

    @Test
    @DisplayName("GETAUTH: missing userId -> 400 with K/O zeroes as STRINGS")
    void getAuthNoUserId() throws Exception {
        call("GET", "method=GETAUTH", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.K").value("0"))
                .andExpect(jsonPath("$.O").value("0"));
    }

    @Test
    @DisplayName("GETAUTH: user with no email -> 200 K/O zeroes, webhook never called")
    void getAuthNoEmail() throws Exception {
        jdbc.update("update backenduser set email = '' where userId = 'U2'");
        try {
            call("GET", "method=GETAUTH", "{\"userId\":\"U2\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.K").value("0"))
                    .andExpect(jsonPath("$.O").value("0"));
        } finally {
            jdbc.update("update backenduser set email = 'bob@example.com' where userId = 'U2'");
        }
    }

    @Test
    @DisplayName("GETAUTH: success -> K stringified, O left as the NUMBER the webhook sent")
    void getAuthSuccess() throws Exception {
        LAST_GETAUTH_KEY.set(null);
        call("GET", "method=GETAUTH", "{\"userId\":\"U1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.K").value("abc123"))
                // PHP casts K to string but passes O through untouched, so an integer
                // O must remain a JSON number.
                .andExpect(jsonPath("$.O").value(7));

        // 8 chars, and the shuffle must preserve one lower, one upper and one digit
        assertThat(LAST_GETAUTH_KEY.get())
                .hasSize(8)
                .matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9]).{8}$");
    }

    @Test
    @DisplayName("GETAUTH: webhook 500 -> 502 with K/O zeroes")
    void getAuthWebhookFailure() throws Exception {
        call("GET", "method=GETAUTH", "{\"userId\":\"U6\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.K").value("0"));
    }

    @Test
    @DisplayName("GETAUTH: webhook response missing K -> 502")
    void getAuthMissingK() throws Exception {
        call("GET", "method=GETAUTH", "{\"userId\":\"U7\"}")
                .andExpect(status().isBadGateway());
    }

    // ---------------------------------------------------------- CHKAUTH

    @Test
    @DisplayName("CHKAUTH: empty key -> 200 {\"key\":\"<key>xxx\"} without calling the webhook")
    void chkAuthEmptyKey() throws Exception {
        call("GET", "method=CHKAUTH", "{\"key\":\"\",\"otp\":\"123456\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("xxx"));
    }

    @Test
    @DisplayName("CHKAUTH: success passes the upstream body through BYTE-FOR-BYTE")
    void chkAuthPassthrough() throws Exception {
        // If this were decoded and re-encoded, "2.50" would become 2.5 and key order
        // could change. Exact string equality proves the raw passthrough.
        call("GET", "method=CHKAUTH", "{\"key\":\"k1\",\"otp\":\"123456\"}")
                .andExpect(status().isOk())
                .andExpect(content().string(CHKAUTH_OK_BODY));
    }

    @Test
    @DisplayName("CHKAUTH: webhook 500 -> 502 {\"key\":\"<key>xxx\"}")
    void chkAuthWebhookFailure() throws Exception {
        call("GET", "method=CHKAUTH", "{\"key\":\"k2\",\"otp\":\"bad\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.key").value("k2xxx"));
    }

    @Test
    @DisplayName("CHKAUTH: webhook response missing key -> 502")
    void chkAuthMissingKey() throws Exception {
        call("GET", "method=CHKAUTH", "{\"key\":\"k3\",\"otp\":\"nokey\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.key").value("k3xxx"));
    }

    // ------------------------------------------------------------- router

    @Test
    @DisplayName("?method= with an empty value -> 'No method specified!'")
    void emptyMethodOverride() throws Exception {
        call("GET", "method=", "{}")
                .andExpect(status().isOk())
                .andExpect(content().string("No method specified!"));
    }

    @Test
    @DisplayName("?method= with an unknown verb -> 'No method specified!'")
    void unknownMethodOverride() throws Exception {
        call("GET", "method=NOPE", "{}")
                .andExpect(status().isOk())
                .andExpect(content().string("No method specified!"));
    }

    // -------------------------------------------------------------- helpers

    private static String param(String query, String name) {
        if (query == null) return "";
        for (String part : query.split("&")) {
            int i = part.indexOf('=');
            if (i > 0 && part.substring(0, i).equals(name)) {
                return java.net.URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
