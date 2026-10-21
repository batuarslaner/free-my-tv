package io.freemytv.airplay;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

/**
 * Clock sync with the sender (AirPlay's NTP variant over UDP): we ask its timingPort every 3 s. The sender's clock is
 * not wall-clock time: iPhones count from about their boot (the first P1 offset, −56.8 years, was exactly that against
 * our wall clock). So we never compare it with dates: our side is System.nanoTime(), and the offset only maps sender
 * times (mirror headers, audio sync packets) to ours. The mapping, once there, stays: a reply only replaces it when
 * its round trip is as short (a short round trip means a precise offset), or when the mapping is a minute old (then
 * the best of the recent replies). Missing or slow replies on a lossy network change nothing.
 */
public final class Ntp implements Closeable {
    static final long EPOCH_1900 = 2_208_988_800L;
    private static final int SAMPLES = 8;
    /** A reply this much slower than the best one still counts as as good. */
    private static final long RTT_SLACK_NS = 2_000_000L, MAX_AGE_NS = 60_000_000_000L;

    private final DatagramSocket socket;
    private final InetAddress sender;
    private final int senderPort;
    private volatile boolean closed;
    private final long[] offsets = new long[SAMPLES], rtts = new long[SAMPLES], ats = new long[SAMPLES];
    private int samples;
    private long bestRtt = Long.MAX_VALUE, bestAt;
    /** Sender clock minus ours (System.nanoTime), in ns; valid once synced(). */
    private volatile long offsetNs;
    private volatile boolean synced;
    /**
     * How long after its earliest possible arrival (the stream's arrival floor, see Pacing) the player shows a frame,
     * in ns; MIN_VALUE until known. Audio plays the same time after its own arrival floor, so the two meet whatever
     * the sender's timestamps mean (audio follows video). Set by the video player.
     */
    public volatile long videoDelayNs = Long.MIN_VALUE;
    /** When the video player last showed a frame (System.nanoTime), MIN_VALUE before the first. */
    public volatile long videoShownAt = Long.MIN_VALUE;

    public Ntp(InetAddress sender, int senderPort) throws IOException {
        this.sender = sender;
        this.senderPort = senderPort;
        socket = new DatagramSocket(0);
        socket.setSoTimeout(3000);
        Thread t = new Thread(this::loop, "airplay-ntp");
        t.setDaemon(true);
        t.start();
    }

    /** For tests and audio-only use: a clock with a known offset. */
    public Ntp(long offsetNs) {
        socket = null;
        sender = null;
        senderPort = 0;
        this.offsetNs = offsetNs;
        synced = true;
    }

    public int port() {
        return socket == null ? 0 : socket.getLocalPort();
    }

    public boolean synced() {
        return synced;
    }

    /** A sender time (ns, as ntpToNanos gives it) on our System.nanoTime() clock. */
    public long toLocal(long senderNs) {
        return senderNs - offsetNs;
    }

    /**
     * A 64-bit NTP time (32.32 fixed point) in ns. Senders write some times with the 1900 epoch (timing replies, audio
     * sync) and some without (mirror headers); a clock counting from boot is far below 2^31 s, so anything above
     * that has the epoch, which comes off. Both kinds then land in the same domain.
     */
    public static long ntpToNanos(long ntp) {
        long seconds = ntp >>> 32, fraction = ntp & 0xffffffffL;
        if (seconds >= 1L << 31) seconds -= EPOCH_1900;
        return seconds * 1_000_000_000L + ((fraction * 1_000_000_000L) >>> 32);
    }

    /** ns → 32.32 fixed point, without an epoch (what we send is only echoed back). */
    static long nanosToNtp(long ns) {
        long seconds = ns / 1_000_000_000L, rest = ns % 1_000_000_000L;
        return seconds << 32 | ((rest << 32) / 1_000_000_000L);
    }

    /** One exchange: t1 we sent, t2 they received, t3 they replied, t4 we received (all ns). */
    public void sample(long t1, long t2, long t3, long t4) {
        long rtt = (t4 - t1) - (t3 - t2);
        long offset = ((t2 - t1) + (t3 - t4)) / 2;
        synchronized (offsets) {
            int i = samples++ % SAMPLES;
            offsets[i] = offset;
            rtts[i] = rtt;
            ats[i] = t4;
            if (rtt < 0) return;   // nonsense
            if (bestRtt == Long.MAX_VALUE || rtt <= bestRtt + RTT_SLACK_NS) {
                bestRtt = Math.min(bestRtt, rtt);
                bestAt = t4;
                offsetNs = offset;
            } else if (t4 - bestAt > MAX_AGE_NS) {   // old: the best reply of the last minute takes over
                int best = i, n = Math.min(samples, SAMPLES);
                for (int j = 0; j < n; j++) {
                    if (rtts[j] >= 0 && t4 - ats[j] <= MAX_AGE_NS && rtts[j] < rtts[best]) best = j;
                }
                bestRtt = rtts[best];
                bestAt = t4;
                offsetNs = offsets[best];
            }
            if (!synced) Log.w("ntp: synced with the sender (round trip " + rtt / 1_000_000 + " ms)");
            synced = true;
        }
    }

    private void loop() {
        byte[] request = new byte[32], reply = new byte[128];
        long nextSend = 0;
        while (!closed) {
            try {
                long now = System.nanoTime();
                if (senderPort > 0 && now - nextSend >= 0) {
                    java.util.Arrays.fill(request, (byte) 0);
                    request[0] = (byte) 0x80;
                    request[1] = (byte) 0xd2;
                    request[3] = 0x07;
                    putLong(request, 24, nanosToNtp(now));
                    socket.send(new DatagramPacket(request, 32, sender, senderPort));
                    nextSend = now + 3_000_000_000L;
                }
                DatagramPacket p = new DatagramPacket(reply, reply.length);
                socket.receive(p);
                long t4 = System.nanoTime();
                if (p.getLength() >= 32 && (reply[1] & 0x7f) == 0x53 && sender.equals(p.getAddress())) {
                    long origin = getLong(reply, 8), received = getLong(reply, 16), sent = getLong(reply, 24);
                    Log.d("ntp: origin " + Long.toHexString(origin) + " t2 " + Long.toHexString(received) + " t3 "
                            + Long.toHexString(sent) + " t4 " + t4);
                    long t1 = (origin >>> 32) * 1_000_000_000L + (((origin & 0xffffffffL) * 1_000_000_000L) >>> 32);
                    sample(t1, ntpToNanos(received), ntpToNanos(sent), t4);
                }
            } catch (SocketTimeoutException ignored) {
            } catch (IOException e) {
                if (!closed) Log.d("ntp: " + e.getMessage());
                if (socket.isClosed()) return;
            }
        }
    }

    private static void putLong(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (56 - 8 * i));
    }

    static long getLong(byte[] b, int off) {
        return Bytes.longBE(b, off);
    }

    @Override public void close() {
        closed = true;
        if (socket != null) socket.close();
    }
}
