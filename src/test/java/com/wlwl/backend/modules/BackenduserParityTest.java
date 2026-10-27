package com.wlwl.backend.modules;

import com.wlwl.backend.db.MysqlPassword;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parity tests for backenduser/.
 *
 * The class is @Transactional so the create/update cases roll back: the listing
 * tests assert exact row counts, and a leaked user from a previous test would make
 * them order-dependent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BackenduserParityTest {

    /** backenduser is a restdx module, so every call needs a live restkey. */
    private static final String KEY = "K1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ResultActions post(String query, String body) throws Exception {
        String url = "/backend/backenduser/" + (query == null ? "" : "?" + query);
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions put(String body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/backend/backenduser/").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // --------------------------------------------------------------- POST

    @Test
    @DisplayName("POST: level <= mod, and the stored password hash IS returned (PHP does not blank it)")
    void listUsers() throws Exception {
        post(null, "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":5}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(7))
                // post.php re-selects `select *` and never clears loginPassword.
                .andExpect(jsonPath("$[0].loginpassword")
                        .value("*14E65567ABDB5135D0CFD9A70B3032C179A49EE7"));
    }

    @Test
    @DisplayName("POST: mod = 0 returns only level-0 users")
    void listLevelZero() throws Exception {
        post(null, "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":0}")
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    @DisplayName("POST: package filter selects one company")
    void filterByPackage() throws Exception {
        post(null, "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":5,\"package\":\"pkg1\"}")
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    @DisplayName("POST: backend flag adds level > 0")
    void filterBackend() throws Exception {
        post(null, "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":5,\"backend\":true}")
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @DisplayName("POST: limit is applied")
    void withLimit() throws Exception {
        post(null, "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":5,\"limit\":2}")
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("POST: no match -> 200 with [] (NOT 204; the older saved copy used 204)")
    void emptyIsArrayNotNoContent() throws Exception {
        // mod is a `level <= mod` bound, so a NEGATIVE value matches nothing.
        // (mod:99 would match every user in the fixture.)
        post(null, "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":-1}")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    @DisplayName("POST: query string collapses the response to a single object")
    void objectWhenQueryString() throws Exception {
        post("x", "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":0}")
                .andExpect(jsonPath("$").isMap())
                .andExpect(jsonPath("$.userid").exists());
    }

    @Test
    @DisplayName("POST: non-numeric mod -> 500, because the PHP interpolates it into SQL")
    void nonNumericModFails() throws Exception {
        post(null, "{\"restkey\":\"" + KEY + "\",\"method\":\"POST\",\"mod\":\"abc\"}")
                .andExpect(status().isInternalServerError());
    }

    // ---------------------------------------------------------------- PUT

    @Test
    @DisplayName("PUT: creates a general user, hashes the password, and consumes a licence seat")
    void createUser() throws Exception {
        String json = put("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                + "\"qdata\":{\"userName\":\"New Person\",\"loginName\":\"newperson\","
                + "\"loginPassword\":\"secret\",\"level\":0,\"active\":1,"
                + "\"companyName\":\"pkg1\",\"instructor\":0}}")
                .andExpect(status().isOk())
                // No query string -> the PHP appends to $jsonarray, so the response is
                // an ARRAY of one row, not an object.
                // the hash comes back in the response — faithful, and a real leak
                .andExpect(jsonPath("$[0].loginpassword")
                        .value("*14E65567ABDB5135D0CFD9A70B3032C179A49EE7"))
                .andExpect(jsonPath("$[0].companyname").value("pkg1"))
                .andReturn().getResponse().getContentAsString();

        String userId = com.jayway.jsonpath.JsonPath.read(json, "$[0].userid");
        assertThat(userId).matches("[0-9a-f]{13}");

        // A licence row was created against the only batch with free capacity (B1),
        // and companyName stores the company's DISPLAY name, not the package id.
        Integer licensed = jdbc.queryForObject(
                "select count(*) from license where userId = ? and batchId = 'B1' "
                        + "and companyName = 'Acme'", Integer.class, userId);
        assertThat(licensed).isEqualTo(1);
    }

    @Test
    @DisplayName("PUT: inventory user (level > 0) needs no licence, and a valid email is stored")
    void createBackendUser() throws Exception {
        String json = put("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                + "\"qdata\":{\"userName\":\"Agent Smith\",\"loginName\":\"smith\","
                + "\"loginPassword\":\"secret\",\"level\":1,\"active\":1,"
                + "\"companyName\":\"pkg1\",\"instructor\":1,\"email\":\"smith@example.com\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("smith@example.com"))
                .andExpect(jsonPath("$[0].level").value(1))
                .andReturn().getResponse().getContentAsString();

        String userId = com.jayway.jsonpath.JsonPath.read(json, "$[0].userid");
        Integer licensed = jdbc.queryForObject(
                "select count(*) from license where userId = ?", Integer.class, userId);
        assertThat(licensed).isZero();
    }

    @Test
    @DisplayName("PUT: level > 0 with an invalid email -> 406, no user created")
    void invalidEmail() throws Exception {
        put("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                + "\"qdata\":{\"userName\":\"Bad\",\"loginName\":\"badmail\","
                + "\"level\":1,\"companyName\":\"pkg1\",\"email\":\"not-an-email\"}}")
                .andExpect(status().isNotAcceptable())
                .andExpect(content().string(""));

        Integer rows = jdbc.queryForObject(
                "select count(*) from backenduser where loginName = 'badmail'", Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("PUT: duplicate loginName -> 406")
    void duplicateLoginName() throws Exception {
        put("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                + "\"qdata\":{\"userName\":\"Copy\",\"loginName\":\"alice\","
                + "\"level\":0,\"companyName\":\"pkg1\"}}")
                .andExpect(status().isNotAcceptable());
    }

    @Test
    @DisplayName("PUT: update existing user — the password is NOT touched")
    void updateKeepsPassword() throws Exception {
        put("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                + "\"qdata\":{\"userId\":\"U1\",\"userName\":\"Alice Renamed\","
                + "\"loginName\":\"alice\",\"loginPassword\":\"a-new-password\","
                + "\"level\":0,\"active\":1,\"companyName\":\"pkg1\",\"instructor\":0}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("Alice Renamed"));

        String stored = jdbc.queryForObject(
                "select loginPassword from backenduser where userId = 'U1'", String.class);
        // still the ORIGINAL hash: put.php's UPDATE has no password column
        assertThat(stored).isEqualTo(MysqlPassword.encode("secret"));
    }

    @Test
    @DisplayName("PUT: empty companyName becomes 'admin'")
    void emptyCompanyBecomesAdmin() throws Exception {
        put("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                + "\"qdata\":{\"userName\":\"Root\",\"loginName\":\"rootuser\","
                + "\"loginPassword\":\"secret\",\"level\":1,\"active\":1,"
                + "\"companyName\":\"\",\"instructor\":1,\"email\":\"root@example.com\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].companyname").value("admin"));
    }

    @Test
    @DisplayName("PUT: no licence capacity -> 405 and the user is NOT created")
    void noLicenseCapacity() throws Exception {
        put("{\"restkey\":\"" + KEY + "\",\"method\":\"PUT\","
                + "\"qdata\":{\"userName\":\"Orphan\",\"loginName\":\"orphan\","
                + "\"loginPassword\":\"secret\",\"level\":0,\"active\":1,"
                + "\"companyName\":\"pkg5\",\"instructor\":0}}")
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().string(""));

        Integer rows = jdbc.queryForObject(
                "select count(*) from backenduser where loginName = 'orphan'", Integer.class);
        assertThat(rows).isZero();
    }
}
