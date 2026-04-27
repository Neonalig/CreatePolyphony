package org.neonalig.createpolyphony.synth.meltysynth;

final class Lfo {
    private final Synthesizer synthesizer;

    private boolean active;
    private double delay;
    private double period;
    private int processedSampleCount;
    private float value;

    Lfo(Synthesizer synthesizer) {
        this.synthesizer = synthesizer;
    }

    void start(float delay, float frequency) {
        if (frequency > 1.0E-3F) {
            active = true;
            this.delay = delay;
            this.period = 1.0 / frequency;
            processedSampleCount = 0;
            value = 0F;
        } else {
            active = false;
            value = 0F;
        }
    }

    void process() {
        if (!active) {
            return;
        }
        processedSampleCount += synthesizer.blockSize();
        double currentTime = (double) processedSampleCount / synthesizer.sampleRate();
        if (currentTime < delay) {
            value = 0F;
        } else {
            double phase = ((currentTime - delay) % period) / period;
            if (phase < 0.25) {
                value = (float) (4 * phase);
            } else if (phase < 0.75) {
                value = (float) (4 * (0.5 - phase));
            } else {
                value = (float) (4 * (phase - 1.0));
            }
        }
    }

    float value() {
        return value;
    }
}

