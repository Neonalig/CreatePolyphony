package org.neonalig.createpolyphony.network;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.client.PolyphonyClientNoteHandler;

/**
 * Registers our custom packet payloads with NeoForge's network registrar.
 *
 * <p>We currently have a single payload, {@link PlayInstrumentNotePayload},
 * sent server-to-client whenever a tracker bar emits a note that should be
 * played on the recipient's held instrument.</p>
 */
@SuppressWarnings("removal") // EventBusSubscriber.Bus is deprecated but still functional in 1.21.1; matches the
                              // pattern used by the NeoForge MDK template's Config.java in this project.
@EventBusSubscriber(modid = CreatePolyphony.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class CPNetwork {

    /** Bumped whenever the wire format changes. Strict matching keeps us safe. */
    public static final String VERSION = "1";

    private CPNetwork() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(CreatePolyphony.MODID).versioned(VERSION);

        // Server -> Client: play a note on the player's held instrument.
        registrar.playToClient(
            PlayInstrumentNotePayload.TYPE,
            PlayInstrumentNotePayload.STREAM_CODEC,
            PolyphonyClientNoteHandler::handle
        );
    }
}
