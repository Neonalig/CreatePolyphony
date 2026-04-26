package org.neonalig.createpolyphony.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.neonalig.createpolyphony.instrument.InstrumentItem;

/**
 * A single active musical note, played at the local player's position.
 *
 * <p>Implemented as a {@link AbstractTickableSoundInstance} so the client
 * sound system keeps it ticking until either:</p>
 * <ul>
 *   <li>It naturally ends (the {@code .ogg} sample finishes), or</li>
 *   <li>{@link #stopNote()} is called by the note-off handler, or</li>
 *   <li>The local player vanishes / dies (defensive cleanup).</li>
 * </ul>
 *
 * <h2>Pitch &amp; octave-anchor selection</h2>
 * <p>Resource packs ship up to three sample anchors per program (C2, C4, C6).
 * Before constructing this instance, the handler picks the closest anchor for
 * the played note via
 * {@link org.neonalig.createpolyphony.registry.CPSounds.OctaveSample#nearest(int)}.
 * That anchor's {@link org.neonalig.createpolyphony.registry.CPSounds.OctaveSample#midiBaseNote midiBaseNote}
 * becomes the reference for the pitch multiplier here:</p>
 * <pre>{@code pitch = 2 ^ ((midiNote - anchorBaseNote) / 12)}</pre>
 * <p>Because we always pick the closest anchor, this delta is at most one
 * octave (factor 0.5..2.0), keeping pitch-shift artefacts bounded.</p>
 *
 * <h2>Volume</h2>
 * <p>MIDI velocity (0-127) maps linearly into volume {@code 0.15..1.0}. The
 * floor avoids inaudible {@code velocity=1} stragglers that sometimes appear
 * in poorly-quantised MIDI files.</p>
 */
public class PolyphonyNoteSoundInstance extends AbstractTickableSoundInstance {

    /** Set to true by {@link #stopNote()} so the client sound engine drops us next tick. */
    private boolean stopped = false;

    /**
     * @param event           the per-(program,octave) {@link SoundEvent} to play.
     * @param midiNote        the MIDI note number being played (0-127).
     * @param anchorBaseNote  the MIDI base note of the chosen octave anchor
     *                        (e.g. 60 for the C4 anchor). The pitch multiplier
     *                        is computed relative to this so the right octave's
     *                        sample plays at the right detune.
     * @param midiVelocity    MIDI velocity (0-127); drives volume.
     * @param follow          the player whose position the sound rides on.
     */
    public PolyphonyNoteSoundInstance(SoundEvent event,
                                      int midiNote,
                                      int anchorBaseNote,
                                      int midiVelocity,
                                      Player follow) {
        super(event, SoundSource.PLAYERS, Minecraft.getInstance().level == null
            ? net.minecraft.util.RandomSource.create()
            : Minecraft.getInstance().level.getRandom());

        this.x = follow.getX();
        this.y = follow.getY();
        this.z = follow.getZ();
        this.looping = false;
        this.delay = 0;
        this.attenuation = Attenuation.LINEAR;
        // Velocity 0..127 -> volume 0.15..1.0 (avoid total silence on velocity 1).
        this.volume = 0.15f + 0.85f * Math.min(127, Math.max(1, midiVelocity)) / 127f;
        this.pitch = (float) Math.pow(2.0, (midiNote - anchorBaseNote) / 12.0);
    }

    /** Mark this note as finished; the sound engine will drop it next tick. */
    public void stopNote() {
        this.stopped = true;
    }

    @Override
    public void tick() {
        // Track the local player so the note "rides" on them - prevents the
        // sound from getting culled by attenuation if they walk away from
        // where they triggered it.
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            stopped = true;
        } else {
            this.x = p.getX();
            this.y = p.getY();
            this.z = p.getZ();

            // Stop playing if the player is no longer holding an instrument in main hand or off-hand
            ItemStack mainHand = p.getMainHandItem();
            ItemStack offHand = p.getOffhandItem();
            if (!(mainHand.getItem() instanceof InstrumentItem) &&
                !(offHand.getItem() instanceof InstrumentItem)) {
                stopped = true;
            }
        }

        if (stopped) {
            this.stop();
        }
    }
}
