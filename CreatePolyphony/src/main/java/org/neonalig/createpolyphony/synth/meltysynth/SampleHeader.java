package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.DataInput;
import java.io.IOException;

public final class SampleHeader {
    static final SampleHeader DEFAULT = new SampleHeader();

    private final String name;
    private final int start;
    private final int end;
    private final int startLoop;
    private final int endLoop;
    private final int sampleRate;
    private final int originalPitch;
    private final int pitchCorrection;
    private final int link;
    private final SampleType type;

    private SampleHeader() {
        name = "Default";
        start = 0;
        end = 0;
        startLoop = 0;
        endLoop = 0;
        sampleRate = 0;
        originalPitch = 60;
        pitchCorrection = 0;
        link = 0;
        type = SampleType.MONO;
    }

    private SampleHeader(DataInput in) throws IOException {
        name = BinaryReaderEx.readFixedLengthString(in, 20);
        start = BinaryReaderEx.readInt32LE(in);
        end = BinaryReaderEx.readInt32LE(in);
        startLoop = BinaryReaderEx.readInt32LE(in);
        endLoop = BinaryReaderEx.readInt32LE(in);
        sampleRate = BinaryReaderEx.readInt32LE(in);
        originalPitch = in.readUnsignedByte();
        pitchCorrection = (byte) in.readUnsignedByte();
        link = BinaryReaderEx.readInt16LE(in);
        type = SampleType.fromValue(BinaryReaderEx.readInt16LE(in));
    }

    static SampleHeader[] readFromChunk(DataInput in, int size) throws IOException {
        if (size == 0 || size % 46 != 0) {
            throw new IOException("The sample header list is invalid.");
        }
        int count = size / 46;
        SampleHeader[] headers = new SampleHeader[count - 1];
        for (int i = 0; i < headers.length; i++) {
            headers[i] = new SampleHeader(in);
        }
        new SampleHeader(in);
        return headers;
    }

    @Override
    public String toString() {
        return name;
    }

    public String name() { return name; }
    public int start() { return start; }
    public int end() { return end; }
    public int startLoop() { return startLoop; }
    public int endLoop() { return endLoop; }
    public int sampleRate() { return sampleRate; }
    public int originalPitch() { return originalPitch; }
    public int pitchCorrection() { return pitchCorrection; }
    public int link() { return link; }
    public SampleType type() { return type; }
}

