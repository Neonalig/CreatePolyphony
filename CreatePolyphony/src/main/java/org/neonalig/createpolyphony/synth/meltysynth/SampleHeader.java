package org.neonalig.createpolyphony.synth.meltysynth;

public record SampleHeader(
    String name,
    int start,
    int end,
    int startLoop,
    int endLoop,
    int sampleRate,
    int originalPitch,
    int pitchCorrection,
    int sampleType
) {
}

