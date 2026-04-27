package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SoundFont (.sf2) parser and runtime model used by the melty-style Java synth backend.
 */
public final class MeltySoundFont {

    private final File sourceFile;
    private final short[] waveData;
    private final SampleHeader[] sampleHeaders;
    private final Preset[] presets;
    private final Instrument[] instruments;

    private final Map<Integer, Preset> presetById;

    private MeltySoundFont(File sourceFile,
                           short[] waveData,
                           SampleHeader[] sampleHeaders,
                           Preset[] presets,
                           Instrument[] instruments) {
        this.sourceFile = sourceFile;
        this.waveData = waveData;
        this.sampleHeaders = sampleHeaders;
        this.presets = presets;
        this.instruments = instruments;

        this.presetById = new HashMap<>();
        for (Preset preset : presets) {
            presetById.put((preset.bank() << 16) | preset.program(), preset);
        }
    }

    public File sourceFile() { return sourceFile; }
    public int presetCount() { return presets.length; }
    public int instrumentCount() { return instruments.length; }
    public int sampleCount() { return sampleHeaders.length; }

    public short[] waveData() { return waveData; }
    public Preset[] presets() { return presets; }

    public Preset findPreset(int bank, int program) {
        Preset p = presetById.get(((bank & 0xFFFF) << 16) | (program & 0xFFFF));
        if (p != null) return p;
        p = presetById.get(program & 0xFFFF);
        return p != null ? p : (presets.length > 0 ? presets[0] : null);
    }

    public static MeltySoundFont load(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("SoundFont file not found: " + file);
        }

        short[] smpl = null;
        List<PresetHeader> phdr = null;
        List<Bag> pbag = null;
        List<Generator> pgen = null;
        List<InstHeader> inst = null;
        List<Bag> ibag = null;
        List<Generator> igen = null;
        List<SampleHeader> shdr = null;

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            String riff = readAscii4(in);
            if (!"RIFF".equals(riff)) {
                throw new IOException("Not a RIFF container: " + file);
            }
            long riffSize = readU32LE(in);
            String form = readAscii4(in);
            if (!"sfbk".equals(form)) {
                throw new IOException("Not a SoundFont (expected sfbk): " + file);
            }

            long consumed = 4;
            while (consumed + 8 <= riffSize) {
                String chunkId = readAscii4(in);
                long size = readU32LE(in);
                consumed += 8;

                if ("LIST".equals(chunkId)) {
                    String listType = readAscii4(in);
                    long listDataSize = size - 4;
                    consumed += size + (size & 1L);

                    if ("sdta".equals(listType)) {
                        long read = 0;
                        while (read + 8 <= listDataSize) {
                            String id = readAscii4(in);
                            long s = readU32LE(in);
                            read += 8;
                            if ("smpl".equals(id)) {
                                smpl = readSmpl(in, s);
                            } else {
                                skipFully(in, s);
                            }
                            if ((s & 1L) != 0) skipFully(in, 1);
                            read += s + (s & 1L);
                        }
                        if (read < listDataSize) {
                            skipFully(in, listDataSize - read);
                        }
                    } else if ("pdta".equals(listType)) {
                        long read = 0;
                        while (read + 8 <= listDataSize) {
                            String id = readAscii4(in);
                            long s = readU32LE(in);
                            read += 8;

                            switch (id) {
                                case "phdr" -> phdr = readPhdr(in, s);
                                case "pbag" -> pbag = readBag(in, s);
                                case "pgen" -> pgen = readGen(in, s);
                                case "inst" -> inst = readInst(in, s);
                                case "ibag" -> ibag = readBag(in, s);
                                case "igen" -> igen = readGen(in, s);
                                case "shdr" -> shdr = readShdr(in, s);
                                default -> skipFully(in, s);
                            }
                            if ((s & 1L) != 0) skipFully(in, 1);
                            read += s + (s & 1L);
                        }
                        if (read < listDataSize) {
                            skipFully(in, listDataSize - read);
                        }
                    } else {
                        skipFully(in, listDataSize);
                    }
                } else {
                    skipFully(in, size + (size & 1L));
                    consumed += size + (size & 1L);
                }
            }
        }

        if (smpl == null) throw new IOException("SoundFont missing sdta/smpl");
        if (phdr == null || pbag == null || pgen == null || inst == null || ibag == null || igen == null || shdr == null) {
            throw new IOException("SoundFont missing required pdta subchunks");
        }

        // Terminators are the last records in SF2 tables.
        if (!phdr.isEmpty()) phdr.remove(phdr.size() - 1);
        if (!inst.isEmpty()) inst.remove(inst.size() - 1);
        if (!shdr.isEmpty()) shdr.remove(shdr.size() - 1);

        Instrument[] instruments = buildInstruments(inst, ibag, igen, shdr.toArray(SampleHeader[]::new));
        Preset[] presets = buildPresets(phdr, pbag, pgen, instruments);

        return new MeltySoundFont(file, smpl, shdr.toArray(SampleHeader[]::new), presets, instruments);
    }

    private static Instrument[] buildInstruments(List<InstHeader> inst,
                                                 List<Bag> ibag,
                                                 List<Generator> igen,
                                                 SampleHeader[] samples) throws IOException {
        Instrument[] out = new Instrument[inst.size()];
        for (int i = 0; i < inst.size(); i++) {
            int bagStart = inst.get(i).bagIndex;
            int bagEnd = ((i + 1) < inst.size() ? inst.get(i + 1).bagIndex : ibag.size() - 1) - 1;
            if (bagEnd < bagStart) {
                out[i] = new Instrument(inst.get(i).name, new InstrumentRegion[0]);
                continue;
            }

            List<int[]> zoneGen = collectZoneGenerators(ibag, igen, bagStart, bagEnd);
            int[] global = defaultInstrumentGens();
            int firstPlayable = 0;
            if (!zoneGen.isEmpty() && !hasGenerator(zoneGen.get(0), GeneratorDefs.GEN_SAMPLE_ID)) {
                mergeInto(global, zoneGen.get(0));
                firstPlayable = 1;
            }

            List<InstrumentRegion> regions = new ArrayList<>();
            for (int z = firstPlayable; z < zoneGen.size(); z++) {
                int[] g = Arrays.copyOf(global, global.length);
                mergeInto(g, zoneGen.get(z));
                int sampleId = g[GeneratorDefs.GEN_SAMPLE_ID];
                if (sampleId < 0 || sampleId >= samples.length) {
                    continue;
                }
                regions.add(new InstrumentRegion(g, samples[sampleId]));
            }

            out[i] = new Instrument(inst.get(i).name, regions.toArray(InstrumentRegion[]::new));
        }
        return out;
    }

    private static Preset[] buildPresets(List<PresetHeader> phdr,
                                         List<Bag> pbag,
                                         List<Generator> pgen,
                                         Instrument[] instruments) throws IOException {
        Preset[] out = new Preset[phdr.size()];
        for (int i = 0; i < phdr.size(); i++) {
            int bagStart = phdr.get(i).bagIndex;
            int bagEnd = ((i + 1) < phdr.size() ? phdr.get(i + 1).bagIndex : pbag.size() - 1) - 1;
            if (bagEnd < bagStart) {
                out[i] = new Preset(phdr.get(i).name, phdr.get(i).program, phdr.get(i).bank, new PresetRegion[0]);
                continue;
            }

            List<int[]> zoneGen = collectZoneGenerators(pbag, pgen, bagStart, bagEnd);
            int[] global = defaultPresetGens();
            int firstPlayable = 0;
            if (!zoneGen.isEmpty() && !hasGenerator(zoneGen.get(0), GeneratorDefs.GEN_INSTRUMENT)) {
                mergeInto(global, zoneGen.get(0));
                firstPlayable = 1;
            }

            List<PresetRegion> regions = new ArrayList<>();
            for (int z = firstPlayable; z < zoneGen.size(); z++) {
                int[] g = Arrays.copyOf(global, global.length);
                mergeInto(g, zoneGen.get(z));
                int instrumentId = g[GeneratorDefs.GEN_INSTRUMENT];
                if (instrumentId < 0 || instrumentId >= instruments.length) {
                    continue;
                }
                regions.add(new PresetRegion(g, instruments[instrumentId]));
            }

            out[i] = new Preset(phdr.get(i).name, phdr.get(i).program, phdr.get(i).bank, regions.toArray(PresetRegion[]::new));
        }
        return out;
    }

    private static List<int[]> collectZoneGenerators(List<Bag> bags,
                                                     List<Generator> gens,
                                                     int bagStart,
                                                     int bagEnd) {
        List<int[]> zones = new ArrayList<>();
        for (int b = bagStart; b <= bagEnd; b++) {
            int genStart = bags.get(b).genIndex;
            int genEnd = ((b + 1) < bags.size() ? bags.get(b + 1).genIndex : gens.size()) - 1;
            int[] g = new int[61];
            Arrays.fill(g, Integer.MIN_VALUE);
            for (int gi = genStart; gi <= genEnd && gi < gens.size(); gi++) {
                Generator gen = gens.get(gi);
                if (gen.op >= 0 && gen.op < g.length) {
                    g[gen.op] = gen.amount;
                }
            }
            zones.add(g);
        }
        return zones;
    }

    private static boolean hasGenerator(int[] gens, int op) {
        return op >= 0 && op < gens.length && gens[op] != Integer.MIN_VALUE;
    }

    private static void mergeInto(int[] base, int[] local) {
        for (int i = 0; i < Math.min(base.length, local.length); i++) {
            if (local[i] != Integer.MIN_VALUE) base[i] = local[i];
        }
    }

    private static int[] defaultPresetGens() {
        int[] g = new int[61];
        Arrays.fill(g, 0);
        g[GeneratorDefs.GEN_KEY_RANGE] = 0x7F00;
        g[GeneratorDefs.GEN_VEL_RANGE] = 0x7F00;
        return g;
    }

    private static int[] defaultInstrumentGens() {
        int[] g = new int[61];
        Arrays.fill(g, 0);
        g[8] = 13_500;
        g[21] = -12_000;
        g[23] = -12_000;
        g[25] = -12_000;
        g[26] = -12_000;
        g[27] = -12_000;
        g[28] = -12_000;
        g[30] = -12_000;
        g[33] = -12_000;
        g[34] = -12_000;
        g[35] = -12_000;
        g[36] = -12_000;
        g[38] = -12_000;
        g[GeneratorDefs.GEN_KEY_RANGE] = 0x7F00;
        g[GeneratorDefs.GEN_VEL_RANGE] = 0x7F00;
        g[46] = -1;
        g[47] = -1;
        g[56] = 100;
        g[58] = -1;
        g[GeneratorDefs.GEN_SAMPLE_ID] = -1;
        return g;
    }

    private static short[] readSmpl(DataInputStream in, long size) throws IOException {
        if ((size & 1L) != 0L) {
            throw new IOException("Invalid smpl chunk size");
        }
        int sampleCount = (int) (size / 2L);
        short[] samples = new short[sampleCount + 4];
        for (int i = 0; i < sampleCount; i++) {
            samples[i] = (short) readU16LE(in);
        }
        return samples;
    }

    private static List<PresetHeader> readPhdr(DataInputStream in, long size) throws IOException {
        if (size % 38L != 0L) throw new IOException("Invalid phdr size");
        List<PresetHeader> list = new ArrayList<>();
        int count = (int) (size / 38L);
        for (int i = 0; i < count; i++) {
            String name = readFixedAscii(in, 20);
            int program = readU16LE(in);
            int bank = readU16LE(in);
            int bagIndex = readU16LE(in);
            readU32LE(in); // library
            readU32LE(in); // genre
            readU32LE(in); // morphology
            list.add(new PresetHeader(name, program, bank, bagIndex));
        }
        return list;
    }

    private static List<InstHeader> readInst(DataInputStream in, long size) throws IOException {
        if (size % 22L != 0L) throw new IOException("Invalid inst size");
        List<InstHeader> list = new ArrayList<>();
        int count = (int) (size / 22L);
        for (int i = 0; i < count; i++) {
            String name = readFixedAscii(in, 20);
            int bagIndex = readU16LE(in);
            list.add(new InstHeader(name, bagIndex));
        }
        return list;
    }

    private static List<Bag> readBag(DataInputStream in, long size) throws IOException {
        if (size % 4L != 0L) throw new IOException("Invalid bag size");
        List<Bag> list = new ArrayList<>();
        int count = (int) (size / 4L);
        for (int i = 0; i < count; i++) {
            int genIndex = readU16LE(in);
            readU16LE(in); // mod index
            list.add(new Bag(genIndex));
        }
        return list;
    }

    private static List<Generator> readGen(DataInputStream in, long size) throws IOException {
        if (size % 4L != 0L) throw new IOException("Invalid gen size");
        List<Generator> list = new ArrayList<>();
        int count = (int) (size / 4L);
        for (int i = 0; i < count; i++) {
            int op = readU16LE(in);
            int amount = (short) readU16LE(in);
            list.add(new Generator(op, amount));
        }
        if (!list.isEmpty()) list.remove(list.size() - 1);
        return list;
    }

    private static List<SampleHeader> readShdr(DataInputStream in, long size) throws IOException {
        if (size % 46L != 0L) throw new IOException("Invalid shdr size");
        List<SampleHeader> list = new ArrayList<>();
        int count = (int) (size / 46L);
        for (int i = 0; i < count; i++) {
            String name = readFixedAscii(in, 20);
            int start = (int) readU32LE(in);
            int end = (int) readU32LE(in);
            int startLoop = (int) readU32LE(in);
            int endLoop = (int) readU32LE(in);
            int sampleRate = (int) readU32LE(in);
            int rootKey = in.readUnsignedByte();
            int pitchCorrection = in.readByte();
            readU16LE(in); // sampleLink
            int sampleType = readU16LE(in);
            list.add(new SampleHeader(name, start, end, startLoop, endLoop, sampleRate, rootKey, pitchCorrection, sampleType));
        }
        return list;
    }

    private static String readFixedAscii(DataInputStream in, int len) throws IOException {
        byte[] b = in.readNBytes(len);
        if (b.length != len) throw new EOFException("Unexpected EOF");
        int end = 0;
        while (end < b.length && b[end] != 0) end++;
        return new String(b, 0, end, StandardCharsets.US_ASCII);
    }

    private static String readAscii4(DataInputStream in) throws IOException {
        byte[] b = in.readNBytes(4);
        if (b.length != 4) throw new EOFException("Unexpected EOF");
        return new String(b, StandardCharsets.US_ASCII);
    }

    private static int readU16LE(DataInputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        if ((b0 | b1) < 0) throw new EOFException("Unexpected EOF");
        return (b0 & 0xFF) | ((b1 & 0xFF) << 8);
    }

    private static long readU32LE(DataInputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        if ((b0 | b1 | b2 | b3) < 0) throw new EOFException("Unexpected EOF");
        return ((long) (b0 & 0xFF))
            | ((long) (b1 & 0xFF) << 8)
            | ((long) (b2 & 0xFF) << 16)
            | ((long) (b3 & 0xFF) << 24);
    }

    private static void skipFully(DataInputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            int skipped = (int) in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) throw new EOFException("Unexpected EOF while skipping");
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private record PresetHeader(String name, int program, int bank, int bagIndex) {}
    private record InstHeader(String name, int bagIndex) {}
    private record Bag(int genIndex) {}
    private record Generator(int op, int amount) {}
}
