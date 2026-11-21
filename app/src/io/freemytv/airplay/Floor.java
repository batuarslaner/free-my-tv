package io.freemytv.airplay;

/**
 * The lowest value seen over the last 5–10 s (two 5 s buckets), for "arrival minus timestamp" lags: the floor is a
 * stream's best-case delay, whatever its timestamps mean, and slow clock drift ages out. Not thread-safe; times in ns.
 */
public final class Floor {
    private static final long BUCKET_NS = 5_000_000_000L;
    private long bucketStart = Long.MIN_VALUE, minNow = Long.MAX_VALUE, minBefore = Long.MAX_VALUE;

    /** Adds a value seen at time now; returns the floor. */
    public long add(long value, long now) {
        if (bucketStart == Long.MIN_VALUE || now - bucketStart >= BUCKET_NS) {
            minBefore = minNow;
            minNow = Long.MAX_VALUE;
            bucketStart = now;
        }
        if (value < minNow) minNow = value;
        return get();
    }

    public long get() {
        return Math.min(minNow, minBefore);
    }

    public boolean known() {
        return get() != Long.MAX_VALUE;
    }
}
