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

    private final SynthSettings settings;
    private final float sampleRate;
    private final int maxVoices;
    private final int blockFrames;

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
        this.blockFrames = 64;
        this.voices = new Voice[this.maxVoices];
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new ChannelState(i == 9);
        }
    }

    public SynthSettings settings() {
        return settings;
    }

    public void loadSoundFont(MeltySoundFont soundFont) {
        this.soundFont = soundFont;
        this.soundFontLoaded = true;
        allNotesOff();
        for (int i = 0; i < channels.length; i++) {
            channels[i].resetForNewBank();
        }
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

        float[] leftBlock = new float[blockFrames];
        float[] rightBlock = new float[blockFrames];
        int rendered = 0;
        while (rendered < frames) {
            int count = Math.min(blockFrames, frames - rendered);
            Arrays.fill(leftBlock, 0, count, 0F);
            Arrays.fill(rightBlock, 0, count, 0F);

            for (int v = voiceCount - 1; v >= 0; v--) {
                Voice voice = voices[v];
                ChannelState channel = channels[voice.channel];
                if (!voice.renderInto(channel, leftBlock, rightBlock, count)) {
                    removeVoice(v);
                }
            }

            for (int i = 0; i < count; i++) {
                short l = toPcm16(leftBlock[i]);
                if (channelsOut == 1) {
                    out[write] = (byte) (l & 0xFF);
                    out[write + 1] = (byte) ((l >>> 8) & 0xFF);
                    write += 2;
                } else {
                    short r = toPcm16(rightBlock[i]);
                    for (int ch = 0; ch < channelsOut; ch++) {
                        short s = (ch & 1) == 0 ? l : r;
                        out[write++] = (byte) (s & 0xFF);
                        out[write++] = (byte) ((s >>> 8) & 0xFF);
                    }
                }
            }
            rendered += count;
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
            case 0 -> state.bankMsb = value & 0x7F;
            case 7 -> state.volume = value / 127F;
            case 10 -> state.pan = (value / 127F) * 100F - 50F;
            case 11 -> state.expression = value / 127F;
            case 91 -> state.reverbSend = value / 127F;
            case 93 -> state.chorusSend = value / 127F;
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
        ChannelState state = channels[channel];
        int bank = state.percussion ? 128 : state.bankMsb;
        Preset preset = soundFont.findPreset(bank, state.program);
        if (preset == null) return;

        for (PresetRegion presetRegion : preset.regions()) {
            if (!presetRegion.contains(note, velocity)) continue;

            Instrument instrument = presetRegion.instrument();
            for (InstrumentRegion instrumentRegion : instrument.regions()) {
                if (!instrumentRegion.contains(note, velocity)) continue;
                RegionPair pair = new RegionPair(presetRegion, instrumentRegion);
                startVoice(channel, note, velocity, pair);
            }
        }
    }

    private void startVoice(int channel, int note, int velocity, RegionPair pair) {
        int idx = requestVoiceSlot(channel, note);
        if (idx < 0) return;
        Voice voice = voices[idx];
        if (voice == null) {
            voice = new Voice();
            voices[idx] = voice;
            if (idx == voiceCount) voiceCount++;
        }
        voice.start(soundFont, channel, note, velocity, pair, sampleRate, ++voiceClock);
    }

    private int requestVoiceSlot(int channel, int key) {
        if (voiceCount < maxVoices) {
            return voiceCount;
        }
        int worst = -1;
        float worstPriority = Float.MAX_VALUE;
        for (int i = 0; i < voiceCount; i++) {
            Voice v = voices[i];
            if (v == null) continue;
            if (v.channel == channel && v.note == key) return i;
            float p = v.priority();
            if (p < worstPriority) {
                worstPriority = p;
                worst = i;
            }
        }
        return worst;
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
        }
    }

    private void startRelease(Voice voice) {
        voice.requestRelease();
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

    private static short toPcm16(float sample) {
        float clamped = Math.max(-1F, Math.min(1F, sample));
        return (short) Math.round(clamped * Short.MAX_VALUE);
    }
}
