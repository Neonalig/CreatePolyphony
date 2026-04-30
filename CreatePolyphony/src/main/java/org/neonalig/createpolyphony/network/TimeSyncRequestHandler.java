package org.neonalig.createpolyphony.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server-side handler for {@link TimeSyncRequestPayload}. Echoes the client's
 * stamp back together with the current {@link System#nanoTime()} taken at
 * receive time, so the client can compute its server-to-local clock offset.
 *
 * <p>The work is done on the network thread for minimum receive-to-respond
 * latency: there is nothing here that needs the server tick (we're not
 * touching world state). Lower latency = tighter offset estimate.</p>
 */
public final class TimeSyncRequestHandler {

    private TimeSyncRequestHandler() {}

    public static void handle(TimeSyncRequestPayload payload, IPayloadContext context) {
        long serverNanosAtReceive = System.nanoTime();
        if (!(context.player() instanceof ServerPlayer serverPlayer)) return;
        PacketDistributor.sendToPlayer(serverPlayer,
            new TimeSyncResponsePayload(payload.clientNanos(), serverNanosAtReceive));
    }
}

