package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.DataInput;
import java.io.IOException;

final class PresetInfo {
    private final String name;
    private final int patchNumber;
    private final int bankNumber;
    private final int zoneStartIndex;
    private int zoneEndIndex;
    private final int library;
    private final int genre;
    private final int morphology;

    private PresetInfo(DataInput in) throws IOException {
        name = BinaryReaderEx.readFixedLengthString(in, 20);
        patchNumber = BinaryReaderEx.readInt16LE(in);
        bankNumber = BinaryReaderEx.readInt16LE(in);
        zoneStartIndex = BinaryReaderEx.readInt16LE(in);
        library = BinaryReaderEx.readInt32LE(in);
        genre = BinaryReaderEx.readInt32LE(in);
        morphology = BinaryReaderEx.readInt32LE(in);
    }

    static PresetInfo[] readFromChunk(DataInput in, int size) throws IOException {
        if (size == 0 || size % 38 != 0) {
            throw new IOException("The preset list is invalid.");
        }
        int count = size / 38;
        PresetInfo[] presets = new PresetInfo[count];
        for (int i = 0; i < count; i++) {
            presets[i] = new PresetInfo(in);
        }
        for (int i = 0; i < count - 1; i++) {
            presets[i].zoneEndIndex = presets[i + 1].zoneStartIndex - 1;
        }
        return presets;
    }

    String name() { return name; }
    int patchNumber() { return patchNumber; }
    int bankNumber() { return bankNumber; }
    int zoneStartIndex() { return zoneStartIndex; }
    int zoneEndIndex() { return zoneEndIndex; }
    int library() { return library; }
    int genre() { return genre; }
    int morphology() { return morphology; }
}

