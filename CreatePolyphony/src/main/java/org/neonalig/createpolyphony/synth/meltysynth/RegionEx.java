package org.neonalig.createpolyphony.synth.meltysynth;

final class RegionEx {
    private RegionEx() {}

    static void start(Oscillator oscillator, short[] data, InstrumentRegion region) {
        start(oscillator, data, new RegionPair(PresetRegion.DEFAULT, region));
    }

    static void start(Oscillator oscillator, short[] data, RegionPair region) {
        oscillator.start(
            data,
            instrumentLoopMode(region),
            region.sampleRate(),
            region.sampleStart(),
            region.sampleEnd(),
            region.sampleStartLoop(),
            region.sampleEndLoop(),
            region.rootKey(),
            region.coarseTune(),
            region.fineTune(),
            region.scaleTuning()
        );
    }

    static void start(VolumeEnvelope envelope, InstrumentRegion region, int key, int velocity) {
        start(envelope, new RegionPair(PresetRegion.DEFAULT, region), key, velocity);
    }

    static void start(VolumeEnvelope envelope, RegionPair region, int key, int velocity) {
        float delay = region.delayVolumeEnvelope();
        float attack = region.attackVolumeEnvelope();
        float hold = region.holdVolumeEnvelope() * SoundFontMath.keyNumberToMultiplyingFactor(region.keyNumberToVolumeEnvelopeHold(), key);
        float decay = region.decayVolumeEnvelope() * SoundFontMath.keyNumberToMultiplyingFactor(region.keyNumberToVolumeEnvelopeDecay(), key);
        float sustain = SoundFontMath.decibelsToLinear(-region.sustainVolumeEnvelope());
        float release = Math.max(region.releaseVolumeEnvelope(), 0.01F);
        envelope.start(delay, attack, hold, decay, sustain, release);
    }

    static void start(ModulationEnvelope envelope, InstrumentRegion region, int key, int velocity) {
        start(envelope, new RegionPair(PresetRegion.DEFAULT, region), key, velocity);
    }

    static void start(ModulationEnvelope envelope, RegionPair region, int key, int velocity) {
        float delay = region.delayModulationEnvelope();
        float attack = region.attackModulationEnvelope() * ((145 - velocity) / 144F);
        float hold = region.holdModulationEnvelope() * SoundFontMath.keyNumberToMultiplyingFactor(region.keyNumberToModulationEnvelopeHold(), key);
        float decay = region.decayModulationEnvelope() * SoundFontMath.keyNumberToMultiplyingFactor(region.keyNumberToModulationEnvelopeDecay(), key);
        float sustain = 1F - region.sustainModulationEnvelope() / 100F;
        float release = region.releaseModulationEnvelope();
        envelope.start(delay, attack, hold, decay, sustain, release);
    }

    static void startVibrato(Lfo lfo, InstrumentRegion region, int key, int velocity) {
        startVibrato(lfo, new RegionPair(PresetRegion.DEFAULT, region), key, velocity);
    }

    static void startVibrato(Lfo lfo, RegionPair region, int key, int velocity) {
        lfo.start(region.delayVibratoLfo(), region.frequencyVibratoLfo());
    }

    static void startModulation(Lfo lfo, InstrumentRegion region, int key, int velocity) {
        startModulation(lfo, new RegionPair(PresetRegion.DEFAULT, region), key, velocity);
    }

    static void startModulation(Lfo lfo, RegionPair region, int key, int velocity) {
        lfo.start(region.delayModulationLfo(), region.frequencyModulationLfo());
    }

    private static LoopMode instrumentLoopMode(RegionPair region) {
        return LoopMode.fromValue(region.loopMode());
    }
}

