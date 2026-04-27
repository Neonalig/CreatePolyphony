package org.neonalig.createpolyphony.synth.meltysynth;

public record RegionPair(PresetRegion preset, InstrumentRegion instrument) {

    public int g(int op) {
        return instrument.get(GeneratorType.fromValue(op)) + preset.get(GeneratorType.fromValue(op));
    }

    public int sampleStart() {
        return instrument.sampleStart();
    }

    public int sampleEnd() {
        return instrument.sampleEnd();
    }

    public int sampleStartLoop() {
        return instrument.sampleStartLoop();
    }

    public int sampleEndLoop() {
        return instrument.sampleEndLoop();
    }

    public int loopMode() {
        return instrument.sampleModes().value();
    }

    public int rootKey() {
        return instrument.rootKey();
    }

    public int coarseTune() { return g(GeneratorType.COARSE_TUNE.value()); }

    public int fineTune() { return g(GeneratorType.FINE_TUNE.value()) + instrument.sample().pitchCorrection(); }

    public int scaleTuning() { return g(GeneratorType.SCALE_TUNING.value()); }

    public float initialAttenuationDb() { return 0.1F * g(GeneratorType.INITIAL_ATTENUATION.value()); }

    public float pan() { return 0.1F * g(GeneratorType.PAN.value()); }

    public float initialFilterCutoffFrequency() { return SoundFontMath.centsToHertz(g(GeneratorType.INITIAL_FILTER_CUTOFF_FREQUENCY.value())); }
    public float initialFilterQ() { return 0.1F * g(GeneratorType.INITIAL_FILTER_Q.value()); }
    public int modulationLfoToFilterCutoffFrequency() { return g(GeneratorType.MODULATION_LFO_TO_FILTER_CUTOFF_FREQUENCY.value()); }
    public int modulationEnvelopeToFilterCutoffFrequency() { return g(GeneratorType.MODULATION_ENVELOPE_TO_FILTER_CUTOFF_FREQUENCY.value()); }
    public float modulationLfoToVolume() { return 0.1F * g(GeneratorType.MODULATION_LFO_TO_VOLUME.value()); }
    public float chorusEffectsSend() { return 0.1F * g(GeneratorType.CHORUS_EFFECTS_SEND.value()); }
    public float reverbEffectsSend() { return 0.1F * g(GeneratorType.REVERB_EFFECTS_SEND.value()); }
    public float delayModulationLfo() { return SoundFontMath.timecentsToSeconds(g(GeneratorType.DELAY_MODULATION_LFO.value())); }
    public float frequencyModulationLfo() { return SoundFontMath.centsToHertz(g(GeneratorType.FREQUENCY_MODULATION_LFO.value())); }
    public float delayVibratoLfo() { return SoundFontMath.timecentsToSeconds(g(GeneratorType.DELAY_VIBRATO_LFO.value())); }
    public float frequencyVibratoLfo() { return SoundFontMath.centsToHertz(g(GeneratorType.FREQUENCY_VIBRATO_LFO.value())); }
    public float delayModulationEnvelope() { return SoundFontMath.timecentsToSeconds(g(GeneratorType.DELAY_MODULATION_ENVELOPE.value())); }
    public float attackModulationEnvelope() { return SoundFontMath.timecentsToSeconds(g(GeneratorType.ATTACK_MODULATION_ENVELOPE.value())); }
    public float holdModulationEnvelope() { return SoundFontMath.timecentsToSeconds(g(GeneratorType.HOLD_MODULATION_ENVELOPE.value())); }
    public float decayModulationEnvelope() { return SoundFontMath.timecentsToSeconds(g(GeneratorType.DECAY_MODULATION_ENVELOPE.value())); }
    public float sustainModulationEnvelope() { return 0.1F * g(GeneratorType.SUSTAIN_MODULATION_ENVELOPE.value()); }
    public float releaseModulationEnvelope() { return SoundFontMath.timecentsToSeconds(g(GeneratorType.RELEASE_MODULATION_ENVELOPE.value())); }
    public int keyNumberToModulationEnvelopeHold() { return g(GeneratorType.KEY_NUMBER_TO_MODULATION_ENVELOPE_HOLD.value()); }
    public int keyNumberToModulationEnvelopeDecay() { return g(GeneratorType.KEY_NUMBER_TO_MODULATION_ENVELOPE_DECAY.value()); }
    public float delayVolumeEnvelope() { return SoundFontMath.timecentsToSeconds(g(GeneratorType.DELAY_VOLUME_ENVELOPE.value())); }
    public float attackVolumeEnvelope() { return SoundFontMath.timecentsToSeconds(g(GeneratorType.ATTACK_VOLUME_ENVELOPE.value())); }
    public float holdVolumeEnvelope() { return SoundFontMath.timecentsToSeconds(g(GeneratorType.HOLD_VOLUME_ENVELOPE.value())); }
    public float decayVolumeEnvelope() { return SoundFontMath.timecentsToSeconds(g(GeneratorType.DECAY_VOLUME_ENVELOPE.value())); }
    public float sustainVolumeEnvelope() { return 0.1F * g(GeneratorType.SUSTAIN_VOLUME_ENVELOPE.value()); }
    public float releaseVolumeEnvelope() { return SoundFontMath.timecentsToSeconds(g(GeneratorType.RELEASE_VOLUME_ENVELOPE.value())); }
    public int keyNumberToVolumeEnvelopeHold() { return g(GeneratorType.KEY_NUMBER_TO_VOLUME_ENVELOPE_HOLD.value()); }
    public int keyNumberToVolumeEnvelopeDecay() { return g(GeneratorType.KEY_NUMBER_TO_VOLUME_ENVELOPE_DECAY.value()); }
    public int vibLfoToPitchCents() { return g(GeneratorType.VIBRATO_LFO_TO_PITCH.value()); }
    public int modLfoToPitchCents() { return g(GeneratorType.MODULATION_LFO_TO_PITCH.value()); }
    public int modEnvToPitchCents() { return g(GeneratorType.MODULATION_ENVELOPE_TO_PITCH.value()); }
    public float modLfoToVolumeDb() { return 0.1F * g(GeneratorType.MODULATION_LFO_TO_VOLUME.value()); }
    public int sampleRate() { return instrument.sample().sampleRate(); }
    public int keyRangeStart() { return g(GeneratorType.KEY_RANGE.value()) & 0xFF; }
    public int keyRangeEnd() { return (g(GeneratorType.KEY_RANGE.value()) >> 8) & 0xFF; }
    public int velocityRangeStart() { return g(GeneratorType.VELOCITY_RANGE.value()) & 0xFF; }
    public int velocityRangeEnd() { return (g(GeneratorType.VELOCITY_RANGE.value()) >> 8) & 0xFF; }
    public int exclusiveClass() { return instrument.exclusiveClass(); }
}

