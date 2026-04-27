package org.neonalig.createpolyphony.synth.meltysynth;

final class Voice {
    private final Synthesizer synthesizer;
    private final VolumeEnvelope volEnv;
    private final ModulationEnvelope modEnv;
    private final Lfo vibLfo;
    private final Lfo modLfo;
    private final Oscillator oscillator;
    private final BiQuadFilter filter;
    private final float[] block;

    private float previousMixGainLeft;
    private float previousMixGainRight;
    private float currentMixGainLeft;
    private float currentMixGainRight;
    private float previousReverbSend;
    private float previousChorusSend;
    private float currentReverbSend;
    private float currentChorusSend;
    private int exclusiveClass;
    private int channel;
    private int key;
    private int velocity;
    private float noteGain;
    private float cutoff;
    private float resonance;
    private float vibLfoToPitch;
    private float modLfoToPitch;
    private float modEnvToPitch;
    private int modLfoToCutoff;
    private int modEnvToCutoff;
    private boolean dynamicCutoff;
    private float modLfoToVolume;
    private boolean dynamicVolume;
    private float instrumentPan;
    private float instrumentReverb;
    private float instrumentChorus;
    private float smoothedCutoff;
    private VoiceState voiceState;
    private int voiceLength;

    Voice(Synthesizer synthesizer) {
        this.synthesizer = synthesizer;
        volEnv = new VolumeEnvelope(synthesizer);
        modEnv = new ModulationEnvelope(synthesizer);
        vibLfo = new Lfo(synthesizer);
        modLfo = new Lfo(synthesizer);
        oscillator = new Oscillator(synthesizer);
        filter = new BiQuadFilter(synthesizer);
        block = new float[synthesizer.blockSize()];
    }

    void start(RegionPair region, int channel, int key, int velocity) {
        this.exclusiveClass = region.exclusiveClass();
        this.channel = channel;
        this.key = key;
        this.velocity = velocity;

        if (velocity > 0) {
            float sampleAttenuation = 0.4F * region.initialAttenuationDb();
            float filterAttenuation = 0.5F * region.initialFilterQ();
            float decibels = 2 * SoundFontMath.linearToDecibels(velocity / 127F) - sampleAttenuation - filterAttenuation;
            noteGain = SoundFontMath.decibelsToLinear(decibels);
        } else {
            noteGain = 0F;
        }

        cutoff = region.initialFilterCutoffFrequency();
        resonance = SoundFontMath.decibelsToLinear(region.initialFilterQ());
        vibLfoToPitch = 0.01F * region.vibLfoToPitchCents();
        modLfoToPitch = 0.01F * region.modLfoToPitchCents();
        modEnvToPitch = 0.01F * region.modEnvToPitchCents();
        modLfoToCutoff = region.modulationLfoToFilterCutoffFrequency();
        modEnvToCutoff = region.modulationEnvelopeToFilterCutoffFrequency();
        dynamicCutoff = modLfoToCutoff != 0 || modEnvToCutoff != 0;
        modLfoToVolume = region.modLfoToVolumeDb();
        dynamicVolume = modLfoToVolume > 0.05F;
        instrumentPan = Math.clamp(region.pan(), -50F, 50F);
        instrumentReverb = 0.01F * region.reverbEffectsSend();
        instrumentChorus = 0.01F * region.chorusEffectsSend();

        RegionEx.start(volEnv, region, key, velocity);
        RegionEx.start(modEnv, region, key, velocity);
        RegionEx.startVibrato(vibLfo, region, key, velocity);
        RegionEx.startModulation(modLfo, region, key, velocity);
        RegionEx.start(oscillator, synthesizer.soundFont().waveDataArray(), region);
        filter.clearBuffer();
        filter.setLowPassFilter(cutoff, resonance);
        smoothedCutoff = cutoff;
        voiceState = VoiceState.PLAYING;
        voiceLength = 0;
    }

    void end() {
        if (voiceState == VoiceState.PLAYING) {
            voiceState = VoiceState.RELEASE_REQUESTED;
        }
    }

    void kill() {
        noteGain = 0F;
    }

    boolean process() {
        if (noteGain < SoundFontMath.NON_AUDIBLE) {
            return false;
        }

        Channel channelInfo = synthesizer.channels()[channel];
        releaseIfNecessary(channelInfo);
        if (!volEnv.process()) {
            return false;
        }
        modEnv.process();
        vibLfo.process();
        modLfo.process();

        float vibPitchChange = (0.01F * channelInfo.modulation() + vibLfoToPitch) * vibLfo.value();
        float modPitchChange = modLfoToPitch * modLfo.value() + modEnvToPitch * modEnv.value();
        float channelPitchChange = channelInfo.tune() + channelInfo.pitchBend();
        float pitch = key + vibPitchChange + modPitchChange + channelPitchChange;
        if (!oscillator.process(block, pitch)) {
            return false;
        }

        if (dynamicCutoff) {
            float cents = modLfoToCutoff * modLfo.value() + modEnvToCutoff * modEnv.value();
            float factor = SoundFontMath.centsToMultiplyingFactor(cents);
            float newCutoff = factor * cutoff;
            float lowerLimit = 0.5F * smoothedCutoff;
            float upperLimit = 2F * smoothedCutoff;
            smoothedCutoff = Math.clamp(newCutoff, lowerLimit, upperLimit);
            filter.setLowPassFilter(smoothedCutoff, resonance);
        }
        filter.process(block);

        previousMixGainLeft = currentMixGainLeft;
        previousMixGainRight = currentMixGainRight;
        previousReverbSend = currentReverbSend;
        previousChorusSend = currentChorusSend;

        float ve = channelInfo.volume() * channelInfo.expression();
        float channelGain = ve * ve;
        float mixGain = noteGain * channelGain * volEnv.value();
        if (dynamicVolume) {
            float decibels = modLfoToVolume * modLfo.value();
            mixGain *= SoundFontMath.decibelsToLinear(decibels);
        }

        float angle = (float) (Math.PI / 200F) * (channelInfo.pan() + instrumentPan + 50F);
        if (angle <= 0F) {
            currentMixGainLeft = mixGain;
            currentMixGainRight = 0F;
        } else if (angle >= SoundFontMath.HALF_PI) {
            currentMixGainLeft = 0F;
            currentMixGainRight = mixGain;
        } else {
            currentMixGainLeft = mixGain * (float) Math.cos(angle);
            currentMixGainRight = mixGain * (float) Math.sin(angle);
        }

        currentReverbSend = Math.clamp(channelInfo.reverbSend() + instrumentReverb, 0F, 1F);
        currentChorusSend = Math.clamp(channelInfo.chorusSend() + instrumentChorus, 0F, 1F);

        if (voiceLength == 0) {
            previousMixGainLeft = currentMixGainLeft;
            previousMixGainRight = currentMixGainRight;
            previousReverbSend = currentReverbSend;
            previousChorusSend = currentChorusSend;
        }

        voiceLength += synthesizer.blockSize();
        return true;
    }

    private void releaseIfNecessary(Channel channelInfo) {
        if (voiceLength < synthesizer.minimumVoiceDuration()) {
            return;
        }
        if (voiceState == VoiceState.RELEASE_REQUESTED && !channelInfo.holdPedal()) {
            volEnv.release();
            modEnv.release();
            oscillator.release();
            voiceState = VoiceState.RELEASED;
        }
    }

    float priority() {
        return noteGain < SoundFontMath.NON_AUDIBLE ? 0F : volEnv.priority();
    }

    float[] block() { return block; }
    float previousMixGainLeft() { return previousMixGainLeft; }
    float previousMixGainRight() { return previousMixGainRight; }
    float currentMixGainLeft() { return currentMixGainLeft; }
    float currentMixGainRight() { return currentMixGainRight; }
    float previousReverbSend() { return previousReverbSend; }
    float previousChorusSend() { return previousChorusSend; }
    float currentReverbSend() { return currentReverbSend; }
    float currentChorusSend() { return currentChorusSend; }
    int exclusiveClass() { return exclusiveClass; }
    int channel() { return channel; }
    int key() { return key; }
    int velocity() { return velocity; }
    int voiceLength() { return voiceLength; }

    private enum VoiceState {
        PLAYING,
        RELEASE_REQUESTED,
        RELEASED
    }
}

