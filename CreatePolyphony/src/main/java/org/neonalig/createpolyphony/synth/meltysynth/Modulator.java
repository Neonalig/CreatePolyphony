package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.IOException;
import java.io.RandomAccessFile;

final class Modulator {
    private Modulator() {}


    static void discardData(RandomAccessFile in, int size) throws IOException {
        if (size % 10 != 0) {
            throw new IOException("The modulator list is invalid.");
        }
        in.seek(in.getFilePointer() + size);
    }
}

