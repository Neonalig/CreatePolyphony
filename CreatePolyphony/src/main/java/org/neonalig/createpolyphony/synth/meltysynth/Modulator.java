package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

final class Modulator {
    private Modulator() {}

    static void discardData(InputStream in, int size) throws IOException {
        if (size % 10 != 0) {
            throw new IOException("The modulator list is invalid.");
        }
        long remaining = size;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new IOException("Unexpected EOF while discarding modulator data.");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    static void discardData(RandomAccessFile in, int size) throws IOException {
        if (size % 10 != 0) {
            throw new IOException("The modulator list is invalid.");
        }
        in.seek(in.getFilePointer() + size);
    }
}

