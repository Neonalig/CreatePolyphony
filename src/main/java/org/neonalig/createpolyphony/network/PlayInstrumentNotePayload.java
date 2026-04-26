package org.neonalig.createpolyphony.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.instrument.InstrumentFamily;

/**
 * Server-to-client payload telling the client to play (or stop) a single
 * MIDI note on the recipient's held instrument.
 *
 * <p>Only sent to a player when they are linked to a tracker bar that is
 * actively playing, and only for channels assigned to them by
 * {@link org.neonalig.createpolyphony.link.PolyphonyLink}.</p>
 *
 * @param family   the instrument family that should produce the sound. Sent
 *                 (rather than reading the player's held item on the client)
 *                 so a bow-swap mid-song doesn't desync.
 * @param channel  the MIDI channel (0-15) the note belongs to. The client uses
 *                 this together with {@code command} to maintain its own
 *                 active-note table for note-off matching / cleanup.
 * @param command  MIDI command nibble: 0x80 = NoteOff, 0x90 = NoteOn.
 * @param note     MIDI note number, 0-127.
 * @param velocity MIDI velocity, 0-127. Note: a NoteOn with velocity 0 is
 *                 by convention equivalent to NoteOff.
 */
public record PlayInstrumentNotePayload(
    InstrumentFamily family,
    int channel,
    int command,
    int note,
    int velocity
) implements CustomPacketPayload {

    public static final Type<PlayInstrumentNotePayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(CreatePolyphony.MODID, "play_instrument_note")
    );

    /**
     * StreamCodec for the payload. We pack the family as a byte (ordinal),
     * channel as a byte (0-15), command as a byte (we only ever send 0x80 / 0x90),
     * and note/velocity as bytes (0-127).
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayInstrumentNotePayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.BYTE, p -> (byte) p.family.ordinal(),
            ByteBufCodecs.BYTE, p -> (byte) p.channel,
            ByteBufCodecs.BYTE, p -> (byte) p.command,
            ByteBufCodecs.BYTE, p -> (byte) p.note,
            ByteBufCodecs.BYTE, p -> (byte) p.velocity,
            (familyOrd, ch, cmd, n, v) -> new PlayInstrumentNotePayload(
                InstrumentFamily.values()[Byte.toUnsignedInt(familyOrd) % InstrumentFamily.values().length],
                Byte.toUnsignedInt(ch) & 0x0F,
                Byte.toUnsignedInt(cmd) & 0xF0,
                Byte.toUnsignedInt(n) & 0x7F,
                Byte.toUnsignedInt(v) & 0x7F
            )
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** True if this is a note-on with non-zero velocity (i.e. should actually start a note). */
    public boolean isNoteOn() {
        return command == 0x90 && velocity > 0;
    }

    /** True if this is a note-off (or note-on with velocity 0, by GM convention). */
    public boolean isNoteOff() {
        return command == 0x80 || (command == 0x90 && velocity == 0);
    }
}
