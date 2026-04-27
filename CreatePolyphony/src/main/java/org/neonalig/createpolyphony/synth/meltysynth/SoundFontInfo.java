package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.IOException;
import java.io.RandomAccessFile;

public final class SoundFontInfo {
    private SoundFontVersion version = new SoundFontVersion((short) 0, (short) 0);
    private String targetSoundEngine = "";
    private String bankName = "";
    private String romName = "";
    private SoundFontVersion romVersion = new SoundFontVersion((short) 0, (short) 0);
    private String creationDate = "";
    private String author = "";
    private String targetProduct = "";
    private String copyright = "";
    private String comments = "";
    private String tools = "";

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
            switch (id) {
                case "ifil" -> version = new SoundFontVersion((short) BinaryReaderEx.readInt16LE(reader), (short) BinaryReaderEx.readInt16LE(reader));
                case "isng" -> targetSoundEngine = BinaryReaderEx.readFixedLengthString(reader, size);
                case "INAM" -> bankName = BinaryReaderEx.readFixedLengthString(reader, size);
                case "irom" -> romName = BinaryReaderEx.readFixedLengthString(reader, size);
                case "iver" -> romVersion = new SoundFontVersion((short) BinaryReaderEx.readInt16LE(reader), (short) BinaryReaderEx.readInt16LE(reader));
                case "ICRD" -> creationDate = BinaryReaderEx.readFixedLengthString(reader, size);
                case "IENG" -> author = BinaryReaderEx.readFixedLengthString(reader, size);
                case "IPRD" -> targetProduct = BinaryReaderEx.readFixedLengthString(reader, size);
                case "ICOP" -> copyright = BinaryReaderEx.readFixedLengthString(reader, size);
                case "ICMT" -> comments = BinaryReaderEx.readFixedLengthString(reader, size);
                case "ISFT" -> tools = BinaryReaderEx.readFixedLengthString(reader, size);
                default -> throw new IOException("The INFO list contains an unknown ID '" + id + "'.");
            }
            if ((size & 1) != 0) {
                reader.skipBytes(1);
            }
        }
    }

    @Override
    public String toString() { return bankName; }

    public SoundFontVersion version() { return version; }
    public String targetSoundEngine() { return targetSoundEngine; }
    public String bankName() { return bankName; }
    public String romName() { return romName; }
    public SoundFontVersion romVersion() { return romVersion; }
    public String creationDate() { return creationDate; }
    public String author() { return author; }
    public String targetProduct() { return targetProduct; }
    public String copyright() { return copyright; }
    public String comments() { return comments; }
    public String tools() { return tools; }
}

