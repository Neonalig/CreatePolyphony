package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.IOException;

public final class InstrumentRegion {

    private final short[] gs;
    private SampleHeader sample;

    private InstrumentRegion() {
        gs = new short[61];
        gs[GeneratorType.INITIAL_FILTER_CUTOFF_FREQUENCY.value()] = 13_500;
        gs[GeneratorType.DELAY_MODULATION_LFO.value()] = -12_000;
        gs[GeneratorType.DELAY_VIBRATO_LFO.value()] = -12_000;
        gs[GeneratorType.DELAY_MODULATION_ENVELOPE.value()] = -12_000;
        gs[GeneratorType.ATTACK_MODULATION_ENVELOPE.value()] = -12_000;
        gs[GeneratorType.HOLD_MODULATION_ENVELOPE.value()] = -12_000;
        gs[GeneratorType.DECAY_MODULATION_ENVELOPE.value()] = -12_000;
        gs[GeneratorType.RELEASE_MODULATION_ENVELOPE.value()] = -12_000;
        gs[GeneratorType.DELAY_VOLUME_ENVELOPE.value()] = -12_000;
        gs[GeneratorType.ATTACK_VOLUME_ENVELOPE.value()] = -12_000;
        gs[GeneratorType.HOLD_VOLUME_ENVELOPE.value()] = -12_000;
        gs[GeneratorType.DECAY_VOLUME_ENVELOPE.value()] = -12_000;
        gs[GeneratorType.RELEASE_VOLUME_ENVELOPE.value()] = -12_000;
        gs[GeneratorType.KEY_RANGE.value()] = (short) 0x7F00;
        gs[GeneratorType.VELOCITY_RANGE.value()] = (short) 0x7F00;
        gs[GeneratorType.KEY_NUMBER.value()] = -1;
        gs[GeneratorType.VELOCITY.value()] = -1;
        gs[GeneratorType.SCALE_TUNING.value()] = 100;
        gs[GeneratorType.OVERRIDING_ROOT_KEY.value()] = -1;
        sample = SampleHeader.DEFAULT;
    }

    private InstrumentRegion(Instrument instrument, Zone global, Zone local, SampleHeader[] samples) throws IOException {
        this();
        for (Generator generator : global.generators()) {
            setParameter(generator);
        }
        for (Generator generator : local.generators()) {
            setParameter(generator);
        }
        int id = gs[GeneratorType.SAMPLE_ID.value()];
        if (id < 0 || id >= samples.length) {
            throw new IOException("The instrument '" + instrument.name() + "' contains an invalid sample ID '" + id + "'.");
        }
        sample = samples[id];
    }

    static InstrumentRegion[] create(Instrument instrument, Zone[] zones, SampleHeader[] samples) throws IOException {
        boolean firstIsGlobal = zones[0].generators().length == 0
            || zones[0].generators()[zones[0].generators().length - 1].type() != GeneratorType.SAMPLE_ID;

        if (firstIsGlobal) {
            Zone global = zones[0];
            InstrumentRegion[] regions = new InstrumentRegion[zones.length - 1];
            for (int i = 0; i < regions.length; i++) {
                regions[i] = new InstrumentRegion(instrument, global, zones[i + 1], samples);
            }
            return regions;
        }

        InstrumentRegion[] regions = new InstrumentRegion[zones.length];
        for (int i = 0; i < regions.length; i++) {
            regions[i] = new InstrumentRegion(instrument, Zone.EMPTY, zones[i], samples);
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
        return sample.name() + " (Key: " + keyRangeStart() + "-" + keyRangeEnd()
            + ", Velocity: " + velocityRangeStart() + "-" + velocityRangeEnd() + ")";
    }

    short get(GeneratorType type) { return gs[type.value()]; }

    public SampleHeader sample() { return sample; }
    public int sampleStart() { return sample.start() + startAddressOffset(); }
    public int sampleEnd() { return sample.end() + endAddressOffset(); }
    public int sampleStartLoop() { return sample.startLoop() + startLoopAddressOffset(); }
    public int sampleEndLoop() { return sample.endLoop() + endLoopAddressOffset(); }
    public int startAddressOffset() { return 32768 * get(GeneratorType.START_ADDRESS_COARSE_OFFSET) + get(GeneratorType.START_ADDRESS_OFFSET); }
    public int endAddressOffset() { return 32768 * get(GeneratorType.END_ADDRESS_COARSE_OFFSET) + get(GeneratorType.END_ADDRESS_OFFSET); }
    public int startLoopAddressOffset() { return 32768 * get(GeneratorType.START_LOOP_ADDRESS_COARSE_OFFSET) + get(GeneratorType.START_LOOP_ADDRESS_OFFSET); }
    public int endLoopAddressOffset() { return 32768 * get(GeneratorType.END_LOOP_ADDRESS_COARSE_OFFSET) + get(GeneratorType.END_LOOP_ADDRESS_OFFSET); }
    public int keyRangeStart() { return get(GeneratorType.KEY_RANGE) & 0xFF; }
    public int keyRangeEnd() { return (get(GeneratorType.KEY_RANGE) >> 8) & 0xFF; }
    public int velocityRangeStart() { return get(GeneratorType.VELOCITY_RANGE) & 0xFF; }
    public int velocityRangeEnd() { return (get(GeneratorType.VELOCITY_RANGE) >> 8) & 0xFF; }
    public LoopMode sampleModes() { return get(GeneratorType.SAMPLE_MODES) != 2 ? LoopMode.fromValue(get(GeneratorType.SAMPLE_MODES)) : LoopMode.NO_LOOP; }
    public int exclusiveClass() { return get(GeneratorType.EXCLUSIVE_CLASS); }
    public int rootKey() { return get(GeneratorType.OVERRIDING_ROOT_KEY) != -1 ? get(GeneratorType.OVERRIDING_ROOT_KEY) : sample.originalPitch(); }
}

