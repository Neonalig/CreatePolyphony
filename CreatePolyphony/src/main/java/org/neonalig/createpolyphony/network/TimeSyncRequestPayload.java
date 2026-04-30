package org.neonalig.createpolyphony.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.neonalig.createpolyphony.CreatePolyphony;

/**
 * Client-to-server clock-sync probe. The client stamps {@code clientNanos} with
 * {@link System#nanoTime()} at send time. The server immediately echoes it back
 * inside a {@link TimeSyncResponsePayload}, alongside the server's own
 * {@code System.nanoTime()} taken at receive time. The client uses the round-trip
 * to estimate a steady server-to-local nanosecond offset that drives the
 * MIDI scheduler's look-ahead.
 *
 * <p>Cost: 8 bytes per request, sent every few seconds. Negligible.</p>
 */
public record TimeSyncRequestPayload(long clientNanos) implements CustomPacketPayload {

    public static final Type<TimeSyncRequestPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(CreatePolyphony.MODID, "time_sync_request")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, TimeSyncRequestPayload> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> buf.writeLong(p.clientNanos()),
            buf -> new TimeSyncRequestPayload(buf.readLong())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

