package io.freemytv.airplay;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The screen-mirroring video connection (the sender connects to our dataPort). Each packet is a 128-byte header
 * (payload size LE at 0, type at 4: 0 video, 1 codec config, 2 heartbeat, 5 report; option at 6; NTP time at 8)
 * and a payload. Video payloads are AVCC (4-byte big-endian NAL lengths), AES-CTR encrypted as one continuous stream
 * across packets; codec config is a plain avcC (or hvcC) record.
 */
public final class MirrorStream {
    /** Where decoded data goes (P2: the decoder). Null in P1: decrypt, check and drop. */
    public interface Listener {
        /** csd: Annex-B parameter sets (H.264: {SPS, PPS}; H.265: {VPS+SPS+PPS}). */
        void codec(String mime, byte[][] csd, int width, int height);

        /**
         * One access unit, Annex-B, in data[0..len) (the buffer is reused after this returns). senderNs: when the
         * sender captured it, on its clock (Ntp.toLocal maps it to ours).
         */
        void frame(byte[] data, int len, long senderNs);
    }

    static final int HEADER = 128, MAX_PAYLOAD = 16 << 20;

    private final Cipher cipher;
    private final byte[] residual = new byte[16];
    private int residualLeft;
    private final Listener listener;
    private boolean checked;
    private long frames, broken;

    public MirrorStream(byte[] key, byte[] iv, Listener listener) throws IOException {
        try {
            cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        } catch (GeneralSecurityException e) {
            throw new IOException("AES-CTR unavailable", e);
        }
        this.listener = listener;
    }

    /** Reads packets until the sender closes the connection (normal end) or sends garbage (exception). */
    public void run(InputStream in) throws IOException {
        byte[] header = new byte[HEADER];
        byte[] payload = new byte[256 * 1024];
        try {
            while (true) {
                int first = in.read();
                if (first < 0) break;
                header[0] = (byte) first;
                Bytes.readFully(in, header, 1, HEADER - 1);
                int size = Bytes.intLE(header, 0);
                if (size < 0 || size > MAX_PAYLOAD) throw new ProtocolException("mirror packet of " + size + " bytes");
                if (size > payload.length) payload = new byte[Math.min(MAX_PAYLOAD, Math.max(size, payload.length * 2))];
                Bytes.readFully(in, payload, 0, size);
                packet(header, payload, size);
            }
        } catch (EOFException e) {
            Log.w("mirror: connection cut mid-packet");
        } finally {
            Log.w("mirror: ended after " + frames + " frames" + (broken > 0 ? ", " + broken + " with broken NAL lengths" : ""));
        }
    }

    void packet(byte[] header, byte[] payload, int size) throws IOException {
        int type = header[4] & 0xff, option = header[6] & 0xff;
        long senderNs = Ntp.ntpToNanos(Bytes.longLE(header, 8));
        switch (type) {
            case 0:
                decrypt(payload, size);
                frames++;
                if (!nalLengthsOk(payload, size)) {
                    if (broken++ == 0) Log.w("mirror: NAL lengths don't add up in a " + size + "-byte frame (wrong stream key?)");
                    checked = true;
                    return;
                }
                if (!checked) {
                    checked = true;
                    Log.w("mirror: first NAL lengths ok (" + size + " bytes)");
                }
                if (listener != null) {
                    toAnnexB(payload, size);
                    listener.frame(payload, size, senderNs);
                }
                break;
            case 1:
                codec(header, payload, size, option);
                break;
            case 2:   // heartbeat
                break;
            case 5:   // the sender's stream report
                Log.d("mirror: report of " + size + " bytes");
                break;
            default:
                Log.d("mirror: packet type " + type + " (" + size + " bytes)");
        }
    }

    private void codec(byte[] header, byte[] payload, int size, int option) {
        int w = (int) Bytes.floatLE(header, 56), h = (int) Bytes.floatLE(header, 60);
        int sw = (int) Bytes.floatLE(header, 40), sh = (int) Bytes.floatLE(header, 44);
        if (option == 0x56 || option == 0x5e) Log.w("mirror: the sender paused (screen locked or asleep)");
        if (size == 0) {
            Log.w("mirror: EMPTY codec config: the sender wants a codec we don't announce (not H.264)");
            return;
        }
        byte[] p = Bytes.slice(payload, 0, size);
        boolean hevc = option == 0x1e || option == 0x5e || (size > 0 && p[0] == 1 && size >= 23 && !looksAvcC(p));
        byte[][] csd = hevc ? hvcC(p) : avcC(p);
        if (csd == null) {
            Log.w("mirror: codec config not understood (" + size + " bytes, option 0x" + Integer.toHexString(option) + ")");
            return;
        }
        Log.w("mirror: codec config " + (hevc ? "h265" : "h264") + " " + w + "x" + h + " (source " + sw + "x" + sh + ")");
        if (listener != null) listener.codec(hevc ? "video/hevc" : "video/avc", csd, w, h);
    }

    /** AES-CTR over the payload, continuing the keystream from the previous packet. */
    public void decrypt(byte[] b, int len) throws IOException {
        int at = 0;
        while (residualLeft > 0 && at < len) {
            b[at++] ^= residual[16 - residualLeft--];
        }
        int blocks = (len - at) / 16 * 16;
        try {
            if (blocks > 0) cipher.update(b, at, blocks, b, at);
            int rest = len - at - blocks;
            if (rest > 0) {
                java.util.Arrays.fill(residual, (byte) 0);
                cipher.update(residual, 0, 16, residual, 0);
                for (int i = 0; i < rest; i++) b[at + blocks + i] ^= residual[i];
                residualLeft = 16 - rest;
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("AES-CTR failed", e);
        }
    }

    /** Every 4-byte NAL length must land exactly on the end: a quick sign that the stream key is right. */
    public static boolean nalLengthsOk(byte[] b, int len) {
        int at = 0;
        if (len < 5) return false;
        while (at + 4 <= len) {
            int n = Bytes.intBE(b, at);
            if (n <= 0 || n > len - at - 4) return false;
            at += 4 + n;
        }
        return at == len;
    }

    /** Whether an Annex-B H.264 access unit holds an IDR slice (NAL type 5): decoding can start there. */
    public static boolean isIdr(byte[] b, int len) {
        for (int i = 0; i + 3 < len; i++) {
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1 && (b[i + 3] & 0x1f) == 5) return true;
        }
        return false;
    }

    /** Whether other frames may refer to this one: nal_ref_idc of its first slice (type 1 or 5) isn't 0. */
    public static boolean isReference(byte[] b, int len) {
        for (int i = 0; i + 3 < len; i++) {
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) {
                int type = b[i + 3] & 0x1f;
                if (type == 1 || type == 5) return (b[i + 3] & 0x60) != 0;
            }
        }
        return true;   // no slice found: keep it
    }

    /** AVCC → Annex-B in place (the 4-byte lengths become 00 00 00 01 start codes). */
    public static void toAnnexB(byte[] b, int len) {
        int at = 0;
        while (at + 4 <= len) {
            int n = Bytes.intBE(b, at);
            b[at] = 0;
            b[at + 1] = 0;
            b[at + 2] = 0;
            b[at + 3] = 1;
            if (n < 0) return;
            at += 4 + n;
        }
    }

    private static boolean looksAvcC(byte[] p) {
        return p.length >= 7 && (p[4] & 0xfc) == 0xfc && (p[5] & 0xe0) == 0xe0;
    }

    /** avcC → {SPS, PPS} with start codes, or null. */
    public static byte[][] avcC(byte[] p) {
        try {
            if (p.length < 7 || p[0] != 1) return null;
            ByteArrayOutputStream sps = new ByteArrayOutputStream(), pps = new ByteArrayOutputStream();
            int at = 5;
            int count = p[at++] & 0x1f;
            for (int i = 0; i < count; i++) at = nal(p, at, sps);
            count = p[at++] & 0xff;
            for (int i = 0; i < count; i++) at = nal(p, at, pps);
            if (sps.size() == 0 || pps.size() == 0) return null;
            return new byte[][] {sps.toByteArray(), pps.toByteArray()};
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    /** hvcC → {VPS+SPS+PPS} with start codes (MediaCodec takes them all in csd-0), or null. */
    public static byte[][] hvcC(byte[] p) {
        try {
            if (p.length < 23) return null;
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            int at = 22;
            int arrays = p[at++] & 0xff;
            for (int a = 0; a < arrays; a++) {
                at++;   // NAL type
                int count = (p[at] & 0xff) << 8 | (p[at + 1] & 0xff);
                at += 2;
                for (int i = 0; i < count; i++) at = nal(p, at, all);
            }
            return all.size() == 0 ? null : new byte[][] {all.toByteArray()};
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    /** One 2-byte-length NAL unit from a config record, written with a start code. */
    private static int nal(byte[] p, int at, ByteArrayOutputStream out) {
        int n = (p[at] & 0xff) << 8 | (p[at + 1] & 0xff);
        at += 2;
        if (at + n > p.length) throw new IndexOutOfBoundsException();
        out.write(0); out.write(0); out.write(0); out.write(1);
        out.write(p, at, n);
        return at + n;
    }
}
