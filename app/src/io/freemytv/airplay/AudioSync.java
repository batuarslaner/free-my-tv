package io.freemytv.airplay;

/**
 * Keeps the sound where it should be. Each decoded frame comes with when it would be heard if written now (heardAt),
 * when it should be heard (due; MIN_VALUE when unknown) and how much sound the track still holds (bufferNs: written
 * but not yet taken by the audio hardware; the hardware's own delay is in heardAt, not here). The target is never so
 * early that less than QUEUE_NS is held: that's what lets AudioStream wait for a resent packet without a gap, and a
 * frame is never dropped below it (that only starves the track).
 * error = heardAt − target: positive means late (heard after its time: drop frames), negative means early (repeat
 * frames or add silence).
 *
 * At the start, one alignment (silence, or dropped frames). Then by the averaged error:
 *  - within 40 ms: nothing;
 *  - beyond 40 ms for 2 s: one frame dropped or repeated per second (small drifts, never heard as gaps);
 *  - beyond 150 ms for 1 s: one every 100 ms, until back within 20 ms (catching up);
 *  - beyond 300 ms for 3 s: a hard re-align, all at once (silence, or a run of dropped frames).
 * Not thread-safe; times in ns.
 */
public final class AudioSync {
    public static final int PLAY = 0, DROP = 1, REPEAT = 2;
    public static final long QUEUE_NS = AudioStream.WAIT_NS + 20_000_000L;
    public static final long FRAME_NS = 480 * 1_000_000_000L / AudioStream.RATE;
    static final long THRESHOLD_NS = 40_000_000L, SETTLE_NS = 20_000_000L, FAST_NS = 150_000_000L, HARD_NS = 300_000_000L,
            SUSTAIN_NS = 2_000_000_000L, FAST_SUSTAIN_NS = 1_000_000_000L, HARD_SUSTAIN_NS = 3_000_000_000L,
            GAP_NS = 1_000_000_000L,
            FAST_GAP_NS = 100_000_000L, MAX_SILENCE_NS = 400_000_000L, NONSENSE_NS = 2_000_000_000L;
    private static final int START_FRAMES = 50;

    private boolean aligned, fast;
    private int frames;
    private long average, beyondSince = Long.MIN_VALUE, lastCorrection = Long.MIN_VALUE / 2, dropBudget;
    /** Silence to write before this frame (ns), set by decide(). */
    public long silenceNs;
    /** Counters for the stats line, and the error (ns, + is late) of the last frame. */
    public long drops, repeats, realigns, silenceTotalNs, nonsense, errorSum, errorCount, errorMax, error;

    /**
     * What to do with the next frame. bufferNs: sound the track holds (written, not yet taken by the hardware).
     * Returns PLAY (after silenceNs of silence, usually 0), DROP or REPEAT (write it twice).
     */
    public int decide(long now, long heardAt, long due, long bufferNs) {
        silenceNs = 0;
        frames++;
        long queuedNs = bufferNs;
        long floor = heardAt - bufferNs + QUEUE_NS;   // the earliest target that keeps QUEUE_NS held
        long target = due == Long.MIN_VALUE ? floor : Math.max(due, floor);
        error = heardAt - target;
        if (Math.abs(error) > NONSENSE_NS) {   // a target that makes no sense: just keep the queue
            nonsense++;
            target = floor;
            error = heardAt - target;
        }
        errorSum += error;
        errorCount++;
        if (Math.abs(error) > Math.abs(errorMax)) errorMax = error;
        if (!aligned) {
            if (error < -10_000_000L) {
                silenceNs = Math.min(-error, MAX_SILENCE_NS);
                silenceTotalNs += silenceNs;
                aligned = true;
                average = error + silenceNs;
                return PLAY;
            }
            if (error > THRESHOLD_NS && queuedNs > QUEUE_NS && frames < START_FRAMES) {
                drops++;
                return DROP;
            }
            aligned = frames >= 3 || Math.abs(error) <= THRESHOLD_NS;
            average = error;
            return PLAY;
        }
        if (dropBudget > 0) {   // the rest of a hard re-align
            if (error > 0 && queuedNs > QUEUE_NS + 2 * FRAME_NS) {
                dropBudget -= FRAME_NS;
                drops++;
                return DROP;
            }
            dropBudget = 0;
        }
        average += (error - average) / 64;
        long size = Math.abs(average);
        if (fast && size <= SETTLE_NS) fast = false;
        if (!fast && size <= THRESHOLD_NS) {
            beyondSince = Long.MIN_VALUE;
            return PLAY;
        }
        if (beyondSince == Long.MIN_VALUE) beyondSince = now;
        long sustained = now - beyondSince;
        if (size > HARD_NS && sustained >= HARD_SUSTAIN_NS) {
            // Sized by the current error: the average lags behind it.
            realigns++;
            lastCorrection = now;
            beyondSince = Long.MIN_VALUE;
            if (average > 0) {   // late: drop frames until on time (this one and the next ones)
                if (error <= 0 || queuedNs <= QUEUE_NS + 2 * FRAME_NS) return PLAY;
                dropBudget = error - FRAME_NS;
                average = 0;
                drops++;
                return DROP;
            }
            if (error >= 0) return PLAY;
            silenceNs = Math.min(-error, MAX_SILENCE_NS);   // early: wait
            silenceTotalNs += silenceNs;
            average = 0;
            return PLAY;
        }
        if (size > FAST_NS && sustained >= FAST_SUSTAIN_NS) fast = true;
        if (!fast && sustained < SUSTAIN_NS) return PLAY;
        if (now - lastCorrection < (fast ? FAST_GAP_NS : GAP_NS)) return PLAY;
        lastCorrection = now;
        if (average > 0) {
            if (queuedNs <= QUEUE_NS + 2 * FRAME_NS) return PLAY;   // dropping would only starve the track
            average -= FRAME_NS;
            drops++;
            return DROP;
        }
        average += FRAME_NS;
        repeats++;
        return REPEAT;
    }

    public void clearCounts() {
        drops = repeats = realigns = silenceTotalNs = nonsense = errorSum = errorCount = errorMax = 0;
    }
}
