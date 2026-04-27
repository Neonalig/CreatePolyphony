package org.neonalig.createpolyphony.synth.meltysynth;

public final class AudioRendererEx {
    private AudioRendererEx() {}

    public static void renderInterleaved(IAudioRenderer renderer, float[] destination) {
        if ((destination.length & 1) != 0) {
            throw new IllegalArgumentException("The length of the destination buffer must be even.");
        }
        int sampleCount = destination.length / 2;
        float[] left = new float[sampleCount];
        float[] right = new float[sampleCount];
        renderer.render(left, right, 0, sampleCount);
        int pos = 0;
        for (int t = 0; t < sampleCount; t++) {
            destination[pos++] = left[t];
            destination[pos++] = right[t];
        }
    }

    public static void renderMono(IAudioRenderer renderer, float[] destination) {
        int sampleCount = destination.length;
        float[] left = new float[sampleCount];
        float[] right = new float[sampleCount];
        renderer.render(left, right, 0, sampleCount);
        for (int t = 0; t < sampleCount; t++) {
            destination[t] = (left[t] + right[t]) / 2F;
        }
    }

    public static void renderInt16(IAudioRenderer renderer, short[] left, short[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("The output buffers for the left and right must be the same length.");
        }
        int sampleCount = left.length;
        float[] bufLeft = new float[sampleCount];
        float[] bufRight = new float[sampleCount];
        renderer.render(bufLeft, bufRight, 0, sampleCount);
        for (int t = 0; t < sampleCount; t++) {
            left[t] = quantizeToInt16(bufLeft[t]);
            right[t] = quantizeToInt16(bufRight[t]);
        }
    }

    public static void renderInterleavedInt16(IAudioRenderer renderer, short[] destination) {
        if ((destination.length & 1) != 0) {
            throw new IllegalArgumentException("The length of the destination buffer must be even.");
        }
        int sampleCount = destination.length / 2;
        float[] left = new float[sampleCount];
        float[] right = new float[sampleCount];
        renderer.render(left, right, 0, sampleCount);
        int pos = 0;
        for (int t = 0; t < sampleCount; t++) {
            destination[pos++] = quantizeToInt16(left[t]);
            destination[pos++] = quantizeToInt16(right[t]);
        }
    }

    public static void renderMonoInt16(IAudioRenderer renderer, short[] destination) {
        int sampleCount = destination.length;
        float[] left = new float[sampleCount];
        float[] right = new float[sampleCount];
        renderer.render(left, right, 0, sampleCount);
        for (int t = 0; t < sampleCount; t++) {
            destination[t] = quantizeToInt16(0.5F * (left[t] + right[t]));
        }
    }

    private static short quantizeToInt16(float sample) {
        int quantized = (int) (32768 * sample);
        if (quantized < Short.MIN_VALUE) quantized = Short.MIN_VALUE;
        if (quantized > Short.MAX_VALUE) quantized = Short.MAX_VALUE;
        return (short) quantized;
    }
}

