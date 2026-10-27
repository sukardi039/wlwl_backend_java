package com.wlwl.backend.db;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHP's array-vs-object rule decides the wire format of every nested aggregate in
 * basicinformation/post.php. These tests pin the rule down; if they go red, the
 * Vue client's `Array.isArray` / `Object.keys` checks start returning wrong answers
 * without any error surfacing.
 */
class PhpArrayTest {

    @Test
    @DisplayName("empty array -> [] , never {}")
    void emptyIsArray() {
        assertThat(new PhpArray().toJsonNode().isArray()).isTrue();
    }

    @Test
    @DisplayName("append() produces sequential int keys -> JSON array")
    void appendMakesArray() {
        PhpArray a = new PhpArray();
        a.append("AN").append("DR");
        JsonNode n = a.toJsonNode();
        assertThat(n.isArray()).isTrue();
        assertThat(n.get(0).asText()).isEqualTo("AN");
        assertThat(n.get(1).asText()).isEqualTo("DR");
    }

    @Test
    @DisplayName("string keys -> JSON object")
    void stringKeysMakeObject() {
        PhpArray a = new PhpArray();
        a.put("AN", "Analytical");
        JsonNode n = a.toJsonNode();
        assertThat(n.isObject()).isTrue();
        assertThat(n.get("AN").asText()).isEqualTo("Analytical");
    }

    @Test
    @DisplayName("numeric-string keys are coerced to int; non-sequential -> object with quoted keys")
    void numericStringKeysCoerce() {
        PhpArray a = new PhpArray();
        a.put("5", "five");
        JsonNode n = a.toJsonNode();
        // key 5 alone is 0..n-1? no -> object
        assertThat(n.isObject()).isTrue();
        assertThat(n.has("5")).isTrue();
        assertThat(a.containsKey(5L)).isTrue();
        assertThat(a.containsKey("5")).isTrue();
    }

    @Test
    @DisplayName("leading-zero and decimal strings stay string keys, exactly like zend_hash")
    void nonCanonicalKeysStayStrings() {
        assertThat(PhpArray.key("01")).isEqualTo("01");
        assertThat(PhpArray.key("1.0")).isEqualTo("1.0");
        assertThat(PhpArray.key("+5")).isEqualTo("+5");
        assertThat(PhpArray.key(" 5")).isEqualTo(" 5");
        assertThat(PhpArray.key("0")).isEqualTo(0L);
        assertThat(PhpArray.key("-3")).isEqualTo(-3L);
        assertThat(PhpArray.key(null)).isEqualTo("");
    }

    @Test
    @DisplayName("keys 0,1 reached by path -> array; keys 1,2 -> object")
    void sequentialRule() {
        PhpArray a = new PhpArray();
        a.put("0", "x");
        a.put("1", "y");
        assertThat(a.toJsonNode().isArray()).isTrue();

        PhpArray b = new PhpArray();
        b.put("1", "x");
        b.put("2", "y");
        assertThat(b.toJsonNode().isObject()).isTrue();
    }

    @Test
    @DisplayName("child() auto-vivifies nested arrays like $a['x']['y'] = ...")
    void autovivification() {
        PhpArray a = new PhpArray();
        a.child("base_lst").child("styles").append("AN");
        a.child("base_lst").child("styles").append("DR");
        JsonNode n = a.toJsonNode();
        assertThat(n.get("base_lst").get("styles").isArray()).isTrue();
        assertThat(n.get("base_lst").get("styles")).hasSize(2);
    }
}
