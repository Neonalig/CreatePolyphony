package org.neonalig.createpolyphony.synth.meltysynth;

import java.util.Arrays;

public final class Synthesizer implements IAudioRenderer {
    private static final int CHANNEL_COUNT = 16;
    private static final int PERCUSSION_CHANNEL = 9;

    private final SoundFont soundFont;
    private final int sampleRate;
    private final int blockSize;
    private final int maximumPolyphony;
    private final boolean enableReverbAndChorus;
    private final int minimumVoiceDuration;
    private final Channel[] channels;
    private final VoiceCollection voices;
    private final float[] blockLeft;
    private final float[] blockRight;
    private final float inverseBlockSize;
    private int blockRead;
    private float masterVolume;
    private Reverb reverb;
    private float[] reverbInput;
    private float[] reverbOutputLeft;
    private float[] reverbOutputRight;
    private Chorus chorus;
    private float[] chorusInputLeft;
    private float[] chorusInputRight;
    private float[] chorusOutputLeft;
    private float[] chorusOutputRight;

    public Synthesizer(SoundFont soundFont, int sampleRate) {
        this(soundFont, new SynthesizerSettings(sampleRate));
    }

    public Synthesizer(SoundFont soundFont, SynthesizerSettings settings) {
        if (soundFont == null || settings == null) {
            throw new IllegalArgumentException("soundFont and settings must not be null");
        }
        this.soundFont = soundFont;
        this.sampleRate = settings.sampleRate();
        this.blockSize = settings.blockSize();
        this.maximumPolyphony = settings.maximumPolyphony();
        this.enableReverbAndChorus = settings.enableReverbAndChorus();
        this.minimumVoiceDuration = sampleRate / 500;
        this.channels = new Channel[CHANNEL_COUNT];
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new Channel(i == PERCUSSION_CHANNEL);
        }
        this.voices = new VoiceCollection(this, maximumPolyphony);
        this.blockLeft = new float[blockSize];
        this.blockRight = new float[blockSize];
        this.inverseBlockSize = 1F / blockSize;
        this.blockRead = blockSize;
        this.masterVolume = 0.5F;
        if (enableReverbAndChorus) {
            reverb = new Reverb(sampleRate);
            reverbInput = new float[blockSize];
            reverbOutputLeft = new float[blockSize];
            reverbOutputRight = new float[blockSize];
            chorus = new Chorus(sampleRate, 0.002, 0.0019, 0.4);
            chorusInputLeft = new float[blockSize];
            chorusInputRight = new float[blockSize];
            chorusOutputLeft = new float[blockSize];
            chorusOutputRight = new float[blockSize];
        }
    }

    public void processMidiMessage(int channel, int command, int data1, int data2) {
        if (!(0 <= channel && channel < channels.length)) {
            return;
        }
        Channel channelInfo = channels[channel];
        switch (command) {
            case 0x80 -> noteOff(channel, data1);
            case 0x90 -> noteOn(channel, data1, data2);
            case 0xB0 -> {
                switch (data1) {
                    case 0x00 -> channelInfo.setBank(data2);
                    case 0x01 -> channelInfo.setModulationCoarse(data2);
                    case 0x21 -> channelInfo.setModulationFine(data2);
                    case 0x06 -> channelInfo.dataEntryCoarse(data2);
                    case 0x26 -> channelInfo.dataEntryFine(data2);
                    case 0x07 -> channelInfo.setVolumeCoarse(data2);
                    case 0x27 -> channelInfo.setVolumeFine(data2);
                    case 0x0A -> channelInfo.setPanCoarse(data2);
                    case 0x2A -> channelInfo.setPanFine(data2);
                    case 0x0B -> channelInfo.setExpressionCoarse(data2);
                    case 0x2B -> channelInfo.setExpressionFine(data2);
                    case 0x40 -> channelInfo.setHoldPedal(data2);
                    case 0x5B -> channelInfo.setReverbSend(data2);
                    case 0x5D -> channelInfo.setChorusSend(data2);
                    case 0x63 -> channelInfo.setNrpnCoarse(data2);
                    case 0x62 -> channelInfo.setNrpnFine(data2);
                    case 0x65 -> channelInfo.setRpnCoarse(data2);
                    case 0x64 -> channelInfo.setRpnFine(data2);
                    case 0x78 -> noteOffAll(channel, true);
                    case 0x79 -> resetAllControllers(channel);
                    case 0x7B -> noteOffAll(channel, false);
                    default -> {}
                }
            }
            case 0xC0 -> channelInfo.setPatch(data1);
            case 0xE0 -> channelInfo.setPitchBend(data1, data2);
            default -> {}
        }
    }

    public void noteOff(int channel, int key) {
        if (!(0 <= channel && channel < channels.length)) return;
        for (Voice voice : voices) {
            if (voice.channel() == channel && voice.key() == key) {
                voice.end();
            }
        }
    }

    public void noteOn(int channel, int key, int velocity) {
        if (velocity == 0) {
            noteOff(channel, key);
            return;
        }
        if (!(0 <= channel && channel < channels.length)) return;
        Channel channelInfo = channels[channel];
        Preset preset = soundFont.findPreset(channelInfo.bankNumber(), channelInfo.patchNumber());
        for (PresetRegion presetRegion : preset.regionArray()) {
            if (presetRegion.contains(key, velocity)) {
                for (InstrumentRegion instrumentRegion : presetRegion.instrument().regionArray()) {
                    if (instrumentRegion.contains(key, velocity)) {
                        RegionPair regionPair = new RegionPair(presetRegion, instrumentRegion);
                        Voice voice = voices.requestNew(instrumentRegion, channel);
                        if (voice != null) {
                            voice.start(regionPair, channel, key, velocity);
                        }
                    }
                }
            }
        }
    }

    public void noteOffAll(boolean immediate) {
        if (immediate) {
            voices.clear();
        } else {
            for (Voice voice : voices) {
                voice.end();
            }
        }
    }

    public void noteOffAll(int channel, boolean immediate) {
        if (immediate) {
            for (Voice voice : voices) {
                if (voice.channel() == channel) {
                    voice.kill();
                }
            }
        } else {
            for (Voice voice : voices) {
                if (voice.channel() == channel) {
                    voice.end();
                }
            }
        }
    }

    public void resetAllControllers() {
        for (Channel channel : channels) {
            channel.resetAllControllers();
        }
    }

    public void resetAllControllers(int channel) {
        if (!(0 <= channel && channel < channels.length)) return;
        channels[channel].resetAllControllers();
    }

    public void reset() {
        voices.clear();
        for (Channel channel : channels) {
            channel.reset();
        }
        if (enableReverbAndChorus) {
            reverb.mute();
            chorus.mute();
        }
        blockRead = blockSize;
    }

    @Override
    public void render(float[] left, float[] right, int offset, int length) {
        if (length < 0 || offset < 0 || offset + length > left.length || offset + length > right.length) {
            throw new IllegalArgumentException("Invalid render range.");
        }
        int wrote = 0;
        while (wrote < length) {
            if (blockRead == blockSize) {
                renderBlock();
                blockRead = 0;
            }
            int srcRem = blockSize - blockRead;
            int dstRem = length - wrote;
            int rem = Math.min(srcRem, dstRem);
            System.arraycopy(blockLeft, blockRead, left, offset + wrote, rem);
            System.arraycopy(blockRight, blockRead, right, offset + wrote, rem);
            blockRead += rem;
            wrote += rem;
        }
    }

    private void renderBlock() {
        voices.process();
        Arrays.fill(blockLeft, 0F);
        Arrays.fill(blockRight, 0F);
        for (Voice voice : voices) {
            float previousGainLeft = masterVolume * voice.previousMixGainLeft();
            float currentGainLeft = masterVolume * voice.currentMixGainLeft();
            writeBlock(previousGainLeft, currentGainLeft, voice.block(), blockLeft);
            float previousGainRight = masterVolume * voice.previousMixGainRight();
            float currentGainRight = masterVolume * voice.currentMixGainRight();
            writeBlock(previousGainRight, currentGainRight, voice.block(), blockRight);
        }
        if (enableReverbAndChorus) {
            Arrays.fill(chorusInputLeft, 0F);
            Arrays.fill(chorusInputRight, 0F);
            for (Voice voice : voices) {
                float previousGainLeft = voice.previousChorusSend() * voice.previousMixGainLeft();
                float currentGainLeft = voice.currentChorusSend() * voice.currentMixGainLeft();
                writeBlock(previousGainLeft, currentGainLeft, voice.block(), chorusInputLeft);
                float previousGainRight = voice.previousChorusSend() * voice.previousMixGainRight();
                float currentGainRight = voice.currentChorusSend() * voice.currentMixGainRight();
                writeBlock(previousGainRight, currentGainRight, voice.block(), chorusInputRight);
            }
            chorus.process(chorusInputLeft, chorusInputRight, chorusOutputLeft, chorusOutputRight);
            ArrayMath.multiplyAdd(masterVolume, chorusOutputLeft, blockLeft);
            ArrayMath.multiplyAdd(masterVolume, chorusOutputRight, blockRight);

            Arrays.fill(reverbInput, 0F);
            for (Voice voice : voices) {
                float previousGain = reverb.inputGain() * voice.previousReverbSend() * (voice.previousMixGainLeft() + voice.previousMixGainRight());
                float currentGain = reverb.inputGain() * voice.currentReverbSend() * (voice.currentMixGainLeft() + voice.currentMixGainRight());
                writeBlock(previousGain, currentGain, voice.block(), reverbInput);
            }
            reverb.process(reverbInput, reverbOutputLeft, reverbOutputRight);
            ArrayMath.multiplyAdd(masterVolume, reverbOutputLeft, blockLeft);
            ArrayMath.multiplyAdd(masterVolume, reverbOutputRight, blockRight);
        }
    }

    private void writeBlock(float previousGain, float currentGain, float[] source, float[] destination) {
        if (Math.max(previousGain, currentGain) < SoundFontMath.NON_AUDIBLE) {
            return;
        }
        if (Math.abs(currentGain - previousGain) < 1.0E-3F) {
            ArrayMath.multiplyAdd(currentGain, source, destination);
        } else {
            float step = inverseBlockSize * (currentGain - previousGain);
            ArrayMath.multiplyAdd(previousGain, step, source, destination);
        }
    }

    public int blockSize() { return blockSize; }
    public int maximumPolyphony() { return maximumPolyphony; }
    public int channelCount() { return CHANNEL_COUNT; }
    public int percussionChannel() { return PERCUSSION_CHANNEL; }
    public SoundFont soundFont() { return soundFont; }
    public int sampleRate() { return sampleRate; }
    public int activeVoiceCount() { return voices.activeVoiceCount(); }
    public float masterVolume() { return masterVolume; }
    public void masterVolume(float value) { masterVolume = value; }
    int minimumVoiceDuration() { return minimumVoiceDuration; }
    Channel[] channels() { return channels; }
}

