package org.neonalig.createpolyphony.client;

import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.neonalig.createpolyphony.client.timing.PolyphonyClientClock;
import org.neonalig.createpolyphony.network.PlayInstrumentNotePayload;
import org.neonalig.createpolyphony.network.TimeSyncResponsePayload;

/**
 * Client-only payload registrations. Lives in its own class so that the
 * dedicated server never resolves the method handle to
 * {@link PolyphonyClientNoteHandler#handle}, which transitively references
 * client-only classes such as {@code SoundInstance}.
 */
public final class CPClientPayloadRegistrar {

    private CPClientPayloadRegistrar() {}

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(
            PlayInstrumentNotePayload.TYPE,
            PlayInstrumentNotePayload.STREAM_CODEC,
            PolyphonyClientNoteHandler::handle
        );
        registrar.playToClient(
            TimeSyncResponsePayload.TYPE,
            TimeSyncResponsePayload.STREAM_CODEC,
            // Handled directly on the network thread - the offset update is just
            // a couple of memory writes, and skipping enqueueWork keeps the
            // measured RTT closer to the actual wire latency.
            (payload, ctx) -> PolyphonyClientClock.onResponse(payload)
        );
    }
}
