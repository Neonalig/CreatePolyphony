package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.File;
import java.io.IOException;
import java.util.function.IntConsumer;

public final class MeltySoundFont {
    private final SoundFont soundFont;

    private MeltySoundFont(SoundFont soundFont) {
        this.soundFont = soundFont;
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
        return new MeltySoundFont(new SoundFont(file));
    }

    public static MeltySoundFont load(File file, IntConsumer progressCallback) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("SoundFont file not found: " + file);
        }
        return new MeltySoundFont(new SoundFont(file, progressCallback));
    }

    SoundFont soundFont() {
        return soundFont;
    }
}
