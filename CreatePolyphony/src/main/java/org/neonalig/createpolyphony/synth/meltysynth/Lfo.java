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
        value = 0F;
        if (frequency > 1.0E-3F) {
            active = true;
            this.delay = delay;
            this.period = 1.0 / frequency;
            processedSampleCount = 0;
        } else {
            active = false;
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
            double shape;
            if (phase < 0.25) {
                shape = phase;
            } else if (phase < 0.75) {
                shape = 0.5 - phase;
            } else {
                shape = phase - 1.0;
            }
            value = (float) (4 * shape);
        }
    }

    float value() {
        return value;
    }
}

