package org.neonalig.createpolyphony.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.neonalig.createpolyphony.CreatePolyphony;

/**
 * Server-to-client payload telling the client to play (or stop) a single
 * MIDI note using a specific General-MIDI program.
 *
 * <p>Only sent to a player when they are linked to a tracker bar that is
 * actively playing, and only for channels assigned to them by
 * {@link org.neonalig.createpolyphony.link.PolyphonyLink}.</p>
 *
 * <h2>Why the server still sends a GM program</h2>
 * <p>The client synth API is keyed on GM program number. The server now derives
 * that program from the assigned player's held
 * {@link org.neonalig.createpolyphony.instrument.InstrumentFamily} so playback
 * timbre follows the linked instrument rather than the tracker's original
 * channel program. Channel 10 (index 9) remains reserved for drums.</p>
 *
 * @param program  General-MIDI program number, 0-127. Maps to sound id
 *                 {@code instruments.<NNN>.<octave>} (NNN is 1-indexed,
 *                 zero-padded to 3 digits).
 * @param channel  the MIDI channel (0-15) the note belongs to. The client uses
 *                 this together with the note number to maintain its own
 *                 active-note table for note-off matching / cleanup.
 * @param command  MIDI command nibble: 0x80 = NoteOff, 0x90 = NoteOn.
 * @param note     MIDI note number, 0-127.
 * @param velocity MIDI velocity, 0-127. Note: a NoteOn with velocity 0 is
 *                 by convention equivalent to NoteOff.
 */
public record PlayInstrumentNotePayload(
    int program,
    int channel,
    int command,
    int note,
    int velocity
) implements CustomPacketPayload {

    public static final Type<PlayInstrumentNotePayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(CreatePolyphony.MODID, "play_instrument_note")
    );

    /**
     * Compact StreamCodec: 5 bytes on the wire (program/channel/command/note/velocity).
     * Channel is masked to 4 bits, command to its high nibble, note/velocity/program
     * to 7 bits each (MIDI's natural range).
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayInstrumentNotePayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.BYTE, p -> (byte) p.program,
            ByteBufCodecs.BYTE, p -> (byte) p.channel,
            ByteBufCodecs.BYTE, p -> (byte) p.command,
            ByteBufCodecs.BYTE, p -> (byte) p.note,
            ByteBufCodecs.BYTE, p -> (byte) p.velocity,
            (prog, ch, cmd, n, v) -> new PlayInstrumentNotePayload(
                Byte.toUnsignedInt(prog) & 0x7F,
                Byte.toUnsignedInt(ch)   & 0x0F,
                Byte.toUnsignedInt(cmd)  & 0xF0,
                Byte.toUnsignedInt(n)    & 0x7F,
                Byte.toUnsignedInt(v)    & 0x7F
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
