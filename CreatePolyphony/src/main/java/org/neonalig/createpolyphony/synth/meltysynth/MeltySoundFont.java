package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.File;
import java.io.IOException;

public final class MeltySoundFont {
    private final File sourceFile;
    private final SoundFont soundFont;

    private MeltySoundFont(File sourceFile, SoundFont soundFont) {
        this.sourceFile = sourceFile;
        this.soundFont = soundFont;
    }

    public File sourceFile() {
        return sourceFile;
    }

    public int presetCount() {
        return soundFont.presetArray().length;
    }

    public int instrumentCount() {
        return soundFont.instrumentArray().length;
    }

    public int sampleCount() {
        return soundFont.sampleHeaderArray().length;
    }

    public static MeltySoundFont load(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("SoundFont file not found: " + file);
        }
        return new MeltySoundFont(file, new SoundFont(file));
    }

    SoundFont soundFont() {
        return soundFont;
    }
}
