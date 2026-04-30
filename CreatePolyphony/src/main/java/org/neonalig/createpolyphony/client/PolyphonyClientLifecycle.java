package org.neonalig.createpolyphony.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.client.sound.SoundFontManager;
import org.neonalig.createpolyphony.client.timing.PolyphonyClientClock;
import org.neonalig.createpolyphony.client.timing.PolyphonyClockSyncDriver;
import org.neonalig.createpolyphony.client.timing.PolyphonyEventScheduler;

/**
 * Client-only lifecycle hooks that keep our active-note bookkeeping clean
 * across world / connection boundaries.
 *
 * <p>Without these, a stuck note from one world (e.g. you got teleported,
 * disconnected, or quit-to-title mid-NoteOn) could survive into the next
 * world via the active-notes table inside {@link PolyphonyClientNoteHandler}.
 * Stale program/channel state in that handler would prevent clean retriggering
 * until matching NoteOff packets arrived. {@link PolyphonyClientNoteHandler#stopAll()}
 * clears it.</p>
 *
 * <p>We also start/stop the timing infrastructure here: the look-ahead
 * scheduler thread and the clock-sync ping driver run only while connected
 * to a world, so single-player title-screen idle has zero overhead.</p>
 */
@SuppressWarnings("removal") // EventBusSubscriber.Bus deprecation: see CPNetwork for context.
@EventBusSubscriber(modid = CreatePolyphony.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class PolyphonyClientLifecycle {

    private PolyphonyClientLifecycle() {}

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PolyphonyClockSyncDriver.stop();
        PolyphonyEventScheduler.flushAll();
        PolyphonyClientNoteHandler.stopAll();
        PolyphonyEventScheduler.shutdown();
        PolyphonyClientClock.reset();
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // Defensive - in case a connection failed mid-NoteOn before LoggingOut fired.
        PolyphonyClientClock.reset();
        PolyphonyEventScheduler.flushAll();
        PolyphonyClientNoteHandler.stopAll();
        // Boot the timing pipeline before any note packet can arrive.
        PolyphonyEventScheduler.start();
        PolyphonyClockSyncDriver.start();
        // Ensure persisted soundfont selection is restored before first incoming note packet.
        SoundFontManager.get();
    }

    /**
     * Fired when the local player entity is recreated on the client (dimension travel, respawn).
     * Clears any in-flight notes so dimension transitions and deaths don't leave drones.
     */
    @SubscribeEvent
    public static void onPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        PolyphonyEventScheduler.flushAll();
        PolyphonyClientNoteHandler.stopAll();
    }
}
