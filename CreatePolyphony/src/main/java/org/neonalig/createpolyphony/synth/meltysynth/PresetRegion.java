package org.neonalig.createpolyphony.synth.meltysynth;

public final class PresetRegion {
    private final int[] gen;
    private final Instrument instrument;

    PresetRegion(int[] gen, Instrument instrument) {
        this.gen = gen;
        this.instrument = instrument;
    }

    public Instrument instrument() {
        return instrument;
    }

    public boolean contains(int key, int velocity) {
        int k = gen[GeneratorDefs.GEN_KEY_RANGE];
        int v = gen[GeneratorDefs.GEN_VEL_RANGE];
        int kLo = k & 0xFF;
        int kHi = (k >>> 8) & 0xFF;
        int vLo = v & 0xFF;
        int vHi = (v >>> 8) & 0xFF;
        return key >= kLo && key <= kHi && velocity >= vLo && velocity <= vHi;
    }

    int get(int op) {
        return op >= 0 && op < gen.length ? gen[op] : 0;
    }
}

