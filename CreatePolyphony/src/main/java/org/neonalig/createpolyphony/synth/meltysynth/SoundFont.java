package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

public final class SoundFont {
    private SoundFontInfo info;
    private int bitsPerSample;
    private short[] waveData;
    private SampleHeader[] sampleHeaders;
    private Preset[] presets;
    private Instrument[] instruments;
    private final Map<Integer, Preset> presetLookup = new HashMap<>();
    private Preset defaultPreset;

    public SoundFont(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            load(raf);
        }
    }

    private void load(RandomAccessFile reader) throws IOException {
        String chunkId = BinaryReaderEx.readFourCC(reader);
        if (!"RIFF".equals(chunkId)) {
            throw new IOException("The RIFF chunk was not found.");
        }
        BinaryReaderEx.readInt32LE(reader);
        String formType = BinaryReaderEx.readFourCC(reader);
        if (!"sfbk".equals(formType)) {
            throw new IOException("The type of the RIFF chunk must be 'sfbk', but was '" + formType + "'.");
        }

        info = new SoundFontInfo(reader);
        SoundFontSampleData sampleData = new SoundFontSampleData(reader);
        bitsPerSample = sampleData.bitsPerSample();
        waveData = sampleData.samples();
        SoundFontParameters parameters = new SoundFontParameters(reader);
        sampleHeaders = parameters.sampleHeaders();
        presets = parameters.presets();
        instruments = parameters.instruments();

        checkSamples();
        checkRegions();

        int minPresetId = Integer.MAX_VALUE;
        for (Preset preset : presets) {
            int presetId = (preset.bank() << 16) | preset.program();
            presetLookup.putIfAbsent(presetId, preset);
            if (presetId < minPresetId) {
                defaultPreset = preset;
                minPresetId = presetId;
            }
        }
        if (defaultPreset == null && presets.length > 0) {
            defaultPreset = presets[0];
        }
    }

    private void checkSamples() throws IOException {
        int sampleCount = waveData.length - 4;
        for (SampleHeader sample : sampleHeaders) {
            if (!(0 <= sample.start() && sample.start() < sampleCount)) {
                throw new IOException("The start position of the sample '" + sample.name() + "' is out of range.");
            }
            if (!(0 <= sample.startLoop() && sample.startLoop() < sampleCount)) {
                throw new IOException("The loop start position of the sample '" + sample.name() + "' is out of range.");
            }
            if (!(0 < sample.end() && sample.end() <= sampleCount)) {
                throw new IOException("The end position of the sample '" + sample.name() + "' is out of range.");
            }
            if (!(0 <= sample.endLoop() && sample.endLoop() <= sampleCount)) {
                throw new IOException("The loop end position of the sample '" + sample.name() + "' is out of range.");
            }
        }
    }

    private void checkRegions() throws IOException {
        int sampleCount = waveData.length - 4;
        for (Instrument instrument : instruments) {
            for (InstrumentRegion region : instrument.regionArray()) {
                if (!(0 <= region.sampleStart() && region.sampleStart() < sampleCount)) {
                    throw new IOException("The start position of the sample '" + region.sample().name() + "' in the instrument '" + instrument.name() + "' is out of range.");
                }
                if (!(0 <= region.sampleStartLoop() && region.sampleStartLoop() < sampleCount)) {
                    throw new IOException("The loop start position of the sample '" + region.sample().name() + "' in the instrument '" + instrument.name() + "' is out of range.");
                }
                if (!(0 < region.sampleEnd() && region.sampleEnd() <= sampleCount)) {
                    throw new IOException("The end position of the sample '" + region.sample().name() + "' in the instrument '" + instrument.name() + "' is out of range.");
                }
                if (!(0 <= region.sampleEndLoop() && region.sampleEndLoop() <= sampleCount)) {
                    throw new IOException("The loop end position of the sample '" + region.sample().name() + "' in the instrument '" + instrument.name() + "' is out of range.");
                }
                LoopMode mode = region.sampleModes();
                if (mode != LoopMode.NO_LOOP && mode != LoopMode.CONTINUOUS && mode != LoopMode.LOOP_UNTIL_NOTE_OFF) {
                    throw new IOException("The sample '" + region.sample().name() + "' in the instrument '" + instrument.name() + "' has an invalid loop mode.");
                }
            }
        }
    }

    @Override
    public String toString() {
        return info.bankName();
    }

    public SoundFontInfo info() { return info; }
    public int bitsPerSample() { return bitsPerSample; }
    public short[] waveDataArray() { return waveData; }
    public SampleHeader[] sampleHeaderArray() { return sampleHeaders; }
    public Preset[] presetArray() { return presets; }
    public Instrument[] instrumentArray() { return instruments; }

    public Preset findPreset(int bank, int patch) {
        Preset preset = presetLookup.get((bank << 16) | patch);
        if (preset != null) {
            return preset;
        }
        int gmPresetId = bank < 128 ? patch : (128 << 16);
        preset = presetLookup.get(gmPresetId);
        return preset != null ? preset : defaultPreset;
    }
}

