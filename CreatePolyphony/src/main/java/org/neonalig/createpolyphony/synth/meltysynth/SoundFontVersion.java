package org.neonalig.createpolyphony.synth.meltysynth;

public record SoundFontVersion(short major, short minor) {
    @Override
    public String toString() {
        return major + "." + minor;
    }
}

