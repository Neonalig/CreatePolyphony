package org.neonalig.createpolyphony.synth.meltysynth;

final class Envelope {
    private static final float NON_AUDIBLE = 1.0E-4F;

    private final boolean exponential;

    private float sampleRate;
    private double t;
    private Stage stage;
    float value;
    private float priority;

    private double attackSlope;
    private double decaySlope;
    private double releaseSlope;
    private double attackStart;
    private double holdStart;
    private double decayStart;
    private double releaseStart;
    private float sustain;
    private float releaseLevel;

    Envelope(boolean exponential) {
        this.exponential = exponential;
    }

    void start(float delay, float attack, float hold, float decay, float sustain, float release, float sampleRate) {
        this.sampleRate = sampleRate;
        this.attackSlope = 1.0 / Math.max(1.0E-5, attack);
        if (exponential) {
            this.decaySlope = -9.226 / Math.max(1.0E-5, decay);
            this.releaseSlope = -9.226 / Math.max(1.0E-5, release);
        } else {
            this.decaySlope = 1.0 / Math.max(1.0E-5, decay);
            this.releaseSlope = 1.0 / Math.max(1.0E-5, release);
        }
        this.attackStart = Math.max(0.0, delay);
        this.holdStart = this.attackStart + Math.max(0.0, attack);
        this.decayStart = this.holdStart + Math.max(0.0, hold);
        this.releaseStart = 0.0;
        this.sustain = Math.max(0F, Math.min(1F, sustain));
        this.releaseLevel = 0F;
        this.t = 0.0;
        this.stage = Stage.DELAY;
        this.value = 0F;
        this.priority = 4F;
    }

    void release() {
        if (stage == Stage.RELEASE) return;
        stage = Stage.RELEASE;
        releaseStart = t;
        releaseLevel = value;
    }

    boolean advance(int samples) {
        t += samples / sampleRate;
        while (stage.ordinal() <= Stage.HOLD.ordinal()) {
            double endTime = switch (stage) {
                case DELAY -> attackStart;
                case ATTACK -> holdStart;
                case HOLD -> decayStart;
                default -> decayStart;
            };
            if (t < endTime) break;
            stage = Stage.values()[stage.ordinal() + 1];
        }

        switch (stage) {
            case DELAY -> {
                value = 0F;
                priority = 4F;
                return true;
            }
            case ATTACK -> {
                value = (float) (attackSlope * (t - attackStart));
                priority = 3F + value;
                return true;
            }
            case HOLD -> {
                value = 1F;
                priority = 2F + value;
                return true;
            }
            case DECAY -> {
                if (exponential) {
                    double x = decaySlope * (t - decayStart);
                    value = Math.max((float) Math.exp(Math.max(x, -100.0)), sustain);
                } else {
                    double decayEnd = decayStart + (1.0 / Math.max(decaySlope, 1.0E-8));
                    value = Math.max((float) (decaySlope * (decayEnd - t)), sustain);
                }
                priority = 1F + value;
                return value > NON_AUDIBLE;
            }
            case RELEASE -> {
                if (exponential) {
                    value = (float) (releaseLevel * Math.exp(Math.max(releaseSlope * (t - releaseStart), -100.0)));
                } else {
                    double endTime = releaseStart + (1.0 / Math.max(releaseSlope, 1.0E-8));
                    value = Math.max((float) (releaseLevel * releaseSlope * (endTime - t)), 0F);
                }
                priority = value;
                return value > NON_AUDIBLE;
            }
        }
        return false;
    }

    float priority() {
        return priority;
    }

    private enum Stage {
        DELAY,
        ATTACK,
        HOLD,
        DECAY,
        RELEASE
    }
}

