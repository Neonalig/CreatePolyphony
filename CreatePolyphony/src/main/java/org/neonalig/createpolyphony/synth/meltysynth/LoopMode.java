package org.neonalig.createpolyphony.synth.meltysynth;

public enum LoopMode {
    NO_LOOP(0),
    CONTINUOUS(1),
    LOOP_UNTIL_NOTE_OFF(3);

    private final int value;

    LoopMode(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    static LoopMode fromValue(int value) {
        return switch (value) {
            case 1 -> CONTINUOUS;
            case 3 -> LOOP_UNTIL_NOTE_OFF;
            default -> NO_LOOP;
        };
    }
}

