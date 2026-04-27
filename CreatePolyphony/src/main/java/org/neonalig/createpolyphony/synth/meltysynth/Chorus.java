package org.neonalig.createpolyphony.synth.meltysynth;

import java.util.Arrays;

final class Chorus {
    private final float[] bufferL;
    private final float[] bufferR;
    private final float[] delayTable;
    private int bufferIndex;
    private int delayTableIndexL;
    private int delayTableIndexR;

    Chorus(int sampleRate, double delay, double depth, double frequency) {
        bufferL = new float[(int) (sampleRate * (delay + depth)) + 2];
        bufferR = new float[(int) (sampleRate * (delay + depth)) + 2];
        delayTable = new float[(int) Math.round(sampleRate / frequency)];
        for (int t = 0; t < delayTable.length; t++) {
            double phase = 2 * Math.PI * t / delayTable.length;
            delayTable[t] = (float) (sampleRate * (delay + depth * Math.sin(phase)));
        }
        bufferIndex = 0;
        delayTableIndexL = 0;
        delayTableIndexR = delayTable.length / 4;
    }

    void process(float[] inputLeft, float[] inputRight, float[] outputLeft, float[] outputRight) {
        for (int t = 0; t < outputLeft.length; t++) {
            double positionL = bufferIndex - (double) delayTable[delayTableIndexL];
            if (positionL < 0.0) positionL += bufferL.length;
            int index1L = (int) positionL;
            int index2L = index1L + 1;
            if (index2L == bufferL.length) index2L = 0;
            double aL = positionL - index1L;
            outputLeft[t] = (float) (bufferL[index1L] + aL * (bufferL[index2L] - bufferL[index1L]));
            delayTableIndexL = (delayTableIndexL + 1) % delayTable.length;

            double positionR = bufferIndex - (double) delayTable[delayTableIndexR];
            if (positionR < 0.0) positionR += bufferR.length;
            int index1R = (int) positionR;
            int index2R = index1R + 1;
            if (index2R == bufferR.length) index2R = 0;
            double aR = positionR - index1R;
            outputRight[t] = (float) (bufferR[index1R] + aR * (bufferR[index2R] - bufferR[index1R]));
            delayTableIndexR = (delayTableIndexR + 1) % delayTable.length;

            bufferL[bufferIndex] = inputLeft[t];
            bufferR[bufferIndex] = inputRight[t];
            bufferIndex++;
            if (bufferIndex == bufferL.length) {
                bufferIndex = 0;
            }
        }
    }

    void mute() {
        Arrays.fill(bufferL, 0F);
        Arrays.fill(bufferR, 0F);
    }
}

