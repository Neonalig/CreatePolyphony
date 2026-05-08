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
    public int keyRangeStart() { return get(GeneratorType.KEY_RANGE) & 0xFF; }
    public int keyRangeEnd() { return (get(GeneratorType.KEY_RANGE) >> 8) & 0xFF; }
    public int velocityRangeStart() { return get(GeneratorType.VELOCITY_RANGE) & 0xFF; }
    public int velocityRangeEnd() { return (get(GeneratorType.VELOCITY_RANGE) >> 8) & 0xFF; }
}
