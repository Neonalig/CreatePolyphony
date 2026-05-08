package org.neonalig.createpolyphony.synth.meltysynth;

/**
 * Rendering interface implemented by {@link Synthesizer}.
 * The concrete type is always used directly; this interface exists as a
 * structural contract / documentation only.
 */
public interface IAudioRenderer {
    @SuppressWarnings("unused") // Implemented by Synthesizer; interface kept as structural contract.
    void render(float[] left, float[] right, int offset, int length);
}
