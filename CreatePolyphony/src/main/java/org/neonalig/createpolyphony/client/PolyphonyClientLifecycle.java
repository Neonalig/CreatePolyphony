package org.neonalig.createpolyphony.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.client.sound.SoundFontManager;

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
 */
@SuppressWarnings("removal") // EventBusSubscriber.Bus deprecation: see CPNetwork for context.
@EventBusSubscriber(modid = CreatePolyphony.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class PolyphonyClientLifecycle {

    private PolyphonyClientLifecycle() {}

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PolyphonyClientNoteHandler.stopAll();
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // Defensive - in case a connection failed mid-NoteOn before LoggingOut fired.
        PolyphonyClientNoteHandler.stopAll();
        // Ensure persisted soundfont selection is restored before first incoming note packet.
        SoundFontManager.get();
    }
}
