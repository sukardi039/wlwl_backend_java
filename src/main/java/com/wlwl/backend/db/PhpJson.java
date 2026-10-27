package com.wlwl.backend.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * PHP-side JSON handling for modules that store JSON blobs in a column and re-emit
 * them inside their response (drilloverview, drilleffective, drillcapable, drill).
 *
 * The critical difference from plain Jackson:
 *
 * <pre>
 *   PHP: $row['content'] = json_decode($row['content'], true);   // ASSOC MODE
 * </pre>
 *
 * With {@code true}, JSON objects become PHP arrays, and PHP arrays carry the
 * array-vs-object rule on re-encode. So {@code {"0":"a","1":"b"}} decodes to an
 * array with int keys 0 and 1 and {@code json_encode} emits {@code ["a","b"]} —
 * whereas Jackson would emit {@code {"0":"a","1":"b"}}. Likewise an empty JSON
 * object {@code {}} decodes to an empty array and re-emits as {@code []}.
 *
 * Decoding into {@link PhpArray} (rather than a Jackson tree) reproduces that.
 * Modules must then render values through {@link PhpArray#node(Object)} or keep them
 * inside a PhpArray, never through Jackson directly.
 */
public final class PhpJson {

    private PhpJson() { }

    /** json_decode($raw, true). Returns null for empty or malformed input, like PHP. */
    public static Object decodeAssoc(ObjectMapper mapper, Object raw) {
        if (raw == null) return null;
        String s = raw.toString();
        if (s.isEmpty()) return null;
        try {
            return fromJson(mapper.readTree(s));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * json_decode($raw) with NO second argument — object mode.
     *
     * JSON objects stay objects here, so {@code {}} must survive as {@code {}} and
     * {@code {"0":"a"}} must stay an object. Returns the raw Jackson tree, which has
     * exactly those semantics.
     */
    public static JsonNode decodeObject(ObjectMapper mapper, Object raw) {
        if (raw == null) return null;
        String s = raw.toString();
        if (s.isEmpty()) return null;
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** JsonNode -> PHP value: objects become PhpArray (with key coercion), arrays Lists. */
    public static Object fromJson(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isObject()) {
            PhpArray a = new PhpArray();
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                a.put(e.getKey(), fromJson(e.getValue()));
            }
            return a;
        }
        if (n.isArray()) {
            List<Object> list = new ArrayList<>(n.size());
            for (JsonNode c : n) list.add(fromJson(c));
            return list;
        }
        if (n.isTextual()) return n.asText();
        if (n.isBoolean()) return n.asBoolean();
        if (n.isIntegralNumber()) return n.asLong();
        if (n.isFloatingPointNumber()) return n.asDouble();
        return n.asText();
    }

    /**
     * Null-safe walk of $a['x'][0]['y']. PHP emits a warning and yields null for a
     * missing key or an out-of-range index, so null propagates instead of throwing.
     */
    public static Object path(Object root, Object... keys) {
        Object cur = root;
        for (Object k : keys) {
            if (cur == null) return null;
            if (cur instanceof PhpArray a) {
                cur = a.get(k);
            } else if (cur instanceof Map<?, ?> m) {
                cur = m.get(k instanceof Number n ? n.longValue() : k);
                if (cur == null) cur = m.get(String.valueOf(k));
            } else if (cur instanceof List<?> list && k instanceof Number n) {
                int i = n.intValue();
                cur = (i >= 0 && i < list.size()) ? list.get(i) : null;
            } else {
                return null;
            }
        }
        return cur;
    }

    // --------------------------------------------------------- numeric rules

    /** PHP int stays int; anything else is a double. */
    public static boolean isIntegral(Object v) {
        return v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte;
    }

    public static double num(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean b) return b ? 1 : 0;
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public static long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        return (long) num(v);
    }

    /** PHP arithmetic: int op int -> int, any float operand -> float. */
    public static Object subtract(Object a, Object b) {
        if (a == null || b == null) return null;
        if (isIntegral(a) && isIntegral(b)) return toLong(a) - toLong(b);
        return num(a) - num(b);
    }

    public static Object multiply(Object a, Object b) {
        if (a == null || b == null) return null;
        if (isIntegral(a) && isIntegral(b)) return toLong(a) * toLong(b);
        return num(a) * num(b);
    }

    /**
     * PHP round($x, $precision). Always returns a float, so 35 becomes 35.0 and
     * json_encode emits {@code 35.0} — that is deliberate: emitting an int would
     * change the JSON type versus the PHP.
     */
    public static double round(Object value, int precision) {
        BigDecimal bd = BigDecimal.valueOf(num(value)).setScale(precision, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    /** PHP round($x) — no precision argument. */
    public static double round(Object value) {
        return round(value, 0);
    }
}
