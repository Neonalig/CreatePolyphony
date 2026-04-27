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
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
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
 * <p>The instance is anchored to the local player and ticked to follow
 * them. Attenuation is set to {@code NONE} because synth output is
 * conceptually "in the player's ears" - they're playing it themselves.
 * If we ever want spatial behaviour (e.g. another player's instrument
 * audible at distance), we'd swap to {@code LINEAR} and feed an
 * {@link net.minecraft.world.entity.Entity} position instead.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PolyphonySynthSoundInstance extends AbstractSoundInstance implements TickableSoundInstance {

    /** A synthetic resource location for our streaming sound. Never registered or looked up. */
    public static final ResourceLocation SYNTH_LOCATION =
        ResourceLocation.fromNamespaceAndPath(CreatePolyphony.MODID, "synth_stream");

    private final PolyphonySynthesizer synth;
    private final Sound syntheticSound;
    private final WeighedSoundEvents syntheticEvents;
    private boolean stopped = false;

    public PolyphonySynthSoundInstance(PolyphonySynthesizer synth) {
        super(SYNTH_LOCATION, SoundSource.RECORDS, RandomSource.create());
        this.synth = synth;
        // Build a Sound with stream=true so SoundEngine routes through our getStream() override
        // rather than the static-buffer path. The path/location values don't matter because
        // SoundBufferLibrary is never asked to resolve them.
        this.syntheticSound = new Sound(
            SYNTH_LOCATION,
            ConstantFloat.of(1.0F),
            ConstantFloat.of(1.0F),
            1,                    // weight
            Sound.Type.FILE,
            true,                 // stream
            false,                // preload
            16                    // attenuation distance (unused with attenuation=NONE)
        );
        this.sound = syntheticSound;
        this.syntheticEvents = new WeighedSoundEvents(SYNTH_LOCATION, null);

        // Default placement; tick() repositions to the player every frame.
        this.volume = 1.0F;
        this.pitch = 1.0F;
        this.looping = true;        // streaming sounds shouldn't auto-finish; the synth runs forever
        this.delay = 0;
        this.relative = true;       // attached-to-listener, not world space
        this.attenuation = Attenuation.NONE;
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

    @Override
    public void tick() {
        // Follow the local player so the sound never falls outside attenuation
        // range. We've set attenuation=NONE anyway, but this also keeps things
        // sensible if the user mods that to LINEAR for distant playback.
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            stopped = true;
            return;
        }
        this.x = p.getX();
        this.y = p.getY();
        this.z = p.getZ();

        // Defensive: if the synth was closed out from under us (e.g. soundfont
        // swap that rebuilt the synth), we should also retire so a fresh
        // instance can take over.
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
