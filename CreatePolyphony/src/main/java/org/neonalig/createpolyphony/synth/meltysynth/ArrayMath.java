package org.neonalig.createpolyphony.synth.meltysynth;

final class ArrayMath {
    private ArrayMath() {}

    static void multiplyAdd(float a, float[] x, float[] destination) {
        for (int i = 0; i < destination.length; i++) {
            destination[i] += a * x[i];
        }
    }

    static void multiplyAdd(float a, float step, float[] x, float[] destination) {
        for (int i = 0; i < destination.length; i++) {
            destination[i] += a * x[i];
            a += step;
        }
    }
}

