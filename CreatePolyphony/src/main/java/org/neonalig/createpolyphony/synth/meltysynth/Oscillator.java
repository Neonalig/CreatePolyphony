package org.neonalig.createpolyphony.synth.meltysynth;

final class Oscillator {
    private static final int FRAC_BITS = 24;
    private static final long FRAC_UNIT = 1L << FRAC_BITS;
    private static final float FP_TO_SAMPLE = 1F / (32768 * FRAC_UNIT);

    private final Synthesizer synthesizer;

    private short[] data;
    private LoopMode loopMode;
    private int sampleRate;
    private int start;
    private int end;
    private int startLoop;
    private int endLoop;
    private int rootKey;
    private float tune;
    private float pitchChangeScale;
    private float sampleRateRatio;
    private boolean looping;
    private long positionFp;

    Oscillator(Synthesizer synthesizer) {
        this.synthesizer = synthesizer;
    }

    void start(short[] data, LoopMode loopMode, int sampleRate, int start, int end, int startLoop, int endLoop,
               int rootKey, int coarseTune, int fineTune, int scaleTuning) {
        this.data = data;
        this.loopMode = loopMode;
        this.sampleRate = sampleRate;
        // Clamp start/end defensively within data bounds to avoid AIOOBE on malformed SF2 files.
        int maxIdx = data.length - 1;
        this.start = Math.max(0, Math.min(start, maxIdx));
        this.end = Math.max(1, Math.min(end, maxIdx));
        // Loop points may be bogus in non-spec-compliant fonts; clamp and fall back to no-loop if invalid.
        int clampedStartLoop = Math.max(this.start, Math.min(startLoop, this.end - 1));
        int clampedEndLoop = Math.max(clampedStartLoop + 1, Math.min(endLoop, this.end));
        if (clampedStartLoop >= clampedEndLoop) {
            // Loop window collapsed - force no-loop so the sample plays once and exits cleanly.
            this.startLoop = this.start;
            this.endLoop = this.end;
            this.loopMode = LoopMode.NO_LOOP;
        } else {
            this.startLoop = clampedStartLoop;
            this.endLoop = clampedEndLoop;
        }
        this.rootKey = rootKey;
        this.tune = coarseTune + 0.01F * fineTune;
        this.pitchChangeScale = 0.01F * scaleTuning;
        this.sampleRateRatio = (float) sampleRate / synthesizer.sampleRate();
        this.looping = this.loopMode != LoopMode.NO_LOOP;
        this.positionFp = (long) start << FRAC_BITS;
    }

    void release() {
        if (loopMode == LoopMode.LOOP_UNTIL_NOTE_OFF) {
            looping = false;
        }
    }

    boolean process(float[] block, float pitch) {
        float pitchChange = pitchChangeScale * (pitch - rootKey) + tune;
        float pitchRatio = (float) (sampleRateRatio * Math.pow(2.0, pitchChange / 12.0));
        return fillBlock(block, pitchRatio);
    }

    private boolean fillBlock(float[] block, double pitchRatio) {
        long pitchRatioFp = (long) (FRAC_UNIT * pitchRatio);
        return looping ? fillBlockContinuous(block, pitchRatioFp) : fillBlockNoLoop(block, pitchRatioFp);
    }

    private boolean fillBlockNoLoop(float[] block, long pitchRatioFp) {
        for (int t = 0; t < block.length; t++) {
            long index = positionFp >> FRAC_BITS;
            if (index >= end) {
                if (t > 0) {
                    java.util.Arrays.fill(block, t, block.length, 0F);
                    return true;
                }
                return false;
            }
            short x1 = data[(int) index];
            short x2 = data[(int) index + 1];
            long aFp = positionFp & (FRAC_UNIT - 1);
            block[t] = FP_TO_SAMPLE * (((long) x1 << FRAC_BITS) + aFp * (x2 - x1));
            positionFp += pitchRatioFp;
        }
        return true;
    }

    private boolean fillBlockContinuous(float[] block, long pitchRatioFp) {
        long endLoopFp = (long) endLoop << FRAC_BITS;
        long loopLength = endLoop - startLoop;
        long loopLengthFp = loopLength << FRAC_BITS;
        for (int t = 0; t < block.length; t++) {
            if (positionFp >= endLoopFp) {
                positionFp -= loopLengthFp;
            }
            long index1 = positionFp >> FRAC_BITS;
            long index2 = index1 + 1;
            if (index2 >= endLoop) {
                index2 -= loopLength;
            }
            short x1 = data[(int) index1];
            short x2 = data[(int) index2];
            long aFp = positionFp & (FRAC_UNIT - 1);
            block[t] = FP_TO_SAMPLE * (((long) x1 << FRAC_BITS) + aFp * (x2 - x1));
            positionFp += pitchRatioFp;
        }
        return true;
    }
}

