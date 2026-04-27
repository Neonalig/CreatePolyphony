package org.neonalig.createpolyphony.synth.meltysynth;

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

    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final float BEND_RANGE_SEMITONES = 2.0F;

    private final SynthSettings settings;
    private final float sampleRate;
    private final int maxVoices;

    private final ChannelState[] channels = new ChannelState[16];
    private final Voice[] voices;
    private int voiceCount;
    private long voiceClock;

    private final MidiEventQueue midiQueue = new MidiEventQueue(8192);
    private final int[] midiEventScratch = new int[4];

    private MeltySoundFont soundFont;
    private boolean soundFontLoaded;
    private volatile boolean closed;

    public MeltySynthEngine(SynthSettings settings) {
        this.settings = settings;
        this.sampleRate = settings.sampleRate();
        this.maxVoices = Math.max(1, settings.maxVoices());
        this.voices = new Voice[this.maxVoices];
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new ChannelState();
        }
    }

    public SynthSettings settings() {
        return settings;
    }

    public void loadSoundFont(MeltySoundFont soundFont) {
        this.soundFont = soundFont;
        this.soundFontLoaded = true;
        allNotesOff();
    }

    public void unloadSoundFont() {
        this.soundFont = null;
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
        midiQueue.clear();
        voiceCount = 0;
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

        drainMidi();

        if (!soundFontLoaded || voiceCount == 0) {
            Arrays.fill(out, offset, offset + frameBytes, (byte) 0);
            return frameBytes;
        }

        int frames = frameBytes / frameSize;
        int write = offset;
        int channelsOut = Math.max(1, settings.channels());

        for (int i = 0; i < frames; i++) {
            float left = 0F;
            float right = 0F;

            for (int v = voiceCount - 1; v >= 0; v--) {
                Voice voice = voices[v];
                ChannelState channel = channels[voice.channel];

                float level = voice.currentGain * channel.outputGain;
                if (level < 1.0E-5F) {
                    if (voice.inRelease) {
                        removeVoice(v);
                    }
                    continue;
                }

                float sample = waveform(voice.program, voice.phase);
                left += sample * level * voice.panLeft;
                right += sample * level * voice.panRight;

                voice.phase += voice.phaseStep * pitchBendFactor(channel.pitchBend);
                if (voice.phase >= 1F) {
                    voice.phase -= (float) Math.floor(voice.phase);
                }

                if (voice.inRelease) {
                    voice.currentGain -= voice.releaseDelta;
                    if (voice.currentGain <= 0F) {
                        removeVoice(v);
                    }
                } else if (voice.currentGain < voice.targetGain) {
                    voice.currentGain = Math.min(voice.targetGain, voice.currentGain + voice.attackDelta);
                }
            }

            short l = toPcm16(left);
            if (channelsOut == 1) {
                out[write] = (byte) (l & 0xFF);
                out[write + 1] = (byte) ((l >>> 8) & 0xFF);
                write += 2;
            } else {
                short r = toPcm16(right);
                for (int ch = 0; ch < channelsOut; ch++) {
                    short s = (ch & 1) == 0 ? l : r;
                    out[write++] = (byte) (s & 0xFF);
                    out[write++] = (byte) ((s >>> 8) & 0xFF);
                }
            }
        }

        return frameBytes;
    }

    private void drainMidi() {
        while (midiQueue.poll(midiEventScratch)) {
            int type = midiEventScratch[0];
            int channel = midiEventScratch[1];
            int data1 = midiEventScratch[2];
            int data2 = midiEventScratch[3];

            switch (type) {
                case EVT_NOTE_ON -> handleNoteOn(channel, data1, data2);
                case EVT_NOTE_OFF -> handleNoteOff(channel, data1);
                case EVT_PROGRAM -> channels[channel].program = data1;
                case EVT_BEND -> channels[channel].pitchBend = data1;
                case EVT_CC -> handleControlChange(channel, data1, data2);
                case EVT_ALL_NOTES_OFF -> clearVoices();
                default -> {
                }
            }
        }
    }

    private void handleControlChange(int channel, int controller, int value) {
        ChannelState state = channels[channel];
        switch (controller) {
            case 7 -> state.volume = value / 127F;
            case 10 -> state.pan = value / 127F;
            case 11 -> state.expression = value / 127F;
            case 64 -> {
                state.sustain = value >= 64;
                if (!state.sustain) {
                    for (int i = 0; i < voiceCount; i++) {
                        Voice voice = voices[i];
                        if (voice.channel == channel && voice.heldBySustain) {
                            startRelease(voice);
                            voice.heldBySustain = false;
                        }
                    }
                }
            }
            default -> {
            }
        }
        state.recomputeOutputGain();
    }

    private void handleNoteOn(int channel, int note, int velocity) {
        if (!soundFontLoaded || velocity <= 0) {
            return;
        }

        if (voiceCount >= maxVoices) {
            int oldest = 0;
            long oldestStamp = Long.MAX_VALUE;
            for (int i = 0; i < voiceCount; i++) {
                Voice voice = voices[i];
                if (voice.startedAt < oldestStamp) {
                    oldestStamp = voice.startedAt;
                    oldest = i;
                }
            }
            removeVoice(oldest);
        }

        ChannelState state = channels[channel];
        Voice voice = new Voice();
        voice.channel = channel;
        voice.note = note;
        voice.program = state.program;
        voice.startedAt = ++voiceClock;
        voice.phase = 0F;
        voice.phaseStep = midiNoteToFrequency(note) / sampleRate;
        voice.targetGain = (velocity / 127F) * 0.28F;
        voice.currentGain = 0F;
        voice.attackDelta = 1F / Math.max(1F, sampleRate * 0.004F);
        voice.releaseDelta = voice.targetGain / Math.max(1F, sampleRate * 0.08F);

        float pan = Math.max(0F, Math.min(1F, state.pan));
        voice.panLeft = (float) Math.cos(pan * Math.PI * 0.5);
        voice.panRight = (float) Math.sin(pan * Math.PI * 0.5);

        voices[voiceCount++] = voice;
    }

    private void handleNoteOff(int channel, int note) {
        ChannelState state = channels[channel];
        for (int i = voiceCount - 1; i >= 0; i--) {
            Voice voice = voices[i];
            if (voice.channel != channel || voice.note != note) {
                continue;
            }
            if (state.sustain) {
                voice.heldBySustain = true;
            } else {
                startRelease(voice);
            }
            return;
        }
    }

    private void startRelease(Voice voice) {
        voice.inRelease = true;
        voice.currentGain = Math.max(voice.currentGain, 0.0005F);
        voice.releaseDelta = voice.currentGain / Math.max(1F, sampleRate * 0.08F);
    }

    private void clearVoices() {
        voiceCount = 0;
    }

    private void removeVoice(int index) {
        int last = voiceCount - 1;
        voices[index] = voices[last];
        voices[last] = null;
        voiceCount = last;
    }

    private static float waveform(int program, float phase) {
        int group = (program / 8) & 0x07;
        return switch (group) {
            case 0 -> (float) Math.sin(TWO_PI * phase);
            case 1 -> 2F * phase - 1F;
            case 2 -> phase < 0.5F ? 1F : -1F;
            case 3 -> 1F - 4F * Math.abs(phase - 0.5F);
            default -> 0.65F * (float) Math.sin(TWO_PI * phase) + 0.35F * (2F * phase - 1F);
        };
    }

    private static float midiNoteToFrequency(int note) {
        return 440F * (float) Math.pow(2.0, (note - 69) / 12.0);
    }

    private static float pitchBendFactor(int bendValue) {
        float normalized = (bendValue - 8192) / 8192F;
        float semitones = normalized * BEND_RANGE_SEMITONES;
        return (float) Math.pow(2.0, semitones / 12.0);
    }

    private static short toPcm16(float sample) {
        float clamped = Math.max(-1F, Math.min(1F, sample));
        return (short) Math.round(clamped * Short.MAX_VALUE);
    }

    private static final class ChannelState {
        int program;
        int pitchBend = 8192;
        float volume = 1F;
        float expression = 1F;
        float pan = 0.5F;
        float outputGain = 1F;
        boolean sustain;

        void recomputeOutputGain() {
            outputGain = volume * expression;
        }
    }

    private static final class Voice {
        int channel;
        int note;
        int program;
        long startedAt;
        float phase;
        float phaseStep;
        float targetGain;
        float currentGain;
        float attackDelta;
        float releaseDelta;
        float panLeft;
        float panRight;
        boolean inRelease;
        boolean heldBySustain;
    }

    private static final class MidiEventQueue {

        private final int[] type;
        private final int[] channel;
        private final int[] data1;
        private final int[] data2;
        private final int capacity;

        private int head;
        private int tail;
        private int size;

        MidiEventQueue(int capacity) {
            this.capacity = Math.max(32, capacity);
            this.type = new int[this.capacity];
            this.channel = new int[this.capacity];
            this.data1 = new int[this.capacity];
            this.data2 = new int[this.capacity];
        }

        synchronized void offer(int eventType, int eventChannel, int eventData1, int eventData2) {
            if (size == capacity) {
                head = (head + 1) % capacity;
                size--;
            }
            type[tail] = eventType;
            channel[tail] = eventChannel;
            data1[tail] = eventData1;
            data2[tail] = eventData2;
            tail = (tail + 1) % capacity;
            size++;
        }

        synchronized boolean poll(int[] out) {
            if (size == 0) {
                return false;
            }
            out[0] = type[head];
            out[1] = channel[head];
            out[2] = data1[head];
            out[3] = data2[head];
            head = (head + 1) % capacity;
            size--;
            return true;
        }

        synchronized void clear() {
            head = 0;
            tail = 0;
            size = 0;
        }
    }
}
