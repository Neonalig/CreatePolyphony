package org.neonalig.createpolyphony.synth.meltysynth;

public interface IAudioRenderer {
    void render(float[] left, float[] right, int offset, int length);
}

