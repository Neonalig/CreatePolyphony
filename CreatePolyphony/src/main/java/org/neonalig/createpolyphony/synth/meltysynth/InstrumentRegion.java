package org.neonalig.createpolyphony.synth.meltysynth;

public final class InstrumentRegion {
    private final int[] gen;
    private final SampleHeader sample;

    InstrumentRegion(int[] gen, SampleHeader sample) {
        this.gen = gen;
        this.sample = sample;
    }

    public SampleHeader sample() {
        return sample;
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

