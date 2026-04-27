package org.neonalig.createpolyphony.synth;

import org.neonalig.createpolyphony.CreatePolyphony;

import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Patch;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The mod's real-time SoundFont (.sf2) synthesizer.
 *
 * <h2>Engine</h2>
 * <p>We use the JDK's built-in <b>Gervill</b> synth (its public name is
 * {@code com.sun.media.sound.SoftSynthesizer}). Gervill is shipped with
 * every desktop JRE so we incur zero new runtime dependencies, and it
 * supports the SoundFont 2 (.sf2) format directly via
 * {@link MidiSystem#getSoundbank(File)} +
 * {@link Synthesizer#loadAllInstruments(Soundbank)}.</p>
 *
 * <h2>Headless audio routing</h2>
 * <p>Critically, we never call {@link Synthesizer#open()} - that variant
 * grabs the OS default audio device, which is exactly what we don't
 * want. Instead we reflectively invoke
 * {@code SoftSynthesizer.openStream(AudioFormat, Map)} which returns an
 * {@link AudioInputStream} of raw PCM and never touches the OS mixer.
 * Reflection keeps us off the {@code com.sun.media.sound} package
 * dependency, which is internal and not all build setups expose it.</p>
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li><b>MIDI events</b> ({@link #noteOn}, {@link #noteOff},
 *       {@link #programChange}, {@link #pitchBend}) are forwarded directly
 *       to Gervill's {@code MidiChannel}s. Those calls are documented as
 *       thread-safe and complete in microseconds; we accept them from any
 *       thread.</li>
 *   <li><b>PCM pump</b> runs on a single daemon thread that reads chunks
 *       from the {@link AudioInputStream} and pushes them into a
 *       {@link PcmRingBuffer}. Back-pressure from the consumer (audio
 *       thread) is what governs how fast we synthesize.</li>
 *   <li><b>Voice tracking</b> for our own culling logic uses a small
 *       lock-protected deque ordered by NoteOn time.</li>
 * </ul>
 *
 * <h2>Voice culling</h2>
 * <p>Gervill applies its own polyphony cap (we pass it via the
 * {@code "max polyphony"} stream property), but its default cull strategy
 * (drop the quietest voice) can audibly thin pad chords. We additionally
 * maintain our own oldest-first voice queue: if a NoteOn would push us
 * over {@link SynthSettings#maxVoices()}, we explicitly NoteOff the
 * oldest voice on the same {@link MidiChannel} first. Net result: the
 * <em>most recent</em> N notes are always audible, which matches user
 * expectation for hand-played MIDI.</p>
 */
public final class PolyphonySynthesizer {

    /** Settings used to construct the active synth. Immutable per instance. */
    private final SynthSettings settings;

    /** The Gervill synth instance, never {@code null} after a successful constructor. */
    private final Synthesizer synth;

    /** PCM stream pulled from Gervill; ownership transfers to the pump thread. */
    private final AudioInputStream synthStream;

    /** Ring buffer the pump thread fills and the audio stream bridge drains. */
    private final PcmRingBuffer ring;

    /** The pump thread itself. Daemon so it never holds Minecraft up at shutdown. */
    private final Thread pumpThread;

    /** Single source of monotonic timestamps for voice ordering. */
    private final AtomicLong voiceClock = new AtomicLong();

    /** Active voices, oldest at head. Guarded by {@link #voiceLock}. */
    private final Deque<Voice> voices = new ArrayDeque<>();
    private final Object voiceLock = new Object();

    private volatile boolean closed = false;

    /**
     * Build and start a synthesizer using the supplied settings. The synth
     * is silent until a soundfont is loaded via {@link #loadSoundFont(File)}.
     *
     * @throws MidiUnavailableException if Gervill cannot be acquired (highly
     *                                  unusual on a desktop JRE).
     */
    public PolyphonySynthesizer(SynthSettings settings) throws MidiUnavailableException {
        this.settings = settings;
        this.synth = MidiSystem.getSynthesizer();

        // Don't call synth.open() - that grabs the OS audio device. Use openStream() instead via reflection.
        Map<String, Object> streamProps = new HashMap<>();
        streamProps.put("max polyphony", settings.maxVoices());
        // "interpolation" options: "none", "linear", "cubic", "sinc". "linear" is the sweet spot
        // for CPU vs quality on typical sf2 banks.
        streamProps.put("interpolation", "linear");
        // Lower latency = less buffered audio inside Gervill itself; we add our own ring buffer
        // on top, so we keep Gervill's internal queue short.
        streamProps.put("latency", 50_000L); // 50 ms in microseconds

        AudioInputStream stream;
        try {
            // SoftSynthesizer.openStream(AudioFormat, Map<String,Object>) - reflective so we
            // don't compile-time depend on the com.sun.media.sound package.
            Method m = synth.getClass().getMethod("openStream", AudioFormat.class, Map.class);
            stream = (AudioInputStream) m.invoke(synth, settings.toAudioFormat(), streamProps);
        } catch (ReflectiveOperationException ex) {
            // Fallback: open() with no device argument and hope the impl supports a
            // headless mode. In practice every JRE we target ships SoftSynthesizer,
            // so this branch is just a safety net.
            CreatePolyphony.LOGGER.warn(
                "Synthesizer {} denied openStream(AudioFormat, Map). On Java 21 module layers this usually means missing --add-exports=java.desktop/com.sun.media.sound=createpolyphony.",
                synth.getClass().getName());
            synth.open();
            throw new MidiUnavailableException(
                "Active synthesizer doesn't support headless openStream(); add JVM arg --add-exports=java.desktop/com.sun.media.sound=createpolyphony. Root: " + ex.getMessage());
        }
        this.synthStream = stream;

        // Allocate the ring buffer based on configured size, rounded up to a frame boundary.
        int frame = settings.frameSize();
        int ringSize = Math.max(frame * 2, ((settings.ringBufferBytes() + frame - 1) / frame) * frame);
        this.ring = new PcmRingBuffer(ringSize);

        this.pumpThread = new Thread(this::pumpLoop, "CreatePolyphony-SynthPump");
        this.pumpThread.setDaemon(true);
        this.pumpThread.setPriority(Thread.NORM_PRIORITY + 1); // slight bump above default
        this.pumpThread.start();

        CreatePolyphony.LOGGER.info(
            "PolyphonySynthesizer started: {} Hz, {} ch, {}-bit, {} voice cap, {} byte ring",
            settings.sampleRate(), settings.channels(), settings.sampleSizeInBits(),
            settings.maxVoices(), ringSize);
    }

    public PcmRingBuffer ringBuffer() { return ring; }

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
        try {
            Soundbank bank = MidiSystem.getSoundbank(sf2File);
            if (bank == null) {
                throw new IOException("Not a recognised soundbank: " + sf2File);
            }
            allNotesOff();
            // Unload anything currently loaded.
            Soundbank cur = synth.getDefaultSoundbank();
            if (cur != null) {
                synth.unloadAllInstruments(cur);
            }
            // unloadInstruments(bank) is a no-op until loadAllInstruments has been called,
            // so unloadAllInstruments(default) above covers the prior bank cleanly.
            boolean ok = synth.loadAllInstruments(bank);
            if (!ok) {
                CreatePolyphony.LOGGER.warn(
                    "Synthesizer reported partial instrument load for {} - some patches may be silent",
                    sf2File.getName());
            } else {
                CreatePolyphony.LOGGER.info(
                    "Loaded soundfont {} ({} instruments)",
                    sf2File.getName(), bank.getInstruments().length);
            }
        } catch (javax.sound.midi.InvalidMidiDataException ex) {
            throw new IOException("Invalid soundfont file: " + sf2File, ex);
        }
    }

    /**
     * Forget all loaded patches; the synth becomes silent until another
     * {@link #loadSoundFont(File)} call. Used when the user picks
     * "None / No Sound" in the GUI.
     */
    public void unloadSoundFont() {
        allNotesOff();
        Soundbank cur = synth.getDefaultSoundbank();
        if (cur != null) synth.unloadAllInstruments(cur);
    }

    // ---- MIDI event entry points (thread-safe, callable from network thread) ------------------

    public void programChange(int channel, int program) {
        if (closed) return;
        var ch = channelOrNull(channel);
        if (ch == null) return;
        ch.programChange(program & 0x7F);
    }

    public void noteOn(int channel, int note, int velocity) {
        if (closed) return;
        var ch = channelOrNull(channel);
        if (ch == null) return;

        // Pre-cull oldest voice on this channel if we'd exceed the configured cap.
        synchronized (voiceLock) {
            if (voices.size() >= settings.maxVoices()) {
                Voice oldest = voices.pollFirst();
                if (oldest != null) {
                    var och = channelOrNull(oldest.channel);
                    if (och != null) och.noteOff(oldest.note);
                }
            }
            voices.addLast(new Voice(channel, note, voiceClock.incrementAndGet()));
        }
        ch.noteOn(note & 0x7F, velocity & 0x7F);
    }

    public void noteOff(int channel, int note) {
        if (closed) return;
        var ch = channelOrNull(channel);
        if (ch == null) return;
        synchronized (voiceLock) {
            // Remove the most recent matching voice (LIFO removal handles same-note retriggers correctly).
            Voice match = null;
            var it = voices.descendingIterator();
            while (it.hasNext()) {
                Voice v = it.next();
                if (v.channel == channel && v.note == note) { match = v; break; }
            }
            if (match != null) voices.remove(match);
        }
        ch.noteOff(note & 0x7F);
    }

    /** Channel pressure / pitch-bend pass-through. {@code value} is 0..16383 (centred at 8192). */
    public void pitchBend(int channel, int value) {
        if (closed) return;
        var ch = channelOrNull(channel);
        if (ch == null) return;
        ch.setPitchBend(Math.max(0, Math.min(16_383, value)));
    }

    /** Standard MIDI control change (volume, expression, sustain pedal, ...). */
    public void controlChange(int channel, int controller, int value) {
        if (closed) return;
        var ch = channelOrNull(channel);
        if (ch == null) return;
        ch.controlChange(controller & 0x7F, value & 0x7F);
    }

    /** Hard panic - silences every channel immediately and clears voice tracking. */
    public void allNotesOff() {
        synchronized (voiceLock) { voices.clear(); }
        var chans = synth.getChannels();
        if (chans == null) return;
        for (var ch : chans) {
            if (ch == null) continue;
            ch.allNotesOff();
            ch.allSoundOff();
        }
    }

    /** Diagnostic: which patches the bank we loaded actually defines. */
    public Patch[] availablePatches() {
        Soundbank bank = synth.getDefaultSoundbank();
        if (bank == null) return new Patch[0];
        var instruments = bank.getInstruments();
        Patch[] out = new Patch[instruments.length];
        for (int i = 0; i < instruments.length; i++) out[i] = instruments[i].getPatch();
        return out;
    }

    /** Tear everything down. Idempotent. Called on world unload / disconnect / mod shutdown. */
    public void close() {
        if (closed) return;
        closed = true;
        try { allNotesOff(); } catch (Throwable ignored) { }
        ring.close();
        try {
            if (synthStream != null) synthStream.close();
        } catch (IOException ignored) { }
        try {
            synth.close();
        } catch (Throwable ignored) { }
        try {
            pumpThread.join(500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        CreatePolyphony.LOGGER.info("PolyphonySynthesizer closed");
    }

    // ---- internals ---------------------------------------------------------------------------

    private MidiChannel channelOrNull(int channel) {
        var chans = synth.getChannels();
        if (chans == null) return null;
        if (channel < 0 || channel >= chans.length) return null;
        return chans[channel];
    }

    private void pumpLoop() {
        // The pump thread's only job: read from synthStream into the ring buffer until shutdown.
        // synthStream.read() blocks until Gervill renders the requested PCM, which paces us
        // automatically - we never busy-wait.
        byte[] chunk = new byte[settings.pumpChunkBytes()];
        try {
            while (!closed) {
                int read = synthStream.read(chunk, 0, chunk.length);
                if (read < 0) break; // synth closed
                if (read == 0) {
                    // Defensive: spin yield, shouldn't happen with Gervill.
                    Thread.yield();
                    continue;
                }
                int written = ring.write(chunk, 0, read);
                if (written < read && closed) break;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (IOException io) {
            if (!closed) {
                CreatePolyphony.LOGGER.error("Synth pump aborted on IO error", io);
            }
        } catch (Throwable t) {
            CreatePolyphony.LOGGER.error("Synth pump aborted on unexpected error", t);
        } finally {
            ring.close();
        }
    }

    /** A note we've started and not yet seen NoteOff for. */
    private record Voice(int channel, int note, long startedAt) { }
}
