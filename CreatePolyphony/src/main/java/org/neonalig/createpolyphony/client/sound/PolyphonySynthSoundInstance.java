package org.neonalig.createpolyphony.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.neonalig.createpolyphony.Config;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.synth.PolyphonySynthesizer;

import java.util.concurrent.CompletableFuture;

/**
 * The single, long-lived sound instance that streams synthesized PCM into
 * Minecraft's audio engine.
 *
 * <h2>Lifecycle model</h2>
 * <p>Unlike the previous "one sound instance per note" design, this class
 * is intended to be a <b>singleton per synthesizer session</b>. We pass
 * MIDI events directly to {@link PolyphonySynthesizer}; the sound
 * instance itself stays alive for as long as the local player wants to
 * hear synth output, regardless of how many notes are currently held.
 * This is the same model FL Studio / DAWs use for a software synth
 * channel: the audio path is permanent, only the MIDI feed changes.</p>
 *
 * <h2>How MC's streaming bridge is satisfied</h2>
 * <p>MC's {@code SoundEngine.play()} expects three things to align:</p>
 * <ol>
 *   <li>{@link #resolve(SoundManager)} returns a non-null
 *       {@link WeighedSoundEvents} (otherwise we're skipped with a
 *       "Unable to play unknown soundEvent" warning).</li>
 *   <li>{@link #getSound()} returns a {@link Sound} whose
 *       {@link Sound#shouldStream()} is {@code true} (otherwise MC tries
 *       to load us as a static buffer via {@code SoundBufferLibrary
 *       .getCompleteBuffer}, which would 404).</li>
 *   <li>{@link #getStream(SoundBufferLibrary, Sound, boolean)} returns a
 *       future that resolves to our custom {@link PolyphonyAudioStream}.</li>
 * </ol>
 * <p>We satisfy (1) and (2) by manufacturing a {@code WeighedSoundEvents}
 * and {@code Sound} ourselves - no resource pack or registered
 * {@code SoundEvent} required. (3) just hands back our PCM bridge.</p>
 *
 * <h2>Spatial behaviour</h2>
 * <p>The stream is world-positioned ({@code relative=false}, {@code attenuation=LINEAR}).
 * When the local player is the holder ({@code selfPlay=true}) the source tracks the
 * player's position (distance ≈ 0 → full volume, centre pan — "in ears" feel).
 * When the source is an external holder (mob, deployer — {@code selfPlay=false}) it
 * stays at the holder's last reported world position so OpenAL applies correct
 * distance falloff and stereo panning relative to the listener.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PolyphonySynthSoundInstance extends AbstractSoundInstance implements TickableSoundInstance {

    /** A synthetic resource location for our streaming sound. Never registered or looked up. */
    public static final ResourceLocation SYNTH_LOCATION =
        ResourceLocation.fromNamespaceAndPath(CreatePolyphony.MODID, "synth_stream");

    private final PolyphonySynthesizer synth;
    private final Sound syntheticSound;
    private final WeighedSoundEvents syntheticEvents;
    private final int maxDistanceBlocks;
    private boolean stopped = false;

    /**
     * When {@code true} (default) the stream is anchored to the listener's ears
     * (player is the holder). When {@code false} the stream is positioned at
     * {@code (srcX, srcY, srcZ)} in world space for external-source spatial audio.
     */
    private volatile boolean selfPlay = true;
    private volatile float srcX, srcY, srcZ;

    public PolyphonySynthSoundInstance(PolyphonySynthesizer synth, int maxDistanceBlocks) {
        super(SYNTH_LOCATION, Config.synthSoundSource(), RandomSource.create());
        this.synth = synth;
        this.maxDistanceBlocks = Math.max(16, maxDistanceBlocks);
        // attenuationDistance is expressed in 16-block sound units.
        int attenuationField = Math.max(1, this.maxDistanceBlocks / 16);
        this.syntheticSound = new Sound(
            SYNTH_LOCATION,
            ConstantFloat.of(1.0F),
            ConstantFloat.of(1.0F),
            1,                    // weight
            Sound.Type.FILE,
            true,                 // stream
            false,                // preload
            attenuationField      // attenuation distance (in SoundManager units)
        );
        this.sound = syntheticSound;
        this.syntheticEvents = new WeighedSoundEvents(SYNTH_LOCATION, null);

        this.volume = 1.0F;
        this.pitch = 1.0F;
        this.looping = true;
        this.delay = 0;
        // relative=false: world-space positioning so OpenAL computes 3D panning.
        // For self-play the source tracks the player (distance ≈ 0 → full volume,
        // centre pan). For external sources the source stays at the holder position.
        this.relative = false;
        this.attenuation = Attenuation.LINEAR;
    }

    public int maxDistanceBlocks() {
        return maxDistanceBlocks;
    }

    /**
     * Override the standard sound-events lookup so MC doesn't need a
     * registered {@code SoundEvent} for our synthetic location.
     */
    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        this.sound = syntheticSound;
        return syntheticEvents;
    }

    /**
     * The Neo-added stream override: hand MC our custom PCM bridge instead
     * of letting it try to read a non-existent .ogg.
     */
    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(new PolyphonyAudioStream(synth));
    }

    @Override
    public boolean isStopped() {
        return stopped;
    }

    /** Mark this instance as finished; MC will drop it next tick. */
    public void stopInstance() {
        this.stopped = true;
        this.looping = false;
    }

    /**
     * Update the world-space position of the sound source.
     *
     * @param x         world X of the holder / source.
     * @param y         world Y.
     * @param z         world Z.
     * @param selfPlay  {@code true} = the local player is the holder (source tracks
     *                  the listener, effectively "in ears"); {@code false} = external
     *                  source at the supplied world position.
     */
    public void setSourcePosition(float x, float y, float z, boolean selfPlay) {
        this.srcX = x;
        this.srcY = y;
        this.srcZ = z;
        this.selfPlay = selfPlay;
    }

    @Override
    public void tick() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            stopped = true;
            return;
        }

        if (selfPlay) {
            // Player is the holder: anchor source to the listener so the sound
            // plays at full volume with centre panning (equivalent to "in ears").
            this.x = p.getX();
            this.y = p.getY();
            this.z = p.getZ();
        } else {
            // External source: keep at the last-reported holder position so
            // OpenAL computes correct distance attenuation and stereo panning.
            this.x = srcX;
            this.y = srcY;
            this.z = srcZ;
        }

        // Defensive: retire if the synth was closed (e.g. soundfont swap).
        if (synth.isClosed()) {
            stopped = true;
        }
    }

    @Override
    public boolean canStartSilent() {
        // The synth may be silent for long stretches (no notes held). MC's
        // default behaviour drops zero-volume sounds before play(); overriding
        // this prevents that and keeps our stream warm for the next NoteOn.
        return true;
    }
}
