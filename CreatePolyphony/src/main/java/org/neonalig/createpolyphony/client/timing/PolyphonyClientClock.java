package org.neonalig.createpolyphony.client.timing;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.network.TimeSyncResponsePayload;

/**
 * Client-side server-to-local clock-offset estimator.
 *
 * <p>The server stamps each MIDI event with {@link System#nanoTime()} on its
 * own JVM. {@code nanoTime} is monotonic but its absolute origin is arbitrary
 * per-process, so the client cannot use server timestamps directly. Instead
 * we measure {@code offset = serverNanos - localNanos} via periodic
 * round-trips and apply the offset whenever we need to convert a server
 * timestamp to a local play-time.</p>
 *
 * <h2>Algorithm (Cristian's, with light filtering)</h2>
 * <ul>
 *   <li>Client sends {@code clientSendNanos}; server echoes it back along with
 *       its own {@code serverRecvNanos}.</li>
 *   <li>On reply: {@code rtt = clientRecvNanos - clientSendNanos}.</li>
 *   <li>Approximate one-way: {@code serverNanosAtClientRecv ≈ serverRecvNanos + rtt/2}.</li>
 *   <li>Sample offset: {@code offset = serverNanosAtClientRecv - clientRecvNanos}.</li>
 *   <li>Keep the offset from the lowest-RTT sample seen recently (best=most-accurate),
 *       and additionally apply a tight EWMA so transient single-spike network jitter
 *       doesn't bounce the schedule.</li>
 * </ul>
 *
 * <p>Until the first response arrives, the offset is {@code 0L}, which means
 * "treat server timestamps as local timestamps" - this gives correct relative
 * timing inside a single-player world (server JVM == client JVM, so nanoTime
 * is identical) and is only off by a small constant on dedicated servers
 * until the first reply lands.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PolyphonyClientClock {

    /** Best (lowest-RTT) offset seen so far. Read by the scheduler hot path. */
    private static volatile long offsetNanos = 0L;
    /** RTT of the sample that produced the current best offset. */
    private static volatile long bestRttNanos = Long.MAX_VALUE;
    /** True once at least one round-trip has succeeded. */
    private static volatile boolean primed = false;

    /** EWMA over recent good samples (alpha = 1/8) to soften single-spike outliers. */
    private static volatile long ewmaOffsetNanos = 0L;
    private static volatile boolean ewmaSeeded = false;

    private PolyphonyClientClock() {}

    /** Convert a server-side {@code nanoTime} stamp to the equivalent local-clock {@code nanoTime}. */
    public static long serverToLocal(long serverNanos) {
        return serverNanos - offsetNanos;
    }

    public static boolean isPrimed() {
        return primed;
    }

    public static long currentOffsetNanos() {
        return offsetNanos;
    }

    public static long currentBestRttNanos() {
        return bestRttNanos == Long.MAX_VALUE ? -1L : bestRttNanos;
    }

    /** Reset state on disconnect so a fresh server isn't biased by the previous one. */
    public static void reset() {
        offsetNanos = 0L;
        bestRttNanos = Long.MAX_VALUE;
        primed = false;
        ewmaOffsetNanos = 0L;
        ewmaSeeded = false;
    }

    /** Called from {@link TimeSyncResponsePayload}'s client handler. */
    public static synchronized void onResponse(TimeSyncResponsePayload payload) {
        long clientRecvNanos = System.nanoTime();
        long rtt = clientRecvNanos - payload.clientNanos();
        if (rtt < 0) return; // overflow / clock anomaly; ignore
        long serverAtClientRecv = payload.serverNanos() + (rtt >> 1);
        long sampleOffset = serverAtClientRecv - clientRecvNanos;

        if (!ewmaSeeded) {
            ewmaOffsetNanos = sampleOffset;
            ewmaSeeded = true;
        } else {
            // EWMA with alpha = 1/8.
            ewmaOffsetNanos += (sampleOffset - ewmaOffsetNanos) >> 3;
        }

        // Prefer the lowest-RTT sample we've seen, but let the EWMA catch slow drift
        // when no better sample arrives.
        if (rtt < bestRttNanos) {
            bestRttNanos = rtt;
            offsetNanos = sampleOffset;
        } else {
            offsetNanos = ewmaOffsetNanos;
        }
        primed = true;

        if (CreatePolyphony.LOGGER.isDebugEnabled()) {
            CreatePolyphony.LOGGER.debug("clock-sync rtt={}us offset={}us bestRtt={}us",
                rtt / 1_000L, offsetNanos / 1_000L, bestRttNanos / 1_000L);
        }
    }
}


