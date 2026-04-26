package org.neonalig.createpolyphony.client;

import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.neonalig.createpolyphony.network.PlayInstrumentNotePayload;

/**
 * Client-side handler for {@link PlayInstrumentNotePayload}.
 *
 * <p>Final implementation lives in todo 7 of the build. For now this is a
 * shaped stub so that {@link org.neonalig.createpolyphony.network.CPNetwork}
 * can register its callback reference at compile time.</p>
 */
public final class PolyphonyClientNoteHandler {

    private PolyphonyClientNoteHandler() {}

    /**
     * Handle an incoming note packet. Called on the network thread; the
     * implementation must hop to the client main thread (via
     * {@link IPayloadContext#enqueueWork}) before touching any Minecraft state.
     */
    public static void handle(PlayInstrumentNotePayload payload, IPayloadContext context) {
        // TODO[todo-7]: enqueue on client thread and play / stop a sound based on
        //               payload.family + payload.note + payload.isNoteOn / isNoteOff.
    }
}
