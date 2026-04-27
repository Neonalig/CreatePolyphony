package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.IOException;
import java.io.RandomAccessFile;

final class SoundFontParameters {
    private final SampleHeader[] sampleHeaders;
    private final Preset[] presets;
    private final Instrument[] instruments;

    SoundFontParameters(RandomAccessFile reader) throws IOException {
        String chunkId = BinaryReaderEx.readFourCC(reader);
        if (!"LIST".equals(chunkId)) {
            throw new IOException("The LIST chunk was not found.");
        }
        long end = Integer.toUnsignedLong(BinaryReaderEx.readInt32LE(reader)) + reader.getFilePointer();
        String listType = BinaryReaderEx.readFourCC(reader);
        if (!"pdta".equals(listType)) {
            throw new IOException("The type of the LIST chunk must be 'pdta', but was '" + listType + "'.");
        }

        PresetInfo[] presetInfos = null;
        ZoneInfo[] presetBag = null;
        Generator[] presetGenerators = null;
        InstrumentInfo[] instrumentInfos = null;
        ZoneInfo[] instrumentBag = null;
        Generator[] instrumentGenerators = null;
        SampleHeader[] localSampleHeaders = null;

        while (reader.getFilePointer() < end) {
            String id = BinaryReaderEx.readFourCC(reader);
            int size = BinaryReaderEx.readInt32LE(reader);
            switch (id) {
                case "phdr" -> presetInfos = PresetInfo.readFromChunk(reader, size);
                case "pbag" -> presetBag = ZoneInfo.readFromChunk(reader, size);
                case "pmod" -> Modulator.discardData((RandomAccessFile) reader, size);
                case "pgen" -> presetGenerators = Generator.readFromChunk(reader, size);
                case "inst" -> instrumentInfos = InstrumentInfo.readFromChunk(reader, size);
                case "ibag" -> instrumentBag = ZoneInfo.readFromChunk(reader, size);
                case "imod" -> Modulator.discardData((RandomAccessFile) reader, size);
                case "igen" -> instrumentGenerators = Generator.readFromChunk(reader, size);
                case "shdr" -> localSampleHeaders = SampleHeader.readFromChunk(reader, size);
                default -> throw new IOException("The INFO list contains an unknown ID '" + id + "'.");
            }
            if ((size & 1) != 0) {
                reader.skipBytes(1);
            }
        }

        if (presetInfos == null) throw new IOException("The PHDR sub-chunk was not found.");
        if (presetBag == null) throw new IOException("The PBAG sub-chunk was not found.");
        if (presetGenerators == null) throw new IOException("The PGEN sub-chunk was not found.");
        if (instrumentInfos == null) throw new IOException("The INST sub-chunk was not found.");
        if (instrumentBag == null) throw new IOException("The IBAG sub-chunk was not found.");
        if (instrumentGenerators == null) throw new IOException("The IGEN sub-chunk was not found.");
        if (localSampleHeaders == null) throw new IOException("The SHDR sub-chunk was not found.");

        Zone[] instrumentZones = Zone.create(instrumentBag, instrumentGenerators);
        instruments = Instrument.create(instrumentInfos, instrumentZones, localSampleHeaders);

        Zone[] presetZones = Zone.create(presetBag, presetGenerators);
        presets = Preset.create(presetInfos, presetZones, instruments);
        sampleHeaders = localSampleHeaders;
    }

    SampleHeader[] sampleHeaders() { return sampleHeaders; }
    Preset[] presets() { return presets; }
    Instrument[] instruments() { return instruments; }
}

