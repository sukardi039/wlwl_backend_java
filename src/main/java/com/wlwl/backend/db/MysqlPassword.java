package com.wlwl.backend.db;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MySQL's pre-8.0 {@code PASSWORD()} function, reimplemented in Java.
 *
 * This is the answer to the "MySQL 8 removed PASSWORD()" blocker. The PHP does:
 *
 * <pre>
 *   ... where b.loginName = '...' and b.loginPassword = password('...')
 * </pre>
 *
 * and {@code PASSWORD()} no longer exists on MySQL 8.0.40, so that statement is a
 * hard error today. Two ways out:
 *
 *   (a) change the stored hashes (a data migration, and the old hashes are what
 *       the production rows contain);
 *   (b) compute the SAME hash in the application and bind it as a parameter.
 *
 * (b) is what this class does. It is behaviour-preserving: the SQL becomes
 * {@code loginPassword = ?} with the identical value, so the result set is exactly
 * what MySQL's own PASSWORD() would have produced. No schema change, no password
 * reset, no locked-out users.
 *
 * The algorithm (MySQL 4.1+ "old" 41-byte format):
 *
 * <pre>
 *   step1 = SHA1(password)            // 20 raw bytes
 *   step2 = SHA1(step1)               // 20 raw bytes
 *   return "*" + uppercase_hex(step2) // 1 + 40 = 41 chars
 * </pre>
 *
 * Verified against the well-known vector {@code PASSWORD('password')} =
 * {@code *2470C0C06DEE42FD1618BB99005ADCA2EC9D1E19} (see MysqlPasswordTest).
 *
 * Note the empty-string case: {@code PASSWORD('')} returns {@code ''}, NOT a hash.
 * That matters, because a user row with an empty password would otherwise never
 * match.
 */
public final class MysqlPassword {

    private MysqlPassword() { }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * @param plain the plaintext password as the client submitted it
     * @return the 41-character MySQL 4.1 hash, or {@code ""} for a null/empty password
     */
    public static String encode(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        byte[] step1 = sha1(plain.getBytes(StandardCharsets.UTF_8));
        byte[] step2 = sha1(step1);
        StringBuilder sb = new StringBuilder(41);
        sb.append('*');
        for (byte b : step2) {
            sb.append(HEX[(b >> 4) & 0x0F]);
            sb.append(HEX[b & 0x0F]);
        }
        return sb.toString();
    }

    private static byte[] sha1(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is mandatory in every JRE; this cannot happen.
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}
