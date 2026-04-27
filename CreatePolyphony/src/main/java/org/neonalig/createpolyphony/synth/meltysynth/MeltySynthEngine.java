package org.neonalig.createpolyphony.synth.meltysynth;

import org.jetbrains.annotations.Nullable;
import org.neonalig.createpolyphony.synth.SynthSettings;

import java.util.Arrays;

/**
 * Real-time software synth core tailored for Minecraft's pull-based OpenAL stream.
 *
 * <p>The render thread is expected to be the only consumer of voice state. MIDI
 * events are enqueued from any thread and drained at the beginning of each render
 * block to keep lock hold times short on the client tick/network threads.</p>
 */
public final class MeltySynthEngine {

    private static final int EVT_NOTE_ON = 1;
    private static final int EVT_NOTE_OFF = 2;
    private static final int EVT_PROGRAM = 3;
    private static final int EVT_BEND = 4;
    private static final int EVT_CC = 5;
    private static final int EVT_ALL_NOTES_OFF = 6;

    private final SynthSettings settings;

    private final MidiEventQueue midiQueue = new MidiEventQueue(8192);
    private final int[] midiEventScratch = new int[4];

    private volatile MeltySoundFont soundFont;
    private volatile Synthesizer synthesizer;
    private volatile boolean soundFontLoaded;
    private volatile boolean closed;

    public MeltySynthEngine(SynthSettings settings) {
        this.settings = settings;
    }

    public SynthSettings settings() {
        return settings;
    }

    public void loadSoundFont(MeltySoundFont soundFont) {
        SynthesizerSettings synthSettings = new SynthesizerSettings((int) settings.sampleRate());
        synthSettings.blockSize(Math.max(8, Math.min(1024, settings.pumpChunkBytes() / Math.max(1, settings.frameSize()))));
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

    public void noteOn(int channel, int note, int velocity) {
        if (closed) {
            return;
        }
        midiQueue.offer(EVT_NOTE_ON, channel & 0x0F, note & 0x7F, velocity & 0x7F);
    }

    public void noteOff(int channel, int note) {
        if (closed) {
            return;
        }
        midiQueue.offer(EVT_NOTE_OFF, channel & 0x0F, note & 0x7F, 0);
    }

    public void programChange(int channel, int program) {
        if (closed) {
            return;
        }
        midiQueue.offer(EVT_PROGRAM, channel & 0x0F, program & 0x7F, 0);
    }

    public void pitchBend(int channel, int value) {
        if (closed) {
            return;
        }
        midiQueue.offer(EVT_BEND, channel & 0x0F, Math.max(0, Math.min(16383, value)), 0);
    }

    public void controlChange(int channel, int controller, int value) {
        if (closed) {
            return;
        }
        midiQueue.offer(EVT_CC, channel & 0x0F, controller & 0x7F, value & 0x7F);
    }

    public void allNotesOff() {
        if (closed) {
            return;
        }
        midiQueue.offer(EVT_ALL_NOTES_OFF, 0, 0, 0);
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

        int frames = frameBytes / frameSize;
        Synthesizer current = synthesizer;
        drainMidi(current);

        float[] left = null;
        float[] right = null;
        if (soundFontLoaded && current != null && current.activeVoiceCount() > 0) {
            left = new float[frames];
            right = new float[frames];
            current.render(left, right, 0, frames);
        }
        if (left == null) {
            Arrays.fill(out, offset, offset + frameBytes, (byte) 0);
            return frameBytes;
        }

        int write = offset;
        int channelsOut = Math.max(1, settings.channels());
        for (int i = 0; i < frames; i++) {
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

        return frameBytes;
    }

    private void drainMidi(@Nullable Synthesizer synth) {
        while (midiQueue.poll(midiEventScratch)) {
            int type = midiEventScratch[0];
            int channel = midiEventScratch[1];
            int data1 = midiEventScratch[2];
            int data2 = midiEventScratch[3];

            if (synth == null && type != EVT_ALL_NOTES_OFF) {
                continue;
            }

            switch (type) {
                case EVT_NOTE_ON -> synth.noteOn(channel, data1, data2);
                case EVT_NOTE_OFF -> synth.noteOff(channel, data1);
                case EVT_PROGRAM -> synth.processMidiMessage(channel, 0xC0, data1, 0);
                case EVT_BEND -> synth.processMidiMessage(channel, 0xE0, data1 & 0x7F, (data1 >> 7) & 0x7F);
                case EVT_CC -> synth.processMidiMessage(channel, 0xB0, data1, data2);
                case EVT_ALL_NOTES_OFF -> {
                    if (synth != null) {
                        synth.noteOffAll(false);
                    }
                }
                default -> {
                }
            }
        }
    }


    private static short toPcm16(float sample) {
        float clamped = Math.max(-1F, Math.min(1F, sample));
        return (short) Math.round(clamped * Short.MAX_VALUE);
    }
}
