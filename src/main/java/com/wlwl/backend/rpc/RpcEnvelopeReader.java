package com.wlwl.backend.rpc;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Thin accessor over a raw envelope node for the few field coercions repeated in
 * every module. Kept separate from RpcEnvelope so handlers that were handed a
 * bare JsonNode (no typed binding yet) use identical rules.
 */
public class RpcEnvelopeReader {

    private final JsonNode env;

    public RpcEnvelopeReader(JsonNode env) {
        this.env = env;
    }

    /** PHP truthiness for $limit: null, "", "0", 0 -> no LIMIT clause. */
    public Integer limitOrNull() {
        if (env == null) return null;
        JsonNode limit = env.get("limit");
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
            // PHP would interpolate this straight into LIMIT and let MySQL error out.
            // We refuse the clause; recorded as a deliberate divergence.
            return null;
        }
    }

    /** PHP concatenation semantics: missing/null becomes "". */
    public String textOrEmpty(String field) {
        if (env == null) return "";
        JsonNode v = env.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    public String restkey() {
        String v = textOrEmpty("restkey");
        return v.isEmpty() ? null : v;
    }
}
