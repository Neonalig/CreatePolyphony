package org.neonalig.createpolyphony.synth.meltysynth;

final class VolumeEnvelope {
    private final Synthesizer synthesizer;

    private double attackSlope;
    private double decaySlope;
    private double releaseSlope;
    private double attackStartTime;
    private double holdStartTime;
    private double decayStartTime;
    private double releaseStartTime;
    private float sustainLevel;
    private float releaseLevel;
    private int processedSampleCount;
    private Stage stage;
    private float value;
    private float priority;

    VolumeEnvelope(Synthesizer synthesizer) {
        this.synthesizer = synthesizer;
    }

    void start(float delay, float attack, float hold, float decay, float sustain, float release) {
        attackSlope = 1 / attack;
        decaySlope = -9.226 / decay;
        releaseSlope = -9.226 / release;
        attackStartTime = delay;
        holdStartTime = attackStartTime + attack;
        decayStartTime = holdStartTime + hold;
        releaseStartTime = 0;
        sustainLevel = Math.clamp(sustain, 0F, 1F);
        releaseLevel = 0F;
        processedSampleCount = 0;
        stage = Stage.DELAY;
        value = 0F;
        process(0);
    }

    void release() {
        stage = Stage.RELEASE;
        releaseStartTime = (double) processedSampleCount / synthesizer.sampleRate();
        releaseLevel = value;
    }

    boolean process() {
        return process(synthesizer.blockSize());
    }

    private boolean process(int sampleCount) {
        processedSampleCount += sampleCount;
        double currentTime = (double) processedSampleCount / synthesizer.sampleRate();
        while (stage.ordinal() <= Stage.HOLD.ordinal()) {
            double endTime = switch (stage) {
                case DELAY -> attackStartTime;
                case ATTACK -> holdStartTime;
                case HOLD -> decayStartTime;
                default -> throw new IllegalStateException("Invalid envelope stage.");
            };
            if (currentTime < endTime) {
                break;
            } else {
                stage = Stage.values()[stage.ordinal() + 1];
            }
        }

        switch (stage) {
            case DELAY -> {
                value = 0F;
                priority = 4F + value;
                return true;
            }
            case ATTACK -> {
                value = (float) (attackSlope * (currentTime - attackStartTime));
                priority = 3F + value;
                return true;
            }
            case HOLD -> {
                value = 1F;
                priority = 2F + value;
                return true;
            }
            case DECAY -> {
                value = Math.max((float) SoundFontMath.expCutoff(decaySlope * (currentTime - decayStartTime)), sustainLevel);
                priority = 1F + value;
                return value > SoundFontMath.NON_AUDIBLE;
            }
            case RELEASE -> {
                value = (float) (releaseLevel * SoundFontMath.expCutoff(releaseSlope * (currentTime - releaseStartTime)));
                priority = value;
                return value > SoundFontMath.NON_AUDIBLE;
            }
            default -> throw new IllegalStateException("Invalid envelope stage.");
        }
    }

    float value() { return value; }
    float priority() { return priority; }

    private enum Stage {
        DELAY,
        ATTACK,
        HOLD,
        DECAY,
        RELEASE
    }
}

