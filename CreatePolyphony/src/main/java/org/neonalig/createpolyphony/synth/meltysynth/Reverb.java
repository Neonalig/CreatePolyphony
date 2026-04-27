package org.neonalig.createpolyphony.synth.meltysynth;

import java.util.Arrays;

final class Reverb {
    private static final float FIXED_GAIN = 0.015F;
    private static final float SCALE_WET = 3F;
    private static final float SCALE_DAMP = 0.4F;
    private static final float SCALE_ROOM = 0.28F;
    private static final float OFFSET_ROOM = 0.7F;
    private static final float INITIAL_ROOM = 0.5F;
    private static final float INITIAL_DAMP = 0.5F;
    private static final float INITIAL_WET = 1F / SCALE_WET;
    private static final float INITIAL_WIDTH = 1F;
    private static final int STEREO_SPREAD = 23;

    private final CombFilter[] cfsL;
    private final CombFilter[] cfsR;
    private final AllPassFilter[] apfsL;
    private final AllPassFilter[] apfsR;

    private float gain;
    private float roomSize;
    private float roomSize1;
    private float damp;
    private float damp1;
    private float wet;
    private float wet1;
    private float wet2;
    private float width;

    Reverb(int sampleRate) {
        cfsL = new CombFilter[] {
            new CombFilter(scaleTuning(sampleRate, 1116)),
            new CombFilter(scaleTuning(sampleRate, 1188)),
            new CombFilter(scaleTuning(sampleRate, 1277)),
            new CombFilter(scaleTuning(sampleRate, 1356)),
            new CombFilter(scaleTuning(sampleRate, 1422)),
            new CombFilter(scaleTuning(sampleRate, 1491)),
            new CombFilter(scaleTuning(sampleRate, 1557)),
            new CombFilter(scaleTuning(sampleRate, 1617))
        };
        cfsR = new CombFilter[] {
            new CombFilter(scaleTuning(sampleRate, 1116 + STEREO_SPREAD)),
            new CombFilter(scaleTuning(sampleRate, 1188 + STEREO_SPREAD)),
            new CombFilter(scaleTuning(sampleRate, 1277 + STEREO_SPREAD)),
            new CombFilter(scaleTuning(sampleRate, 1356 + STEREO_SPREAD)),
            new CombFilter(scaleTuning(sampleRate, 1422 + STEREO_SPREAD)),
            new CombFilter(scaleTuning(sampleRate, 1491 + STEREO_SPREAD)),
            new CombFilter(scaleTuning(sampleRate, 1557 + STEREO_SPREAD)),
            new CombFilter(scaleTuning(sampleRate, 1617 + STEREO_SPREAD))
        };
        apfsL = new AllPassFilter[] {
            new AllPassFilter(scaleTuning(sampleRate, 556)),
            new AllPassFilter(scaleTuning(sampleRate, 441)),
            new AllPassFilter(scaleTuning(sampleRate, 341)),
            new AllPassFilter(scaleTuning(sampleRate, 225))
        };
        apfsR = new AllPassFilter[] {
            new AllPassFilter(scaleTuning(sampleRate, 556 + STEREO_SPREAD)),
            new AllPassFilter(scaleTuning(sampleRate, 441 + STEREO_SPREAD)),
            new AllPassFilter(scaleTuning(sampleRate, 341 + STEREO_SPREAD)),
            new AllPassFilter(scaleTuning(sampleRate, 225 + STEREO_SPREAD))
        };
        for (AllPassFilter apf : apfsL) apf.feedback(0.5F);
        for (AllPassFilter apf : apfsR) apf.feedback(0.5F);
        wet(INITIAL_WET);
        roomSize(INITIAL_ROOM);
        damp(INITIAL_DAMP);
        width(INITIAL_WIDTH);
    }

    private int scaleTuning(int sampleRate, int tuning) {
        return (int) Math.round((double) sampleRate / 44100.0 * tuning);
    }

    void process(float[] input, float[] outputLeft, float[] outputRight) {
        Arrays.fill(outputLeft, 0F);
        Arrays.fill(outputRight, 0F);
        for (CombFilter cf : cfsL) cf.process(input, outputLeft);
        for (AllPassFilter apf : apfsL) apf.process(outputLeft);
        for (CombFilter cf : cfsR) cf.process(input, outputRight);
        for (AllPassFilter apf : apfsR) apf.process(outputRight);
        if (1F - wet1 > 1.0E-3F || wet2 > 1.0E-3F) {
            for (int t = 0; t < input.length; t++) {
                float left = outputLeft[t];
                float right = outputRight[t];
                outputLeft[t] = left * wet1 + right * wet2;
                outputRight[t] = right * wet1 + left * wet2;
            }
        }
    }

    void mute() {
        for (CombFilter cf : cfsL) cf.mute();
        for (CombFilter cf : cfsR) cf.mute();
        for (AllPassFilter apf : apfsL) apf.mute();
        for (AllPassFilter apf : apfsR) apf.mute();
    }

    private void update() {
        wet1 = wet * (width / 2F + 0.5F);
        wet2 = wet * ((1F - width) / 2F);
        roomSize1 = roomSize;
        damp1 = damp;
        gain = FIXED_GAIN;
        for (CombFilter cf : cfsL) {
            cf.feedback(roomSize1);
            cf.damp(damp1);
        }
        for (CombFilter cf : cfsR) {
            cf.feedback(roomSize1);
            cf.damp(damp1);
        }
    }

    float inputGain() { return gain; }
    float roomSize() { return (roomSize - OFFSET_ROOM) / SCALE_ROOM; }
    void roomSize(float value) { roomSize = value * SCALE_ROOM + OFFSET_ROOM; update(); }
    float damp() { return damp / SCALE_DAMP; }
    void damp(float value) { damp = value * SCALE_DAMP; update(); }
    float wet() { return wet / SCALE_WET; }
    void wet(float value) { wet = value * SCALE_WET; update(); }
    float width() { return width; }
    void width(float value) { width = value; update(); }

    private static final class CombFilter {
        private final float[] buffer;
        private int bufferIndex;
        private float filterStore;
        private float feedback;
        private float damp1;
        private float damp2;

        private CombFilter(int bufferSize) {
            buffer = new float[bufferSize];
        }

        private void mute() {
            Arrays.fill(buffer, 0F);
            filterStore = 0F;
        }

        private void process(float[] inputBlock, float[] outputBlock) {
            int blockIndex = 0;
            while (blockIndex < outputBlock.length) {
                if (bufferIndex == buffer.length) bufferIndex = 0;
                int srcRem = buffer.length - bufferIndex;
                int dstRem = outputBlock.length - blockIndex;
                int rem = Math.min(srcRem, dstRem);
                for (int t = 0; t < rem; t++) {
                    int blockPos = blockIndex + t;
                    int bufferPos = bufferIndex + t;
                    float input = inputBlock[blockPos];
                    float output = buffer[bufferPos];
                    if (Math.abs(output) < 1.0E-6F) output = 0F;
                    filterStore = output * damp2 + filterStore * damp1;
                    if (Math.abs(filterStore) < 1.0E-6F) filterStore = 0F;
                    buffer[bufferPos] = input + filterStore * feedback;
                    outputBlock[blockPos] += output;
                }
                bufferIndex += rem;
                blockIndex += rem;
            }
        }

        private void feedback(float value) { feedback = value; }
        private void damp(float value) { damp1 = value; damp2 = 1F - value; }
    }

    private static final class AllPassFilter {
        private final float[] buffer;
        private int bufferIndex;
        private float feedback;

        private AllPassFilter(int bufferSize) {
            buffer = new float[bufferSize];
        }

        private void mute() { Arrays.fill(buffer, 0F); }

        private void process(float[] block) {
            int blockIndex = 0;
            while (blockIndex < block.length) {
                if (bufferIndex == buffer.length) bufferIndex = 0;
                int srcRem = buffer.length - bufferIndex;
                int dstRem = block.length - blockIndex;
                int rem = Math.min(srcRem, dstRem);
                for (int t = 0; t < rem; t++) {
                    int blockPos = blockIndex + t;
                    int bufferPos = bufferIndex + t;
                    float input = block[blockPos];
                    float bufout = buffer[bufferPos];
                    if (Math.abs(bufout) < 1.0E-6F) bufout = 0F;
                    block[blockPos] = bufout - input;
                    buffer[bufferPos] = input + bufout * feedback;
                }
                bufferIndex += rem;
                blockIndex += rem;
            }
        }

        private void feedback(float value) { feedback = value; }
    }
}

