package org.neonalig.createpolyphony.synth.meltysynth;

final class SoundFontMath {
    static final float HALF_PI = (float) (Math.PI / 2.0);
    static final float NON_AUDIBLE = 1.0E-3F;
    private static final double LOG_NON_AUDIBLE = Math.log(1.0E-3);

    private SoundFontMath() {}

    static float timecentsToSeconds(float value) {
        return (float) Math.pow(2.0, value / 1200.0);
    }

    static float centsToHertz(float value) {
        return (float) (8.176 * Math.pow(2.0, value / 1200.0));
    }

    static float centsToMultiplyingFactor(float value) {
        return (float) Math.pow(2.0, value / 1200.0);
    }

    static float decibelsToLinear(float value) {
        return (float) Math.pow(10.0, 0.05 * value);
    }

    static float linearToDecibels(float value) {
        return (float) (20.0 * Math.log10(value));
    }

    static float keyNumberToMultiplyingFactor(int cents, int key) {
        return timecentsToSeconds(cents * (60 - key));
    }

    static double expCutoff(double value) {
        if (value < LOG_NON_AUDIBLE) {
            return 0.0;
        }
        return Math.exp(value);
    }
}

