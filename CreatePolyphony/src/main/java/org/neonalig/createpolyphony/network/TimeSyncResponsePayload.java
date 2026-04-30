package org.neonalig.createpolyphony.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.neonalig.createpolyphony.CreatePolyphony;

/**
 * Server-to-client response to {@link TimeSyncRequestPayload}.
 *
 * <p>{@code clientNanos} is echoed unchanged so the client can compute the
 * round-trip time without keeping per-request state. {@code serverNanos} is
 * the server's {@link System#nanoTime()} at the moment it received the
 * request. Approximate one-way latency is {@code rtt / 2}; the server clock
 * at the client's receive time is {@code serverNanos + rtt / 2}, and the
 * server-to-client offset is {@code (serverNanos + rtt/2) - clientRecvNanos}.</p>
 */
public record TimeSyncResponsePayload(long clientNanos, long serverNanos) implements CustomPacketPayload {

    public static final Type<TimeSyncResponsePayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(CreatePolyphony.MODID, "time_sync_response")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, TimeSyncResponsePayload> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> {
                buf.writeLong(p.clientNanos());
                buf.writeLong(p.serverNanos());
            },
            buf -> new TimeSyncResponsePayload(buf.readLong(), buf.readLong())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

