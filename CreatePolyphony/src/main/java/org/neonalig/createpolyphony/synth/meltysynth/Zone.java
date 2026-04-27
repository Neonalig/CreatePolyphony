package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.IOException;
import java.util.Arrays;

final class Zone {
    static final Zone EMPTY = new Zone(new Generator[0]);

    private final Generator[] generators;

    private Zone(Generator[] generators) {
        this.generators = generators;
    }

    private Zone(ZoneInfo info, Generator[] generators) {
        this.generators = Arrays.copyOfRange(
            generators,
            info.generatorIndex(),
            info.generatorIndex() + info.generatorCount()
        );
    }

    static Zone[] create(ZoneInfo[] infos, Generator[] generators) throws IOException {
        if (infos.length <= 1) {
            throw new IOException("No valid zone was found.");
        }
        Zone[] zones = new Zone[infos.length - 1];
        for (int i = 0; i < zones.length; i++) {
            zones[i] = new Zone(infos[i], generators);
        }
        return zones;
    }

    Generator[] generators() {
        return generators;
    }
}

