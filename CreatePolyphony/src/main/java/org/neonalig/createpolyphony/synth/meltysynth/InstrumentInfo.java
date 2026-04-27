package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.DataInput;
import java.io.IOException;

final class InstrumentInfo {
    private final String name;
    private final int zoneStartIndex;
    private int zoneEndIndex;

    private InstrumentInfo(DataInput in) throws IOException {
        name = BinaryReaderEx.readFixedLengthString(in, 20);
        zoneStartIndex = BinaryReaderEx.readInt16LE(in);
    }

    static InstrumentInfo[] readFromChunk(DataInput in, int size) throws IOException {
        if (size == 0 || size % 22 != 0) {
            throw new IOException("The instrument list is invalid.");
        }
        int count = size / 22;
        InstrumentInfo[] instruments = new InstrumentInfo[count];
        for (int i = 0; i < count; i++) {
            instruments[i] = new InstrumentInfo(in);
        }
        for (int i = 0; i < count - 1; i++) {
            instruments[i].zoneEndIndex = instruments[i + 1].zoneStartIndex - 1;
        }
        return instruments;
    }

    String name() { return name; }
    int zoneStartIndex() { return zoneStartIndex; }
    int zoneEndIndex() { return zoneEndIndex; }
}

