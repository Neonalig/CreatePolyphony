package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.DataInput;
import java.io.EOFException;
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

    static short readInt16BigEndian(DataInput in) throws IOException {
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        return (short) ((b1 << 8) | b2);
    }

    static int readInt32BigEndian(DataInput in) throws IOException {
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        int b4 = in.readUnsignedByte();
        return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
    }

    static int readIntVariableLength(DataInput in) throws IOException {
        int acc = 0;
        int count = 0;
        while (true) {
            int value = in.readUnsignedByte();
            acc = (acc << 7) | (value & 127);
            if ((value & 128) == 0) {
                return acc;
            }
            count++;
            if (count == 4) {
                throw new EOFException("The length of the value must be equal to or less than 4.");
            }
        }
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

