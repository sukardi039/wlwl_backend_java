package com.wlwl.backend.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MySQL 8 PASSWORD() blocker, solved without migrating any stored hash.
 *
 * If these vectors are right, then binding {@code MysqlPassword.encode(pw)} into
 * {@code loginPassword = ?} returns exactly the row MySQL's own PASSWORD() would
 * have matched — so the port needs no schema change and no password resets.
 */
class MysqlPasswordTest {

    @Test
    @DisplayName("matches MySQL's documented 4.1 hash vectors")
    void knownVectors() {
        // PASSWORD('password') as returned by MySQL 5.x/4.1+
        assertThat(MysqlPassword.encode("password"))
                .isEqualTo("*2470C0C06DEE42FD1618BB99005ADCA2EC9D1E19");
        // verified against openssl sha1(sha1(x)) during the port
        assertThat(MysqlPassword.encode("secret"))
                .isEqualTo("*14E65567ABDB5135D0CFD9A70B3032C179A49EE7");
        assertThat(MysqlPassword.encode("hunter2"))
                .isEqualTo("*58815970BE77B3720276F63DB198B1FA42E5CC02");
        assertThat(MysqlPassword.encode("letmein"))
                .isEqualTo("*D37C49F9CBEFBF8B6F4B165AC703AA271E079004");
    }

    @Test
    @DisplayName("41 characters: '*' plus 40 uppercase hex")
    void shape() {
        String h = MysqlPassword.encode("whatever");
        assertThat(h).hasSize(41).startsWith("*");
        assertThat(h.substring(1)).matches("[0-9A-F]{40}");
    }

    @Test
    @DisplayName("empty password hashes to empty string, exactly like PASSWORD('')")
    void emptyPassword() {
        // PASSWORD('') returns '' — NOT a hash. A user row with an empty stored
        // password must therefore match an empty submitted password.
        assertThat(MysqlPassword.encode("")).isEmpty();
        assertThat(MysqlPassword.encode(null)).isEmpty();
    }

    @Test
    @DisplayName("case-sensitive: the same password always yields the same hash")
    void deterministic() {
        assertThat(MysqlPassword.encode("Secret")).isNotEqualTo(MysqlPassword.encode("secret"));
        assertThat(MysqlPassword.encode("secret")).isEqualTo(MysqlPassword.encode("secret"));
    }
}
