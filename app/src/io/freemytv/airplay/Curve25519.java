package io.freemytv.airplay;

/**
 * X25519 (RFC 7748) and Ed25519 (RFC 8032): our own port of TweetNaCl (public domain), field elements as 16 limbs of
 * 16 bits in a long[16]. Constant time where TweetNaCl is (the ladders swap with masks, never branch on secrets).
 * SHA-512 comes from MessageDigest. Android 9 has neither in its crypto providers, hence the port.
 */
public final class Curve25519 {
    private Curve25519() {}

    // MARK: - Public API

    /** X25519: scalar (32 bytes, clamped here) times the point u (32 bytes). */
    public static byte[] x25519(byte[] scalar, byte[] u) {
        byte[] z = scalar.clone();
        z[31] = (byte) ((z[31] & 127) | 64);
        z[0] &= (byte) 248;
        long[] x = gf(), a = gf(), b = gf(), c = gf(), d = gf(), e = gf(), f = gf();
        unpack25519(x, u);
        set(b, x);
        a[0] = 1;
        d[0] = 1;
        for (int i = 254; i >= 0; --i) {
            int r = (z[i >>> 3] >>> (i & 7)) & 1;
            sel25519(a, b, r);
            sel25519(c, d, r);
            add(e, a, c);
            sub(a, a, c);
            add(c, b, d);
            sub(b, b, d);
            mul(d, e, e);
            mul(f, a, a);
            mul(a, c, a);
            mul(c, b, e);
            add(e, a, c);
            sub(a, a, c);
            mul(b, a, a);
            sub(c, d, f);
            mul(a, c, A24);
            add(a, a, d);
            mul(c, c, a);
            mul(a, d, f);
            mul(d, b, x);
            mul(b, e, e);
            sel25519(a, b, r);
            sel25519(c, d, r);
        }
        inv25519(c, c);
        mul(a, a, c);
        byte[] q = new byte[32];
        pack25519(q, a);
        return q;
    }

    private static final byte[] NINE = new byte[32];
    static { NINE[0] = 9; }

    public static byte[] x25519Public(byte[] scalar) {
        return x25519(scalar, NINE);
    }

    /** Ed25519 public key (32 bytes) of a 32-byte seed. */
    public static byte[] edPublic(byte[] seed) {
        byte[] d = Bytes.sha512(seed);
        clamp(d);
        long[][] p = point();
        scalarbase(p, d);
        byte[] pk = new byte[32];
        pack(pk, p);
        return pk;
    }

    /** Ed25519 signature (R ‖ S, 64 bytes) of m, deterministic. */
    public static byte[] sign(byte[] seed, byte[] pk, byte[] m) {
        byte[] d = Bytes.sha512(seed);
        clamp(d);
        byte[] r = Bytes.sha512(Bytes.slice(d, 32, 64), m);
        reduce(r);
        long[][] p = point();
        scalarbase(p, r);
        byte[] sig = new byte[64];
        pack(sig, p);
        byte[] h = Bytes.sha512(Bytes.slice(sig, 0, 32), pk, m);
        reduce(h);
        long[] x = new long[64];
        for (int i = 0; i < 32; i++) x[i] = r[i] & 0xff;
        for (int i = 0; i < 32; i++) for (int j = 0; j < 32; j++) x[i + j] += (h[i] & 0xff) * (long) (d[j] & 0xff);
        byte[] s = new byte[32];
        modL(s, x);
        System.arraycopy(s, 0, sig, 32, 32);
        return sig;
    }

    /** Checks an Ed25519 signature; false for anything malformed (bad point, S not below L, wrong sizes). */
    public static boolean verify(byte[] pk, byte[] m, byte[] sig) {
        if (pk == null || pk.length != 32 || sig == null || sig.length != 64) return false;
        if (!sLessThanL(sig)) return false;
        long[][] q = point();
        if (!unpackneg(q, pk)) return false;
        byte[] h = Bytes.sha512(Bytes.slice(sig, 0, 32), pk, m);
        reduce(h);
        long[][] p = point();
        scalarmult(p, q, h);
        scalarbase(q, Bytes.slice(sig, 32, 64));
        add(p, q);
        byte[] t = new byte[32];
        pack(t, p);
        return Bytes.same(t, Bytes.slice(sig, 0, 32));
    }

    /** One vector each (RFC 7748 §5.2 #1, RFC 8032 test 1): the on-device self-check at every service start. */
    public static boolean selfCheck() {
        byte[] k = Bytes.unhex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4");
        byte[] u = Bytes.unhex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c");
        if (!Bytes.hex(x25519(k, u)).equals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552")) return false;
        byte[] seed = Bytes.unhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        byte[] pk = edPublic(seed);
        if (!Bytes.hex(pk).equals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")) return false;
        byte[] sig = sign(seed, pk, new byte[0]);
        return Bytes.hex(sig).equals("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
                + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b") && verify(pk, new byte[0], sig);
    }

    // MARK: - Field arithmetic mod 2^255 - 19

    private static final long[] A24 = gf(0xDB41, 1);
    private static final long[] D = gf(0x78a3, 0x1359, 0x4dca, 0x75eb, 0xd8ab, 0x4141, 0x0a4d, 0x0070,
            0xe898, 0x7779, 0x4079, 0x8cc7, 0xfe73, 0x2b6f, 0x6cee, 0x5203);
    private static final long[] D2 = gf(0xf159, 0x26b2, 0x9b94, 0xebd6, 0xb156, 0x8283, 0x149a, 0x00e0,
            0xd130, 0xeef3, 0x80f2, 0x198e, 0xfce7, 0x56df, 0xd9dc, 0x2406);
    private static final long[] X = gf(0xd51a, 0x8f25, 0x2d60, 0xc956, 0xa7b2, 0x9525, 0xc760, 0x692c,
            0xdc5c, 0xfdd6, 0xe231, 0xc0a4, 0x53fe, 0xcd6e, 0x36d3, 0x2169);
    private static final long[] Y = gf(0x6658, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666,
            0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666);
    private static final long[] I = gf(0xa0b0, 0x4a0e, 0x1b27, 0xc4ee, 0xe478, 0xad2f, 0x1806, 0x2f43,
            0xd7a7, 0x3dfb, 0x0099, 0x2b4d, 0xdf0b, 0x4fc1, 0x2480, 0x2b83);

    private static long[] gf(long... init) {
        long[] r = new long[16];
        System.arraycopy(init, 0, r, 0, init.length);
        return r;
    }

    private static void set(long[] r, long[] a) {
        System.arraycopy(a, 0, r, 0, 16);
    }

    private static void car25519(long[] o) {
        for (int i = 0; i < 16; i++) {
            o[i] += 1L << 16;
            long c = o[i] >> 16;
            if (i < 15) o[i + 1] += c - 1;
            else o[0] += 38 * (c - 1);
            o[i] -= c << 16;
        }
    }

    private static void sel25519(long[] p, long[] q, int b) {
        long c = ~(b - 1L);
        for (int i = 0; i < 16; i++) {
            long t = c & (p[i] ^ q[i]);
            p[i] ^= t;
            q[i] ^= t;
        }
    }

    private static void pack25519(byte[] o, long[] n) {
        long[] m = gf(), t = gf();
        set(t, n);
        car25519(t);
        car25519(t);
        car25519(t);
        for (int j = 0; j < 2; j++) {
            m[0] = t[0] - 0xffed;
            for (int i = 1; i < 15; i++) {
                m[i] = t[i] - 0xffff - ((m[i - 1] >> 16) & 1);
                m[i - 1] &= 0xffff;
            }
            m[15] = t[15] - 0x7fff - ((m[14] >> 16) & 1);
            int b = (int) ((m[15] >> 16) & 1);
            m[14] &= 0xffff;
            sel25519(t, m, 1 - b);
        }
        for (int i = 0; i < 16; i++) {
            o[2 * i] = (byte) t[i];
            o[2 * i + 1] = (byte) (t[i] >> 8);
        }
    }

    private static boolean neq25519(long[] a, long[] b) {
        byte[] c = new byte[32], d = new byte[32];
        pack25519(c, a);
        pack25519(d, b);
        return !Bytes.same(c, d);
    }

    private static int par25519(long[] a) {
        byte[] d = new byte[32];
        pack25519(d, a);
        return d[0] & 1;
    }

    private static void unpack25519(long[] o, byte[] n) {
        for (int i = 0; i < 16; i++) o[i] = (n[2 * i] & 0xff) + ((long) (n[2 * i + 1] & 0xff) << 8);
        o[15] &= 0x7fff;
    }

    private static void add(long[] o, long[] a, long[] b) {
        for (int i = 0; i < 16; i++) o[i] = a[i] + b[i];
    }

    private static void sub(long[] o, long[] a, long[] b) {
        for (int i = 0; i < 16; i++) o[i] = a[i] - b[i];
    }

    private static void mul(long[] o, long[] a, long[] b) {
        long[] t = new long[31];
        for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++) t[i + j] += a[i] * b[j];
        for (int i = 0; i < 15; i++) t[i] += 38 * t[i + 16];
        System.arraycopy(t, 0, o, 0, 16);
        car25519(o);
        car25519(o);
    }

    private static void inv25519(long[] o, long[] i) {
        long[] c = gf();
        set(c, i);
        for (int a = 253; a >= 0; a--) {
            mul(c, c, c);
            if (a != 2 && a != 4) mul(c, c, i);
        }
        set(o, c);
    }

    private static void pow2523(long[] o, long[] i) {
        long[] c = gf();
        set(c, i);
        for (int a = 250; a >= 0; a--) {
            mul(c, c, c);
            if (a != 1) mul(c, c, i);
        }
        set(o, c);
    }

    // MARK: - Edwards points (extended coordinates X, Y, Z, T)

    private static long[][] point() {
        return new long[][] {gf(), gf(), gf(), gf()};
    }

    private static void add(long[][] p, long[][] q) {
        long[] a = gf(), b = gf(), c = gf(), d = gf(), t = gf(), e = gf(), f = gf(), g = gf(), h = gf();
        sub(a, p[1], p[0]);
        sub(t, q[1], q[0]);
        mul(a, a, t);
        add(b, p[0], p[1]);
        add(t, q[0], q[1]);
        mul(b, b, t);
        mul(c, p[3], q[3]);
        mul(c, c, D2);
        mul(d, p[2], q[2]);
        add(d, d, d);
        sub(e, b, a);
        sub(f, d, c);
        add(g, d, c);
        add(h, b, a);
        mul(p[0], e, f);
        mul(p[1], h, g);
        mul(p[2], g, f);
        mul(p[3], e, h);
    }

    private static void cswap(long[][] p, long[][] q, int b) {
        for (int i = 0; i < 4; i++) sel25519(p[i], q[i], b);
    }

    private static void pack(byte[] r, long[][] p) {
        long[] tx = gf(), ty = gf(), zi = gf();
        inv25519(zi, p[2]);
        mul(tx, p[0], zi);
        mul(ty, p[1], zi);
        pack25519(r, ty);
        r[31] ^= (byte) (par25519(tx) << 7);
    }

    private static void scalarmult(long[][] p, long[][] q, byte[] s) {
        set(p[0], gf());
        set(p[1], gf(1));
        set(p[2], gf(1));
        set(p[3], gf());
        for (int i = 255; i >= 0; --i) {
            int b = (s[i >>> 3] >>> (i & 7)) & 1;
            cswap(p, q, b);
            add(q, p);
            add(p, p);
            cswap(p, q, b);
        }
    }

    private static void scalarbase(long[][] p, byte[] s) {
        long[][] q = point();
        set(q[0], X);
        set(q[1], Y);
        set(q[2], gf(1));
        mul(q[3], X, Y);
        scalarmult(p, q, s);
    }

    private static boolean unpackneg(long[][] r, byte[] p) {
        long[] t = gf(), chk = gf(), num = gf(), den = gf(), den2 = gf(), den4 = gf(), den6 = gf();
        set(r[2], gf(1));
        unpack25519(r[1], p);
        mul(num, r[1], r[1]);
        mul(den, num, D);
        sub(num, num, r[2]);
        add(den, r[2], den);
        mul(den2, den, den);
        mul(den4, den2, den2);
        mul(den6, den4, den2);
        mul(t, den6, num);
        mul(t, t, den);
        pow2523(t, t);
        mul(t, t, num);
        mul(t, t, den);
        mul(t, t, den);
        mul(r[0], t, den);
        mul(chk, r[0], r[0]);
        mul(chk, chk, den);
        if (neq25519(chk, num)) mul(r[0], r[0], I);
        mul(chk, r[0], r[0]);
        mul(chk, chk, den);
        if (neq25519(chk, num)) return false;
        if (par25519(r[0]) == ((p[31] & 0xff) >> 7)) sub(r[0], gf(), r[0]);
        mul(r[3], r[0], r[1]);
        return true;
    }

    // MARK: - Scalars mod L = 2^252 + 27742317777372353535851937790883648493

    private static final long[] L = {0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9,
            0xde, 0x14, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10};

    private static void clamp(byte[] d) {
        d[0] &= (byte) 248;
        d[31] &= 127;
        d[31] |= 64;
    }

    private static void modL(byte[] r, long[] x) {
        long carry;
        for (int i = 63; i >= 32; --i) {
            carry = 0;
            int j;
            for (j = i - 32; j < i - 12; ++j) {
                x[j] += carry - 16 * x[i] * L[j - (i - 32)];
                carry = (x[j] + 128) >> 8;
                x[j] -= carry << 8;
            }
            x[j] += carry;
            x[i] = 0;
        }
        carry = 0;
        for (int j = 0; j < 32; j++) {
            x[j] += carry - (x[31] >> 4) * L[j];
            carry = x[j] >> 8;
            x[j] &= 255;
        }
        for (int j = 0; j < 32; j++) x[j] -= carry * L[j];
        for (int i = 0; i < 32; i++) {
            x[i + 1] += x[i] >> 8;
            r[i] = (byte) (x[i] & 255);
        }
    }

    /** r (64 bytes) mod L, into its first 32 bytes. */
    private static void reduce(byte[] r) {
        long[] x = new long[64];
        for (int i = 0; i < 64; i++) x[i] = r[i] & 0xff;
        for (int i = 0; i < 64; i++) r[i] = 0;
        modL(r, x);
    }

    /** RFC 8032 §5.1.7: S must be below L (rejects malleable signatures). */
    private static boolean sLessThanL(byte[] sig) {
        for (int i = 31; i >= 0; i--) {
            int s = sig[32 + i] & 0xff, l = (int) L[i];
            if (s != l) return s < l;
        }
        return false;
    }
}
