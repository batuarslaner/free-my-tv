package io.freemytv.airplay;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The audio of a mirroring session (stream type 96): RTP over UDP on our dataPort, sync and resent packets on our
 * controlPort. Each packet's payload is one AAC-ELD frame (480 samples at 44.1 kHz), AES-128-CBC encrypted on its
 * own (the IV is the session's eiv every time) over len/16*16 bytes; the tail is clear.
 * A missing packet is asked for again at once (a resend request to the sender's control port, once more 30 ms later)
 * and waited for up to 80 ms while the packets after it are held; then it's given up. The player keeps that much
 * sound queued (AudioSync), so a recovered packet costs nothing audible.
 */
public final class AudioStream implements Closeable {
    public interface Listener {
        /** One decrypted frame in data[0..len), in order (the buffer is reused). rtpTime: its 32-bit RTP time. */
        void audio(byte[] data, int len, long rtpTime);

        /** From sync packets: the sample at rtpTime was captured at senderNs (sender clock, as Ntp.ntpToNanos). */
        void sync(long rtpTime, long senderNs);
    }

    public static final int RATE = 44100;
    /** How long a missing packet is waited for, and when it's asked for a second time. */
    public static final long WAIT_NS = 80_000_000L, RETRY_NS = 30_000_000L;
    private static final long STATS_NS = 10_000_000_000L;

    private final DatagramSocket data, control;
    private final byte[] key, iv;
    private final Listener listener;
    private final InetAddress sender;
    private final int senderControlPort;
    private final Reorder reorder = new Reorder(WAIT_NS, RETRY_NS);
    private final Cipher cipher;
    private volatile boolean closed;
    private int requestSeq;
    private long statsSince, packets, packetsTotal, resent, syncs;

    /** sender and senderControlPort: where resend requests go (0: none are sent). */
    public AudioStream(byte[] aesKey, byte[] eiv, Listener listener, InetAddress sender, int senderControlPort)
            throws IOException {
        this.key = aesKey.clone();
        this.iv = eiv == null || eiv.length != 16 ? new byte[16] : eiv.clone();
        this.listener = listener;
        this.sender = sender;
        this.senderControlPort = senderControlPort;
        try {
            cipher = Cipher.getInstance("AES/CBC/NoPadding");
        } catch (GeneralSecurityException e) {
            throw new IOException("AES-CBC unavailable", e);
        }
        data = new DatagramSocket(0);
        control = new DatagramSocket(0);
        data.setReceiveBufferSize(256 * 1024);
        start(data, "airplay-audio", 10);   // a short timeout: the reorder buffer's deadlines are checked between packets
        start(control, "airplay-audio-control", 1000);
    }

    public int dataPort() {
        return data.getLocalPort();
    }

    public int controlPort() {
        return control.getLocalPort();
    }

    /** FLUSH (RTP-Info seq=…): drop what's buffered, the next packet is seq. */
    public void flush(int nextSeq) {
        synchronized (reorder) {
            reorder.reset(nextSeq);
        }
    }

    private void start(final DatagramSocket socket, String name, int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        Thread t = new Thread(() -> {
            byte[] b = new byte[2048];
            DatagramPacket p = new DatagramPacket(b, b.length);
            while (!closed) {
                try {
                    p.setLength(b.length);
                    socket.receive(p);
                    packet(b, p.getLength(), socket == control);
                } catch (SocketTimeoutException ignored) {
                } catch (IOException e) {
                    if (!closed) Log.d("audio: " + e.getMessage());
                } catch (RuntimeException e) {   // a bad packet must not end the stream
                    Log.d("audio: bad packet: " + e);
                }
                if (socket == data) tick();
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    void packet(byte[] b, int len, boolean fromControl) {
        if (len < 4 || (b[0] & 0xc0) != 0x80) return;
        int type = b[1] & 0x7f;
        if (fromControl && type == 0x54 && len >= 20) {   // sync: RTP time "now" (16) and its sender time (8)
            syncs++;
            if (listener != null) listener.sync(Bytes.intBE(b, 16) & 0xffffffffL, Ntp.ntpToNanos(Ntp.getLong(b, 8)));
            return;
        }
        if (fromControl && type == 0x56 && len >= 16) {   // a resent packet, after a 4-byte header
            byte[] inner = new byte[len - 4];
            System.arraycopy(b, 4, inner, 0, inner.length);
            resent++;
            rtp(inner, inner.length);
            return;
        }
        if (!fromControl && type == 0x60) rtp(b, len);
    }

    private void rtp(byte[] b, int len) {
        if (len < 12) return;
        int seq = ((b[2] & 0xff) << 8) | (b[3] & 0xff);
        long rtpTime = Bytes.intBE(b, 4) & 0xffffffffL;
        byte[] payload = new byte[len - 12];
        System.arraycopy(b, 12, payload, 0, payload.length);
        synchronized (reorder) {
            try {
                decrypt(cipher, key, iv, payload, payload.length);
            } catch (GeneralSecurityException e) {
                Log.w("audio: decrypt failed: " + e);
                return;
            }
            packets++;
            deliver(reorder.push(seq, rtpTime, payload, System.nanoTime()));
            ask();
        }
    }

    /** Deadlines and retries, between packets (data thread). */
    private void tick() {
        long now = System.nanoTime();
        synchronized (reorder) {
            deliver(reorder.poll(now));
            ask();
            if (statsSince == 0) statsSince = now;
            if (now - statsSince >= STATS_NS) {
                if (packets > 0) {
                    Log.w("audio: 10 s: " + packets + " packets (" + reorder.delivered + " played, " + reorder.duplicates
                            + " duplicates, " + reorder.sameTime + " repeats of an RTP time, " + resent + " resent), " + syncs
                            + " sync, " + reorder.missing + " missing, " + reorder.asked + " asked again, " + reorder.recovered
                            + " recovered, " + reorder.lost + " given up");
                }
                packetsTotal += packets;
                packets = resent = syncs = 0;
                reorder.clearCounts();
                statsSince = now;
            }
        }
    }

    private void deliver(List<Reorder.Packet> ready) {
        if (listener == null) return;
        for (Reorder.Packet o : ready) listener.audio(o.data, o.data.length, o.rtpTime);
    }

    /** Sends the resend requests the reorder buffer wants (under its lock). */
    private void ask() {
        int[] range;
        while ((range = reorder.nextRequest()) != null) {
            if (sender == null || senderControlPort <= 0) continue;
            byte[] req = resendRequest(++requestSeq, range[0], range[1]);
            try {
                control.send(new DatagramPacket(req, req.length, sender, senderControlPort));
            } catch (IOException e) {
                Log.d("audio: resend request: " + e.getMessage());
            }
        }
    }

    /** RTP resend request (type 0x55 with the marker bit): our request number, the first missing seq, the count. */
    public static byte[] resendRequest(int requestSeq, int first, int count) {
        return new byte[] {(byte) 0x80, (byte) 0xd5, (byte) (requestSeq >> 8), (byte) requestSeq,
                (byte) (first >> 8), (byte) first, (byte) (count >> 8), (byte) count};
    }

    /** AES-128-CBC over len/16*16 bytes with a fresh IV; the last len%16 bytes are sent in the clear. */
    public static void decrypt(Cipher c, byte[] key, byte[] iv, byte[] b, int len) throws GeneralSecurityException {
        int whole = len / 16 * 16;
        if (whole == 0) return;
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        c.doFinal(b, 0, whole, b, 0);
    }

    /** AirPlay's volume (dB: 0 is full, −30 the quietest, −144 mute) as an AudioTrack gain. */
    public static float gain(float db) {
        if (Float.isNaN(db) || db <= -144f) return 0f;
        return (float) Math.pow(10, Math.max(-30f, Math.min(0f, db)) / 20.0);
    }

    @Override public void close() {
        closed = true;
        data.close();
        control.close();
        synchronized (reorder) {
            long total = packetsTotal + packets;
            if (total > 0) Log.w("audio: " + total + " packets, " + reorder.lostTotal + " given up");
        }
    }

    /**
     * Puts RTP packets back in order. A gap is asked for at once (nextRequest) and once more after `retry`; the
     * packets after it wait until it comes or `wait` has passed since it went missing, then it's given up.
     * Times are passed in (ns), so it can be tested without a clock. Not thread-safe.
     */
    public static final class Reorder {
        public static final class Packet {
            public final int seq;
            public final long rtpTime;
            public final byte[] data;

            Packet(int seq, long rtpTime, byte[] data) {
                this.seq = seq;
                this.rtpTime = rtpTime;
                this.data = data;
            }
        }

        private static final int SLOTS = 128;
        private final long wait, retry;
        private final Packet[] slots = new Packet[SLOTS];
        /** For missing seqs: when they went missing (NONE: not missing), and whether the retry went out. */
        private static final long NONE = Long.MIN_VALUE;
        private final long[] missingAt = new long[SLOTS];
        private final boolean[] retried = new boolean[SLOTS];
        private final List<int[]> requests = new ArrayList<>();
        private int next = -1, highest = -1;
        /** Counters: since clearCounts() and for the whole stream (lostTotal). */
        public long missing, asked, recovered, lost, lostTotal, delivered, duplicates, sameTime;
        /** The RTP time of the last packet let out: a packet that doesn't move past it carries nothing new. */
        private long lastTime = -1;

        public Reorder(long wait, long retry) {
            this.wait = wait;
            this.retry = retry;
            java.util.Arrays.fill(missingAt, NONE);
        }

        void reset(int nextSeq) {
            java.util.Arrays.fill(slots, null);
            java.util.Arrays.fill(missingAt, NONE);
            requests.clear();
            lastTime = -1;
            next = nextSeq & 0xffff;
            highest = (next - 1) & 0xffff;
        }

        void clearCounts() {
            missing = asked = recovered = lost = delivered = duplicates = sameTime = 0;
        }

        /** Takes a packet at time now; returns the ones now ready, in order. */
        public List<Packet> push(int seq, long rtpTime, byte[] data, long now) {
            seq &= 0xffff;
            if (next < 0) reset(seq);
            int ahead = (short) (seq - next);
            if (ahead < 0) {   // late, or a copy (iPhones send audio packets more than once)
                duplicates++;
                return poll(now);
            }
            if (ahead >= SLOTS) {              // far ahead: the sender jumped, start over there
                reset(seq);
                ahead = 0;
            }
            int slot = seq % SLOTS;
            if (slots[slot] != null && slots[slot].seq == seq) {   // a copy of one still waiting
                duplicates++;
                return poll(now);
            }
            if (missingAt[slot] != NONE) {
                missingAt[slot] = NONE;
                recovered++;
            }
            slots[slot] = new Packet(seq, rtpTime, data);
            int beyond = (short) (seq - highest);
            if (beyond > 0) {
                // Everything between the highest seen and this one is missing: ask for it.
                int first = (highest + 1) & 0xffff, count = beyond - 1;
                for (int i = 0; i < count; i++) {
                    int s = (first + i) & 0xffff;
                    missingAt[s % SLOTS] = now;
                    retried[s % SLOTS] = false;
                }
                if (count > 0) {
                    missing += count;
                    request(first, count);
                }
                highest = seq;
            }
            return poll(now);
        }

        /** The packets ready at time now: in order, past missing ones whose wait is over. Asks again when due. */
        public List<Packet> poll(long now) {
            List<Packet> out = new ArrayList<>(2);
            if (next < 0) return out;
            while ((short) (highest - next) >= 0) {
                int slot = next % SLOTS;
                Packet p = slots[slot];
                if (p != null && p.seq == next) {
                    slots[slot] = null;
                    if (lastTime >= 0 && (int) (p.rtpTime - lastTime) <= 0) {
                        sameTime++;   // another sequence number, but no new sound
                    } else {
                        lastTime = p.rtpTime;
                        delivered++;
                        out.add(p);
                    }
                } else if (missingAt[slot] != NONE && now - missingAt[slot] < wait) {
                    if (!retried[slot] && now - missingAt[slot] >= retry) {
                        retried[slot] = true;
                        request(next, 1);
                    }
                    break;   // still worth waiting for
                } else {
                    missingAt[slot] = NONE;
                    lost++;
                    lostTotal++;
                }
                next = (next + 1) & 0xffff;
            }
            return out;
        }

        private void request(int first, int count) {
            requests.add(new int[] {first, count});
            asked += count;
        }

        /** The next resend request to send ({first seq, count}), or null. */
        public int[] nextRequest() {
            return requests.isEmpty() ? null : requests.remove(0);
        }
    }
}
