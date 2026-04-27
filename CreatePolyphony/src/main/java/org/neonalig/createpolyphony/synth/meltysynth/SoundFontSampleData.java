package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.function.IntConsumer;

final class SoundFontSampleData {
    private final int bitsPerSample;
    private final short[] samples;

    private static final int READ_CHUNK_BYTES = 65_536;

    SoundFontSampleData(RandomAccessFile reader, IntConsumer progressCallback) throws IOException {
        String chunkId = BinaryReaderEx.readFourCC(reader);
        if (!"LIST".equals(chunkId)) {
            throw new IOException("The LIST chunk was not found.");
        }
        long end = Integer.toUnsignedLong(BinaryReaderEx.readInt32LE(reader)) + reader.getFilePointer();
        String listType = BinaryReaderEx.readFourCC(reader);
        if (!"sdta".equals(listType)) {
            throw new IOException("The type of the LIST chunk must be 'sdta', but was '" + listType + "'.");
        }

        int localBitsPerSample = 0;
        short[] localSamples = null;

        while (reader.getFilePointer() < end) {
            String id = BinaryReaderEx.readFourCC(reader);
            int size = BinaryReaderEx.readInt32LE(reader);
            switch (id) {
                case "smpl" -> {
                    localBitsPerSample = 16;
                    int sampleCount = size / 2;
                    localSamples = new short[sampleCount];
                    // Bulk-read in large chunks: avoids millions of per-byte RandomAccessFile calls
                    // and enables genuine byte-level progress reporting.
                    byte[] buf = new byte[READ_CHUNK_BYTES];
                    int written = 0;
                    int remaining = size;
                    while (remaining > 0) {
                        int toRead = Math.min(READ_CHUNK_BYTES, remaining);
                        if ((toRead & 1) != 0) toRead--;           // keep read frame-aligned to shorts
                        if (toRead <= 0) break;
                        reader.readFully(buf, 0, toRead);
                        remaining -= toRead;
                        int count = toRead >> 1;                    // toRead / 2
                        for (int s = 0; s < count; s++) {
                            int b0 = buf[s * 2]     & 0xFF;
                            int b1 = buf[s * 2 + 1] & 0xFF;
                            localSamples[written++] = (short) (b0 | (b1 << 8));
                        }
                        if (progressCallback != null && sampleCount > 0) {
                            progressCallback.accept((int) (100L * written / sampleCount));
                        }
                    }
                }
                case "sm24" -> reader.seek(reader.getFilePointer() + size);
                default -> throw new IOException("The INFO list contains an unknown ID '" + id + "'.");
            }
            if ((size & 1) != 0) {
                reader.skipBytes(1);
            }
        }

        if (localSamples == null) {
            throw new IOException("No valid sample data was found.");
        }
        if (localSamples.length >= 2) {
            byte b0 = (byte) (localSamples[0] & 0xFF);
            byte b1 = (byte) ((localSamples[0] >>> 8) & 0xFF);
            byte b2 = (byte) (localSamples[1] & 0xFF);
            byte b3 = (byte) ((localSamples[1] >>> 8) & 0xFF);
            if (b0 == 'O' && b1 == 'g' && b2 == 'g' && b3 == 'S') {
                throw new UnsupportedOperationException("SoundFont3 is not yet supported.");
            }
        }

        this.bitsPerSample = localBitsPerSample;
        this.samples = localSamples;
    }

    int bitsPerSample() { return bitsPerSample; }
    short[] samples() { return samples; }
}

