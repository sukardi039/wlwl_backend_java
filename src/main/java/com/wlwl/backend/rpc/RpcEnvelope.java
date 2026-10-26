package com.wlwl.backend.rpc;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The request envelope every Vue call sends.
 *
 * Mirrors the PHP contract exactly (see etc/restdx.inc):
 *
 *   POST /backend/{module}/
 *   { "restkey": "...", "classes": ..., "mod": ..., "method": "POST",
 *     "limit": ..., "qdata": {...}, "package": {...} }
 *
 * Field notes, all deliberate:
 *  - {@code method} is a STRING inside the body and OVERRIDES the HTTP verb.
 *    PHP: if ($data->method) $method = strtoupper($data->method);
 *  - {@code mod} is dual-purpose in the PHP: an action selector (drill) and a
 *    plain SQL filter value (baseinfo). It is kept as a raw JsonNode so numeric
 *    or string values both survive untouched.
 *  - {@code package} is a Java keyword, hence the field rename. The JSON key on
 *    the wire must stay "package".
 *  - qdata/package/classes are opaque to the gateway; only module handlers
 *    interpret them.
 */
public class RpcEnvelope {

    @JsonProperty("restkey")
    public String restkey;

    @JsonProperty("method")
    public String method;

    @JsonProperty("mod")
    public JsonNode mod;

    @JsonProperty("qdata")
    public JsonNode qdata;

    @JsonProperty("package")
    public JsonNode packageData;

    @JsonProperty("limit")
    public JsonNode limit;

    @JsonProperty("classes")
    public JsonNode classes;

    /** PHP truthiness for $data->mod: null, "", "0", 0 are all falsy. */
    public String modAsStringOrEmpty() {
        if (mod == null || mod.isNull() || mod.isMissingNode()) return "";
        String s = mod.asText();
        if ("0".equals(s)) return "";
        return s;
    }

    /** PHP truthiness for $data->limit: 0, "", null mean "no limit". */
    public Integer limitOrNull() {
        if (limit == null || limit.isNull() || limit.isMissingNode()) return null;
        if (limit.isNumber()) {
            int v = limit.asInt();
            return v == 0 ? null : v;
        }
        String s = limit.asText("").trim();
        if (s.isEmpty() || "0".equals(s)) return null;
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            // PHP would interpolate the string straight into LIMIT and let MySQL
            // fail; we refuse. Recorded in PORTING_NOTES.md as a deliberate fix.
            return null;
        }
    }
}
