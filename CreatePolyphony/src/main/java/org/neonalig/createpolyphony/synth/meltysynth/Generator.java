package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

record Generator(GeneratorType type, short value) {
    static Generator[] readFromChunk(DataInput in, int size) throws IOException {
        if (size == 0 || size % 4 != 0) {
            throw new IOException("The generator list is invalid.");
        }

        int count = size / 4;
        List<Generator> generators = new ArrayList<>(Math.max(0, count - 1));
        for (int i = 0; i < count; i++) {
            Generator generator = new Generator(
                GeneratorType.fromValue(BinaryReaderEx.readInt16LE(in)),
                (short) BinaryReaderEx.readInt16LE(in)
            );
            if (i < count - 1) {
                generators.add(generator);
            }
        }
        return generators.toArray(Generator[]::new);
    }
}

