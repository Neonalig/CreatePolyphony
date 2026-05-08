package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.IOException;
import java.io.RandomAccessFile;

public final class SoundFontInfo {
    private String bankName = "";
    private short versionMajor;
    private short versionMinor;

    SoundFontInfo(RandomAccessFile reader) throws IOException {
        String chunkId = BinaryReaderEx.readFourCC(reader);
        if (!"LIST".equals(chunkId)) {
            throw new IOException("The LIST chunk was not found.");
        }
        long end = Integer.toUnsignedLong(BinaryReaderEx.readInt32LE(reader)) + reader.getFilePointer();
        String listType = BinaryReaderEx.readFourCC(reader);
        if (!"INFO".equals(listType)) {
            throw new IOException("The type of the LIST chunk must be 'INFO', but was '" + listType + "'.");
        }
        while (reader.getFilePointer() < end) {
            String id = BinaryReaderEx.readFourCC(reader);
            int size = BinaryReaderEx.readInt32LE(reader);
            if ("ifil".equals(id)) {
                versionMajor = (short) BinaryReaderEx.readInt16LE(reader);
                versionMinor = (short) BinaryReaderEx.readInt16LE(reader);
                int remaining = size - 4;
                if (remaining > 0) {
                    reader.skipBytes(remaining);
                }
            } else if ("INAM".equals(id)) {
                bankName = BinaryReaderEx.readFixedLengthString(reader, size);
            } else {
                // All other INFO sub-chunks (version, sound engine, ROM info, author, etc.)
                // are not used by the synthesiser – skip the bytes to maintain correct offset.
                reader.skipBytes(size);
            }
            if ((size & 1) != 0) {
                reader.skipBytes(1);
            }
        }
    }

    @Override
    public String toString() { return bankName; }

    public String bankName() { return bankName; }
    @SuppressWarnings("unused")
    public String version() { return versionMajor + "." + versionMinor; }
}
