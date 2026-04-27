package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Minimal SoundFont metadata parser used by the in-mod melty synth backend.
 *
 * <p>The parser validates the RIFF/sfbk container and extracts key table sizes
 * from the pdta list. We only need this metadata for now; waveform generation is
 * performed by the Java synth core itself.</p>
 */
public final class MeltySoundFont {

    private final File sourceFile;
    private final int presetCount;
    private final int instrumentCount;
    private final int sampleCount;

    private MeltySoundFont(File sourceFile, int presetCount, int instrumentCount, int sampleCount) {
        this.sourceFile = sourceFile;
        this.presetCount = Math.max(0, presetCount);
        this.instrumentCount = Math.max(0, instrumentCount);
        this.sampleCount = Math.max(0, sampleCount);
    }

    public File sourceFile() {
        return sourceFile;
    }

    public int presetCount() {
        return presetCount;
    }

    public int instrumentCount() {
        return instrumentCount;
    }

    public int sampleCount() {
        return sampleCount;
    }

    public static MeltySoundFont load(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("SoundFont file not found: " + file);
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            String riff = readAscii4(raf);
            if (!"RIFF".equals(riff)) {
                throw new IOException("Not a RIFF container: " + file);
            }
            long riffSize = readU32LE(raf);
            String form = readAscii4(raf);
            if (!"sfbk".equals(form)) {
                throw new IOException("Not a SoundFont (expected sfbk): " + file);
            }

            long riffEnd = Math.min(raf.length(), 8L + riffSize);
            int presets = 0;
            int instruments = 0;
            int samples = 0;

            while (raf.getFilePointer() + 8 <= riffEnd) {
                String id = readAscii4(raf);
                long size = readU32LE(raf);
                long chunkDataStart = raf.getFilePointer();
                long chunkDataEnd = chunkDataStart + size;
                if (chunkDataEnd > raf.length()) {
                    throw new IOException("Truncated SoundFont chunk: " + id);
                }

                if ("LIST".equals(id) && size >= 4) {
                    String listType = readAscii4(raf);
                    if ("pdta".equals(listType)) {
                        long pdtaEnd = chunkDataEnd;
                        while (raf.getFilePointer() + 8 <= pdtaEnd) {
                            String subId = readAscii4(raf);
                            long subSize = readU32LE(raf);
                            long subStart = raf.getFilePointer();
                            long subEnd = subStart + subSize;
                            if (subEnd > pdtaEnd) {
                                throw new IOException("Truncated pdta subchunk: " + subId);
                            }

                            if ("phdr".equals(subId)) {
                                presets = Math.max(0, (int) (subSize / 38L) - 1);
                            } else if ("inst".equals(subId)) {
                                instruments = Math.max(0, (int) (subSize / 22L) - 1);
                            } else if ("shdr".equals(subId)) {
                                samples = Math.max(0, (int) (subSize / 46L) - 1);
                            }

                            raf.seek(subEnd + (subSize & 1L));
                        }
                    }
                }

                raf.seek(chunkDataEnd + (size & 1L));
            }

            return new MeltySoundFont(file, presets, instruments, samples);
        }
    }

    private static String readAscii4(RandomAccessFile raf) throws IOException {
        byte[] b = new byte[4];
        int read = raf.read(b);
        if (read != 4) {
            throw new EOFException("Unexpected EOF");
        }
        return new String(b, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static long readU32LE(RandomAccessFile raf) throws IOException {
        int b0 = raf.read();
        int b1 = raf.read();
        int b2 = raf.read();
        int b3 = raf.read();
        if ((b0 | b1 | b2 | b3) < 0) {
            throw new EOFException("Unexpected EOF");
        }
        return ((long) b0) | ((long) b1 << 8) | ((long) b2 << 16) | ((long) b3 << 24);
    }
}
