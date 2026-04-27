package org.neonalig.createpolyphony.synth;

import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.synth.meltysynth.MeltySoundFont;
import org.neonalig.createpolyphony.synth.meltysynth.MeltySynthEngine;

import java.io.File;
import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * The mod's real-time SoundFont (.sf2) synthesizer adapter.
 *
 * <p>This class is now a thin facade over the in-tree Java melty engine in
 * {@code org.neonalig.createpolyphony.synth.meltysynth}. No
 * {@code javax.sound.midi} dependency remains in the render path.</p>
 *
 * <p>The audio thread pulls raw PCM by calling {@link #renderPcm(byte[], int)}.
 * MIDI events can be submitted from any thread and are applied on the audio
 * thread at block boundaries.</p>
 */
public final class PolyphonySynthesizer {

    private final SynthSettings settings;
    private final MeltySynthEngine engine;

    private volatile boolean closed = false;

    public PolyphonySynthesizer(SynthSettings settings) {
        this.settings = settings;
        this.engine = new MeltySynthEngine(settings);

        CreatePolyphony.LOGGER.info(
            "PolyphonySynthesizer started (melty backend): {} Hz, {} ch, {}-bit, {} voice cap",
            settings.sampleRate(), settings.channels(), settings.sampleSizeInBits(),
            settings.maxVoices());
    }

    public SynthSettings settings() { return settings; }

    public boolean isClosed() { return closed; }

    /**
     * Replace any currently-loaded instruments with those defined in the
     * given .sf2 file. Triggers a panic stop so no half-played voice
     * survives the swap.
     *
     * @param sf2File the soundfont file on disk; must be readable.
     * @throws IOException if the file cannot be read or isn't a valid sf2.
     */
    public void loadSoundFont(File sf2File) throws IOException {
        MeltySoundFont bank = MeltySoundFont.load(sf2File);
        engine.loadSoundFont(bank);
        CreatePolyphony.LOGGER.info(
            "Loaded soundfont {} ({} presets, {} instruments, {} samples)",
            sf2File.getName(), bank.presetCount(), bank.instrumentCount(), bank.sampleCount());
    }

    public void loadSoundFont(File sf2File, IntConsumer progressCallback) throws IOException {
        MeltySoundFont bank = MeltySoundFont.load(sf2File, progressCallback);
        engine.loadSoundFont(bank);
        CreatePolyphony.LOGGER.info(
            "Loaded soundfont {} ({} presets, {} instruments, {} samples)",
            sf2File.getName(), bank.presetCount(), bank.instrumentCount(), bank.sampleCount());
    }

    /**
     * Forget all loaded patches; the synth becomes silent until another
     * {@link #loadSoundFont(File)} call. Used when the user picks
     * "None / No Sound" in the GUI.
     */
    public void unloadSoundFont() {
        engine.unloadSoundFont();
    }

    // ---- MIDI event entry points (thread-safe, callable from network thread) ------------------

    public void programChange(int channel, int program) {
        if (closed) {
            return;
        }
        engine.programChange(channel, program);
    }

    public void noteOn(int channel, int note, int velocity) {
        if (closed) {
            return;
        }
        engine.noteOn(channel, note, velocity);
    }

    public void noteOff(int channel, int note) {
        if (closed) {
            return;
        }
        engine.noteOff(channel, note);
    }

    public void pitchBend(int channel, int value) {
        if (closed) {
            return;
        }
        engine.pitchBend(channel, value);
    }

    public void controlChange(int channel, int controller, int value) {
        if (closed) {
            return;
        }
        engine.controlChange(channel, controller, value);
    }

    public void allNotesOff() {
        if (closed) {
            return;
        }
        engine.allNotesOff();
    }

    public int renderPcm(byte[] out, int requestedBytes) {
        if (closed) {
            return 0;
        }
        return engine.renderS16Interleaved(out, 0, requestedBytes);
    }

    public void close() {
        if (closed) return;
        closed = true;
        try { engine.close(); } catch (Throwable ignored) { }
        CreatePolyphony.LOGGER.info("PolyphonySynthesizer closed");
    }
}
