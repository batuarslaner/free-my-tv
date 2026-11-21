package io.freemytv.airplay;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SRP-6a (RFC 5054 hashing: k = H(N | PAD(g)), u = H(PAD(A) | PAD(B))), the server side, as Apple's AirPlay code
 * pairing uses it: the 2048-bit group, SHA-1, a 16-byte salt, and Apple's session key K = H(S | 0) ‖ H(S | 1)
 * (4-byte counters, 40 bytes). M1 = H(H(N) xor H(g) | H(I) | s | A | B | K), M2 = H(A | M1 | K).
 */
public final class Srp {
    public static final BigInteger N_1024 = new BigInteger(
            "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C9C256576D674DF7496EA81D3383B4813D692C6E0E0D5D8E2"
            + "50B98BE48E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B297BCF1885C529F566660E57EC68EDBC3C05726CC0"
            + "2FD4CBF4976EAA9AFD5138FE8376435B9FC61D2FC0EB06E3", 16);
    public static final BigInteger N_2048 = new BigInteger(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4A099ED8193E0757767A13DD52312AB"
            + "4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEA"
            + "A80D740ADBF4FF747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E6"
            + "88F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6AF874E7303CE53299CCC041C7BC308D8"
            + "2A5698F3A8D0C38271AE35F8E9DBFBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73", 16);
    public static final BigInteger G = BigInteger.valueOf(2);

    private final BigInteger n, g;
    private final String hash;
    private final boolean appleKey;
    private final int size;

    public Srp(BigInteger n, BigInteger g, String hash, boolean appleKey) {
        this.n = n;
        this.g = g;
        this.hash = hash;
        this.appleKey = appleKey;
        this.size = (n.bitLength() + 7) / 8;
    }

    /** What AirPlay code pairing uses. */
    public static Srp airplay() {
        return new Srp(N_2048, G, "SHA-1", true);
    }

    // MARK: - The pieces (public for the RFC 5054 vectors)

    public BigInteger k() {
        return num(h(bytes(n), pad(g)));
    }

    public BigInteger x(byte[] salt, String user, String password) {
        return num(h(salt, h(utf8(user + ":" + password))));
    }

    public BigInteger verifier(byte[] salt, String user, String password) {
        return g.modPow(x(salt, user, password), n);
    }

    public BigInteger serverPublic(BigInteger v, BigInteger b) {
        return k().multiply(v).add(g.modPow(b, n)).mod(n);
    }

    public BigInteger u(BigInteger a, BigInteger b) {
        return num(h(pad(a), pad(b)));
    }

    /** The premaster secret S = (A · v^u)^b mod N. */
    public BigInteger serverSecret(BigInteger a, BigInteger v, BigInteger b) {
        BigInteger bigB = serverPublic(v, b);
        return a.multiply(v.modPow(u(a, bigB), n)).modPow(b, n);
    }

    /** K: H(S), or Apple's H(S | 00 00 00 00) ‖ H(S | 00 00 00 01). */
    public byte[] sessionKey(BigInteger s) {
        if (!appleKey) return h(bytes(s));
        return Bytes.concat(h(bytes(s), new byte[4]), h(bytes(s), new byte[] {0, 0, 0, 1}));
    }

    public byte[] m1(String user, byte[] salt, BigInteger a, BigInteger b, byte[] key) {
        byte[] hn = h(bytes(n)), hg = h(bytes(g));
        for (int i = 0; i < hn.length; i++) hn[i] ^= hg[i];
        return h(hn, h(utf8(user)), salt, bytes(a), bytes(b), key);
    }

    public byte[] m2(BigInteger a, byte[] m1, byte[] key) {
        return h(bytes(a), m1, key);
    }

    // MARK: - One pairing, server side

    public static final class Server {
        private final Srp srp;
        private final String user;
        private final byte[] salt;
        private final BigInteger v, b, bigB;
        private byte[] key;

        /** b: 32 random bytes (or a test's). */
        public Server(Srp srp, String user, String password, byte[] salt, byte[] b) {
            this.srp = srp;
            this.user = user;
            this.salt = salt.clone();
            this.v = srp.verifier(salt, user, password);
            this.b = new BigInteger(1, b);
            this.bigB = srp.serverPublic(v, this.b);
        }

        public byte[] salt() {
            return salt.clone();
        }

        /** B, as sent (minimal big-endian bytes). */
        public byte[] publicKey() {
            return bytes(bigB);
        }

        /** Checks the client's proof; returns M2, or null when the password (the code) was wrong. */
        public byte[] verify(byte[] clientPublic, byte[] clientProof) {
            BigInteger a = new BigInteger(1, clientPublic);
            if (a.mod(srp.n).signum() == 0) return null;   // A = 0 (mod N) would make S predictable
            BigInteger s = a.multiply(v.modPow(srp.u(a, bigB), srp.n)).modPow(b, srp.n);
            byte[] k = srp.sessionKey(s);
            byte[] expected = srp.m1(user, salt, a, bigB, k);
            if (!MessageDigest.isEqual(expected, clientProof)) return null;
            key = k;
            return srp.m2(a, expected, k);
        }

        /** The session key K, once verified. */
        public byte[] key() {
            return key;
        }
    }

    // MARK: - Helpers

    private byte[] h(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance(hash);
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigInteger num(byte[] b) {
        return new BigInteger(1, b);
    }

    /** Minimal unsigned big-endian bytes. */
    public static byte[] bytes(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) return Bytes.slice(b, 1, b.length);
        return b;
    }

    private byte[] pad(BigInteger v) {
        byte[] b = bytes(v), out = new byte[size];
        System.arraycopy(b, 0, out, size - b.length, b.length);
        return out;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
