package com.wlwl.backend.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A PHP array, faithfully enough for json_encode to produce identical JSON.
 *
 * Why this class exists
 * --------------------
 * Most modules return a flat list of rows, which Jackson serialises correctly as
 * a List. This module does not: basicinformation/post.php builds a 300-key
 * associative array of associative arrays, and in PHP the *key type* decides
 * whether a JSON array or a JSON object comes out.
 *
 *   array('a', 'b')            -> ["a","b"]
 *   array('x' => 1)            -> {"x":1}
 *   array(0 => 'a', 1 => 'b')  -> ["a","b"]     (sequential int keys)
 *   array(1 => 'a', 2 => 'b')  -> {"1":"a","2":"b"}   (not sequential from 0)
 *
 * And PHP coerces numeric-string keys to ints on insertion:
 *
 *   $a['1'] = 'x';             -> key int 1 -> {"1":"x"} if alone
 *   $a['01'] = 'x';            -> key string "01" (leading zero, stays string)
 *   $a['1.0'] = 'x';           -> key string "1.0"
 *
 * So a naive "build a HashMap<String,Object> and serialise" port silently turns
 * some of these objects into arrays (or vice versa) and breaks the Vue client.
 * Every lookup and insertion here normalises the key the way PHP's zend_hash does,
 * and {@link #toJsonNode()} applies the array-vs-object rule.
 *
 * Ordering is insertion order, like every PHP array since 5.x — the client reads
 * some of these lists positionally.
 */
public final class PhpArray extends LinkedHashMap<Object, Object> {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    public PhpArray() { }

    /**
     * PHP's key coercion. Returns a Long for canonical decimal integers, the
     * original String otherwise. Mirrors zend_hash: "+5", " 5", "1.0", "01" and
     * "1e3" all stay strings; "0", "12" and "-3" become ints.
     */
    public static Object key(Object k) {
        if (k == null) return "";
        if (k instanceof Long || k instanceof Integer || k instanceof Short || k instanceof Byte) {
            return ((Number) k).longValue();
        }
        if (k instanceof Boolean) return ((Boolean) k) ? 1L : 0L;
        String s = k.toString();
        if (s.isEmpty()) return s;
        if (!isCanonicalInt(s)) return s;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException overflow) {
            return s;   // PHP keeps ints that overflow the platform int as strings
        }
    }

    private static boolean isCanonicalInt(String s) {
        int i = 0;
        if (s.charAt(0) == '-') {
            if (s.length() == 1) return false;
            i = 1;
        }
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        // no leading zeros ("0" itself is fine)
        int start = (s.charAt(0) == '-') ? 1 : 0;
        if (s.length() - start > 1 && s.charAt(start) == '0') return false;
        return true;
    }

    // ------------------------------------------------------------ mutation

    /**
     * PHP array assignment is a deep-enough COPY: {@code $cont = $content} then mutating
     * {@code $cont} leaves {@code $content} untouched (copy-on-write). drill/action.php
     * depends on it twice — the {@code $cont} probe that may be discarded, and
     * cal_impacts' evaluate branch, which computes a swap on a copy and throws the
     * numbers away. Java arrays are references, so the copy has to be explicit.
     */
    public PhpArray copy() {
        PhpArray out = new PhpArray();
        for (var entry : entrySet()) {
            out.put(entry.getKey(), deepCopy(entry.getValue()));
        }
        return out;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof PhpArray nested) return nested.copy();
        if (value instanceof java.util.List<?> list) {
            java.util.List<Object> copy = new java.util.ArrayList<>(list.size());
            for (Object item : list) copy.add(deepCopy(item));
            return copy;
        }
        if (value instanceof java.util.Map<?, ?> map) {     // not expected, but be safe
            PhpArray copy = new PhpArray();
            for (var e : map.entrySet()) copy.put(e.getKey(), deepCopy(e.getValue()));
            return copy;
        }
        return value;                                       // scalars are immutable
    }

    @Override
    public Object put(Object key, Object value) {
        return super.put(key(key), value);
    }

    /** PHP: $a[] = $value — next integer index, i.e. max(int keys) + 1. */
    public PhpArray append(Object value) {
        long next = 0;
        for (Object existing : keySet()) {
            if (existing instanceof Long l && l >= next) next = l + 1;
        }
        super.put(next, value);
        return this;
    }

    /** PHP auto-vivification: $a['x']['y'] = ... creates the inner array on demand. */
    public PhpArray child(Object key) {
        Object norm = key(key);
        Object current = super.get(norm);
        if (current instanceof PhpArray pa) return pa;
        PhpArray created = new PhpArray();
        super.put(norm, created);
        return created;
    }

    /**
     * Case-insensitive replace-in-place, the PhpArray equivalent of PhpRows.set.
     *
     * Needed when the row being edited came from a driver that reports column names
     * in a different case than the PHP (H2 lowercases unquoted identifiers). A plain
     * put("loginPassword", "") would ADD a camelCase duplicate beside the lowercased
     * key, leaving the original value — including a password hash — in the JSON.
     * This replaces the existing key instead, exactly like $row['loginPassword'] = ''
     * in PHP, and appends when absent.
     */
    public PhpArray set(String column, Object value) {
        for (Object k : keySet()) {
            if (k instanceof String s && s.equalsIgnoreCase(column)) {
                super.put(k, value);
                return this;
            }
        }
        super.put(column, value);
        return this;
    }

    @Override
    public Object get(Object key) {
        return super.get(key(key));
    }

    @Override
    public boolean containsKey(Object key) {
        return super.containsKey(key(key));
    }

    /** Converts a PhpRows row into a PhpArray, preserving column order. */
    public static PhpArray of(Map<String, Object> row) {
        PhpArray a = new PhpArray();
        if (row != null) a.putAll(row);
        return a;
    }

    // ---------------------------------------------------------- to JSON

    /** True when json_encode would emit a JSON array rather than an object. */
    public boolean isList() {
        if (isEmpty()) return true;
        long expected = 0;
        for (Object k : keySet()) {
            if (!(k instanceof Long l) || l != expected) return false;
            expected++;
        }
        return true;
    }

    public JsonNode toJsonNode() {
        if (isList()) {
            ArrayNode arr = F.arrayNode(size());
            for (Object v : values()) arr.add(node(v));
            return arr;
        }
        ObjectNode obj = F.objectNode();
        for (Map.Entry<Object, Object> e : entrySet()) {
            obj.set(e.getKey().toString(), node(e.getValue()));
        }
        return obj;
    }

    /** json_encode() equivalent for the value types this port produces. */
    public static JsonNode node(Object v) {
        if (v == null) return NullNode.getInstance();
        if (v instanceof JsonNode jn) return jn;
        if (v instanceof PhpArray pa) return pa.toJsonNode();
        if (v instanceof Map<?, ?> m) {
            ObjectNode obj = F.objectNode();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                obj.set(String.valueOf(key(e.getKey())), node(e.getValue()));
            }
            return obj;
        }
        if (v instanceof Collection<?> c) {
            ArrayNode arr = F.arrayNode(c.size());
            for (Object o : c) arr.add(node(o));
            return arr;
        }
        if (v instanceof Object[] arr) {
            ArrayNode out = F.arrayNode(arr.length);
            for (Object o : arr) out.add(node(o));
            return out;
        }
        if (v instanceof BigDecimal bd) return F.numberNode(bd);
        if (v instanceof Integer i) return F.numberNode(i);
        if (v instanceof Long l) return F.numberNode(l);
        if (v instanceof Double d) return F.numberNode(d);
        if (v instanceof Float f) return F.numberNode(f);
        if (v instanceof Boolean b) return F.booleanNode(b);
        return F.textNode(v.toString());
    }
}
