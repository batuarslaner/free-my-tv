package io.freemytv.airplay;

/**
 * Keeps the mirror stream near its lowest latency. For each frame read from the network it compares the sender's
 * capture time with our arrival time: their difference has a floor (the stream's best-case delay; the clocks' offset
 * cancels out), and how far a frame lies above that floor is how late it is. Late frames that nothing refers to
 * (nal_ref_idc 0) are skipped. Behind by seconds (a Wi-Fi stall), frames are still decoded: the decoder outruns
 * real time and the player shows only the newest, so the picture catches up within a moment. Only past 8 s,
 * hopelessly behind, is everything up to the next IDR dropped (AirPlay has no way to ask for one). IDRs and
 * config are never dropped. The floor is the minimum of the last 10 s, so slow clock drift doesn't build up.
 */
public final class Pacing {
    public static final int KEEP = 0, SKIP = 1, WAIT_IDR = 2;
    static final long LATE_NS = 150_000_000L, HOPELESS_NS = 8_000_000_000L, BUCKET_NS = 5_000_000_000L;

    private long bucketStart = Long.MIN_VALUE, minNow = Long.MAX_VALUE, minBefore = Long.MAX_VALUE;
    private boolean waitingForIdr;
    /** The last frame's lateness (ns above the floor). */
    public long lateness;
    /** The floor: the lowest arrival-minus-timestamp lag of the last 5–10 s (ns; read by other threads). */
    public volatile long floor = Long.MIN_VALUE;

    /** What to do with a frame: KEEP, SKIP (a late frame nothing refers to) or WAIT_IDR (dropped until an IDR). */
    public int decide(long senderNs, long arrivalNs, boolean idr, boolean reference) {
        long lag = arrivalNs - senderNs;
        if (bucketStart == Long.MIN_VALUE || arrivalNs - bucketStart >= BUCKET_NS) {
            minBefore = minNow;
            minNow = Long.MAX_VALUE;
            bucketStart = arrivalNs;
        }
        if (lag < minNow) minNow = lag;
        floor = Math.min(minNow, minBefore);
        lateness = lag - floor;
        if (idr) {
            waitingForIdr = false;
            return KEEP;
        }
        if (waitingForIdr) return WAIT_IDR;
        if (lateness > HOPELESS_NS) {
            waitingForIdr = true;
            return WAIT_IDR;
        }
        return lateness > LATE_NS && !reference ? SKIP : KEEP;
    }
}
