package io.freemytv;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import io.freemytv.airplay.AudioStream;
import io.freemytv.airplay.AudioSync;
import io.freemytv.airplay.Ntp;

import java.nio.ByteBuffer;

/**
 * The mirror session's sound (in :airplay): AAC-ELD frames from AudioStream through a MediaCodec decoder (Android's
 * own c2.android.aac.decoder first) into a low-latency AudioTrack. Audio follows video by arrival: a frame is heard
 * as long after its earliest possible arrival as pictures are shown after theirs (Ntp.videoDelayNs). That needs no
 * agreement between the sender's audio and video timestamps (they don't mean the same thing: matching them put the
 * sound 300–400 ms late). At least AudioSync.QUEUE_NS of sound stays queued (room to wait for resent packets).
 */
final class AirPlayAudio implements AudioStream.Listener {
    private static final int RATE = AudioStream.RATE, CHANNELS = 2;
    /** AudioSpecificConfig for AAC-ELD, 44.1 kHz, stereo, 480 samples per frame. */
    private static final byte[] ELD_CONFIG = {(byte) 0xf8, (byte) 0xe8, 0x50, 0x00};

    private final Ntp clock;
    private final Object lock = new Object();
    private MediaCodec codec;
    private AudioTrack track;
    private Thread drain;
    private volatile boolean released;
    private volatile float gain = 1f;
    /** RTP times unwrapped to 64 bits; the audio arrival floor (arrival minus RTP time, ns). */
    private long lastRtp = -1;
    private final io.freemytv.airplay.Floor arrival = new io.freemytv.airplay.Floor();
    private volatile long arrivalFloor = Long.MIN_VALUE;
    private long written, frames, failures;
    /** Drain thread only: the sync decisions, the picture's delay as audio uses it, stats. */
    private final AudioSync sync = new AudioSync();
    private long delay = Long.MIN_VALUE, statsSince, lastWrite, maxWriteGap;
    private int lastUnderruns;

    AirPlayAudio(Ntp clock, float volumeDb) {
        this.clock = clock;
        this.gain = AudioStream.gain(volumeDb);
    }

    // MARK: - AudioStream.Listener (audio threads)

    @Override public void audio(byte[] data, int len, long rtpTime) {
        synchronized (lock) {
            if (released) return;
            if (codec == null && !start()) return;
            long rtp = unwrap(rtpTime);
            long now = System.nanoTime();
            arrivalFloor = arrival.add(now - rtp * 1_000_000_000L / RATE, now);
            try {
                int index = codec.dequeueInputBuffer(20_000);
                if (index < 0) return;   // the decoder is behind: this frame is lost
                ByteBuffer in = codec.getInputBuffer(index);
                in.clear();
                in.put(data, 0, len);
                codec.queueInputBuffer(index, 0, len, rtp * 1_000_000L / RATE, 0);
                frames++;
            } catch (RuntimeException e) {
                AppLog.w("audio: decoder error: " + e);
                stop();
            }
        }
    }

    /** Sync packets aren't needed: audio is placed by its arrival (see the class comment). */
    @Override public void sync(long rtpTime, long senderNs) {
    }

    void volume(float db) {
        gain = AudioStream.gain(db);
        AudioTrack t = track;
        if (t != null) t.setVolume(gain);
    }

    void release() {
        synchronized (lock) {
            if (released) return;
            released = true;
            stop();
        }
        if (frames > 0) AppLog.w("audio: " + frames + " frames decoded, " + failures + " decoder failures");
    }

    // MARK: - Decoder and track (under lock)

    private boolean start() {
        try {
            MediaFormat f = MediaFormat.createAudioFormat("audio/mp4a-latm", RATE, CHANNELS);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD);
            f.setInteger(MediaFormat.KEY_IS_ADTS, 0);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(ELD_CONFIG));
            MediaCodec c;
            try {
                c = MediaCodec.createByCodecName("c2.android.aac.decoder");
            } catch (Exception e) {
                c = MediaCodec.createDecoderByType("audio/mp4a-latm");
            }
            c.configure(f, null, null, 0);
            c.start();
            codec = c;
            track = newTrack(RATE, CHANNELS);
            written = 0;
            AppLog.w("audio: decoder " + c.getName() + ", AAC-ELD " + RATE + " Hz, low-latency track");
            startDrain(c, track);
            return true;
        } catch (Exception e) {
            if (failures++ == 0) AppLog.w("audio: could not start: " + e);
            stop();
            return false;
        }
    }

    private AudioTrack newTrack(int rate, int channels) {
        int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                // Room for 400 ms: AudioSync keeps about 100 ms queued, plus whatever waits for the picture.
                .setBufferSizeInBytes(Math.max(min, rate * channels * 2 * 4 / 10))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        t.setVolume(gain);
        t.play();
        return t;
    }

    private void stop() {
        MediaCodec c = codec;
        codec = null;
        AudioTrack t = track;
        track = null;
        Thread d = drain;
        drain = null;
        if (d != null) d.interrupt();
        if (c != null) {
            try {
                c.stop();
            } catch (RuntimeException ignored) {
            }
            c.release();
        }
        if (t != null) {
            try {
                t.pause();
                t.flush();
            } catch (RuntimeException ignored) {
            }
            t.release();
        }
    }

    private long unwrap(long rtp32) {
        if (lastRtp < 0) {
            lastRtp = rtp32;
        } else {
            lastRtp += (int) (rtp32 - (lastRtp & 0xffffffffL));   // the signed 32-bit step
        }
        return lastRtp;
    }

    // MARK: - Output (drain thread)

    private void startDrain(final MediaCodec c, final AudioTrack initial) {
        Thread t = new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            AudioTrack out = initial;
            byte[] pcm = new byte[16 * 1024];
            AudioTimestamp ts = new AudioTimestamp();
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int index = c.dequeueOutputBuffer(info, 50_000);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat f = c.getOutputFormat();
                        int rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (rate != out.getSampleRate() || ch != out.getChannelCount()) {
                            AppLog.w("audio: the decoder gives " + rate + " Hz, " + ch + " channels");
                            synchronized (lock) {
                                if (track != out) return;
                                out.release();
                                out = track = newTrack(rate, ch);
                                written = 0;
                            }
                        }
                        continue;
                    }
                    if (index < 0) continue;
                    ByteBuffer b = c.getOutputBuffer(index);
                    int n = info.size;
                    if (b != null && n > 0) {
                        if (pcm.length < n) pcm = new byte[n];
                        b.position(info.offset);
                        b.get(pcm, 0, n);
                    }
                    c.releaseOutputBuffer(index, false);
                    if (b != null && n > 0) play(out, pcm, n, info.presentationTimeUs, ts);
                } catch (RuntimeException e) {   // stopped or released under us
                    return;
                }
            }
        }, "airplay-audio-out");
        drain = t;
        t.start();
    }

    /** Writes one decoded frame where AudioSync says: after a little silence, twice, or not at all. */
    private void play(AudioTrack out, byte[] pcm, int n, long ptsUs, AudioTimestamp ts) {
        int bytesPerFrame = out.getChannelCount() * 2;
        int rate = out.getSampleRate();
        long now = System.nanoTime();
        boolean stamped = out.getTimestamp(ts);
        long presented = stamped ? ts.framePosition : out.getPlaybackHeadPosition();
        long heardAt = (stamped ? ts.nanoTime : now) + Math.max(0, (written - presented) * 1_000_000_000L / rate);
        // What the track still holds (the hardware's own delay is in heardAt, not in this).
        long bufferNs = Math.max(0, (written - (out.getPlaybackHeadPosition() & 0xffffffffL)) * 1_000_000_000L / rate);
        int action = sync.decide(now, heardAt, due(ptsUs * RATE / 1_000_000L), bufferNs);
        if (sync.silenceNs > 0) {
            int silence = (int) (sync.silenceNs * rate / 1_000_000_000L) * bytesPerFrame;
            out.write(new byte[silence], 0, silence);
            written += silence / bytesPerFrame;
        }
        if (action == AudioSync.DROP) return;
        if (lastWrite != 0) maxWriteGap = Math.max(maxWriteGap, now - lastWrite);
        for (int i = action == AudioSync.REPEAT ? 2 : 1; i > 0; i--) {
            int done = out.write(pcm, 0, n);
            if (done > 0) written += done / bytesPerFrame;
        }
        lastWrite = System.nanoTime();
        if (now - statsSince >= 10_000_000_000L) {
            int underruns = out.getUnderrunCount();
            if (statsSince != 0) {
                AppLog.w("audio: 10 s: sync error " + (sync.errorCount == 0 ? 0 : sync.errorSum / sync.errorCount / 1_000_000)
                        + " ms (+ late, - early; worst " + sync.errorMax / 1_000_000 + "), " + sync.drops + " dropped, "
                        + sync.repeats + " repeated, " + sync.realigns + " re-aligned, " + sync.silenceTotalNs / 1_000_000
                        + " ms silence, " + (underruns - lastUnderruns) + " underruns, " + bufferNs / 1_000_000
                        + " ms held, longest gap between writes " + maxWriteGap / 1_000_000 + " ms"
                        + (following() ? "" : ", no picture to follow") + (sync.nonsense > 0 ? ", clock mapping ignored "
                        + sync.nonsense + "x" : ""));
            }
            lastUnderruns = underruns;
            maxWriteGap = 0;
            sync.clearCounts();
            statsSince = now;
        }
    }

    /** Whether pictures are flowing (one shown within the last second). */
    private boolean following() {
        return clock != null && clock.videoShownAt != Long.MIN_VALUE && System.nanoTime() - clock.videoShownAt < 1_000_000_000L;
    }

    /**
     * When a frame (by its RTP time) should be heard, on System.nanoTime(): its earliest possible arrival plus the
     * picture's delay after arrival; MIN_VALUE without video (then AudioSync just keeps the queue).
     */
    private long due(long rtp) {
        long video = clock == null ? Long.MIN_VALUE : clock.videoDelayNs;
        if (video != Long.MIN_VALUE) {
            // Followed slowly (about 20 s): AudioSync absorbs what's left.
            delay = delay == Long.MIN_VALUE ? video : delay + (video - delay) / 2048;
        }
        long floor = arrivalFloor;
        // No picture for a second (a still screen, music, a paused video): keep the queue, follow nothing.
        if (!following() || delay == Long.MIN_VALUE || floor == Long.MIN_VALUE || floor == Long.MAX_VALUE) return Long.MIN_VALUE;
        return rtp * 1_000_000_000L / RATE + floor + delay;
    }
}
