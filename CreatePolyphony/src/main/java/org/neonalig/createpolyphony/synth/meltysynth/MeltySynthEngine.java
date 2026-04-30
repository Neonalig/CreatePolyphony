package org.neonalig.createpolyphony.synth.meltysynth;

import org.jetbrains.annotations.Nullable;
import org.neonalig.createpolyphony.synth.SynthSettings;

import java.util.Arrays;

/**
 * Real-time software synth core tailored for Minecraft's pull-based OpenAL stream.
 *
 * <h2>Threading</h2>
 * <p>The render thread is the only consumer of voice state. MIDI events are
 * enqueued from any thread and applied on the render thread at sample-accurate
 * positions inside each render block.</p>
 *
 * <h2>Sample-accurate scheduling (Phase 5)</h2>
 * <p>Events accept an optional {@link System#nanoTime()} target. {@link #renderS16Interleaved}
 * maintains an internal wall-clock cursor ({@link #blockStartNanos}) that
 * advances by {@code framesRendered / sampleRate} per slice and is re-anchored
 * to {@code System.nanoTime()} on entry whenever the audio pump has paused
 * (so the cursor never lags real time).</p>
 *
 * <p>Within a render call, the loop walks the event queue and slices the
 * render at every event boundary: events get applied at the precise sample
 * offset {@code (eventNanos - blockStartNanos) * sampleRate / 1e9}. Late
 * events (target in the past) are applied at offset 0 - never reordered to
 * preserve causality. Events scheduled past the end of the current render
 * window stay in the queue.</p>
 *
 * <p>Events with {@code nanos == 0L} are treated as "apply immediately"
 * (legacy / panic / engine-internal), short-circuiting the slicing path.</p>
 */
public final class MeltySynthEngine {

    private static final int EVT_NOTE_ON = 1;
    private static final int EVT_NOTE_OFF = 2;
    private static final int EVT_PROGRAM = 3;
    private static final int EVT_BEND = 4;
    private static final int EVT_CC = 5;
    private static final int EVT_ALL_NOTES_OFF = 6;
    // Keep render/control quanta small so very short notes don't collapse into one large block.
    private static final int TARGET_SYNTH_BLOCK_FRAMES = 64;
    private static final int RENDER_SLICE_FRAMES = 64;

    private final SynthSettings settings;

    private final MidiEventQueue midiQueue = new MidiEventQueue(8192);
    private final int[] midiEventScratch = new int[4];

    private volatile MeltySoundFont soundFont;
    private volatile Synthesizer synthesizer;
    private volatile boolean soundFontLoaded;
    private volatile boolean closed;

    /**
     * Internal wall-clock anchor for the start of the next render slice.
     * Re-anchored to {@code System.nanoTime()} on entry to {@link #renderS16Interleaved}
     * whenever it would otherwise lag real time, then advanced sample-accurately
     * within the call.
     */
    private long blockStartNanos = 0L;
    /** False until {@link #blockStartNanos} has been seeded from wall-clock. */
    private boolean blockClockAnchored = false;

    public MeltySynthEngine(SynthSettings settings) {
        this.settings = settings;
    }

    public SynthSettings settings() {
        return settings;
    }

    public void loadSoundFont(MeltySoundFont soundFont) {
        SynthesizerSettings synthSettings = new SynthesizerSettings((int) settings.sampleRate());
        int configuredFrames = settings.pumpChunkBytes() / Math.max(1, settings.frameSize());
        int blockFrames = Math.max(16, Math.min(256, Math.min(TARGET_SYNTH_BLOCK_FRAMES, Math.max(16, configuredFrames))));
        synthSettings.blockSize(blockFrames);
        synthSettings.maximumPolyphony(Math.max(8, Math.min(256, settings.maxVoices())));
        synthSettings.enableReverbAndChorus(true);
        Synthesizer newSynth = new Synthesizer(soundFont.soundFont(), synthSettings);
        this.soundFont = soundFont;
        this.synthesizer = newSynth;
        this.soundFontLoaded = true;
        allNotesOff();
    }

    public void unloadSoundFont() {
        this.soundFont = null;
        this.synthesizer = null;
        this.soundFontLoaded = false;
        allNotesOff();
    }

    public MeltySoundFont soundFont() {
        return soundFont;
    }

    // ---- MIDI entry points (untimed = legacy "apply immediately") --------------------------

    public void noteOn(int channel, int note, int velocity) {
        if (closed) return;
        midiQueue.offer(EVT_NOTE_ON, channel & 0x0F, note & 0x7F, velocity & 0x7F);
    }

    public void noteOff(int channel, int note) {
        if (closed) return;
        midiQueue.offer(EVT_NOTE_OFF, channel & 0x0F, note & 0x7F, 0);
    }

    public void programChange(int channel, int program) {
        if (closed) return;
        midiQueue.offer(EVT_PROGRAM, channel & 0x0F, program & 0x7F, 0);
    }

    public void pitchBend(int channel, int value) {
        if (closed) return;
        midiQueue.offer(EVT_BEND, channel & 0x0F, Math.max(0, Math.min(16383, value)), 0);
    }

    public void controlChange(int channel, int controller, int value) {
        if (closed) return;
        midiQueue.offer(EVT_CC, channel & 0x0F, controller & 0x7F, value & 0x7F);
    }

    public void allNotesOff() {
        if (closed) return;
        midiQueue.offer(EVT_ALL_NOTES_OFF, 0, 0, 0);
    }

    // ---- MIDI entry points (timed = sample-accurate placement) -----------------------------

    public void noteOnAt(int channel, int note, int velocity, long nanos) {
        if (closed) return;
        midiQueue.offer(EVT_NOTE_ON, channel & 0x0F, note & 0x7F, velocity & 0x7F, nanos);
    }

    public void noteOffAt(int channel, int note, long nanos) {
        if (closed) return;
        midiQueue.offer(EVT_NOTE_OFF, channel & 0x0F, note & 0x7F, 0, nanos);
    }

    public void programChangeAt(int channel, int program, long nanos) {
        if (closed) return;
        midiQueue.offer(EVT_PROGRAM, channel & 0x0F, program & 0x7F, 0, nanos);
    }

    public void pitchBendAt(int channel, int value, long nanos) {
        if (closed) return;
        midiQueue.offer(EVT_BEND, channel & 0x0F, Math.max(0, Math.min(16383, value)), 0, nanos);
    }

    public void controlChangeAt(int channel, int controller, int value, long nanos) {
        if (closed) return;
        midiQueue.offer(EVT_CC, channel & 0x0F, controller & 0x7F, value & 0x7F, nanos);
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        closed = true;
        synthesizer = null;
        soundFont = null;
        soundFontLoaded = false;
        midiQueue.clear();
    }

    /**
     * Render signed 16-bit little-endian interleaved PCM directly into the provided output array.
     *
     * @return number of bytes written (always frame-aligned)
     */
    public int renderS16Interleaved(byte[] out, int offset, int bytes) {
        if (closed || bytes <= 0) {
            return 0;
        }

        final int frameSize = settings.frameSize();
        final int frameBytes = bytes - (bytes % frameSize);
        if (frameBytes <= 0) {
            return 0;
        }

        final int frames = frameBytes / frameSize;
        final Synthesizer current = synthesizer;
        final int channelsOut = Math.max(1, settings.channels());
        final long sampleRateLong = (long) settings.sampleRate();
        // Pre-compute a single nanos-per-frame factor; sample rate stays constant per session.
        final double nanosPerFrame = 1_000_000_000.0D / sampleRateLong;

        // Anchor the internal cursor: never let it lag wall-clock (which would
        // bunch late events together) and never let it leap ahead (which would
        // delay events that are already due by an entire render call).
        long now = System.nanoTime();
        if (!blockClockAnchored || blockStartNanos < now) {
            blockStartNanos = now;
            blockClockAnchored = true;
        }

        int maxSlice = Math.max(1, Math.min(RENDER_SLICE_FRAMES, frames));
        float[] left = new float[maxSlice];
        float[] right = new float[maxSlice];

        int write = offset;
        int remainingFrames = frames;
        while (remainingFrames > 0) {
            int sliceFrames = Math.min(remainingFrames, maxSlice);
            long sliceEndNanos = blockStartNanos + (long) Math.ceil(sliceFrames * nanosPerFrame);

            int producedInSlice = 0;
            // Drain events that fall within this slice, splitting at exact sample offsets.
            while (producedInSlice < sliceFrames) {
                long headNanos = midiQueue.peekNanos();

                // No more events at all for this slice? fall through to render the rest.
                if (headNanos > sliceEndNanos) break;

                int splitOffset;
                if (headNanos == 0L || headNanos <= blockStartNanos) {
                    // Immediate / late event: apply at the current sample boundary.
                    splitOffset = 0;
                } else {
                    long deltaNanos = headNanos - blockStartNanos;
                    long offsetFrames = (long) ((double) deltaNanos / nanosPerFrame);
                    int boundedOffset = offsetFrames < 0
                        ? 0
                        : (offsetFrames > sliceFrames - producedInSlice
                            ? sliceFrames - producedInSlice
                            : (int) offsetFrames);
                    splitOffset = boundedOffset;
                }

                // Render the audio strictly preceding the event, if any.
                if (splitOffset > 0) {
                    write = renderInto(out, write, left, right, current, splitOffset, channelsOut);
                    producedInSlice += splitOffset;
                    blockStartNanos += (long) Math.ceil(splitOffset * nanosPerFrame);
                }

                // Apply the event(s) sharing this exact sample boundary so chord
                // members that came in with identical timestamps fire together.
                applyOneEvent(current);
                while (true) {
                    long n = midiQueue.peekNanos();
                    if (n != headNanos) break;
                    applyOneEvent(current);
                }
            }

            // Render whatever frames remain in this slice after all events handled.
            int remainder = sliceFrames - producedInSlice;
            if (remainder > 0) {
                write = renderInto(out, write, left, right, current, remainder, channelsOut);
                blockStartNanos += (long) Math.ceil(remainder * nanosPerFrame);
            }

            remainingFrames -= sliceFrames;
        }

        return frameBytes;
    }

    /**
     * Render {@code subSliceFrames} of audio into {@code out}, falling back to
     * silence when no soundfont is loaded or no voices are active. Returns the
     * advanced write pointer.
     */
    private int renderInto(byte[] out, int write, float[] left, float[] right,
                           @Nullable Synthesizer current, int subSliceFrames, int channelsOut) {
        boolean canRender = soundFontLoaded && current != null && current.activeVoiceCount() > 0;
        if (canRender) {
            current.render(left, right, 0, subSliceFrames);
            for (int i = 0; i < subSliceFrames; i++) {
                short l = toPcm16(left[i]);
                if (channelsOut == 1) {
                    out[write++] = (byte) (l & 0xFF);
                    out[write++] = (byte) ((l >>> 8) & 0xFF);
                } else {
                    short r = toPcm16(right[i]);
                    for (int ch = 0; ch < channelsOut; ch++) {
                        short s = (ch & 1) == 0 ? l : r;
                        out[write++] = (byte) (s & 0xFF);
                        out[write++] = (byte) ((s >>> 8) & 0xFF);
                    }
                }
            }
        } else {
            int silenceBytes = subSliceFrames * settings.frameSize();
            Arrays.fill(out, write, write + silenceBytes, (byte) 0);
            write += silenceBytes;
        }
        return write;
    }

    /** Pop and apply exactly one event from the queue. No-op when empty. */
    private void applyOneEvent(@Nullable Synthesizer synth) {
        if (!midiQueue.poll(midiEventScratch)) return;
        int type = midiEventScratch[0];
        int channel = midiEventScratch[1];
        int data1 = midiEventScratch[2];
        int data2 = midiEventScratch[3];

        if (synth == null && type != EVT_ALL_NOTES_OFF) return;

        switch (type) {
            case EVT_NOTE_ON -> synth.noteOn(channel, data1, data2);
            case EVT_NOTE_OFF -> synth.noteOff(channel, data1);
            case EVT_PROGRAM -> synth.processMidiMessage(channel, 0xC0, data1, 0);
            case EVT_BEND -> synth.processMidiMessage(channel, 0xE0, data1 & 0x7F, (data1 >> 7) & 0x7F);
            case EVT_CC -> synth.processMidiMessage(channel, 0xB0, data1, data2);
            case EVT_ALL_NOTES_OFF -> {
                if (synth != null) synth.noteOffAll(false);
            }
            default -> {
            }
        }
    }


    private static short toPcm16(float sample) {
        float clamped = Math.max(-1F, Math.min(1F, sample));
        return (short) Math.round(clamped * Short.MAX_VALUE);
    }
}
