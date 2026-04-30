package org.neonalig.createpolyphony.client.timing;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.neonalig.createpolyphony.Config;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.network.TimeSyncRequestPayload;

import java.util.concurrent.locks.LockSupport;

/**
 * Periodic clock-sync ping driver. Sends a {@link TimeSyncRequestPayload} on
 * connect and at a configurable interval thereafter so the server-to-local
 * offset stored in {@link PolyphonyClientClock} stays accurate while a
 * connection is alive.
 *
 * <p>An initial burst of 5 closely-spaced pings primes the offset within the
 * first ~250&nbsp;ms after login, so MIDI playback that starts immediately
 * (e.g. on a tracker that was already running) still gets correct timing.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PolyphonyClockSyncDriver {

    private static volatile Thread thread;
    private static volatile boolean running;

    private PolyphonyClockSyncDriver() {}

    public static synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(PolyphonyClockSyncDriver::run, "CreatePolyphony-ClockSync");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    public static synchronized void stop() {
        running = false;
        Thread t = thread;
        if (t != null) t.interrupt();
        thread = null;
    }

    private static void run() {
        // Initial burst: 5 quick pings to converge the offset fast.
        for (int i = 0; i < 5 && running; i++) {
            sendPing();
            sleepMs(50);
        }
        long intervalMs = Math.max(500L, Config.clockSyncIntervalMs());
        while (running) {
            sendPing();
            sleepMs(intervalMs);
            // Re-read interval each loop so config changes take effect without restart.
            intervalMs = Math.max(500L, Config.clockSyncIntervalMs());
        }
    }

    private static void sendPing() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() == null || !mc.getConnection().getConnection().isConnected()) return;
            PacketDistributor.sendToServer(new TimeSyncRequestPayload(System.nanoTime()));
        } catch (Throwable t) {
            CreatePolyphony.LOGGER.debug("clock-sync ping failed", t);
        }
    }

    private static void sleepMs(long ms) {
        if (!running) return;
        long deadline = System.nanoTime() + ms * 1_000_000L;
        while (running) {
            long remain = deadline - System.nanoTime();
            if (remain <= 0L) break;
            LockSupport.parkNanos(remain);
        }
    }
}

