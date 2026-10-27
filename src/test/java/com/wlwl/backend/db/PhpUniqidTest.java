package com.wlwl.backend.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * uniqid() is used for 24 primary keys in the PHP. It is 13 hex chars, not a UUID,
 * and code elsewhere depends on that width.
 */
class PhpUniqidTest {

    @Test
    @DisplayName("13 lowercase hex characters, like PHP's uniqid()")
    void shape() {
        String id = PhpUniqid.next();
        assertThat(id).hasSize(13).matches("[0-9a-f]{13}");
    }

    @Test
    @DisplayName("ids increase over time (seconds prefix), so they sort by creation")
    void sortable() throws Exception {
        String first = PhpUniqid.next();
        Thread.sleep(1100);            // cross a whole-second boundary
        String second = PhpUniqid.next();
        assertThat(first.compareTo(second)).isLessThan(0);
    }

    @Test
    @DisplayName("first 8 chars are the epoch second in hex, last 5 the microseconds")
    void decodesLikePhp() {
        long before = System.currentTimeMillis() / 1000;
        String id = PhpUniqid.next();
        long after = System.currentTimeMillis() / 1000;

        long seconds = Long.parseLong(id.substring(0, 8), 16);
        assertThat(seconds).isBetween(before, after);
        // %05x of a microsecond value 0..999999 always occupies 5 chars
        assertThat(id.substring(8)).matches("[0-9a-f]{5}");
    }
}
