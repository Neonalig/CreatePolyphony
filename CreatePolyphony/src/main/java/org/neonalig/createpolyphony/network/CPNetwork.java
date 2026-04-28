package org.neonalig.createpolyphony.network;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.neonalig.createpolyphony.CreatePolyphony;

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
        // The handler is registered only on the physical client, since it
        // transitively references client-only classes (SoundInstance, etc.).
        // The type itself is still declared for both sides via playToClient
        // on the client; the dedicated server only needs to know about
        // outgoing types it sends, which it does through the codec usage in
        // CreatePolyphony's send paths (PacketDistributor).
        if (FMLEnvironment.dist == Dist.CLIENT) {
            org.neonalig.createpolyphony.client.CPClientPayloadRegistrar.register(registrar);
        } else {
            // On the dedicated server, register a no-op handler so the type
            // is known to the network protocol. The handler will never be
            // invoked server-side because playToClient packets are only
            // received on the client.
            registrar.playToClient(
                PlayInstrumentNotePayload.TYPE,
                PlayInstrumentNotePayload.STREAM_CODEC,
                (payload, context) -> {}
            );
        }
    }
}
