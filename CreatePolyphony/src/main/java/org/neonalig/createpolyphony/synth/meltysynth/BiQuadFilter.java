package org.neonalig.createpolyphony.synth.meltysynth;

final class BiQuadFilter {
    private static final float RESONANCE_PEAK_OFFSET = 1 - (float) (1 / Math.sqrt(2.0));

    private final Synthesizer synthesizer;
    private boolean active;
    private float a0;
    private float a1;
    private float a2;
    private float a3;
    private float a4;
    private float x1;
    private float x2;
    private float y1;
    private float y2;

    BiQuadFilter(Synthesizer synthesizer) {
        this.synthesizer = synthesizer;
    }

    void clearBuffer() {
        x1 = 0F;
        x2 = 0F;
        y1 = 0F;
        y2 = 0F;
    }

    void setLowPassFilter(float cutoffFrequency, float resonance) {
        if (cutoffFrequency < 0.499F * synthesizer.sampleRate()) {
            active = true;
            float q = resonance - RESONANCE_PEAK_OFFSET / (1 + 6 * (resonance - 1));
            float w = (float) (2 * Math.PI * cutoffFrequency / synthesizer.sampleRate());
            float cosw = (float) Math.cos(w);
            float alpha = (float) Math.sin(w) / (2 * q);
            float b0 = (1 - cosw) / 2;
            float b1 = 1 - cosw;
            float b2 = (1 - cosw) / 2;
            float aa0 = 1 + alpha;
            float aa1 = -2 * cosw;
            float aa2 = 1 - alpha;
            setCoefficients(aa0, aa1, aa2, b0, b1, b2);
        } else {
            active = false;
        }
    }

    void process(float[] block) {
        if (active) {
            for (int t = 0; t < block.length; t++) {
                float input = block[t];
                float output = a0 * input + a1 * x1 + a2 * x2 - a3 * y1 - a4 * y2;
                x2 = x1;
                x1 = input;
                y2 = y1;
                y1 = output;
                block[t] = output;
            }
        } else {
            if (block.length >= 2) {
                x2 = block[block.length - 2];
                x1 = block[block.length - 1];
                y2 = x2;
                y1 = x1;
            }
        }
    }

    private void setCoefficients(float a0, float a1, float a2, float b0, float b1, float b2) {
        this.a0 = b0 / a0;
        this.a1 = b1 / a0;
        this.a2 = b2 / a0;
        this.a3 = a1 / a0;
        this.a4 = a2 / a0;
    }
}

