package com.wlwl.backend.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Everything a module handler needs to answer one request, plus the exact
 * response semantics the PHP used. Those semantics are load-bearing: the Vue
 * client only ever inspects res.status === 200 and otherwise treats the call as
 * "no data", so 204-vs-200 is behaviour, not decoration.
 */
public class RpcContext {

    private final String module;
    private final JsonNode envelope;
    private final String rawQueryString;
    private final HttpServletResponse response;
    private final ObjectMapper mapper;

    public RpcContext(String module, JsonNode envelope, String rawQueryString,
                      HttpServletResponse response, ObjectMapper mapper) {
        this.module = module;
        this.envelope = envelope;
        this.rawQueryString = rawQueryString;
        this.response = response;
        this.mapper = mapper;
    }

    public String module() { return module; }

    /** Parsed body, or null when the body was empty/unparseable (PHP: $data === null). */
    public JsonNode envelope() { return envelope; }

    /**
     * The RAW query string, exactly as PHP's $_SERVER['QUERY_STRING'].
     *
     * This is not a pager. In several modules the query string IS the business
     * key, e.g. drill/post.php does:
     *     where userId = '".$_SERVER['QUERY_STRING']."'
     * so POST /backend/drill/?U123 means userId=U123. HttpServletRequest
     * .getQueryString() is also raw (not URL-decoded), which matches PHP.
     * Never swap this for @RequestParam.
     */
    public String queryString() { return rawQueryString == null ? "" : rawQueryString; }

    public int queryStringLength() { return queryString().length(); }

    /** PHP: header('Content-type: application/json'); http_response_code(200); echo json_encode(...) */
    public void json(Object value) throws IOException {
        response.setStatus(200);
        response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), value);
    }

    /** PHP: echo($json) with no explicit header — used by a few legacy branches. */
    public void rawJsonNoContentType(Object value) throws IOException {
        response.setStatus(200);
        mapper.writeValue(response.getOutputStream(), value);
    }

    /** PHP: http_response_code(204); — 61 call sites in the original. */
    public void noContent() {
        response.setStatus(204);
    }

    /** PHP: http_response_code(n) followed by a JSON body. */
    public void statusJson(int status, Object value) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), value);
    }

    public void status(int status) { response.setStatus(status); }

    /**
     * PHP: header('Content-type: application/json'); http_response_code(n); echo $raw;
     *
     * Writes a pre-formed JSON string byte-for-byte. Needed by staffsignin/chkauth.php,
     * which echoes the upstream webhook's response body VERBATIM rather than
     * re-encoding a decoded structure. Re-encoding would reorder keys and change
     * number formatting, so the raw passthrough is the only faithful option.
     */
    public void rawJson(int status, String raw) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getOutputStream().write(raw == null ? new byte[0] : raw.getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();
    }

    /** PHP: echo "some string"; (text/html by default there, text/plain here). */
    public void text(String body) throws IOException {
        response.setStatus(200);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(body);
    }

    /** Convenience: PHP-style array of rows, with the synthetic 'key' column injected when asked. */
    public void jsonRows(List<Map<String, Object>> rows) throws IOException {
        json(rows);
    }
}
