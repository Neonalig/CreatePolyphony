package org.neonalig.createpolyphony.synth.meltysynth;

public final class SynthesizerSettings {
    static int DEFAULT_BLOCK_SIZE = 64;
    static int DEFAULT_MAXIMUM_POLYPHONY = 64;
    static boolean DEFAULT_ENABLE_REVERB_AND_CHORUS = true;

    private int sampleRate;
    private int blockSize;
    private int maximumPolyphony;
    private boolean enableReverbAndChorus;

    public SynthesizerSettings(int sampleRate) {
        checkSampleRate(sampleRate);
        this.sampleRate = sampleRate;
        this.blockSize = DEFAULT_BLOCK_SIZE;
        this.maximumPolyphony = DEFAULT_MAXIMUM_POLYPHONY;
        this.enableReverbAndChorus = DEFAULT_ENABLE_REVERB_AND_CHORUS;
    }

    private static void checkSampleRate(int value) {
        if (!(16000 <= value && value <= 192000)) {
            throw new IllegalArgumentException("The sample rate must be between 16000 and 192000.");
        }
    }

    private static void checkBlockSize(int value) {
        if (!(8 <= value && value <= 1024)) {
            throw new IllegalArgumentException("The block size must be between 8 and 1024.");
        }
    }

    private static void checkMaximumPolyphony(int value) {
        if (!(8 <= value && value <= 256)) {
            throw new IllegalArgumentException("The maximum number of polyphony must be between 8 and 256.");
        }
    }

    public int sampleRate() { return sampleRate; }
    public void sampleRate(int value) { checkSampleRate(value); sampleRate = value; }
    public int blockSize() { return blockSize; }
    public void blockSize(int value) { checkBlockSize(value); blockSize = value; }
    public int maximumPolyphony() { return maximumPolyphony; }
    public void maximumPolyphony(int value) { checkMaximumPolyphony(value); maximumPolyphony = value; }
    public boolean enableReverbAndChorus() { return enableReverbAndChorus; }
    public void enableReverbAndChorus(boolean value) { enableReverbAndChorus = value; }
}

