package com.wlwl.backend.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wlwl.backend.session.RestSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * The single front door. In the PHP this role is played by etc/restdx.inc, which
 * every module's index.php includes. Same job, same order of operations:
 *
 *   1. read the raw body
 *   2. json_decode; on failure, gzdecode and try again
 *   3. take restkey and the body-supplied verb
 *   4. "authenticate" by touching restsession and checking affected rows
 *   5. include get.php / post.php / put.php / delete.php
 *
 * Steps 4 and 5 are the parts people get wrong when they "clean up" the API, so
 * they are reproduced literally here.
 */
@RestController
public class BackendGateway {

    private static final Logger log = LoggerFactory.getLogger(BackendGateway.class);

    /**
     * Modules whose index.php does NOT include restdx.inc, i.e. the pre-auth
     * surface: signin/, staffsignin/, registerdemo/. They do their own dispatch.
     */
    private static final Set<String> PUBLIC_MODULES = Set.of("signin", "staffsignin", "registerdemo");

    private final ModuleRegistry registry;
    private final RestSessionService sessions;
    private final ObjectMapper mapper;

    public BackendGateway(ModuleRegistry registry, RestSessionService sessions, ObjectMapper mapper) {
        this.registry = registry;
        this.sessions = sessions;
        this.mapper = mapper;
    }

    @RequestMapping(
            value = {"/backend/{module}", "/backend/{module}/"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public void handle(@PathVariable("module") String module,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {

        ModuleHandler handler = registry.find(module);
        if (handler == null) {
            // IIS returns 404 for a directory that does not exist; so do we.
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        JsonNode envelope = parseBody(request);

        if (!PUBLIC_MODULES.contains(module)) {
            String restkey = envelope != null && envelope.hasNonNull("restkey")
                    ? envelope.get("restkey").asText() : null;
            // PHP would run `where restkey = ''` and affect 0 rows.
            if (restkey == null || restkey.isEmpty() || !sessions.touch(restkey)) {
                // Faithful: auth failure produces an EMPTY 200, not 401.
                // The Vue client sees status 200 and then fails to parse JSON,
                // which is the current production behaviour.
                response.setStatus(200);
                return;
            }
        }

        String verb = resolveVerb(handler, envelope, request);
        RpcContext ctx = new RpcContext(module, envelope, request.getQueryString(), response, mapper);

        try {
            handler.handle(verb, ctx);
        } catch (Exception e) {
            // PHP equivalents: mysqli_query returns false, then ->num_rows on
            // false is a fatal error, which IIS surfaces as 500.
            log.error("Unhandled error in module '{}' ({} {}?{})",
                    module, request.getMethod(), request.getRequestURI(), request.getQueryString(), e);
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    /**
     * Faithful to:
     *   $data = json_decode($request_body);
     *   if (json_last_error() !== 0) { $dat = gzdecode($request_body); $data = json_decode($dat); }
     * The frontend ships pako, so gzipped envelopes really do occur.
     */
    private JsonNode parseBody(HttpServletRequest request) throws IOException {
        byte[] raw = request.getInputStream().readAllBytes();
        if (raw.length == 0) return null;
        try {
            return mapper.readTree(raw);
        } catch (IOException notJson) {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                return mapper.readTree(gz.readAllBytes());
            } catch (IOException notGzipEither) {
                return null; // PHP: $data stays null, everything downstream degrades to empty
            }
        }
    }

    /**
     * Two different dispatch rules exist in the PHP and they must not be conflated:
     *
     *  - etc/restdx.inc (most modules): the BODY's "method" wins over the HTTP verb,
     *    when it is truthy. A query-string method parameter is ignored.
     *
     *  - staffsignin/index.php: the HTTP verb is the base, and ?method= overrides it
     *    (`if (isset($_GET['method'])) $method = strtoupper($_GET['method'])`). The
     *    BODY's method is ignored entirely. That is how GETAUTH and CHKAUTH are
     *    reachable — they are not HTTP verbs.
     *
     * Note `isset($_GET['method'])` is true for `?method=` with an empty value, and
     * strtoupper('') is '', which falls to the switch default and echoes
     * "No method specified!". Reproduced by returning "" rather than falling back.
     */
    private String resolveVerb(ModuleHandler handler, JsonNode envelope, HttpServletRequest request) {
        if (handler.allowsQueryStringMethodOverride()) {
            String fromQuery = request.getParameter("method");
            if (fromQuery != null) return fromQuery.trim().toUpperCase(Locale.ROOT);
            return request.getMethod().toUpperCase(Locale.ROOT);
        }
        if (envelope != null) {
            JsonNode m = envelope.get("method");
            if (m != null && !m.isNull() && !m.asText().isEmpty()) {
                String s = m.asText();
                if (!"0".equals(s)) return s.trim().toUpperCase(Locale.ROOT);
            }
        }
        return request.getMethod().toUpperCase(Locale.ROOT);
    }
}
