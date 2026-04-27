package org.neonalig.createpolyphony.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
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
 * <h2>Positional audio</h2>
 * <p>When {@code selfPlay} is {@code false} the note originates from a
 * non-player holder (mob / deployer / contraption). The client positions the
 * synthesiser stream at the world coordinates {@code (srcX, srcY, srcZ)} so
 * the sound is spatially rendered at the correct location with OpenAL
 * attenuation and panning. When {@code selfPlay} is {@code true} the player
 * is the holder themselves; the stream stays anchored to the listener for
 * an "in your hands" feel, and the src fields are ignored.</p>
 *
 * @param program   General-MIDI program number, 0-127.
 * @param channel   MIDI channel (0-15).
 * @param command   MIDI command nibble: 0x80 = NoteOff, 0x90 = NoteOn, 0xF0 = Panic.
 * @param note      MIDI note number, 0-127.
 * @param velocity  MIDI velocity, 0-127.
 * @param selfPlay  {@code true} when the receiving player is the holder themselves
 *                  (sound plays relative to listener); {@code false} for external sources.
 * @param srcX      World X of the sound source (only meaningful when {@code selfPlay=false}).
 * @param srcY      World Y of the sound source.
 * @param srcZ      World Z of the sound source.
 * @param maxDistanceBlocks  Server-computed audible radius in blocks (derived from simulation distance).
 */
public record PlayInstrumentNotePayload(
    int program,
    int channel,
    int command,
    int note,
    int velocity,
    boolean selfPlay,
    float srcX,
    float srcY,
    float srcZ,
    int maxDistanceBlocks
) implements CustomPacketPayload {

    public static final Type<PlayInstrumentNotePayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(CreatePolyphony.MODID, "play_instrument_note")
    );

    /** Manual StreamCodec: 5 MIDI bytes + 1 boolean + 3 floats + 1 int. */
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayInstrumentNotePayload> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> {
                buf.writeByte(p.program());
                buf.writeByte(p.channel());
                buf.writeByte(p.command());
                buf.writeByte(p.note());
                buf.writeByte(p.velocity());
                buf.writeBoolean(p.selfPlay());
                buf.writeFloat(p.srcX());
                buf.writeFloat(p.srcY());
                buf.writeFloat(p.srcZ());
                buf.writeVarInt(p.maxDistanceBlocks());
            },
            buf -> new PlayInstrumentNotePayload(
                Byte.toUnsignedInt(buf.readByte()) & 0x7F,
                Byte.toUnsignedInt(buf.readByte()) & 0x0F,
                Byte.toUnsignedInt(buf.readByte()) & 0xF0,
                Byte.toUnsignedInt(buf.readByte()) & 0x7F,
                Byte.toUnsignedInt(buf.readByte()) & 0x7F,
                buf.readBoolean(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readVarInt()
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
