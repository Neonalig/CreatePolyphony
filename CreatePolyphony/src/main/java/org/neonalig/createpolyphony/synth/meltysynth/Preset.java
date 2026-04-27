package org.neonalig.createpolyphony.synth.meltysynth;

public record Preset(String name, int program, int bank, PresetRegion[] regions) {
}

