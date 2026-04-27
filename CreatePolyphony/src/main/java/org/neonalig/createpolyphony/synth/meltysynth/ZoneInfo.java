package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.DataInput;
import java.io.IOException;

final class ZoneInfo {
    private final int generatorIndex;
    private final int modulatorIndex;
    private int generatorCount;
    private int modulatorCount;

    private ZoneInfo(DataInput in) throws IOException {
        generatorIndex = BinaryReaderEx.readInt16LE(in);
        modulatorIndex = BinaryReaderEx.readInt16LE(in);
    }

    static ZoneInfo[] readFromChunk(DataInput in, int size) throws IOException {
        if (size == 0 || size % 4 != 0) {
            throw new IOException("The zone list is invalid.");
        }
        int count = size / 4;
        ZoneInfo[] zones = new ZoneInfo[count];
        for (int i = 0; i < count; i++) {
            zones[i] = new ZoneInfo(in);
        }
        for (int i = 0; i < count - 1; i++) {
            zones[i].generatorCount = zones[i + 1].generatorIndex - zones[i].generatorIndex;
            zones[i].modulatorCount = zones[i + 1].modulatorIndex - zones[i].modulatorIndex;
        }
        return zones;
    }

    int generatorIndex() { return generatorIndex; }
    int modulatorIndex() { return modulatorIndex; }
    int generatorCount() { return generatorCount; }
    int modulatorCount() { return modulatorCount; }
}

