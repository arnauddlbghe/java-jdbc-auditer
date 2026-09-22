package fr.capture.agent.proxy;

import fr.capture.agent.runtime.Hashing;
import fr.capture.agent.trace.Json;

import java.sql.Blob;
import java.sql.Clob;

/**
 * Renders a bound parameter or a result-set cell as a JSON value fragment.
 * <p>
 * Rules:
 * <ul>
 *   <li>{@code null} → {@code null}.</li>
 *   <li>LOB content (byte[], {@link Blob}, {@link Clob}) → never stored; an object
 *       {@code {"lob":"<kind>","len":N,"sha256":"..."}} carrying only length and digest.</li>
 *   <li>Masked column → stable {@code masked:<hash>} token.</li>
 *   <li>Anything else → the value stringified and quoted. Type information is carried separately
 *       (parameter type or column type), so a stringified value is unambiguous.</li>
 * </ul>
 * Reading LOB length/bytes is done through random-access accessors ({@code Blob#length},
 * {@code Blob#getBytes}) which do not consume the application's streams; still best effort and
 * fully guarded.
 */
public final class ValueFormat {

    private static final int MAX_STR = 8192; // guard against unbounded trace lines

    private ValueFormat() {}

    /** Returns a raw JSON value fragment (already escaped where needed). Never throws. */
    public static String valueJson(Object v, boolean masked) {
        try {
            if (v == null) return "null";

            if (v instanceof byte[]) {
                byte[] b = (byte[]) v;
                return lob("bytes", b.length, Hashing.sha256Hex(b));
            }
            if (v instanceof Blob) {
                Blob blob = (Blob) v;
                long len = blob.length();
                int read = (int) Math.min(len, Integer.MAX_VALUE);
                String hash = Hashing.sha256Hex(blob.getBytes(1, read));
                return lob("blob", len, hash);
            }
            if (v instanceof Clob) {
                Clob clob = (Clob) v;
                long len = clob.length();
                int read = (int) Math.min(len, Integer.MAX_VALUE);
                String hash = Hashing.sha256Hex(clob.getSubString(1, read));
                return lob("clob", len, hash);
            }

            String s = String.valueOf(v);
            if (masked) return Json.quote(Hashing.mask(s));
            if (s.length() > MAX_STR) s = s.substring(0, MAX_STR) + "...(" + s.length() + ")";
            return Json.quote(s);
        } catch (Throwable t) {
            return Json.quote("<unreadable:" + t.getClass().getSimpleName() + ">");
        }
    }

    private static String lob(String kind, long len, String sha256) {
        return Json.obj().add("lob", kind).add("len", len).add("sha256", sha256).end();
    }
}
