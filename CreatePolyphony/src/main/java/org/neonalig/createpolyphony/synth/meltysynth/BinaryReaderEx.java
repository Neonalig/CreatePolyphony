package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.DataInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class BinaryReaderEx {
    private BinaryReaderEx() {}

    static String readFourCC(DataInput in) throws IOException {
        byte[] data = new byte[4];
        in.readFully(data);
        for (int i = 0; i < data.length; i++) {
            int value = data[i] & 0xFF;
            if (value < 32 || value > 126) {
                data[i] = (byte) '?';
            }
        }
        return new String(data, StandardCharsets.US_ASCII);
    }

    static String readFixedLengthString(DataInput in, int length) throws IOException {
        byte[] data = new byte[length];
        in.readFully(data);
        int actualLength = 0;
        while (actualLength < data.length && data[actualLength] != 0) {
            actualLength++;
        }
        return new String(data, 0, actualLength, StandardCharsets.US_ASCII);
    }


    static int readInt16LE(DataInput in) throws IOException {
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        return b1 | (b2 << 8);
    }

    static int readInt32LE(DataInput in) throws IOException {
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        int b4 = in.readUnsignedByte();
        return b1 | (b2 << 8) | (b3 << 16) | (b4 << 24);
    }
}

