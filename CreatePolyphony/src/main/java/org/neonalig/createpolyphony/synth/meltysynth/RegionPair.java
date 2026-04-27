package org.neonalig.createpolyphony.synth.meltysynth;

public record RegionPair(PresetRegion preset, InstrumentRegion instrument) {

    public int g(int op) {
        return preset.get(op) + instrument.get(op);
    }

    public int sampleStart() {
        return instrument.sample().start() + g(0) + (32768 * g(4));
    }

    public int sampleEnd() {
        return instrument.sample().end() + g(1) + (32768 * g(12));
    }

    public int sampleStartLoop() {
        return instrument.sample().startLoop() + g(2) + (32768 * g(45));
    }

    public int sampleEndLoop() {
        return instrument.sample().endLoop() + g(3) + (32768 * g(50));
    }

    public int loopMode() {
        int mode = g(54);
        return mode == 2 ? 0 : mode;
    }

    public int rootKey() {
        int override = instrument.get(58);
        return override >= 0 ? override : instrument.sample().originalPitch();
    }

    public int coarseTune() { return g(51); }

    public int fineTune() { return g(52) + instrument.sample().pitchCorrection(); }

    public int scaleTuning() { return g(56); }

    public float initialAttenuationDb() { return 0.1F * g(48); }

    public float pan() { return 0.1F * g(17); }

    public float ampEnvDelay() { return timecentsToSeconds(g(33)); }
    public float ampEnvAttack() { return timecentsToSeconds(g(34)); }
    public float ampEnvHold() { return timecentsToSeconds(g(35)); }
    public float ampEnvDecay() { return timecentsToSeconds(g(36)); }
    public float ampEnvSustain() { return clamp01(1F - (0.001F * g(37))); }
    public float ampEnvRelease() { return timecentsToSeconds(g(38)); }

    public float modEnvDelay() { return timecentsToSeconds(g(25)); }
    public float modEnvAttack() { return timecentsToSeconds(g(26)); }
    public float modEnvHold() { return timecentsToSeconds(g(27)); }
    public float modEnvDecay() { return timecentsToSeconds(g(28)); }
    public float modEnvSustain() { return clamp01(1F - (0.001F * g(29))); }
    public float modEnvRelease() { return timecentsToSeconds(g(30)); }

    public float vibLfoDelay() { return timecentsToSeconds(g(23)); }
    public float vibLfoFreq() { return centsToHertz(g(24)); }
    public float modLfoDelay() { return timecentsToSeconds(g(21)); }
    public float modLfoFreq() { return centsToHertz(g(22)); }

    public int vibLfoToPitchCents() { return g(6); }
    public int modLfoToPitchCents() { return g(5); }
    public int modEnvToPitchCents() { return g(7); }
    public float modLfoToVolumeDb() { return 0.1F * g(13); }

    public int sampleRate() { return instrument.sample().sampleRate(); }

    private static float clamp01(float v) {
        return Math.max(0F, Math.min(1F, v));
    }

    private static float timecentsToSeconds(float tc) {
        return (float) Math.pow(2.0, tc / 1200.0);
    }

    private static float centsToHertz(float cents) {
        return (float) (8.176 * Math.pow(2.0, cents / 1200.0));
    }
}

