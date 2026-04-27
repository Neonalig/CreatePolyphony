package org.neonalig.createpolyphony.synth.meltysynth;

final class ModulationEnvelope {
    private final Synthesizer synthesizer;

    private double attackSlope;
    private double decaySlope;
    private double releaseSlope;
    private double attackStartTime;
    private double holdStartTime;
    private double decayStartTime;
    private double decayEndTime;
    private double releaseEndTime;
    private float sustainLevel;
    private float releaseLevel;
    private int processedSampleCount;
    private Stage stage;
    private float value;

    ModulationEnvelope(Synthesizer synthesizer) {
        this.synthesizer = synthesizer;
    }

    void start(float delay, float attack, float hold, float decay, float sustain, float release) {
        attackSlope = 1 / attack;
        decaySlope = 1 / decay;
        releaseSlope = 1 / release;
        attackStartTime = delay;
        holdStartTime = attackStartTime + attack;
        decayStartTime = holdStartTime + hold;
        decayEndTime = decayStartTime + decay;
        releaseEndTime = release;
        sustainLevel = Math.clamp(sustain, 0F, 1F);
        releaseLevel = 0F;
        processedSampleCount = 0;
        stage = Stage.DELAY;
        value = 0F;
        process(0);
    }

    void release() {
        stage = Stage.RELEASE;
        releaseEndTime += (double) processedSampleCount / synthesizer.sampleRate();
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
                return true;
            }
            case ATTACK -> {
                value = (float) (attackSlope * (currentTime - attackStartTime));
                return true;
            }
            case HOLD -> {
                value = 1F;
                return true;
            }
            case DECAY -> {
                value = Math.max((float) (decaySlope * (decayEndTime - currentTime)), sustainLevel);
                return value > SoundFontMath.NON_AUDIBLE;
            }
            case RELEASE -> {
                value = Math.max((float) (releaseLevel * releaseSlope * (releaseEndTime - currentTime)), 0F);
                return value > SoundFontMath.NON_AUDIBLE;
            }
            default -> throw new IllegalStateException("Invalid envelope stage.");
        }
    }

    float value() { return value; }

    private enum Stage {
        DELAY,
        ATTACK,
        HOLD,
        DECAY,
        RELEASE
    }
}

