package io.freemytv;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;

import io.freemytv.airplay.MirrorStream;
import io.freemytv.airplay.Ntp;
import io.freemytv.airplay.Pacing;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The mirror stream's decoder (MediaCodec, in :airplay). It starts on a placeholder Surface, so frames that arrive
 * before the player's SurfaceView exists still go through the decoder (the first IDR isn't lost); setOutputSurface
 * moves the picture to the player when it's ready. Frames render as soon as they're decoded. A new codec config
 * (the iPhone rotated, or an app changed the size) restarts the decoder with the new parameter sets.
 * Feeding runs on the mirror thread, draining on its own thread, surface changes on the main thread.
 */
final class AirPlayVideo implements MirrorStream.Listener {
    /** What the service and the player hear about. */
    interface Events {
        /** The first config of the session (open the player), and every size change after it. */
        void size(int width, int height, boolean first);
    }

    private static final long INPUT_WAIT_US = 200_000;
    /** Frames kept since the last IDR, to restart a decoder without waiting for the next one. */
    private static final int REPLAY_BYTES = 16 << 20;

    private final Events events;
    private final Object lock = new Object();
    private MediaCodec codec;
    private String mime;
    private byte[][] csd;
    private int width, height;
    private final SurfaceTexture placeholderTexture = new SurfaceTexture(false);
    private final Surface placeholder = new Surface(placeholderTexture);
    /** The player's surface, or null: then frames are decoded but not shown. */
    private Surface screen;
    /** Where the decoder draws now (the player's surface or the placeholder). */
    private Surface target;
    private volatile boolean onScreen, released;
    private Thread drain;
    private final List<byte[]> sinceIdr = new ArrayList<>();
    private int sinceIdrBytes;
    private long frames, dropped, startedAt, firstShownAt;
    /** The session's clock (null: no latency figures), and the late-frame policy. */
    private final Ntp clock;
    private final Pacing pacing = new Pacing();
    private final Stats stats = new Stats();

    AirPlayVideo(Events events, Ntp clock) {
        this.events = events;
        this.clock = clock;
        placeholderTexture.setDefaultBufferSize(64, 64);
    }

    // MARK: - MirrorStream.Listener (mirror thread)

    @Override public void codec(String mime, byte[][] csd, int width, int height) {
        if (!"video/avc".equals(mime)) {
            AppLog.w("video: " + mime + " is not supported yet");
            return;
        }
        boolean first;
        synchronized (lock) {
            if (released) return;
            if (codec != null && mime.equals(this.mime) && Arrays.deepEquals(csd, this.csd)) return;   // the same again
            first = this.csd == null;
            boolean resized = !first && (width != this.width || height != this.height);
            this.mime = mime;
            this.csd = csd;
            this.width = width;
            this.height = height;
            sinceIdr.clear();
            sinceIdrBytes = 0;
            if (start() && !first) AppLog.w("video: new codec config " + width + "x" + height + (resized ? " (new size)" : "") + ", decoder restarted");
        }
        events.size(width, height, first);
    }

    @Override public void frame(byte[] data, int len, long senderNs) {
        long arrival = System.nanoTime();
        boolean idr = MirrorStream.isIdr(data, len);
        synchronized (lock) {
            if (released || csd == null) return;
            int verdict = pacing.decide(senderNs, arrival, idr, MirrorStream.isReference(data, len));
            synchronized (stats) {
                stats.in++;
                stats.lateness = Math.max(stats.lateness, pacing.lateness);
                if (verdict == Pacing.SKIP) stats.skipped++;
                if (verdict == Pacing.WAIT_IDR && stats.waitedForIdr++ == 0) {
                    AppLog.w("video: " + pacing.lateness / 1_000_000 + " ms behind: dropping until the next IDR");
                }
            }
            if (verdict != Pacing.KEEP) {
                dropped++;
                return;
            }
            if (codec == null) {   // the last decoder failed: a new one can only start at an IDR
                if (!idr || !start()) {
                    dropped++;
                    return;
                }
            }
            if (idr) {
                sinceIdr.clear();
                sinceIdrBytes = 0;
            }
            if (sinceIdrBytes + len <= REPLAY_BYTES) {
                sinceIdr.add(Arrays.copyOf(data, len));
                sinceIdrBytes += len;
            }
            frames++;
            if (!queue(data, len, senderNs)) {
                dropped++;
                if (codec == null) {   // the decoder failed: a new one, fed again from the last IDR
                    if (start()) replay();
                }
            }
        }
    }

    // MARK: - The player's surface (main thread)

    /** The player's surface came (or, with null, is going away: must be off it before this returns). */
    void setSurface(Surface surface) {
        synchronized (lock) {
            screen = surface;
            if (codec == null || released) return;
            Surface want = surface != null ? surface : placeholder;
            if (want == target) return;
            try {
                codec.setOutputSurface(want);
                target = want;
                onScreen = surface != null;
            } catch (RuntimeException e) {
                // Some decoders can't switch: a new one on the new surface, fed again from the last IDR.
                AppLog.w("video: setOutputSurface failed (" + e + "), restarting the decoder");
                if (start()) replay();
            }
        }
    }

    /** At the end of the session. Any thread. */
    void release() {
        synchronized (lock) {
            if (released) return;
            released = true;
            stopCodec();
            screen = null;
        }
        placeholder.release();
        placeholderTexture.release();
        if (frames > 0) AppLog.w("video: " + frames + " frames decoded, " + dropped + " dropped, " + stats.staleTotal + " not shown (stale)");
    }

    // MARK: - The decoder (under lock)

    private boolean start() {
        stopCodec();
        String name = decoderFor(mime);
        try {
            MediaFormat f = MediaFormat.createVideoFormat(mime, Math.max(width, 16), Math.max(height, 16));
            f.setByteBuffer("csd-0", ByteBuffer.wrap(csd[0]));
            if (csd.length > 1) f.setByteBuffer("csd-1", ByteBuffer.wrap(csd[1]));
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(2 << 20, width * height));
            f.setInteger(MediaFormat.KEY_PRIORITY, 0);   // realtime
            boolean lowLatency = lowLatency(name);
            if (lowLatency) f.setInteger("low-latency", 1);   // MediaFormat.KEY_LOW_LATENCY (Android 11)
            MediaCodec c = name != null ? MediaCodec.createByCodecName(name) : MediaCodec.createDecoderByType(mime);
            target = screen != null ? screen : placeholder;
            c.configure(f, target, null, 0);
            c.start();
            codec = c;
            onScreen = screen != null;
            startedAt = SystemClock.elapsedRealtime();
            firstShownAt = 0;
            AppLog.w("video: decoder " + c.getName() + " " + width + "x" + height + (lowLatency ? ", low latency" : ""));
            startDrain(c);
            return true;
        } catch (Exception e) {   // IOException, CodecException, IllegalArgumentException...
            AppLog.w("video: could not start the decoder: " + e);
            codec = null;
            return false;
        }
    }

    private void stopCodec() {
        MediaCodec c = codec;
        codec = null;
        if (c == null) return;
        try {
            c.stop();
        } catch (RuntimeException ignored) {
        }
        c.release();
        Thread t = drain;
        drain = null;
        if (t != null) t.interrupt();
    }

    /** Copies one access unit into the decoder. False when it couldn't (no input buffer in time, or an error). */
    private boolean queue(byte[] data, int len, long senderNs) {
        MediaCodec c = codec;
        try {
            int index = c.dequeueInputBuffer(INPUT_WAIT_US);
            if (index < 0) return false;
            ByteBuffer in = c.getInputBuffer(index);
            if (in == null || in.capacity() < len) {
                c.queueInputBuffer(index, 0, 0, 0, 0);
                AppLog.w("video: a " + len + "-byte frame doesn't fit the decoder's buffer");
                return false;
            }
            in.clear();
            in.put(data, 0, len);
            c.queueInputBuffer(index, 0, len, senderNs / 1000, 0);   // the capture time comes back with the picture
            return true;
        } catch (RuntimeException e) {   // CodecException, IllegalStateException
            AppLog.w("video: decoder error: " + e);
            stopCodec();
            return false;
        }
    }

    private void replay() {
        for (byte[] au : sinceIdr) {
            if (!queue(au, au.length, 0)) break;
        }
    }

    /**
     * Shows the newest decoded frame at once. When several are ready together (after a network burst), the older ones
     * are released unshown. The one shown carries a timestamp of now: with render=true, frames queue in the surface
     * and each waits its own vsync, so at a stream rate equal to the display's the delay after a burst never shrinks;
     * with a timestamp, SurfaceFlinger takes the newest frame due at each vsync and drops the older ones.
     */
    private void startDrain(final MediaCodec c) {
        Thread t = new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(), next = new MediaCodec.BufferInfo();
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int index = c.dequeueOutputBuffer(info, 50_000);
                    if (index >= 0) {
                        long pts = info.presentationTimeUs;
                        int ready = 1;
                        int newer;
                        while ((newer = c.dequeueOutputBuffer(next, 0)) >= 0) {
                            c.releaseOutputBuffer(index, false);
                            index = newer;
                            pts = next.presentationTimeUs;
                            ready++;
                        }
                        boolean show = onScreen;
                        long now = System.nanoTime();
                        if (show) c.releaseOutputBuffer(index, now);
                        else c.releaseOutputBuffer(index, false);
                        shown(show, ready, pts, now);
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        AppLog.d("video: output format " + c.getOutputFormat());
                    }
                } catch (RuntimeException e) {   // the decoder was stopped or released under us
                    return;
                }
            }
        }, "airplay-video");
        drain = t;
        t.start();
    }

    /** Bookkeeping for one frame out (drain thread): latency, the player's delay for audio, stats every 10 s. */
    private void shown(boolean show, int ready, long ptsUs, long now) {
        if (show && firstShownAt == 0) {
            firstShownAt = SystemClock.elapsedRealtime();
            AppLog.w("video: first frame on screen after " + (firstShownAt - startedAt) + " ms");
        }
        synchronized (stats) {
            stats.out++;
            stats.stale += ready - 1;
            stats.staleTotal += ready - 1;
            stats.maxReady = Math.max(stats.maxReady, ready);
            if (show && ptsUs > 0 && clock != null && clock.synced()) {
                long latency = now - clock.toLocal(ptsUs * 1000);
                // Relative to the sender's own timestamps (they may run a little ahead of capture): it can be
                // negative, and is still what audio must follow.
                if (Math.abs(latency) < 5_000_000_000L) {
                    stats.latencySum += latency;
                    stats.latencyCount++;
                    stats.latencyMax = Math.max(stats.latencyMax, latency);
                }
            }
            long floor = pacing.floor;
            if (show && ptsUs > 0 && clock != null && floor != Long.MIN_VALUE) {
                // Shown this long after the earliest arrival: what audio follows (Ntp.videoDelayNs). Smoothed.
                long afterArrival = (now - ptsUs * 1000) - floor;
                clock.videoShownAt = now;
                // Frames out of a stall are late for a moment; they mustn't drag the sound along.
                if (afterArrival >= 0 && afterArrival < 500_000_000L) {
                    long d = clock.videoDelayNs;
                    clock.videoDelayNs = d == Long.MIN_VALUE ? afterArrival : d + (afterArrival - d) / 16;
                    stats.afterArrivalMax = Math.max(stats.afterArrivalMax, afterArrival);
                }
            }
            if (now - stats.since >= 10_000_000_000L) {
                if (stats.since != 0) AppLog.w(stats.line());
                stats.reset(now);
            }
        }
    }

    /** What the 10 s log line counts (under its own lock). */
    private static final class Stats {
        long since, in, out, stale, staleTotal, skipped, waitedForIdr, lateness, latencySum, latencyCount, latencyMax,
                afterArrivalMax;
        int maxReady;

        String line() {
            String latency = latencyCount == 0 ? "unknown (no clock sync)"
                    : "~" + latencySum / latencyCount / 1_000_000 + " ms (max " + latencyMax / 1_000_000 + ")";
            return "video: 10 s: " + in + " in, " + out + " out, " + stale + " stale, " + skipped + " skipped late, "
                    + waitedForIdr + " dropped for an IDR, backlog max " + maxReady + ", lateness max "
                    + lateness / 1_000_000 + " ms, latency " + latency + ", shown after arrival max "
                    + afterArrivalMax / 1_000_000 + " ms";
        }

        void reset(long now) {
            since = now;
            in = out = stale = skipped = waitedForIdr = lateness = latencySum = latencyCount = latencyMax = afterArrivalMax = 0;
            maxReady = 0;
        }
    }

    // MARK: - Choosing the decoder

    /** A hardware decoder for the type, one with low latency first (c2.mtk.avc.decoder on MediaTek TVs). */
    private static String decoderFor(String mime) {
        String best = null;
        boolean bestLow = false;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder() || !Arrays.asList(info.getSupportedTypes()).contains(mime)) continue;
            String n = info.getName();
            if (n.startsWith("OMX.google.") || n.startsWith("c2.android.") || n.contains(".sw.")) continue;
            boolean low = lowLatency(n, mime);
            if (best == null || (low && !bestLow)) {
                best = n;
                bestLow = low;
            }
        }
        return best;
    }

    private boolean lowLatency(String name) {
        return name != null && lowLatency(name, mime);
    }

    /** FEATURE_LowLatency ("low-latency", Android 11+). */
    private static boolean lowLatency(String name, String mime) {
        if (Build.VERSION.SDK_INT < 30) return false;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.getName().equals(name)) continue;
            try {
                return info.getCapabilitiesForType(mime).isFeatureSupported("low-latency");
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return false;
    }
}
