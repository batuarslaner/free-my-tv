package io.freemytv.airplay;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Small byte helpers (API 28 has no readAllBytes or HexFormat). */
public final class Bytes {
    private Bytes() {}

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String hex(byte[] b) {
        return hex(b, 0, b.length);
    }

    public static String hex(byte[] b, int off, int len) {
        char[] out = new char[len * 2];
        for (int i = 0; i < len; i++) {
            out[i * 2] = HEX[(b[off + i] >> 4) & 15];
            out[i * 2 + 1] = HEX[b[off + i] & 15];
        }
        return new String(out);
    }

    public static byte[] unhex(String s) {
        if (s.length() % 2 != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16), lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("not hex");
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }

    public static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    public static byte[] slice(byte[] b, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(b, from, out, 0, out.length);
        return out;
    }

    public static void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            int n = in.read(b, off, len);
            if (n < 0) throw new EOFException();
            off += n;
            len -= n;
        }
    }

    public static int intLE(byte[] b, int off) {
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8 | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
    }

    public static int intBE(byte[] b, int off) {
        return (b[off] & 0xff) << 24 | (b[off + 1] & 0xff) << 16 | (b[off + 2] & 0xff) << 8 | (b[off + 3] & 0xff);
    }

    public static long longLE(byte[] b, int off) {
        return (intLE(b, off) & 0xffffffffL) | (long) intLE(b, off + 4) << 32;
    }

    public static long longBE(byte[] b, int off) {
        return (intBE(b, off) & 0xffffffffL) << 32 | (intBE(b, off + 4) & 0xffffffffL);
    }

    public static float floatLE(byte[] b, int off) {
        return Float.intBitsToFloat(intLE(b, off));
    }

    public static byte[] sha512(byte[]... parts) {
        return digest("SHA-512", parts);
    }

    public static byte[] sha256(byte[]... parts) {
        return digest("SHA-256", parts);
    }

    private static byte[] digest(String algorithm, byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What the log shows instead of a key: the first 4 bytes of its SHA-256. */
    public static String fingerprint(byte[] key) {
        return hex(sha256(key), 0, 4);
    }

    public static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** Constant time, for MACs and signatures. */
    public static boolean same(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }
}
