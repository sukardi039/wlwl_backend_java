package com.wlwl.backend.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHP has ONE container type: an array is an ordered hash map, and a "list" is only an array whose
 * keys happen to be 0..n-1. {@code json_decode($x, true)} returns that type for both {@code [...]}
 * and {@code {...}}, so decode -> mutate -> encode is lossless by construction.
 *
 * <p>Java has two types, and {@code fromJson} used to preserve the JSON distinction: objects became
 * {@link PhpArray}, arrays became {@link java.util.ArrayList}. That split broke mutating code,
 * because {@code PhpArray.child(key)} only reuses a {@code PhpArray} — given a {@code List} it
 * silently replaced it with an empty array, exactly when PHP would have appended to the array it
 * already had. State that round-tripped through the client was therefore destroyed every step:
 * {@code content.summary} kept only the current step, {@code history} lost its rows, and
 * {@code task_work} lost its counters (which kills orders whose task has passingRate > 1).
 *
 * <p>These tests pin the boundary rule: the decoded tree is {@link PhpArray} all the way down, and
 * re-encoding still reproduces PHP's array-vs-object choice from the keys.
 */
class PhpJsonDecodeFidelityTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static PhpArray decode(String json) throws Exception {
        return (PhpArray) PhpJson.fromJson(M.readTree(json));
    }

    @Test
    @DisplayName("every container in a decoded tree is a PhpArray — no java.util.List survives")
    void decodedTreeHasNoLists() throws Exception {
        PhpArray content = decode("""
                {"summary":[{"phase":"INTRODUCTION"},{"phase":"MORE_ACTIONS"}],
                 "history":[{"step":1,"staff":"Ann"}],
                 "task_work":[{"worked":2}],
                 "message":[],
                 "log":{"action":[[null,null,null]]},
                 "members":{"1":{"staffName":"1","message":[{"0":"hi"}]}},
                 "orders":{"1":{"day":1}},
                 "ordersToComplete":"8"}
                """);

        assertNoList(content, "content");
    }

    /** The invariant that makes this class of bug impossible to reintroduce silently. */
    private static void assertNoList(Object value, String path) {
        assertThat(value)
                .as("%s must be a PhpArray, not %s", path,
                        value == null ? "null" : value.getClass().getName())
                .isInstanceOf(PhpArray.class);

        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (v instanceof Map<?, ?> || v instanceof List<?>) {
                    assertNoList(v, path + "[" + k + "]");
                }
            });
        }
    }

    @Test
    @DisplayName("child() reuses a decoded JSON array instead of replacing it")
    void childReusesDecodedArray() throws Exception {
        PhpArray content = decode("""
                {"summary":[{"phase":"INTRODUCTION"},{"phase":"MORE_ACTIONS"}]}
                """);

        PhpArray summary = content.child("summary");
        summary.append("third");

        assertThat(summary).hasSize(3);
        assertThat(PhpJson.path(content, "summary", 0, "phase")).isEqualTo("INTRODUCTION");
        assertThat(PhpJson.path(content, "summary", 1, "phase")).isEqualTo("MORE_ACTIONS");
        // and the three entries still encode as a JSON array in the original order
        JsonNode encoded = PhpArray.node(content).get("summary");
        assertThat(encoded.isArray()).isTrue();
        assertThat(encoded).hasSize(3);
        assertThat(encoded.get(0).get("phase").asText()).isEqualTo("INTRODUCTION");
    }

    @Test
    @DisplayName("re-encoding reproduces PHP's shape: 0-based -> array, 1-based/named -> object")
    void encodingShapeFollowsKeys() throws Exception {
        JsonNode tree = PhpArray.node(decode("""
                {"zeroBased":[1,2],"oneBased":{"1":"a","2":"b"},"named":{"x":1},"empty":[]}
                """));

        assertThat(tree.get("zeroBased").isArray()).as("0..n-1 encodes as an array").isTrue();
        assertThat(tree.get("oneBased").isObject()).as("1..n encodes as an object").isTrue();
        assertThat(tree.get("named").isObject()).isTrue();
        assertThat(tree.get("empty").isArray()).as("PHP encodes array() as []").isTrue();

        // the whole document is unchanged by decode -> encode (compared as JSON text: JsonNode
        // equality is strict about numeric node types, so 1 and 1L would "differ")
        assertThat(tree.toString()).isEqualTo("""
                {"zeroBased":[1,2],"oneBased":{"1":"a","2":"b"},"named":{"x":1},"empty":[]}""");
    }
}
