package org.neonalig.createpolyphony.synth.meltysynth;

final class Voice {
    private static final float NON_AUDIBLE = 1.0E-4F;
    private static final float BEND_RANGE_SEMITONES = 2.0F;

    int channel;
    int note;
    int velocity;
    long startedAt;

    private short[] data;
    private int start;
    private int end;
    private int loopStart;
    private int loopEnd;
    private int loopMode;
    private int rootKey;
    private float tune;
    private float scaleTuning;
    private float sampleRateRatio;
    private double position;
    private boolean looping;

    private float baseGain;
    private float voicePan;

    private int vibToPitch;
    private int modLfoToPitch;
    private int modEnvToPitch;
    private float modLfoToVolume;

    private final Envelope ampEnv = new Envelope(true);
    private final Envelope modEnv = new Envelope(false);
    private final Lfo vibLfo = new Lfo();
    private final Lfo modLfo = new Lfo();

    private boolean releaseRequested;
    boolean heldBySustain;

    void start(MeltySoundFont sf,
               int channel,
               int note,
               int velocity,
               RegionPair pair,
               float outputSampleRate,
               long startedAt) {
        this.channel = channel;
        this.note = note;
        this.velocity = velocity;
        this.startedAt = startedAt;
        this.releaseRequested = false;
        this.heldBySustain = false;

        this.data = sf.waveData();
        this.start = Math.max(0, pair.sampleStart());
        this.end = Math.min(data.length - 2, Math.max(this.start + 1, pair.sampleEnd()));
        this.loopStart = Math.max(this.start, Math.min(this.end - 1, pair.sampleStartLoop()));
        this.loopEnd = Math.max(this.loopStart + 1, Math.min(this.end, pair.sampleEndLoop()));
        this.loopMode = pair.loopMode();
        this.rootKey = pair.rootKey();
        this.tune = pair.coarseTune() + (0.01F * pair.fineTune());
        this.scaleTuning = 0.01F * pair.scaleTuning();
        this.sampleRateRatio = Math.max(1F, pair.sampleRate()) / outputSampleRate;
        this.position = this.start;
        this.looping = (loopMode != 0);

        float velDb = (velocity > 0) ? (float) (40.0 * Math.log10(velocity / 127.0)) : -120F;
        float gainDb = velDb - (0.4F * pair.initialAttenuationDb());
        this.baseGain = dbToLinear(gainDb);
        this.voicePan = Math.max(-50F, Math.min(50F, pair.pan()));

        this.vibToPitch = pair.vibLfoToPitchCents();
        this.modLfoToPitch = pair.modLfoToPitchCents();
        this.modEnvToPitch = pair.modEnvToPitchCents();
        this.modLfoToVolume = pair.modLfoToVolumeDb();

        this.ampEnv.start(pair.ampEnvDelay(), pair.ampEnvAttack(), pair.ampEnvHold(), pair.ampEnvDecay(), pair.ampEnvSustain(), pair.ampEnvRelease(), outputSampleRate);
        this.modEnv.start(pair.modEnvDelay(), pair.modEnvAttack(), pair.modEnvHold(), pair.modEnvDecay(), pair.modEnvSustain(), pair.modEnvRelease(), outputSampleRate);
        this.vibLfo.start(pair.vibLfoDelay(), pair.vibLfoFreq(), outputSampleRate);
        this.modLfo.start(pair.modLfoDelay(), pair.modLfoFreq(), outputSampleRate);
    }

    void requestRelease() {
        releaseRequested = true;
        if (loopMode == 3) {
            looping = false;
        }
    }

    float priority() {
        return ampEnv.priority();
    }

    boolean renderInto(ChannelState channel, float[] left, float[] right, int frames) {
        if (releaseRequested && !channel.sustain) {
            ampEnv.release();
            modEnv.release();
            if (loopMode == 3) looping = false;
            releaseRequested = false;
        }

        if (!ampEnv.advance(frames) || !modEnv.advance(frames)) {
            return false;
        }
        vibLfo.advance(frames);
        modLfo.advance(frames);

        float pitchCents = (0.01F * vibToPitch * vibLfo.value)
            + (0.01F * modLfoToPitch * modLfo.value)
            + (0.01F * modEnvToPitch * modEnv.value);
        float pitchSemis = (scaleTuning * (note - rootKey)) + tune + pitchCents + bendSemitones(channel.pitchBend);
        double ratio = sampleRateRatio * Math.pow(2.0, pitchSemis / 12.0);

        float channelGain = channel.outputGain;
        channelGain *= channelGain;

        float mixGain = baseGain * channelGain * ampEnv.value;
        if (Math.abs(modLfoToVolume) > 0.05F) {
            mixGain *= dbToLinear(modLfoToVolume * modLfo.value);
        }
        if (mixGain < NON_AUDIBLE) {
            return ampEnv.value > NON_AUDIBLE;
        }

        float pan = Math.max(-50F, Math.min(50F, channel.pan + voicePan));
        float angle = (float) (Math.PI * (pan + 50F) / 200F);
        float gainL = mixGain * (float) Math.cos(angle);
        float gainR = mixGain * (float) Math.sin(angle);

        for (int i = 0; i < frames; i++) {
            int i1 = (int) position;
            if (!looping && i1 >= end) {
                return ampEnv.value > NON_AUDIBLE;
            }

            int i2 = i1 + 1;
            if (looping) {
                if (position >= loopEnd) {
                    position -= (loopEnd - loopStart);
                    i1 = (int) position;
                    i2 = i1 + 1;
                }
                if (i2 >= loopEnd) {
                    i2 = loopStart;
                }
            } else {
                if (i2 > end) i2 = end;
            }

            float frac = (float) (position - i1);
            float s1 = data[i1] / 32768F;
            float s2 = data[i2] / 32768F;
            float sample = s1 + (s2 - s1) * frac;

            left[i] += sample * gainL;
            right[i] += sample * gainR;
            position += ratio;
        }

        return ampEnv.value > NON_AUDIBLE;
    }

    private static float dbToLinear(float db) {
        return (float) Math.pow(10.0, 0.05 * db);
    }

    private static float bendSemitones(int bendValue) {
        float normalized = (bendValue - 8192) / 8192F;
        return normalized * BEND_RANGE_SEMITONES;
    }
}

