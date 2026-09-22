package fr.capture.agent.runtime;

import java.nio.charset.Charset;
import java.security.MessageDigest;

/**
 * Stable hashing helpers used for two purposes:
 * <ul>
 *   <li>LOB/BLOB content: we never store the bytes, only their length and a SHA-256 digest.</li>
 *   <li>The {@code mask} option: a masked column value is replaced by a stable, deterministic
 *       token ({@code masked:<12 hex>}), so the same input always yields the same output — a
 *       first pseudonymisation brick that still lets a consumer diff two runs.</li>
 * </ul>
 */
public final class Hashing {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashing() {}

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(data));
        } catch (Throwable t) {
            return "sha256-error";
        }
    }

    public static String sha256Hex(String s) {
        return sha256Hex(s.getBytes(UTF8));
    }

    /** Stable masking token: deterministic for a given input, but not reversible. */
    public static String mask(String s) {
        if (s == null) return null;
        return "masked:" + sha256Hex(s).substring(0, 12);
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
