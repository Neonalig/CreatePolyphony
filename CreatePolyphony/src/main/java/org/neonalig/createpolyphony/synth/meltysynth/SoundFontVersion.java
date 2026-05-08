package org.neonalig.createpolyphony.synth.meltysynth;

import org.jetbrains.annotations.NotNull;

public record SoundFontVersion(short major, short minor) {
    @Override
    public @NotNull String toString() {
        return major + "." + minor;
    }
}

