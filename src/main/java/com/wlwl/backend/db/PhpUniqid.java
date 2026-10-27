package com.wlwl.backend.db;

import java.time.Instant;

/**
 * PHP's {@code uniqid()} with no prefix, ported literally.
 *
 * 24 call sites in the PHP use {@code uniqid()} to mint primary keys (restkey,
 * sessionId, and many row ids). It is NOT a UUID: it is 13 lowercase hex characters
 * produced by
 *
 * <pre>
 *   sprintf("%08x%05x", seconds_since_epoch, microseconds)
 * </pre>
 *
 * i.e. 8 hex chars of whole seconds followed by 5 hex chars of the microsecond part.
 * The porting notes call this out explicitly — switching to {@code UUID.randomUUID()}
 * would change id width and format, and several columns/queries assume 13 chars.
 *
 * Collision behaviour is also part of the original: two calls in the same
 * microsecond return the same string. PHP has that flaw, so this does too, and
 * callers rely on the fact that it does NOT include random entropy (ids are
 * therefore sortable by creation time, which some admin queries depend on).
 */
public final class PhpUniqid {

    private PhpUniqid() { }

    /** @return 13 lowercase hex chars, e.g. {@code 69123f4a0b1c2} */
    public static String next() {
        Instant now = Instant.now();
        long seconds = now.getEpochSecond();
        long micros = now.getNano() / 1_000;
        return String.format("%08x%05x", seconds, micros);
    }
}
