package io.freemytv.airplay;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM (NIST SP 800-38D) for the 16-byte IVs of AirPlay code pairing, built on AES/ECB: Android's own GCM may
 * refuse IVs that aren't 12 bytes. No associated data; a 16-byte tag. Only for a few short messages (bitwise GHASH).
 */
public final class Gcm {
    private Gcm() {}

    /** ciphertext ‖ tag. */
    public static byte[] encrypt(byte[] key, byte[] iv, byte[] plain) throws GeneralSecurityException {
        Cipher aes = aes(key);
        long[] h = block(aes.doFinal(new byte[16]));
        byte[] j0 = j0(h, iv);
        byte[] out = new byte[plain.length + 16];
        ctr(aes, j0, plain, out);
        byte[] tag = tag(aes, h, j0, out, plain.length);
        System.arraycopy(tag, 0, out, plain.length, 16);
        return out;
    }

    /** The plaintext, or null when the tag doesn't match. */
    public static byte[] decrypt(byte[] key, byte[] iv, byte[] sealed) throws GeneralSecurityException {
        if (sealed.length < 16) return null;
        Cipher aes = aes(key);
        long[] h = block(aes.doFinal(new byte[16]));
        byte[] j0 = j0(h, iv);
        int len = sealed.length - 16;
        byte[] tag = tag(aes, h, j0, sealed, len);
        if (!Bytes.same(tag, Bytes.slice(sealed, len, sealed.length))) return null;
        byte[] out = new byte[len];
        ctr(aes, j0, Bytes.slice(sealed, 0, len), out);
        return out;
    }

    private static Cipher aes(byte[] key) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return c;
    }

    /** The first counter block: IV ‖ 0001 for 12-byte IVs, else GHASH of the IV and its length. */
    private static byte[] j0(long[] h, byte[] iv) {
        if (iv.length == 12) {
            byte[] j = new byte[16];
            System.arraycopy(iv, 0, j, 0, 12);
            j[15] = 1;
            return j;
        }
        long[] x = new long[2];
        absorb(x, h, iv, iv.length);
        xor(x, h, new long[] {0, iv.length * 8L});
        return bytes(x);
    }

    /** CTR from inc32(J0) over in, into out (out may be longer). */
    private static void ctr(Cipher aes, byte[] j0, byte[] in, byte[] out) throws GeneralSecurityException {
        byte[] counter = j0.clone();
        for (int at = 0; at < in.length; at += 16) {
            inc32(counter);
            byte[] ks = aes.doFinal(counter);
            for (int i = 0; i < 16 && at + i < in.length; i++) out[at + i] = (byte) (in[at + i] ^ ks[i]);
        }
    }

    private static byte[] tag(Cipher aes, long[] h, byte[] j0, byte[] cipherText, int len) throws GeneralSecurityException {
        long[] x = new long[2];
        absorb(x, h, cipherText, len);
        xor(x, h, new long[] {0, len * 8L});
        byte[] s = bytes(x), e = aes.doFinal(j0);
        for (int i = 0; i < 16; i++) s[i] ^= e[i];
        return s;
    }

    /** GHASH over data[0..len), zero-padded to whole blocks. */
    private static void absorb(long[] x, long[] h, byte[] data, int len) {
        for (int at = 0; at < len; at += 16) {
            byte[] b = new byte[16];
            System.arraycopy(data, at, b, 0, Math.min(16, len - at));
            xor(x, h, block(b));
        }
    }

    /** x = (x ⊕ b) · h in GF(2^128), GCM's bit order. */
    private static void xor(long[] x, long[] h, long[] b) {
        long x0 = x[0] ^ b[0], x1 = x[1] ^ b[1];
        long z0 = 0, z1 = 0, v0 = h[0], v1 = h[1];
        for (int i = 0; i < 128; i++) {
            long bit = i < 64 ? (x0 >>> (63 - i)) & 1 : (x1 >>> (127 - i)) & 1;
            if (bit != 0) {
                z0 ^= v0;
                z1 ^= v1;
            }
            boolean lsb = (v1 & 1) != 0;
            v1 = (v1 >>> 1) | (v0 << 63);
            v0 >>>= 1;
            if (lsb) v0 ^= 0xE100000000000000L;
        }
        x[0] = z0;
        x[1] = z1;
    }

    private static long[] block(byte[] b) {
        return new long[] {Bytes.longBE(b, 0), Bytes.longBE(b, 8)};
    }

    private static byte[] bytes(long[] x) {
        byte[] b = new byte[16];
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (x[0] >>> (56 - 8 * i));
            b[8 + i] = (byte) (x[1] >>> (56 - 8 * i));
        }
        return b;
    }

    private static void inc32(byte[] c) {
        for (int i = 15; i >= 12; i--) if (++c[i] != 0) break;
    }
}
