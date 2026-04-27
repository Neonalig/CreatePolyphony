package org.neonalig.createpolyphony.synth.meltysynth;

final class Lfo {
    private boolean active;
    private double delay;
    private double period;
    private double invSampleRate;
    private double t;
    float value;

    void start(float delaySeconds, float frequencyHz, float sampleRate) {
        if (frequencyHz <= 1.0E-3F) {
            active = false;
            value = 0F;
            t = 0.0;
            return;
        }
        active = true;
        delay = Math.max(0.0, delaySeconds);
        period = 1.0 / Math.max(1.0E-4, frequencyHz);
        invSampleRate = 1.0 / Math.max(1.0, sampleRate);
        t = 0.0;
        value = 0F;
    }

    void advance(int samples) {
        if (!active) return;
        t += samples * invSampleRate;
        if (t < delay) {
            value = 0F;
            return;
        }
        double phase = ((t - delay) % period) / period;
        if (phase < 0.25) {
            value = (float) (4.0 * phase);
        } else if (phase < 0.75) {
            value = (float) (4.0 * (0.5 - phase));
        } else {
            value = (float) (4.0 * (phase - 1.0));
        }
    }
}

