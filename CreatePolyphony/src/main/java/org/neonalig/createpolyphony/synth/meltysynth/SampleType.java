package org.neonalig.createpolyphony.synth.meltysynth;

public enum SampleType {
    MONO(1),
    RIGHT(2),
    LEFT(4),
    LINKED(8),
    ROM_MONO(0x8001),
    ROM_RIGHT(0x8002),
    ROM_LEFT(0x8004),
    ROM_LINKED(0x8008);

    private final int value;

    SampleType(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static SampleType fromValue(int value) {
        for (SampleType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        return MONO;
    }
}

