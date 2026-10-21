import io.freemytv.airplay.Advert;
import io.freemytv.airplay.AudioStream;
import io.freemytv.airplay.Bytes;
import io.freemytv.airplay.Curve25519;
import io.freemytv.airplay.FairPlay;
import io.freemytv.airplay.Log;
import io.freemytv.airplay.MirrorStream;
import io.freemytv.airplay.Ntp;
import io.freemytv.airplay.Pacing;
import io.freemytv.airplay.Pairing;
import io.freemytv.airplay.Plist;
import io.freemytv.airplay.Receiver;
import io.freemytv.airplay.Rtsp;
import io.freemytv.airplay.StreamKeys;
import io.freemytv.fairplay.FairPlayVectors;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The AirPlay receiver's self-test (./build.sh --selftest [captureDir]), on a plain JDK 17: curve vectors and a
 * cross-check against the JDK's own X25519/Ed25519, bplist, RTSP, the Bonjour records, the FairPlay plugin's
 * vectors and a whole mirroring session over loopback, ending in "SelfTest: all N checks passed".
 */
public class SelfTest {
    private static int checks;
    private static final List<String> LOG = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        Log.sink = line -> {
            LOG.add(line);
            if (System.getenv("SELFTEST_VERBOSE") != null) System.out.println("  log: " + line);
        };
        x25519();
        ed25519();
        jdkCrossCheck();
        pairingLoopback();
        plist();
        captures(new File("tools/airplay_selftest/data"));
        if (args.length > 0 && !args[0].isEmpty()) captures(new File(args[0]));
        advert();
        rtspFuzz();
        fairPlay();
        mirrorDecrypt();
        clockAndPacing();
        audioPieces();
        srpVectors();
        gcm();
        settingsFile();
        codePairing(Advert.CODE_FIRST);
        codePairing(Advert.CODE_ALWAYS);
        receiverLoopback();
        System.out.println("SelfTest: all " + checks + " checks passed");
        System.exit(0);
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            System.out.println("SelfTest FAILED: " + what);
            System.exit(1);
        }
        checks++;
    }

    static byte[] hex(String s) {
        return Bytes.unhex(s.replace(" ", ""));
    }

    // MARK: - Curves

    static void x25519() {
        // RFC 7748 §5.2
        check(Bytes.hex(Curve25519.x25519(hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")))
                .equals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"), "RFC 7748 5.2 vector 1");
        check(Bytes.hex(Curve25519.x25519(hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493")))
                .equals("95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957"), "RFC 7748 5.2 vector 2");
        byte[] k = new byte[32], u = new byte[32];
        k[0] = 9;
        u[0] = 9;
        for (int i = 1; i <= 1000; i++) {
            byte[] r = Curve25519.x25519(k, u);
            u = k;
            k = r;
            if (i == 1) check(Bytes.hex(k).equals("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079"), "RFC 7748 5.2 iterated once");
        }
        check(Bytes.hex(k).equals("684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51"), "RFC 7748 5.2 iterated 1000 times");
        // RFC 7748 §6.1
        byte[] a = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        byte[] b = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
        byte[] aPub = Curve25519.x25519Public(a), bPub = Curve25519.x25519Public(b);
        check(Bytes.hex(aPub).equals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), "RFC 7748 6.1 Alice's key");
        check(Bytes.hex(bPub).equals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"), "RFC 7748 6.1 Bob's key");
        String shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742";
        check(Bytes.hex(Curve25519.x25519(a, bPub)).equals(shared), "RFC 7748 6.1 shared secret (Alice)");
        check(Bytes.hex(Curve25519.x25519(b, aPub)).equals(shared), "RFC 7748 6.1 shared secret (Bob)");
        check(Curve25519.selfCheck(), "on-device self-check");
    }

    static void ed25519() {
        String[][] vectors = {   // RFC 8032 §7.1 TEST 1-3: seed, public key, message, signature
            {"9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
             "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
             "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"},
            {"4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
             "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
             "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"},
            {"c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
             "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
             "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"},
        };
        for (int t = 0; t < vectors.length; t++) {
            String[] v = vectors[t];
            byte[] seed = hex(v[0]), msg = hex(v[2]);
            byte[] pk = Curve25519.edPublic(seed);
            check(Bytes.hex(pk).equals(v[1]), "RFC 8032 TEST " + (t + 1) + " public key");
            byte[] sig = Curve25519.sign(seed, pk, msg);
            check(Bytes.hex(sig).equals(v[3]), "RFC 8032 TEST " + (t + 1) + " signature");
            check(Curve25519.verify(pk, msg, sig), "RFC 8032 TEST " + (t + 1) + " verifies");
            byte[] bad = sig.clone();
            bad[5] ^= 1;
            check(!Curve25519.verify(pk, msg, bad), "RFC 8032 TEST " + (t + 1) + " rejects a changed signature");
            byte[] other = Arrays.copyOf(msg, msg.length + 1);
            check(!Curve25519.verify(pk, other, sig), "RFC 8032 TEST " + (t + 1) + " rejects another message");
        }
        // S + L must be refused (malleability).
        byte[] seed = hex(vectors[0][0]);
        byte[] pk = Curve25519.edPublic(seed);
        byte[] sig = Curve25519.sign(seed, pk, new byte[0]);
        BigInteger l = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
        BigInteger s = new BigInteger(1, reverse(Arrays.copyOfRange(sig, 32, 64))).add(l);
        byte[] sPlusL = reverse(toFixed(s, 32));
        System.arraycopy(sPlusL, 0, sig, 32, 32);
        check(!Curve25519.verify(pk, new byte[0], sig), "Ed25519 rejects S + L");
        check(!Curve25519.verify(new byte[31], new byte[0], new byte[64]), "Ed25519 rejects a short key");
    }

    /** 1000 random inputs: our X25519 and Ed25519 against the JDK's. */
    static void jdkCrossCheck() throws Exception {
        SecureRandom rnd = new SecureRandom();
        KeyFactory xdh = KeyFactory.getInstance("XDH");
        int agree = 0, signed = 0, verified = 0, keys = 0;
        for (int i = 0; i < 1000; i++) {
            byte[] scalar = new byte[32], u = new byte[32];
            rnd.nextBytes(scalar);
            rnd.nextBytes(u);
            u[31] &= 0x7f;
            PrivateKey priv = xdh.generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, scalar));
            PublicKey pub = xdh.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, new BigInteger(1, reverse(u))));
            KeyAgreement ka = KeyAgreement.getInstance("XDH");
            ka.init(priv);
            ka.doPhase(pub, true);
            byte[] theirs;
            try {
                theirs = ka.generateSecret();
            } catch (Exception e) {   // an all-zero result (low-order point): the JDK refuses, skip it
                continue;
            }
            if (Arrays.equals(theirs, Curve25519.x25519(scalar, u))) agree++;
            else check(false, "X25519 differs from the JDK for scalar " + Bytes.hex(scalar) + " u " + Bytes.hex(u));

            final byte[] seed = new byte[32];
            rnd.nextBytes(seed);
            byte[] msg = new byte[rnd.nextInt(200)];
            rnd.nextBytes(msg);
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            gen.initialize(NamedParameterSpec.ED25519, new SecureRandom() {
                @Override public void nextBytes(byte[] bytes) {
                    System.arraycopy(seed, 0, bytes, 0, bytes.length);
                }
            });
            KeyPair kp = gen.generateKeyPair();
            byte[] ourPk = Curve25519.edPublic(seed);
            if (Arrays.equals(encode(((EdECPublicKey) kp.getPublic()).getPoint()), ourPk)) keys++;
            Signature js = Signature.getInstance("Ed25519");
            js.initSign(kp.getPrivate());
            js.update(msg);
            byte[] jdkSig = js.sign();
            byte[] ourSig = Curve25519.sign(seed, ourPk, msg);
            if (Arrays.equals(jdkSig, ourSig)) signed++;
            Signature jv = Signature.getInstance("Ed25519");
            jv.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, decode(ourPk))));
            jv.update(msg);
            if (jv.verify(ourSig) && Curve25519.verify(ourPk, msg, jdkSig)) verified++;
        }
        check(agree >= 999, "X25519 agrees with the JDK on random inputs (" + agree + ")");
        check(keys == 1000, "Ed25519 public keys agree with the JDK (" + keys + ")");
        check(signed == 1000, "Ed25519 signatures agree with the JDK (" + signed + ")");
        check(verified == 1000, "Ed25519 signatures verify both ways (" + verified + ")");
    }

    static byte[] encode(EdECPoint p) {
        byte[] y = reverse(toFixed(p.getY(), 32));
        if (p.isXOdd()) y[31] |= (byte) 0x80;
        return y;
    }

    static EdECPoint decode(byte[] pk) {
        byte[] y = pk.clone();
        boolean odd = (y[31] & 0x80) != 0;
        y[31] &= 0x7f;
        return new EdECPoint(odd, new BigInteger(1, reverse(y)));
    }

    static byte[] toFixed(BigInteger v, int len) {
        byte[] b = v.toByteArray(), out = new byte[len];
        int n = Math.min(b.length, len);
        System.arraycopy(b, b.length - n, out, len - n, n);
        return out;
    }

    static byte[] reverse(byte[] b) {
        byte[] r = new byte[b.length];
        for (int i = 0; i < b.length; i++) r[i] = b[b.length - 1 - i];
        return r;
    }

    // MARK: - Pairing

    /** Plays the sender's side of /pair-setup and /pair-verify against Pairing. */
    static void pairingLoopback() throws Exception {
        Advert.Identity id = identity();
        Pairing server = new Pairing(id);
        Sender sender = new Sender();
        check(Arrays.equals(server.setup(sender.edPk), id.edPublic), "pair-setup returns our key");
        byte[] reply = server.verify(sender.verify1());
        check(reply.length == 96, "pair-verify 1 reply size");
        byte[] step2 = sender.verify2(reply, id.edPublic);
        check(sender.serverSignatureOk, "the sender verifies our pair-verify signature");
        check(server.verify(step2).length == 0 && server.verified(), "pair-verify 2 accepts the sender's signature");
        check(Arrays.equals(server.secret(), sender.secret), "both sides share the ECDH secret");

        Pairing other = new Pairing(id);
        Sender liar = new Sender();
        byte[] r = other.verify(liar.verify1());
        byte[] forged = liar.verify2(r, id.edPublic);
        forged[20] ^= 1;
        boolean refused = false;
        try {
            other.verify(forged);
        } catch (IOException e) {
            refused = true;
        }
        check(refused && !other.verified() && other.secret() == null, "pair-verify refuses a bad signature");
    }

    /** An iPhone's side of pairing, from the protocol description. */
    static final class Sender {
        final byte[] edSeed, edPk;

        Sender() {
            edSeed = random(32);
            edPk = Curve25519.edPublic(edSeed);
        }

        /** The same device (its long-term key) on a new connection. */
        Sender(Sender device) {
            edSeed = device.edSeed;
            edPk = device.edPk;
        }

        final byte[] ecdh = random(32), ecdhPk = Curve25519.x25519Public(ecdh);
        byte[] secret;
        boolean serverSignatureOk;

        byte[] verify1() {
            return Bytes.concat(new byte[] {1, 0, 0, 0}, ecdhPk, edPk);
        }

        byte[] verify2(byte[] reply, byte[] serverEdPk) throws Exception {
            byte[] serverEcdh = Arrays.copyOfRange(reply, 0, 32);
            secret = Curve25519.x25519(ecdh, serverEcdh);
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOf(Bytes.sha512(Bytes.ascii("Pair-Verify-AES-Key"), secret), 16), "AES"),
                    new IvParameterSpec(Arrays.copyOf(Bytes.sha512(Bytes.ascii("Pair-Verify-AES-IV"), secret), 16)));
            byte[] serverSig = c.update(Arrays.copyOfRange(reply, 32, 96));
            serverSignatureOk = Curve25519.verify(serverEdPk, Bytes.concat(serverEcdh, ecdhPk), serverSig);
            byte[] sig = Curve25519.sign(edSeed, edPk, Bytes.concat(ecdhPk, serverEcdh));
            return Bytes.concat(new byte[4], c.update(sig));
        }
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    static Advert.Identity identity() {
        return new Advert.Identity("02:11:22:33:44:55", "b08f5a79-db29-4384-b456-a4784d9e6055", random(32));
    }

    // MARK: - bplist and RTSP

    @SuppressWarnings("unchecked")
    static void plist() throws Exception {
        List<Object> list = new ArrayList<>(Arrays.asList(1L, -5L, 300L, 70000L, 5_000_000_000L, Long.MIN_VALUE, 1.5,
                true, false, "short", "a string longer than fifteen characters", "çé ünïcode ✓", new byte[] {1, 2, 3},
                new byte[40], new Date(1_700_000_000_000L)));
        Map<String, Object> inner = Plist.map("streams", new ArrayList<>(Collections.singletonList(
                Plist.map("type", 110L, "streamConnectionID", -3907568444900622110L))));
        Map<String, Object> root = Plist.map("list", list, "inner", inner, "empty", new ArrayList<>(), "name", "Free My TV");
        for (int i = 0; i < 300; i++) list.add("item " + i);   // past 256 objects: 2-byte references
        Map<String, Object> back = Plist.dict(Plist.write(root));
        List<Object> l = (List<Object>) back.get("list");
        check(l.size() == list.size(), "bplist round trip: array size");
        boolean same = true;
        for (int i = 0; i < list.size(); i++) {
            Object a = list.get(i), b = l.get(i);
            same &= a instanceof byte[] ? Arrays.equals((byte[]) a, (byte[]) b) : a.equals(b);
        }
        check(same, "bplist round trip: every value");
        Map<String, Object> stream = (Map<String, Object>) ((List<Object>) ((Map<String, Object>) back.get("inner")).get("streams")).get(0);
        check(Plist.number(stream, "streamConnectionID", 0) == -3907568444900622110L, "bplist round trip: uint64 id");
        check("Free My TV".equals(back.get("name")) && ((List<?>) back.get("empty")).isEmpty(), "bplist round trip: strings, empty array");

        // {"id": 16-byte integer}, the way Apple writes streamConnectionID (a uint64 above 2^63 here).
        byte[] big = Bytes.concat(Bytes.ascii("bplist00"),
                new byte[] {(byte) 0xd1, 1, 2},                                     // 8: dict, key 1, value 2
                new byte[] {0x52, 'i', 'd'},                                         // 11: "id"
                hex("14 0000000000000000 c9c4b48c7b8bafe2"),                         // 14: int, 16 bytes
                new byte[] {8, 11, 14},                                              // 31: offsets
                hex("000000000000 01 01 0000000000000003 0000000000000000 000000000000001f"));
        check(Plist.number(Plist.dict(big), "id", 0) == 0xc9c4b48c7b8bafe2L, "bplist 16-byte integer");
    }

    /** Captured requests (java-airplay's one_mirroring_app, or a folder given on the command line). */
    @SuppressWarnings("unchecked")
    static void captures(File dir) throws Exception {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".bin"));
        check(files != null && files.length > 0, "captures in " + dir);
        Arrays.sort(files);
        for (File f : files) {
            byte[] b = Files.readAllBytes(f.toPath());
            if (new String(b, 0, Math.min(b.length, 5), StandardCharsets.US_ASCII).matches("RTSP/|HTTP/")) continue;
            Rtsp.Request r;
            try {
                r = Rtsp.read(new ByteArrayInputStream(b));
            } catch (java.io.EOFException e) {
                // Some captures lost their CRs (text bodies came out a byte short), one lost its body entirely.
                String s = new String(b, StandardCharsets.ISO_8859_1).replace("\r\n", "\n").replace("\n", "\r\n");
                try {
                    r = Rtsp.read(new ByteArrayInputStream(s.getBytes(StandardCharsets.ISO_8859_1)));
                } catch (java.io.EOFException again) {
                    check(true, "a cut capture is refused cleanly: " + f.getName());
                    continue;
                }
            }
            check(r != null && r.body.length == Integer.parseInt(r.header("Content-Length") == null ? "0" : r.header("Content-Length")),
                    "parses " + f.getName());
            if (Plist.is(r.body)) check(Plist.read(r.body) != null, "bplist in " + f.getName());
        }
        File info = new File(dir, "01_RTSP_GET_info_request.bin");
        if (info.exists()) {
            Rtsp.Request r = Rtsp.read(new ByteArrayInputStream(Files.readAllBytes(info.toPath())));
            check(r.method.equals("GET") && r.path().equals("/info") && "0".equals(r.header("cseq")), "GET /info request line");
            check(((List<Object>) Plist.dict(r.body).get("qualifier")).contains("txtAirPlay"), "GET /info qualifier");
        }
        File setup = new File(dir, "06_RTSP_SETUP_request.bin");
        if (setup.exists()) {
            Rtsp.Request r = Rtsp.read(new ByteArrayInputStream(Files.readAllBytes(setup.toPath())));
            Map<String, Object> p = Plist.dict(r.body);
            check(r.method.equals("SETUP") && Plist.data(p, "ekey").length == 72 && Plist.data(p, "eiv").length == 16,
                    "SETUP ekey and eiv");
            check(Plist.number(p, "timingPort", 0) == 59001 && "NTP".equals(p.get("timingProtocol")), "SETUP timing");
            check(r.path().equals("/12304426294006337347"), "SETUP session path");
            // The FairPlay key of this real session unwraps (no known answer: it must just work).
            File fp = new File(dir, "05_RTSP_POST_fp_setup_request.bin");
            Rtsp.Request m = Rtsp.read(new ByteArrayInputStream(Files.readAllBytes(fp.toPath())));
            check(m.body.length == 164 && io.freemytv.fairplay.FairPlay.decryptKey(m.body, Plist.data(p, "ekey")).length == 16,
                    "FairPlay unwraps the captured session's ekey");
        }
        Rtsp.Request lf = Rtsp.read(new ByteArrayInputStream(
                "GET_PARAMETER rtsp://10.0.0.2/1 RTSP/1.0\nContent-Length: 7\nCSeq: 7\n\nvolume\n".getBytes(StandardCharsets.US_ASCII)));
        check("volume\n".equals(new String(lf.body, StandardCharsets.US_ASCII)) && "7".equals(lf.header("CSEQ")), "bare-LF request");
    }

    static void rtspFuzz() throws Exception {
        Random rnd = new Random(42);
        File[] files = new File("tools/airplay_selftest/data").listFiles((d, n) -> n.endsWith(".bin"));
        List<byte[]> seeds = new ArrayList<>();
        for (File f : files) seeds.add(Files.readAllBytes(f.toPath()));
        int parsed = 0, refused = 0;
        for (int i = 0; i < 20000; i++) {
            byte[] b;
            if (i % 10 == 0) {
                b = new byte[rnd.nextInt(400)];
                rnd.nextBytes(b);
            } else {
                b = seeds.get(rnd.nextInt(seeds.size())).clone();
                int edits = 1 + rnd.nextInt(8);
                for (int e = 0; e < edits; e++) {
                    int at = rnd.nextInt(b.length);
                    switch (rnd.nextInt(3)) {
                        case 0: b[at] = (byte) rnd.nextInt(256); break;
                        case 1: b = Arrays.copyOf(b, at); break;
                        default: b[at] = (byte) "\r\n: 0123456789".charAt(rnd.nextInt(14));
                    }
                    if (b.length == 0) break;
                }
            }
            try {
                Rtsp.Request r = Rtsp.read(new ByteArrayInputStream(b));
                parsed++;
                if (r != null && r.body.length > 0) {
                    try {
                        Plist.read(r.body);
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException e) {
                refused++;
            } catch (RuntimeException | OutOfMemoryError e) {
                check(false, "RTSP/bplist fuzz input " + i + " threw " + e);
            }
        }
        check(parsed > 1000 && refused > 1000, "RTSP fuzz: " + parsed + " parsed, " + refused + " refused, nothing else");
        // bplist fuzz on its own: mutations of a real plist.
        byte[] seed = Plist.write(Advert.info(identity(), "Free My TV"));
        int ok = 0;
        for (int i = 0; i < 20000; i++) {
            byte[] b = seed.clone();
            for (int e = 0; e < 1 + rnd.nextInt(4); e++) b[rnd.nextInt(b.length)] = (byte) rnd.nextInt(256);
            try {
                Plist.read(b);
                ok++;
            } catch (IOException ignored) {
            } catch (RuntimeException | OutOfMemoryError | StackOverflowError e) {
                check(false, "bplist fuzz input " + i + " threw " + e);
            }
        }
        check(true, "bplist fuzz: " + ok + " of 20000 mutations still parse, the rest refused cleanly");
        // Self-referencing arrays must not blow up.
        byte[] bomb = selfReferencingPlist();
        boolean refusedBomb = false;
        try {
            Plist.read(bomb);
        } catch (IOException e) {
            refusedBomb = true;
        }
        check(refusedBomb, "bplist refuses an exponential reference bomb");
        // Limits.
        String huge = "POST /x RTSP/1.0\r\nContent-Length: 99999999\r\n\r\n";
        check(throwsIo(huge.getBytes(StandardCharsets.US_ASCII)), "RTSP refuses a huge Content-Length");
        StringBuilder longHeader = new StringBuilder("GET /info RTSP/1.0\r\nX: ");
        for (int i = 0; i < 20000; i++) longHeader.append('a');
        check(throwsIo(longHeader.append("\r\n\r\n").toString().getBytes(StandardCharsets.US_ASCII)), "RTSP refuses 16 KB+ headers");
        check(throwsIo("GET /info RTSP/1.0\r\nContent-Length: 10\r\n\r\nabc".getBytes(StandardCharsets.US_ASCII)), "RTSP refuses a short body");
    }

    static boolean throwsIo(byte[] b) {
        try {
            Rtsp.read(new ByteArrayInputStream(b));
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /** 20 arrays, each holding the next one twice: 2^20 objects if references were followed blindly. */
    static byte[] selfReferencingPlist() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('b'); out.write('p'); out.write('l'); out.write('i'); out.write('s'); out.write('t'); out.write('0'); out.write('0');
        int n = 21;
        int[] offsets = new int[n];
        for (int i = 0; i < 20; i++) {
            offsets[i] = out.size();
            out.write(0xA2);
            out.write(i + 1);
            out.write(i + 1);
        }
        offsets[20] = out.size();
        out.write(0x08);
        int table = out.size();
        for (int o : offsets) out.write(o);
        byte[] trailer = new byte[32];
        trailer[6] = 1;
        trailer[7] = 1;
        trailer[15] = (byte) n;
        trailer[31] = (byte) table;
        out.write(trailer, 0, 32);
        return out.toByteArray();
    }

    // MARK: - Bonjour records

    static void advert() {
        long bits = 0;
        int[] on = {1, 2, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 25, 27, 28, 30};
        for (int b : on) bits |= 1L << b;
        check(bits == Advert.FEATURES && Advert.FEATURES_TEXT.equals("0x5A7FFEE6,0x0"), "features 0x5A7FFEE6,0x0");
        check((Advert.FEATURES & (1L << 27)) != 0 && (Advert.FEATURES & (1L << 42)) == 0, "legacy pairing on, HEVC off");
        Advert.Identity id = identity();
        Map<String, String> airplay = Advert.airplayTxt(id, Advert.CODE_OFF), raop = Advert.raopTxt(id, Advert.CODE_OFF);
        check("true".equals(Advert.airplayTxt(id, Advert.CODE_FIRST).get("pw")) && "0x8c".equals(Advert.raopTxt(id, Advert.CODE_FIRST).get("sf"))
                && "0xc".equals(Advert.raopTxt(id, Advert.CODE_ALWAYS).get("sf")) && "true".equals(Advert.raopTxt(id, Advert.CODE_ALWAYS).get("pw")),
                "TXT records with an AirPlay code (pw, sf)");
        check(airplay.get("deviceid").equals("02:11:22:33:44:55") && airplay.get("pk").length() == 64
                && airplay.get("pk").equals(Bytes.hex(id.edPublic)) && "0x4".equals(airplay.get("flags"))
                && "AppleTV3,2".equals(airplay.get("model")) && "false".equals(airplay.get("pw")), "_airplay._tcp TXT");
        check("UDP".equals(raop.get("tp")) && "0,3,5".equals(raop.get("et")) && Advert.FEATURES_TEXT.equals(raop.get("ft"))
                && "44100".equals(raop.get("sr")) && raop.get("pk").equals(airplay.get("pk")), "_raop._tcp TXT");
        check(Advert.raopName(id, "Living Room").equals("021122334455@Living Room"), "raop instance name");
        check(Advert.safeName("a.b\u0007c").equals("a b c") && Advert.safeName("").equals("Free My TV")
                && Advert.safeName("x".repeat(80)).length() == 44, "safe service names");
        byte[] txt = Advert.txtBytes(airplay);
        int at = 0, entries = 0;
        boolean ok = true;
        while (at < txt.length) {
            int len = txt[at] & 0xff;
            String kv = new String(txt, at + 1, len, StandardCharsets.UTF_8);
            String key = kv.substring(0, kv.indexOf('='));
            ok &= airplay.get(key).equals(kv.substring(kv.indexOf('=') + 1));
            at += 1 + len;
            entries++;
        }
        check(ok && entries == airplay.size() && at == txt.length, "TXT rdata encoding");
        Map<String, Object> info = Advert.info(id, "Free My TV");
        check(Plist.number(info, "features", 0) == 0x5A7FFEE6L && Plist.number(info, "statusFlags", 0) == 68
                && Arrays.equals(Plist.data(info, "pk"), id.edPublic), "GET /info dictionary");
    }

    // MARK: - FairPlay and the stream

    static void fairPlay() {
        int n = FairPlayVectors.run();
        checks += n;
        check(n > 30, "FairPlay vectors ran (" + n + ")");
        check(Bytes.hex(Bytes.sha256(io.freemytv.fairplay.FairPlay.setup(FairPlay.CHECK_REQUEST))).equals(FairPlay.CHECK_REPLY_SHA256),
                "the on-device plugin check matches the plugin");
    }

    static byte[] payload() throws IOException {
        return Files.readAllBytes(new File(FairPlayVectors.ENCRYPTED_PAYLOAD).toPath());
    }

    /** The captured session end to end: ekey → FairPlay key → stream key → the first frame's NAL lengths add up. */
    static void mirrorDecrypt() throws Exception {
        byte[] key = io.freemytv.fairplay.FairPlay.decryptKey(FairPlayVectors.FP_SETUP2_REQUEST, FairPlayVectors.EKEY);
        byte[][] keys = StreamKeys.video(StreamKeys.aesKey(key, FairPlayVectors.SHARED_SECRET), FairPlayVectors.STREAM_CONNECTION_ID);
        byte[] p = payload();
        new MirrorStream(keys[0], keys[1], null).decrypt(p, p.length);
        check(Bytes.intBE(p, 0) == p.length - 4, "end-to-end decrypt: nalLen == len - 4");
        check(MirrorStream.nalLengthsOk(p, p.length), "end-to-end decrypt: the NAL length chain");
        Log.w("key fp=" + Bytes.fingerprint(key));

        // The keystream continues across packets of any size.
        Random rnd = new Random(7);
        byte[] plain = new byte[100_000];
        rnd.nextBytes(plain);
        byte[] once = plain.clone();
        new MirrorStream(keys[0], keys[1], null).decrypt(once, once.length);
        byte[] split = plain.clone();
        MirrorStream s = new MirrorStream(keys[0], keys[1], null);
        int at = 0;
        while (at < split.length) {
            int n = Math.min(split.length - at, 1 + rnd.nextInt(3000));
            byte[] part = Arrays.copyOfRange(split, at, at + n);
            s.decrypt(part, n);
            System.arraycopy(part, 0, split, at, n);
            at += n;
        }
        check(Arrays.equals(once, split), "AES-CTR keystream continues across packets");

        byte[] avcc = {0, 0, 0, 2, 0x65, 1, 0, 0, 0, 1, 0x41};
        check(MirrorStream.nalLengthsOk(avcc, avcc.length) && !MirrorStream.nalLengthsOk(avcc, avcc.length - 1), "NAL length chain check");
        MirrorStream.toAnnexB(avcc, avcc.length);
        check(Arrays.equals(avcc, new byte[] {0, 0, 0, 1, 0x65, 1, 0, 0, 0, 1, 0x41}), "AVCC to Annex-B");
        byte[][] csd = MirrorStream.avcC(AVCC_CONFIG);
        check(csd != null && Arrays.equals(csd[0], new byte[] {0, 0, 0, 1, 0x67, 0x64, 0, 0x28})
                && Arrays.equals(csd[1], new byte[] {0, 0, 0, 1, 0x68, (byte) 0xee, 0x3c, (byte) 0x80}), "avcC parsing");
        check(MirrorStream.avcC(Arrays.copyOf(AVCC_CONFIG, 9)) == null, "avcC refuses a cut record");
        byte[] idr = {0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2, 0, 0, 0, 1, 0x65, (byte) 0x88};
        byte[] nonIdr = {0, 0, 0, 1, 0x41, (byte) 0x9a, 0, 0, 0, 1, 0x01, 0x05};
        check(MirrorStream.isIdr(idr, idr.length) && !MirrorStream.isIdr(nonIdr, nonIdr.length), "IDR detection");
        byte[] first = payload();
        new MirrorStream(keys[0], keys[1], null).decrypt(first, first.length);
        MirrorStream.toAnnexB(first, first.length);
        check(first[0] == 0 && first[3] == 1, "the captured frame converts to Annex-B (IDR: " + MirrorStream.isIdr(first, first.length) + ")");
    }

    static final byte[] AVCC_CONFIG = {1, 0x64, 0, 0x28, (byte) 0xff, (byte) 0xe1, 0, 4, 0x67, 0x64, 0, 0x28, 1, 0, 4,
            0x68, (byte) 0xee, 0x3c, (byte) 0x80};

    // MARK: - A whole session over loopback

    /** The FairPlay plugin's code called directly, where the app goes through the Binder. */
    static final FairPlay PLUGIN = new FairPlay() {
        @Override public byte[] setup(byte[] request) {
            return io.freemytv.fairplay.FairPlay.setup(request);
        }

        @Override public byte[] decryptKey(byte[] keyMessage, byte[] ekey) {
            return io.freemytv.fairplay.FairPlay.decryptKey(keyMessage, ekey);
        }
    };

    @SuppressWarnings("unchecked")
    static void receiverLoopback() throws Exception {
        LOG.clear();
        Advert.Identity id = identity();
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final List<String> configs = Collections.synchronizedList(new ArrayList<>());
        final List<byte[]> frames = Collections.synchronizedList(new ArrayList<>());
        final MirrorStream.Listener player = new MirrorStream.Listener() {
            @Override public void codec(String mime, byte[][] csd, int width, int height) {
                configs.add(mime + " " + width + "x" + height + " " + Bytes.hex(csd[0]) + " " + Bytes.hex(csd[1]));
            }

            @Override public void frame(byte[] data, int len, long ntpTime) {
                frames.add(Arrays.copyOf(data, len));
            }
        };
        final Ntp[] clocks = new Ntp[1];
        final List<String> sound = Collections.synchronizedList(new ArrayList<>());
        final float[] volume = {1};
        final AudioStream.Listener speaker = new AudioStream.Listener() {
            @Override public void audio(byte[] data, int len, long rtpTime) {
                sound.add(rtpTime + ":" + Bytes.hex(data, 0, len));
            }

            @Override public void sync(long rtpTime, long senderNs) {
                sound.add("sync " + rtpTime + " " + senderNs);
            }
        };
        Receiver receiver = new Receiver(id, "Free My TV", Advert.CODE_OFF, PLUGIN, new Receiver.Sink() {
            @Override public void showCode(String digits) { }
            @Override public boolean knownDevice(byte[] edPublic) { return false; }
            @Override public void addDevice(byte[] edPublic) { }
            @Override public void started(String sender, String deviceName) { events.add("started " + sender + " " + deviceName); }
            @Override public MirrorStream.Listener video(Ntp clock) { clocks[0] = clock; return player; }
            @Override public AudioStream.Listener audio(Ntp clock) { return speaker; }
            @Override public void volume(float db) { volume[0] = db; }
            @Override public void ended(String reason) { events.add("ended " + reason); }
        });
        int port = receiver.start(0);
        check(port > 0, "receiver listens");

        // A first connection, like an iPhone's discovery: GET /info with the txtAirPlay qualifier, then plain.
        try (Socket c = new Socket(InetAddress.getLoopbackAddress(), port)) {
            Reply r = send(c, "GET", "/info", Plist.write(Plist.map("qualifier", new ArrayList<>(Collections.singletonList("txtAirPlay")))));
            check(r.status == 200 && Arrays.equals(Plist.data(Plist.dict(r.body), "txtAirPlay"), Advert.txtBytes(Advert.airplayTxt(id, Advert.CODE_OFF))),
                    "GET /info txtAirPlay");
            r = send(c, "GET", "/info", new byte[0]);
            Map<String, Object> info = Plist.dict(r.body);
            check(r.status == 200 && "02:11:22:33:44:55".equals(info.get("deviceID")) && r.headers.contains("Server: AirTunes/220.68")
                    && r.headers.contains("CSeq: 2"), "GET /info");
        }

        Socket c = new Socket(InetAddress.getLoopbackAddress(), port);
        Sender sender = new Sender();
        Reply r = send(c, "POST", "/pair-setup", sender.edPk);
        check(r.status == 200 && Arrays.equals(r.body, id.edPublic), "POST /pair-setup");
        r = send(c, "POST", "/pair-verify", sender.verify1());
        check(r.status == 200 && r.body.length == 96, "POST /pair-verify 1");
        r = send(c, "POST", "/pair-verify", sender.verify2(r.body, id.edPublic));
        check(r.status == 200 && sender.serverSignatureOk, "POST /pair-verify 2");
        r = send(c, "POST", "/fp-setup", FairPlayVectors.FP_SETUP1_REQUEST);
        check(r.status == 200 && r.body.length == 142, "POST /fp-setup 1");
        r = send(c, "POST", "/fp-setup", FairPlayVectors.FP_SETUP2_REQUEST);
        check(r.status == 200 && Arrays.equals(r.body, FairPlayVectors.FP_SETUP2_RESPONSE), "POST /fp-setup 2");

        // The sender's NTP: answers our timing requests.
        DatagramSocket timing = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        Thread ntp = new Thread(() -> {
            byte[] b = new byte[64];
            try {
                while (true) {
                    DatagramPacket p = new DatagramPacket(b, b.length);
                    timing.receive(p);
                    byte[] reply = new byte[32];
                    reply[0] = (byte) 0x80;
                    reply[1] = (byte) 0xd3;
                    System.arraycopy(b, 24, reply, 8, 8);
                    long ours = Ntp.ntpToNanos(Bytes.longBE(b, 24));
                    long theirs = ours + SENDER_AHEAD_NS;
                    long ntpTime = (theirs / 1_000_000_000L + 2_208_988_800L) << 32
                            | (((theirs % 1_000_000_000L) << 32) / 1_000_000_000L);
                    putLongBE(reply, 16, ntpTime);
                    putLongBE(reply, 24, ntpTime);
                    timing.send(new DatagramPacket(reply, 32, p.getSocketAddress()));
                }
            } catch (IOException ignored) {
            }
        });
        ntp.setDaemon(true);
        ntp.start();

        r = send(c, "SETUP", "rtsp://127.0.0.1/1", Plist.write(Plist.map("ekey", FairPlayVectors.EKEY, "eiv", new byte[16],
                "timingPort", (long) timing.getLocalPort(), "timingProtocol", "NTP", "isScreenMirroringSession", true,
                "model", "iPhone14,5", "osName", "iPhone OS", "osVersion", "17.0", "name", "Someone's iPhone")));
        Map<String, Object> setup = Plist.dict(r.body);
        check(r.status == 200 && Plist.number(setup, "timingPort", 0) > 0 && Plist.number(setup, "eventPort", -1) == 0, "SETUP ekey");
        check(waitFor("ntp: synced with the sender"), "the receiver syncs its clock with the sender");
        r = send(c, "GET_PARAMETER", "rtsp://127.0.0.1/1", "volume\r\n".getBytes(StandardCharsets.US_ASCII));
        check(r.status == 200 && new String(r.body, StandardCharsets.US_ASCII).startsWith("volume: "), "GET_PARAMETER volume");
        r = send(c, "RECORD", "rtsp://127.0.0.1/1", new byte[0]);
        check(r.status == 200 && r.headers.contains("Audio-Latency"), "RECORD");
        r = send(c, "SETUP", "rtsp://127.0.0.1/1", Plist.write(Plist.map("streams", new ArrayList<>(Collections.singletonList(
                Plist.map("type", 110L, "streamConnectionID", FairPlayVectors.STREAM_CONNECTION_ID, "timestampInfo", new ArrayList<>()))))));
        Map<String, Object> stream = (Map<String, Object>) ((List<?>) Plist.dict(r.body).get("streams")).get(0);
        int dataPort = (int) Plist.number(stream, "dataPort", 0);
        check(r.status == 200 && Plist.number(stream, "type", 0) == 110 && dataPort > 0, "SETUP streams 110");
        r = send(c, "SETUP", "rtsp://127.0.0.1/1", Plist.write(Plist.map("streams", new ArrayList<>(Collections.singletonList(
                Plist.map("type", 96L, "ct", 8L, "audioFormat", 0x1000000L, "controlPort", 6001L))))));
        Map<String, Object> audioPorts = (Map<String, Object>) ((List<?>) Plist.dict(r.body).get("streams")).get(0);
        int audioPort = (int) Plist.number(audioPorts, "dataPort", 0), controlPort = (int) Plist.number(audioPorts, "controlPort", 0);
        check(r.status == 200 && audioPort > 0 && controlPort > 0 && waitFor("AAC-ELD"), "SETUP streams 96 (AAC-ELD)");
        check(clocks[0] != null && clocks[0].synced()
                && Math.abs(clocks[0].toLocal(System.nanoTime() + SENDER_AHEAD_NS) - System.nanoTime()) < 50_000_000L,
                "the clock maps the sender's time to ours (offset 7 s, epoch 1900)");
        r = send(c, "SET_PARAMETER", "rtsp://127.0.0.1/1", "volume: -20.000000\r\n".getBytes(StandardCharsets.US_ASCII));
        check(r.status == 200 && volume[0] == -20f, "SET_PARAMETER volume reaches the player");

        // Audio: RTP packets encrypted with this session's key and eiv, sent out of order; a sync packet.
        byte[] audioKey = StreamKeys.aesKey(PLUGIN.decryptKey(FairPlayVectors.FP_SETUP2_REQUEST, FairPlayVectors.EKEY), sender.secret);
        try (DatagramSocket udp = new DatagramSocket()) {
            InetAddress lo = InetAddress.getLoopbackAddress();
            int[] order = {100, 102, 101, 103};
            for (int seq : order) {
                byte[] pkt = rtpPacket(seq, seq * 480L, audioKey, new byte[16], frameFor(seq));
                udp.send(new DatagramPacket(pkt, pkt.length, lo, audioPort));
                Thread.sleep(5);
            }
            byte[] sync = new byte[20];
            sync[0] = (byte) 0x90;
            sync[1] = (byte) 0xd4;
            putLongBE(sync, 8, (5L + 2_208_988_800L) << 32);   // 5 s on the sender's clock
            sync[16] = 0; sync[17] = 0; sync[18] = 0x01; sync[19] = (byte) 0xe0;   // RTP 480
            udp.send(new DatagramPacket(sync, 20, lo, controlPort));
            for (int i = 0; i < 100 && sound.size() < 5; i++) Thread.sleep(20);
        }
        List<String> expected = new ArrayList<>();
        for (int seq = 100; seq <= 103; seq++) expected.add(seq * 480L + ":" + Bytes.hex(frameFor(seq)));
        check(sound.size() == 5 && sound.subList(0, 4).equals(expected), "audio: packets decrypted and put back in order " + sound);
        check(sound.get(4).equals("sync 480 5000000000"), "audio: sync packet mapped to the sender clock " + sound.get(4));
        r = send(c, "FLUSH", "rtsp://127.0.0.1/1", new byte[0]);
        check(r.status == 200, "FLUSH");

        // The mirror connection: codec config, then two frames re-encrypted with this session's stream key.
        byte[] fpKey = PLUGIN.decryptKey(FairPlayVectors.FP_SETUP2_REQUEST, FairPlayVectors.EKEY);
        byte[][] captured = StreamKeys.video(StreamKeys.aesKey(fpKey, FairPlayVectors.SHARED_SECRET), FairPlayVectors.STREAM_CONNECTION_ID);
        byte[] plain = payload();
        new MirrorStream(captured[0], captured[1], null).decrypt(plain, plain.length);
        byte[][] ours = StreamKeys.video(StreamKeys.aesKey(fpKey, sender.secret), FairPlayVectors.STREAM_CONNECTION_ID);
        MirrorStream encrypt = new MirrorStream(ours[0], ours[1], null);
        try (Socket mirror = new Socket(InetAddress.getLoopbackAddress(), dataPort)) {
            OutputStream out = mirror.getOutputStream();
            out.write(header(1, 0x16, AVCC_CONFIG.length));
            out.write(AVCC_CONFIG);
            for (int i = 0; i < 2; i++) {
                byte[] frame = plain.clone();
                encrypt.decrypt(frame, frame.length);
                out.write(header(0, 0, frame.length));
                out.write(frame);
            }
            out.write(header(2, 0, 0));
            // The iPhone rotates: a new config with a landscape size, then a frame.
            out.write(header(1, 0x16, AVCC_CONFIG.length, 1920, 886));
            out.write(AVCC_CONFIG);
            byte[] after = plain.clone();
            encrypt.decrypt(after, after.length);
            out.write(header(0, 0, after.length));
            out.write(after);
            out.flush();
            check(waitFor("mirror: codec config h264 1920x1080"), "mirror: codec config logged");
            check(waitFor("mirror: first NAL lengths ok"), "mirror: first NAL lengths ok");
            r = send(c, "POST", "/feedback", new byte[0]);
            check(r.status == 200, "POST /feedback");
            Thread.sleep(300);   // the second frame and the heartbeat reach the receiver
            r = send(c, "TEARDOWN", "rtsp://127.0.0.1/1", Plist.write(Plist.map("streams", new ArrayList<>(Collections.singletonList(
                    Plist.map("type", 110L))))));
            check(r.status == 200 && waitFor("TEARDOWN stream 110"), "TEARDOWN stream 110");
            check(waitFor("mirror: ended after 3 frames"), "mirror: all frames decrypted, nothing broken");
            check(configs.size() == 2 && configs.get(0).startsWith("video/avc 1920x1080 0000000167640028 0000000168ee3c80")
                    && configs.get(1).startsWith("video/avc 1920x886 "), "the player gets both configs (rotation) " + configs);
            byte[] annexB = plain.clone();
            MirrorStream.toAnnexB(annexB, annexB.length);
            boolean same = frames.size() == 3;
            for (byte[] f : frames) same &= Arrays.equals(f, annexB);
            check(same, "the player gets every frame decrypted, in Annex-B");
        }
        r = send(c, "TEARDOWN", "rtsp://127.0.0.1/1", new byte[0]);
        check(r.status == 200, "TEARDOWN session");
        check(waitFor("session ended: the sender ended the session (TEARDOWN)"), "session end logged");
        check(c.getInputStream().read() == -1, "the receiver closes the connection after a full TEARDOWN");
        c.close();
        check(events.size() == 2 && events.get(0).endsWith(" Someone's iPhone") && events.get(1).startsWith("ended "), "sink events " + events);

        // A sender whose pair-verify signature is wrong gets 470.
        try (Socket bad = new Socket(InetAddress.getLoopbackAddress(), port)) {
            Sender liar = new Sender();
            r = send(bad, "POST", "/pair-verify", liar.verify1());
            byte[] forged = liar.verify2(r.body, id.edPublic);
            forged[10] ^= 1;
            r = send(bad, "POST", "/pair-verify", forged);
            check(r.status == 470, "pair-verify with a bad signature: 470");
        }
        // SETUP before fp-setup is refused, and the connection survives it.
        try (Socket early = new Socket(InetAddress.getLoopbackAddress(), port)) {
            r = send(early, "SETUP", "rtsp://127.0.0.1/2", Plist.write(Plist.map("ekey", FairPlayVectors.EKEY)));
            check(r.status == 400, "SETUP ekey before fp-setup: 400");
            r = send(early, "OPTIONS", "*", new byte[0]);
            check(r.status == 200, "the connection survives a refused request");
            r = send(early, "FOO", "/bar", new byte[0]);
            check(r.status == 501, "unknown method: 501");
        }
        // Screen off: no new session may start.
        receiver.setAccepting(false);
        try (Socket off = new Socket(InetAddress.getLoopbackAddress(), port)) {
            send(off, "POST", "/fp-setup", FairPlayVectors.FP_SETUP1_REQUEST);
            send(off, "POST", "/fp-setup", FairPlayVectors.FP_SETUP2_REQUEST);
            r = send(off, "SETUP", "rtsp://127.0.0.1/3", Plist.write(Plist.map("ekey", FairPlayVectors.EKEY)));
            check(r.status == 453, "SETUP while the screen is off: 453");
        }
        receiver.setAccepting(true);
        // A plist whose references would copy 1 GB fails cleanly (no OutOfMemoryError), and the connection lives on.
        try (Socket bomb = new Socket(InetAddress.getLoopbackAddress(), port)) {
            r = send(bomb, "GET", "/info", dataBomb());
            check(r.status >= 400 && waitFor("plist expands too much"), "GET /info with an expanding plist is refused");
            r = send(bomb, "OPTIONS", "*", new byte[0]);
            check(r.status == 200, "the receiver lives on after the plist bomb");
        }
        receiver.stop();
        timing.close();
    }

    /** An array of 2000 references to one 500 KB data object. */
    static byte[] dataBomb() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('b'); out.write('p'); out.write('l'); out.write('i'); out.write('s'); out.write('t'); out.write('0'); out.write('0');
        out.write(0xAF);
        out.write(0x11);
        out.write(2000 >> 8);
        out.write(2000 & 0xff);
        for (int i = 0; i < 2000; i++) out.write(1);
        int data = out.size();
        out.write(0x4F);
        out.write(0x12);
        int len = 500_000;
        for (int i = 3; i >= 0; i--) out.write(len >> (8 * i));
        out.write(new byte[len], 0, len);
        int table = out.size();
        for (int o : new int[] {8, data}) for (int i = 3; i >= 0; i--) out.write(o >> (8 * i));
        byte[] trailer = new byte[32];
        trailer[6] = 4;
        trailer[7] = 1;
        trailer[15] = 2;
        for (int i = 0; i < 4; i++) trailer[31 - i] = (byte) (table >> (8 * i));
        out.write(trailer, 0, 32);
        return out.toByteArray();
    }

    static final long SENDER_AHEAD_NS = 7_000_000_000L;

    static void putLongBE(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (56 - 8 * i));
    }

    /** A 37-byte "AAC-ELD frame": two whole AES blocks and a 5-byte clear tail. */
    static byte[] frameFor(int seq) {
        byte[] f = new byte[37];
        for (int i = 0; i < f.length; i++) f[i] = (byte) (seq * 7 + i);
        return f;
    }

    static byte[] rtpPacket(int seq, long rtpTime, byte[] key, byte[] iv, byte[] frame) throws Exception {
        byte[] payload = frame.clone();
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        c.doFinal(payload, 0, 32, payload, 0);
        byte[] p = new byte[12 + payload.length];
        p[0] = (byte) 0x80;
        p[1] = (byte) 0x60;
        p[2] = (byte) (seq >> 8);
        p[3] = (byte) seq;
        p[4] = (byte) (rtpTime >> 24);
        p[5] = (byte) (rtpTime >> 16);
        p[6] = (byte) (rtpTime >> 8);
        p[7] = (byte) rtpTime;
        System.arraycopy(payload, 0, p, 12, payload.length);
        return p;
    }

    // MARK: - Clock, pacing, audio pieces

    static void clockAndPacing() {
        long withEpoch = (2_208_988_800L + 100) << 32 | 0x80000000L;   // 100.5 s after the epoch
        check(Ntp.ntpToNanos(withEpoch) == 100_500_000_000L, "NTP time with the 1900 epoch");
        check(Ntp.ntpToNanos(100L << 32 | 0x40000000L) == 100_250_000_000L, "NTP time without an epoch (mirror headers)");
        check(Ntp.ntpToNanos(0) == 0, "NTP zero");

        Pacing p = new Pacing();
        long t = 1_000_000_000_000L, sender = 42_000_000_000L, frame = 16_666_667L;
        boolean steady = true;
        for (int i = 0; i < 300; i++) steady &= p.decide(sender + i * frame, t + i * frame + (i % 3) * 2_000_000L, i == 0, true) == Pacing.KEEP;
        check(steady, "pacing keeps a steady stream");
        int i = 300;
        // A burst: frames arrive 300 ms late.
        check(p.decide(sender + i * frame, t + i * frame + 300_000_000L, false, false) == Pacing.SKIP, "pacing skips a late non-reference frame");
        check(p.decide(sender + i * frame, t + i * frame + 300_000_000L, false, true) == Pacing.KEEP, "pacing keeps a late reference frame");
        check(p.decide(sender + i * frame, t + i * frame + 300_000_000L, true, false) == Pacing.KEEP, "pacing keeps an IDR");
        check(p.decide(sender + i * frame, t + i * frame + 3_000_000_000L, false, true) == Pacing.KEEP, "pacing decodes through a 3 s stall");
        check(p.decide(sender + i * frame, t + i * frame + 9_000_000_000L, false, true) == Pacing.WAIT_IDR, "pacing gives up when hopelessly behind");
        check(p.decide(sender + (i + 1) * frame, t + (i + 1) * frame, false, true) == Pacing.WAIT_IDR, "pacing waits for the IDR");
        check(p.decide(sender + (i + 2) * frame, t + (i + 2) * frame, true, true) == Pacing.KEEP
                && p.decide(sender + (i + 3) * frame, t + (i + 3) * frame, false, false) == Pacing.KEEP, "pacing resumes at the IDR");
        // The floor follows a slow drift (the old minimum ages out after 10 s).
        Pacing drift = new Pacing();
        boolean kept = true;
        for (int k = 0; k < 2000; k++) kept &= drift.decide(sender + k * frame, t + k * frame + k * 100_000L, false, false) == Pacing.KEEP;
        check(kept, "pacing tolerates clock drift (" + drift.lateness / 1_000_000 + " ms)");

        byte[] ref = {0, 0, 0, 1, 0x41, 1}, nonRef = {0, 0, 0, 1, 0x01, 1}, sei = {0, 0, 0, 1, 0x06, 5, 0, 0, 0, 1, 0x21, 1};
        check(MirrorStream.isReference(ref, ref.length) && !MirrorStream.isReference(nonRef, nonRef.length)
                && MirrorStream.isReference(sei, sei.length), "nal_ref_idc");
    }

    static void audioPieces() throws Exception {
        // CBC over whole blocks, the tail in the clear.
        byte[] key = random(16), iv = random(16), plain = random(45);
        byte[] wire = plain.clone();
        Cipher enc = Cipher.getInstance("AES/CBC/NoPadding");
        enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        enc.doFinal(wire, 0, 32, wire, 0);
        check(Arrays.equals(Arrays.copyOfRange(wire, 32, 45), Arrays.copyOfRange(plain, 32, 45)), "the CBC tail stays clear");
        Cipher dec = Cipher.getInstance("AES/CBC/NoPadding");
        AudioStream.decrypt(dec, key, iv, wire, wire.length);
        AudioStream.decrypt(dec, key, iv, new byte[7], 7);   // shorter than a block: nothing to do
        byte[] again = plain.clone();
        enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        enc.doFinal(again, 0, 32, again, 0);
        AudioStream.decrypt(dec, key, iv, again, again.length);
        check(Arrays.equals(wire, plain) && Arrays.equals(again, plain), "AES-CBC per packet, fresh IV each time");

        // Reorder with deadlines: swapped packets, wraparound, a duplicate; a gap recovered by a resend; a gap given up.
        long ms = 1_000_000L;
        AudioStream.Reorder r = new AudioStream.Reorder(AudioStream.WAIT_NS, AudioStream.RETRY_NS);
        StringBuilder got = new StringBuilder();
        int[] in = {65533, 65535, 65534, 65534, 0};
        for (int seq : in) for (AudioStream.Reorder.Packet p : r.push(seq, rt(seq), new byte[0], 0)) got.append(p.seq).append(' ');
        check(got.toString().equals("65533 65534 65535 0 ") && r.lost == 0, "RTP reorder across the wrap: " + got);
        int[] ask = r.nextRequest();
        check(ask != null && ask[0] == 65534 && ask[1] == 1 && r.nextRequest() == null, "a swap is asked for once (it may be lost)");
        got.setLength(0);
        for (AudioStream.Reorder.Packet p : r.push(2, rt(2), new byte[0], 100 * ms)) got.append(p.seq).append(' ');   // 1 missing
        ask = r.nextRequest();
        check(got.length() == 0 && ask[0] == 1 && ask[1] == 1, "a gap is asked for at once");
        check(r.poll(129 * ms).isEmpty() && r.nextRequest() == null, "no second request before 30 ms");
        r.poll(131 * ms);
        ask = r.nextRequest();
        check(ask != null && ask[0] == 1 && ask[1] == 1, "one retry after 30 ms");
        r.poll(150 * ms);
        check(r.nextRequest() == null, "only one retry");
        for (AudioStream.Reorder.Packet p : r.push(1, rt(1), new byte[0], 160 * ms)) got.append(p.seq).append(' ');
        check(got.toString().equals("1 2 ") && r.recovered >= 1 && r.lost == 0, "the resent packet fills the gap: " + got);
        got.setLength(0);
        for (AudioStream.Reorder.Packet p : r.push(5, rt(5), new byte[0], 200 * ms)) got.append(p.seq).append(' ');   // 3, 4 missing
        ask = r.nextRequest();
        check(got.length() == 0 && ask[0] == 3 && ask[1] == 2, "a 2-packet gap: one request for both");
        check(r.poll(279 * ms).isEmpty(), "still waiting before 80 ms");
        for (AudioStream.Reorder.Packet p : r.poll(281 * ms)) got.append(p.seq).append(' ');
        check(got.toString().equals("5 ") && r.lost == 2, "given up after 80 ms: " + got + "lost " + r.lost);
        check(Arrays.equals(AudioStream.resendRequest(7, 65535, 2), new byte[] {(byte) 0x80, (byte) 0xd5, 0, 7, (byte) 0xff, (byte) 0xff, 0, 2}),
                "resend request encoding");

        // Copies: the same seq again (waiting, and already played), and a new seq with no new RTP time.
        AudioStream.Reorder d = new AudioStream.Reorder(AudioStream.WAIT_NS, AudioStream.RETRY_NS);
        int played = 0;
        played += d.push(10, 4800, new byte[0], 0).size();
        played += d.push(12, 5760, new byte[0], 0).size();   // 11 missing: 12 waits
        played += d.push(12, 5760, new byte[0], 0).size();   // a copy of the waiting one
        played += d.push(11, 5280, new byte[0], 0).size();   // 11, 12
        played += d.push(11, 5280, new byte[0], 0).size();   // a copy of a played one
        played += d.push(13, 5760, new byte[0], 0).size();   // new seq, old RTP time
        played += d.push(14, 6240, new byte[0], 0).size();
        check(played == 4 && d.delivered == 4 && d.duplicates == 2 && d.sameTime == 1,
                "copies never reach the decoder (" + played + " played, " + d.duplicates + " copies, " + d.sameTime + " same time)");

        audioSync();
        audioSyncConverges();
        clockPersistence();

        check(AudioStream.gain(-144f) == 0f && AudioStream.gain(0f) == 1f && AudioStream.gain(3f) == 1f
                && Math.abs(AudioStream.gain(-20f) - 0.1f) < 1e-6 && Math.abs(AudioStream.gain(-30f) - 0.031623f) < 1e-5
                && AudioStream.gain(-40f) == AudioStream.gain(-30f) && AudioStream.gain(Float.NaN) == 0f, "volume dB to gain");
    }

    /** AudioSync over simulated playback: 480-sample frames every ~10.9 ms, a track holding queued sound. */
    static void audioSync() {
        long ms = 1_000_000L, frame = io.freemytv.airplay.AudioSync.FRAME_NS;
        // Startup: due 300 ms out, 20 ms queued: one silence to get there, then nothing.
        io.freemytv.airplay.AudioSync s = new io.freemytv.airplay.AudioSync();
        long now = 0, queued = 20 * ms;
        int a = s.decide(now, now + queued, now + 300 * ms, queued);
        check(a == io.freemytv.airplay.AudioSync.PLAY && s.silenceNs == 280 * ms, "audio sync: one silence to align (" + s.silenceNs / ms + " ms)");
        queued += s.silenceNs + frame;
        // Steady on target for 20 s: no correction. The due time moves with each frame; the queue holds.
        long due0 = now + 300 * ms;
        int corrections = 0;
        for (int i = 1; i < 1840; i++) {
            now += frame;
            long due = due0 + i * frame;
            if (s.decide(now, now + queued, due, queued) != io.freemytv.airplay.AudioSync.PLAY || s.silenceNs > 0) corrections++;
        }
        check(corrections == 0, "audio sync: on target, nothing changes (" + corrections + ")");
        // Jitter: the error jumps ±120 ms for 200 ms every 3 s: absorbed, no correction.
        corrections = 0;
        for (int i = 0; i < 1840; i++) {
            now += frame;
            long jitter = (i % 276) < 18 ? ((i / 276) % 2 == 0 ? 120 * ms : -120 * ms) : 0;
            if (s.decide(now, now + queued + jitter, now + queued, queued) != io.freemytv.airplay.AudioSync.PLAY || s.silenceNs > 0) corrections++;
        }
        check(corrections == 0, "audio sync: short jitter is absorbed (" + corrections + ")");
        // The target jumps 100 ms later and stays: only small steps, at most one a second, none in the first 2 s.
        corrections = 0;
        long firstAt = -1, start = now;
        for (int i = 0; i < 460; i++) {   // 5 s
            now += frame;
            int act = s.decide(now, now + queued, now + queued + 100 * ms, queued);
            if (act != io.freemytv.airplay.AudioSync.PLAY || s.silenceNs > 0) {
                corrections++;
                if (firstAt < 0) firstAt = now - start;
                check(act == io.freemytv.airplay.AudioSync.REPEAT && s.silenceNs == 0, "audio sync: early is fixed by repeating a frame");
            }
        }
        check(corrections >= 1 && corrections <= 4 && firstAt >= 2_000 * ms, "audio sync: gentle steps (" + corrections + " in 5 s, first after "
                + firstAt / ms + " ms)");
        // A nonsense mapping (10 s off): never 10 s of silence.
        io.freemytv.airplay.AudioSync n = new io.freemytv.airplay.AudioSync();
        n.decide(0, 20 * ms, 10_000 * ms, 20 * ms);
        check(n.silenceNs <= 400 * ms && n.nonsense == 1, "audio sync: a nonsense mapping is ignored (" + n.silenceNs / ms + " ms silence)");
        // Without any mapping: keep about QUEUE_NS queued.
        io.freemytv.airplay.AudioSync q = new io.freemytv.airplay.AudioSync();
        q.decide(0, 10 * ms, Long.MIN_VALUE, 10 * ms);
        check(Math.abs(q.silenceNs - (io.freemytv.airplay.AudioSync.QUEUE_NS - 10 * ms)) < ms, "audio sync: room for resends without a clock ("
                + q.silenceNs / ms + " ms)");
    }

    static long rt(int seq) {
        return ((seq + 10) & 0xffff) * 480L;
    }

    /**
     * AudioSync driving a simulated track: every frame time the track plays one frame; each decision writes 0, 1 or 2
     * frames (plus silence). Late errors must only ever drop, early ones only repeat or wait; both converge.
     */
    static void audioSyncConverges() {
        long ms = 1_000_000L, frame = io.freemytv.airplay.AudioSync.FRAME_NS;
        io.freemytv.airplay.AudioSync s = new io.freemytv.airplay.AudioSync();
        long[] state = {0, 100 * ms};   // now, queued
        // Steady: the target is 100 ms of queue (due = now + 100 ms).
        for (int i = 0; i < 300; i++) step(s, state, 100 * ms, frame);
        check(Math.abs(state[1] - 100 * ms) < 40 * ms, "audio sync sim: steady at 100 ms queued (" + state[1] / ms + ")");
        // A burst leaves 500 ms queued: late by 400 ms. Only drops; back near target within 5 s.
        state[1] = 500 * ms;
        int[] acts = new int[3];
        long converged = -1, t0 = state[0];
        boolean silent = false;
        for (int i = 0; i < 920; i++) {
            acts[step(s, state, 100 * ms, frame)]++;
            silent |= s.silenceNs > 0;
            if (converged < 0 && Math.abs(state[1] - 100 * ms) <= 40 * ms) converged = state[0] - t0;
        }
        check(acts[io.freemytv.airplay.AudioSync.REPEAT] == 0 && acts[io.freemytv.airplay.AudioSync.DROP] > 0 && !silent,
                "audio sync sim: late is only ever fixed by dropping (" + acts[1] + " drops, " + acts[2] + " repeats)");
        check(converged >= 0 && converged <= 5_000 * ms && Math.abs(state[1] - 100 * ms) <= 40 * ms,
                "audio sync sim: from +400 ms back on target in " + converged / ms + " ms (queued " + state[1] / ms + " ms)");
        // The target moves 350 ms later (early by 350 ms): only repeats or silence; converges within 5 s.
        acts = new int[3];
        converged = -1;
        t0 = state[0];
        for (int i = 0; i < 920; i++) {
            acts[step(s, state, 450 * ms, frame)]++;
            if (converged < 0 && Math.abs(state[1] - 450 * ms) <= 40 * ms) converged = state[0] - t0;
        }
        check(acts[io.freemytv.airplay.AudioSync.DROP] == 0, "audio sync sim: early is never fixed by dropping (" + acts[1] + " drops)");
        check(converged >= 0 && converged <= 5_000 * ms && Math.abs(state[1] - 450 * ms) <= 40 * ms,
                "audio sync sim: from -350 ms back on target in " + converged / ms + " ms (queued " + state[1] / ms + " ms)");
        // A small lasting lateness (60 ms): gentle drops only, at most one a second.
        acts = new int[3];
        state[1] = 510 * ms;
        long firstAt = -1;
        t0 = state[0];
        for (int i = 0; i < 460; i++) {
            int a = step(s, state, 450 * ms, frame);
            acts[a]++;
            if (a != 0 && firstAt < 0) firstAt = state[0] - t0;
        }
        check(acts[io.freemytv.airplay.AudioSync.REPEAT] == 0 && acts[io.freemytv.airplay.AudioSync.DROP] >= 1
                && acts[io.freemytv.airplay.AudioSync.DROP] <= 4 && firstAt >= 2_000 * ms,
                "audio sync sim: 60 ms late, gentle drops (" + acts[1] + " in 5 s, first after " + firstAt / ms + " ms)");
    }

    /** One frame time of the simulated track: play a frame, then let AudioSync decide about the next one. */
    static int step(io.freemytv.airplay.AudioSync s, long[] state, long targetQueue, long frame) {
        state[0] += frame;
        state[1] = Math.max(0, state[1] - frame);
        long now = state[0], queued = state[1];
        int a = s.decide(now, now + queued, now + targetQueue, queued);
        state[1] += s.silenceNs + (a == io.freemytv.airplay.AudioSync.DROP ? 0 : a == io.freemytv.airplay.AudioSync.REPEAT ? 2 * frame : frame);
        return a;
    }

    /** The clock mapping stays when replies are missing or slow; better ones replace it. */
    static void clockPersistence() {
        long ms = 1_000_000L, s = 1_000_000_000L;
        Ntp n = new Ntp(0);
        // Sender 7 s ahead: a precise exchange (10 ms round trip).
        n.sample(100 * s, 107 * s + 5 * ms, 107 * s + 5 * ms, 100 * s + 10 * ms);
        long good = n.toLocal(200 * s);
        check(Math.abs(good - 193 * s) < ms, "clock: a precise reply sets the mapping");
        // Slow, asymmetric replies (an 80 ms queue one way) don't move it.
        for (int i = 1; i <= 8; i++) n.sample((100 + i) * s, (107 + i) * s + 80 * ms, (107 + i) * s + 80 * ms, (100 + i) * s + 90 * ms);
        check(n.toLocal(200 * s) == good && n.synced(), "clock: slow replies leave the mapping alone");
        // No replies at all for a while: still synced, same mapping.
        check(n.synced() && n.toLocal(200 * s) == good, "clock: missing replies change nothing");
        // A reply as precise as the best (within 2 ms) does replace it.
        n.sample(120 * s, 127 * s + 6 * ms, 127 * s + 6 * ms, 120 * s + 11 * ms);
        check(Math.abs(n.toLocal(200 * s) - (193 * s - ms)) < ms, "clock: an equally precise reply updates the mapping");
        // After a minute without one, the best recent reply takes over.
        for (int i = 0; i < 8; i++) n.sample((190 + i) * s, (197 + i) * s + 30 * ms, (197 + i) * s + 30 * ms, (190 + i) * s + 40 * ms);
        check(Math.abs(n.toLocal(300 * s) - (293 * s - 10 * ms)) < ms, "clock: an old mapping gives way to the best recent one");
    }

    // MARK: - AirPlay code (SRP-6a, AES-GCM, pairing over loopback), settings file

    static BigInteger big(String hex) {
        return new BigInteger(hex.replace(" ", ""), 16);
    }

    /** RFC 5054 Appendix B (1024-bit group, SHA-1, plain K = H(S)). */
    static void srpVectors() {
        io.freemytv.airplay.Srp srp = new io.freemytv.airplay.Srp(io.freemytv.airplay.Srp.N_1024, io.freemytv.airplay.Srp.G, "SHA-1", false);
        byte[] salt = hex("BEB25379D1A8581EB5A727673A2441EE");
        check(srp.k().equals(big("7556AA045AEF2CDD07ABAF0F665C3E818913186F")), "SRP RFC 5054: k");
        check(srp.x(salt, "alice", "password123").equals(big("94B7555AABE9127CC58CCF4993DB6CF84D16C124")), "SRP RFC 5054: x");
        BigInteger v = srp.verifier(salt, "alice", "password123");
        check(v.equals(big("7E273DE8696FFC4F4E337D05B4B375BEB0DDE1569E8FA00A9886D8129BADA1F1822223CA1A605B530E379BA4729FDC59F105B478"
                + "7E5186F5C671085A1447B52A48CF1970B4FB6F8400BBF4CEBFBB168152E08AB5EA53D15C1AFF87B2B9DA6E04E058AD51CC72BFC9033B564E26480D78"
                + "E955A5E29E7AB245DB2BE315E2099AFB")), "SRP RFC 5054: v");
        BigInteger a = big("60975527035CF2AD1989806F0407210BC81EDC04E2762A56AFD529DDDA2D4393");
        BigInteger b = big("E487CB59D31AC550471E81F00F6928E01DDA08E974A004F49E61F5D105284D20");
        BigInteger bigA = io.freemytv.airplay.Srp.G.modPow(a, io.freemytv.airplay.Srp.N_1024);
        check(bigA.equals(big("61D5E490F6F1B79547B0704C436F523DD0E560F0C64115BB72557EC44352E8903211C04692272D8B2D1A5358A2CF1B6E0BFCF99F92"
                + "1530EC8E39356179EAE45E42BA92AEACED825171E1E8B9AF6D9C03E1327F44BE087EF06530E69F66615261EEF54073CA11CF5858F0EDFDFE15EFEA"
                + "B349EF5D76988A3672FAC47B0769447B")), "SRP RFC 5054: A");
        BigInteger bigB = srp.serverPublic(v, b);
        check(bigB.equals(big("BD0C61512C692C0CB6D041FA01BB152D4916A1E77AF46AE105393011BAF38964DC46A0670DD125B95A981652236F99D9B681CBF8"
                + "7837EC996C6DA04453728610D0C6DDB58B318885D7D82C7F8DEB75CE7BD4FBAA37089E6F9C6059F388838E7A00030B331EB76840910440B1B27AAEAE"
                + "EB4012B7D7665238A8E3FB004B117B58")), "SRP RFC 5054: B");
        check(srp.u(bigA, bigB).equals(big("CE38B9593487DA98554ED47D70A7AE5F462EF019")), "SRP RFC 5054: u");
        check(srp.serverSecret(bigA, v, b).equals(big("B0DC82BABCF30674AE450C0287745E7990A3381F63B387AAF271A10D233861E359B48220F7C4693C9AE12B0A"
                + "6F67809F0876E2D013800D6C41BB59B6D5979B5C00A172B4A2A5903A0BDCAF8A709585EB2AFAFA8F3499B200210DCC1F10EB33943CD67FC88A2F39A4"
                + "BE5BEC4EC0A3212DC346D7E474B29EDE8A469FFECA686E5A")), "SRP RFC 5054: premaster secret");
        // A client and a server agree on K and the proofs (Apple variant, 2048-bit); a wrong password fails.
        io.freemytv.airplay.Srp apple = io.freemytv.airplay.Srp.airplay();
        io.freemytv.airplay.Srp.Server server = new io.freemytv.airplay.Srp.Server(apple, "AA:BB:CC:DD:EE:FF", "1234", random(16), random(32));
        byte[][] good = srpClient(apple, "AA:BB:CC:DD:EE:FF", "1234", server.salt(), server.publicKey());
        byte[] m2 = server.verify(good[0], good[1]);
        check(m2 != null && Arrays.equals(m2, good[2]) && Arrays.equals(server.key(), good[3]) && good[3].length == 40,
                "SRP Apple variant: both sides agree (40-byte K)");
        io.freemytv.airplay.Srp.Server other = new io.freemytv.airplay.Srp.Server(apple, "AA:BB:CC:DD:EE:FF", "1234", random(16), random(32));
        byte[][] bad = srpClient(apple, "AA:BB:CC:DD:EE:FF", "1235", other.salt(), other.publicKey());
        check(other.verify(bad[0], bad[1]) == null && other.key() == null, "SRP: a wrong code is refused");
        check(other.verify(new byte[256], bad[1]) == null, "SRP: A = 0 is refused");
    }

    /** The client side of SRP-6a: {A, M1, expected M2, K}. */
    static byte[][] srpClient(io.freemytv.airplay.Srp srp, String user, String password, byte[] salt, byte[] serverPublic) {
        BigInteger n = io.freemytv.airplay.Srp.N_2048, g = io.freemytv.airplay.Srp.G;
        BigInteger a = new BigInteger(1, random(32)), bigA = g.modPow(a, n), bigB = new BigInteger(1, serverPublic);
        BigInteger u = srp.u(bigA, bigB), x = srp.x(salt, user, password);
        BigInteger sec = bigB.subtract(srp.k().multiply(g.modPow(x, n))).mod(n).modPow(a.add(u.multiply(x)), n);
        byte[] k = srp.sessionKey(sec);
        byte[] m1 = srp.m1(user, salt, bigA, bigB, k);
        return new byte[][] {io.freemytv.airplay.Srp.bytes(bigA), m1, srp.m2(bigA, m1, k), k};
    }

    /** Our AES-GCM against the JDK's, with 16-byte (AirPlay) and 12-byte IVs; a changed byte fails. */
    static void gcm() throws Exception {
        boolean same = true, refused = true;
        for (int len : new int[] {0, 1, 16, 17, 32, 50}) {
            for (int ivLen : new int[] {12, 16}) {
                byte[] key = random(16), iv = random(ivLen), plain = random(len);
                Cipher jdk = Cipher.getInstance("AES/GCM/NoPadding");
                jdk.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new javax.crypto.spec.GCMParameterSpec(128, iv));
                byte[] theirs = jdk.doFinal(plain);
                byte[] ours = io.freemytv.airplay.Gcm.encrypt(key, iv, plain);
                same &= Arrays.equals(theirs, ours) && Arrays.equals(io.freemytv.airplay.Gcm.decrypt(key, iv, theirs), plain);
                byte[] bad = ours.clone();
                bad[bad.length - 1 - (len % 7)] ^= 4;
                refused &= io.freemytv.airplay.Gcm.decrypt(key, iv, bad) == null;
            }
        }
        check(same, "AES-GCM matches the JDK (12- and 16-byte IVs)");
        check(refused, "AES-GCM refuses a changed message");
    }

    static void settingsFile() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("enabled", "true");
        m.put("name", "Oturma Odası TV'si AirPlay");
        m.put("code", "first");
        m.put("odd", "a=b=c");
        String text = io.freemytv.airplay.KeyValues.format(m);
        check(io.freemytv.airplay.KeyValues.parse(text).equals(m), "settings file round trip");
        m.put("name", "two\nlines");
        Map<String, String> back = io.freemytv.airplay.KeyValues.parse(io.freemytv.airplay.KeyValues.format(m));
        check("two lines".equals(back.get("name")) && back.size() == 4, "a line break can't break the settings file");
        check(io.freemytv.airplay.KeyValues.parse("garbage\n=x\nk = v \n").equals(java.util.Collections.singletonMap("k", "v")),
                "the settings file skips garbage");
    }

    /**
     * An iPhone pairing with the AirPlay code over loopback: refused before, the code shown, SRP, the keys swapped,
     * pair-verify accepted; a wrong code refused; a later connection allowed again only in "first time" mode.
     */
    @SuppressWarnings("unchecked")
    static void codePairing(String mode) throws Exception {
        Advert.Identity id = identity();
        final String[] shown = {null};
        final List<String> devices = Collections.synchronizedList(new ArrayList<>());
        Receiver receiver = new Receiver(id, "TV", mode, PLUGIN, new Receiver.Sink() {
            @Override public void started(String sender, String deviceName) { }
            @Override public MirrorStream.Listener video(Ntp clock) { return null; }
            @Override public AudioStream.Listener audio(Ntp clock) { return null; }
            @Override public void volume(float db) { }
            @Override public void ended(String reason) { }
            @Override public void showCode(String digits) { if (digits != null) shown[0] = digits; }
            @Override public boolean knownDevice(byte[] edPublic) { return devices.contains(Bytes.hex(edPublic)); }
            @Override public void addDevice(byte[] edPublic) { devices.add(Bytes.hex(edPublic)); }
        });
        int port = receiver.start(0);
        String user = "11:22:33:44:55:66";
        Sender phone = new Sender();
        io.freemytv.airplay.Srp srp = io.freemytv.airplay.Srp.airplay();
        try (Socket c = new Socket(InetAddress.getLoopbackAddress(), port)) {
            Reply r = send(c, "GET", "/info", Plist.write(Plist.map("qualifier", new ArrayList<>(Collections.singletonList("txtAirPlay")))));
            check(new String(Plist.data(Plist.dict(r.body), "txtAirPlay"), StandardCharsets.UTF_8).contains("pw=true"), mode + ": /info says a code is needed");
            r = send(c, "POST", "/pair-verify", phone.verify1());
            check(r.status == 200 && r.body.length == 0, mode + ": pair-verify before the code: an empty answer");
            r = send(c, "POST", "/pair-pin-start", new byte[0]);
            check(r.status == 200 && shown[0] != null && shown[0].matches("\\d{4}"), mode + ": a 4-digit code on the TV");
            r = send(c, "POST", "/pair-setup-pin", Plist.write(Plist.map("method", "pin", "user", user)));
            Map<String, Object> step1 = Plist.dict(r.body);
            byte[] salt = Plist.data(step1, "salt"), bigB = Plist.data(step1, "pk");
            check(r.status == 200 && salt.length == 16 && bigB.length >= 255, mode + ": pair-setup-pin 1 (salt, B)");
            byte[][] client = srpClient(srp, user, shown[0], salt, bigB);
            r = send(c, "POST", "/pair-setup-pin", Plist.write(Plist.map("pk", client[0], "proof", client[1])));
            check(r.status == 200 && Arrays.equals(Plist.data(Plist.dict(r.body), "proof"), client[2]), mode + ": pair-setup-pin 2 (M2)");
            byte[] key = Arrays.copyOf(Bytes.sha512(Bytes.ascii("Pair-Setup-AES-Key"), client[3]), 16);
            byte[] iv = Arrays.copyOf(Bytes.sha512(Bytes.ascii("Pair-Setup-AES-IV"), client[3]), 16);
            iv[15]++;
            byte[] sealed = io.freemytv.airplay.Gcm.encrypt(key, iv, phone.edPk);
            r = send(c, "POST", "/pair-setup-pin", Plist.write(Plist.map("epk", Arrays.copyOf(sealed, 32), "authTag", Arrays.copyOfRange(sealed, 32, 48))));
            Map<String, Object> step3 = Plist.dict(r.body);
            iv[15]++;
            byte[] theirs = io.freemytv.airplay.Gcm.decrypt(key, iv, Bytes.concat(Plist.data(step3, "epk"), Plist.data(step3, "authTag")));
            check(r.status == 200 && Arrays.equals(theirs, id.edPublic) && devices.contains(Bytes.hex(phone.edPk)),
                    mode + ": pair-setup-pin 3 (keys swapped, device remembered)");
            r = send(c, "POST", "/pair-verify", phone.verify1());
            check(r.status == 200 && r.body.length == 96, mode + ": pair-verify after the code");
            r = send(c, "POST", "/pair-verify", phone.verify2(r.body, id.edPublic));
            check(r.status == 200 && phone.serverSignatureOk, mode + ": pair-verify completes");
        }
        // A wrong code.
        try (Socket c = new Socket(InetAddress.getLoopbackAddress(), port)) {
            send(c, "POST", "/pair-pin-start", new byte[0]);
            Reply r = send(c, "POST", "/pair-setup-pin", Plist.write(Plist.map("method", "pin", "user", user)));
            Map<String, Object> step1 = Plist.dict(r.body);
            String wrong = String.format("%04d", (Integer.parseInt(shown[0]) + 1) % 10000);
            byte[][] client = srpClient(srp, user, wrong, Plist.data(step1, "salt"), Plist.data(step1, "pk"));
            r = send(c, "POST", "/pair-setup-pin", Plist.write(Plist.map("pk", client[0], "proof", client[1])));
            check(r.status == 470, mode + ": a wrong code gets 470");
        }
        // The same iPhone again, without the code.
        try (Socket c = new Socket(InetAddress.getLoopbackAddress(), port)) {
            Sender again = new Sender();
            Sender same = new Sender(phone);
            Reply r = send(c, "POST", "/pair-verify", same.verify1());
            boolean allowed = r.body.length == 96;
            check(allowed == Advert.CODE_FIRST.equals(mode), mode + ": a known iPhone " + (allowed ? "connects directly" : "needs the code again"));
            r = send(c, "POST", "/pair-verify", again.verify1());
            check(r.body.length == 0, mode + ": an unknown iPhone needs the code");
        }
        receiver.stop();
    }

    static byte[] header(int type, int option, int size) {
        return header(type, option, size, 1920, 1080);
    }

    static byte[] header(int type, int option, int size, int width, int height) {
        byte[] h = new byte[128];
        h[0] = (byte) size;
        h[1] = (byte) (size >> 8);
        h[2] = (byte) (size >> 16);
        h[3] = (byte) (size >> 24);
        h[4] = (byte) type;
        h[6] = (byte) option;
        if (type == 1) {
            for (int off : new int[] {16, 40, 56}) putFloat(h, off, width);
            for (int off : new int[] {20, 44, 60}) putFloat(h, off, height);
        }
        return h;
    }

    static void putFloat(byte[] b, int off, float f) {
        int v = Float.floatToIntBits(f);
        for (int i = 0; i < 4; i++) b[off + i] = (byte) (v >> (8 * i));
    }

    static boolean waitFor(String line) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            synchronized (LOG) {
                for (String l : LOG) if (l.contains(line)) return true;
            }
            Thread.sleep(50);
        }
        System.out.println("  (log so far: " + LOG + ")");
        return false;
    }

    static final class Reply {
        int status;
        String headers;
        byte[] body;
    }

    static int cseq;

    static Reply send(Socket c, String method, String uri, byte[] body) throws IOException {
        c.setSoTimeout(5000);
        String head = method + " " + uri + " RTSP/1.0\r\nCSeq: " + (++cseq) + "\r\nContent-Length: " + body.length
                + "\r\nUser-Agent: AirPlay/670.6.2\r\n\r\n";
        OutputStream out = c.getOutputStream();
        out.write(Bytes.concat(head.getBytes(StandardCharsets.US_ASCII), body));
        out.flush();
        InputStream in = c.getInputStream();
        StringBuilder sb = new StringBuilder();
        while (!sb.toString().endsWith("\r\n\r\n")) {
            int ch = in.read();
            if (ch < 0) throw new IOException("closed");
            sb.append((char) ch);
        }
        Reply r = new Reply();
        r.headers = sb.toString();
        r.status = Integer.parseInt(r.headers.split(" ")[1]);
        int len = 0;
        for (String h : r.headers.split("\r\n")) if (h.startsWith("Content-Length: ")) len = Integer.parseInt(h.substring(16));
        r.body = new byte[len];
        Bytes.readFully(in, r.body, 0, len);
        return r;
    }
}
