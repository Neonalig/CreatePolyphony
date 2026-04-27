package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.IOException;

public final class PresetRegion {
    static final PresetRegion DEFAULT = new PresetRegion();

    private final short[] gs;
    private Instrument instrument;

    private PresetRegion() {
        gs = new short[61];
        gs[GeneratorType.KEY_RANGE.value()] = (short) 0x7F00;
        gs[GeneratorType.VELOCITY_RANGE.value()] = (short) 0x7F00;
        instrument = Instrument.DEFAULT;
    }

    private PresetRegion(Preset preset, Zone global, Zone local, Instrument[] instruments) throws IOException {
        this();
        for (Generator generator : global.generators()) {
            setParameter(generator);
        }
        for (Generator generator : local.generators()) {
            setParameter(generator);
        }
        int id = gs[GeneratorType.INSTRUMENT.value()];
        if (id < 0 || id >= instruments.length) {
            throw new IOException("The preset '" + preset.name() + "' contains an invalid instrument ID '" + id + "'.");
        }
        instrument = instruments[id];
    }

    static PresetRegion[] create(Preset preset, Zone[] zones, Instrument[] instruments) throws IOException {
        boolean firstIsGlobal = zones[0].generators().length == 0
            || zones[0].generators()[zones[0].generators().length - 1].type() != GeneratorType.INSTRUMENT;
        if (firstIsGlobal) {
            Zone global = zones[0];
            PresetRegion[] regions = new PresetRegion[zones.length - 1];
            for (int i = 0; i < regions.length; i++) {
                regions[i] = new PresetRegion(preset, global, zones[i + 1], instruments);
            }
            return regions;
        }
        PresetRegion[] regions = new PresetRegion[zones.length];
        for (int i = 0; i < regions.length; i++) {
            regions[i] = new PresetRegion(preset, Zone.EMPTY, zones[i], instruments);
        }
        return regions;
    }

    private void setParameter(Generator generator) {
        int index = generator.type().value();
        if (0 <= index && index < gs.length) {
            gs[index] = generator.value();
        }
    }

    public boolean contains(int key, int velocity) {
        return keyRangeStart() <= key && key <= keyRangeEnd()
            && velocityRangeStart() <= velocity && velocity <= velocityRangeEnd();
    }

    @Override
    public String toString() {
        return instrument.name() + " (Key: " + keyRangeStart() + "-" + keyRangeEnd()
            + ", Velocity: " + velocityRangeStart() + "-" + velocityRangeEnd() + ")";
    }

    short get(GeneratorType type) { return gs[type.value()]; }
    public Instrument instrument() { return instrument; }
    public int modulationLfoToPitch() { return get(GeneratorType.MODULATION_LFO_TO_PITCH); }
    public int vibratoLfoToPitch() { return get(GeneratorType.VIBRATO_LFO_TO_PITCH); }
    public int modulationEnvelopeToPitch() { return get(GeneratorType.MODULATION_ENVELOPE_TO_PITCH); }
    public float initialFilterCutoffFrequency() { return SoundFontMath.centsToMultiplyingFactor(get(GeneratorType.INITIAL_FILTER_CUTOFF_FREQUENCY)); }
    public float initialFilterQ() { return 0.1F * get(GeneratorType.INITIAL_FILTER_Q); }
    public int modulationLfoToFilterCutoffFrequency() { return get(GeneratorType.MODULATION_LFO_TO_FILTER_CUTOFF_FREQUENCY); }
    public int modulationEnvelopeToFilterCutoffFrequency() { return get(GeneratorType.MODULATION_ENVELOPE_TO_FILTER_CUTOFF_FREQUENCY); }
    public float modulationLfoToVolume() { return 0.1F * get(GeneratorType.MODULATION_LFO_TO_VOLUME); }
    public float chorusEffectsSend() { return 0.1F * get(GeneratorType.CHORUS_EFFECTS_SEND); }
    public float reverbEffectsSend() { return 0.1F * get(GeneratorType.REVERB_EFFECTS_SEND); }
    public float pan() { return 0.1F * get(GeneratorType.PAN); }
    public float delayModulationLfo() { return SoundFontMath.centsToMultiplyingFactor(get(GeneratorType.DELAY_MODULATION_LFO)); }
    public float frequencyModulationLfo() { return SoundFontMath.centsToMultiplyingFactor(get(GeneratorType.FREQUENCY_MODULATION_LFO)); }
    public float delayVibratoLfo() { return SoundFontMath.centsToMultiplyingFactor(get(GeneratorType.DELAY_VIBRATO_LFO)); }
    public float frequencyVibratoLfo() { return SoundFontMath.centsToMultiplyingFactor(get(GeneratorType.FREQUENCY_VIBRATO_LFO)); }
    public float delayModulationEnvelope() { return SoundFontMath.centsToMultiplyingFactor(get(GeneratorType.DELAY_MODULATION_ENVELOPE)); }
    public float attackModulationEnvelope() { return SoundFontMath.centsToMultiplyingFactor(get(GeneratorType.ATTACK_MODULATION_ENVELOPE)); }
    public float holdModulationEnvelope() { return SoundFontMath.centsToMultiplyingFactor(get(GeneratorType.HOLD_MODULATION_ENVELOPE)); }
    public float decayModulationEnvelope() { return SoundFontMath.centsToMultiplyingFactor(get(GeneratorType.DECAY_MODULATION_ENVELOPE)); }
    public float sustainModulationEnvelope() { return 0.1F * get(GeneratorType.SUSTAIN_MODULATION_ENVELOPE); }
    public float releaseModulationEnvelope() { return SoundFontMath.centsToMultiplyingFactor(get(GeneratorType.RELEASE_MODULATION_ENVELOPE)); }
    public int keyNumberToModulationEnvelopeHold() { return get(GeneratorType.KEY_NUMBER_TO_MODULATION_ENVELOPE_HOLD); }
    public int keyNumberToModulationEnvelopeDecay() { return get(GeneratorType.KEY_NUMBER_TO_MODULATION_ENVELOPE_DECAY); }
    public float delayVolumeEnvelope() { return SoundFontMath.centsToMultiplyingFactor(get(GeneratorType.DELAY_VOLUME_ENVELOPE)); }
    public float attackVolumeEnvelope() { return SoundFontMath.centsToMultiplyingFactor(get(GeneratorType.ATTACK_VOLUME_ENVELOPE)); }
    public float holdVolumeEnvelope() { return SoundFontMath.centsToMultiplyingFactor(get(GeneratorType.HOLD_VOLUME_ENVELOPE)); }
    public float decayVolumeEnvelope() { return SoundFontMath.centsToMultiplyingFactor(get(GeneratorType.DECAY_VOLUME_ENVELOPE)); }
    public float sustainVolumeEnvelope() { return 0.1F * get(GeneratorType.SUSTAIN_VOLUME_ENVELOPE); }
    public float releaseVolumeEnvelope() { return SoundFontMath.centsToMultiplyingFactor(get(GeneratorType.RELEASE_VOLUME_ENVELOPE)); }
    public int keyNumberToVolumeEnvelopeHold() { return get(GeneratorType.KEY_NUMBER_TO_VOLUME_ENVELOPE_HOLD); }
    public int keyNumberToVolumeEnvelopeDecay() { return get(GeneratorType.KEY_NUMBER_TO_VOLUME_ENVELOPE_DECAY); }
    public int keyRangeStart() { return get(GeneratorType.KEY_RANGE) & 0xFF; }
    public int keyRangeEnd() { return (get(GeneratorType.KEY_RANGE) >> 8) & 0xFF; }
    public int velocityRangeStart() { return get(GeneratorType.VELOCITY_RANGE) & 0xFF; }
    public int velocityRangeEnd() { return (get(GeneratorType.VELOCITY_RANGE) >> 8) & 0xFF; }
    public float initialAttenuation() { return 0.1F * get(GeneratorType.INITIAL_ATTENUATION); }
    public int coarseTune() { return get(GeneratorType.COARSE_TUNE); }
    public int fineTune() { return get(GeneratorType.FINE_TUNE); }
    public int scaleTuning() { return get(GeneratorType.SCALE_TUNING); }
}

