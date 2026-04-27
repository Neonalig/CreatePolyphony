package org.neonalig.createpolyphony;

import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.common.ModConfigSpec;

import org.neonalig.createpolyphony.synth.SynthSettings;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public enum AudioTimingPreset {
        STABLE,
        BALANCED,
        RESPONSIVE
    }

    public static final ModConfigSpec.IntValue MAX_VOICES = BUILDER
        .comment("Maximum simultaneous active voices in the real-time synth. Oldest voices are culled first when the limit is exceeded.")
        .defineInRange("maxVoices", 32, 1, 256);

    public static final ModConfigSpec.IntValue RING_BUFFER_BYTES = BUILDER
        .comment("Size of the PCM ring buffer between the synth thread and Minecraft's audio thread, in bytes.")
        .defineInRange("ringBufferBytes", 44_100, 8_192, 1_048_576);

    public static final ModConfigSpec.IntValue PUMP_CHUNK_BYTES = BUILDER
        .comment("How many PCM bytes each synth render chunk targets during manual SoundFont synthesis.")
        .defineInRange("pumpChunkBytes", 4_096, 512, 65_536);

    public static final ModConfigSpec.IntValue ADAPTIVE_MIN_SUBCHUNK_BYTES = BUILDER
        .comment("Adaptive stream renderer minimum subchunk size in bytes. Higher values are steadier but less reactive.")
        .defineInRange("adaptiveMinSubchunkBytes", 2_048, 256, 32_768);

    public static final ModConfigSpec.IntValue ADAPTIVE_MAX_SUBCHUNK_BYTES = BUILDER
        .comment("Adaptive stream renderer maximum subchunk size in bytes. Must be >= min.")
        .defineInRange("adaptiveMaxSubchunkBytes", 8_192, 256, 65_536);

    public static final ModConfigSpec.IntValue ADAPTIVE_TARGET_RENDER_NS = BUILDER
        .comment("Adaptive stream target render time per subchunk in nanoseconds.")
        .defineInRange("adaptiveTargetRenderNs", 1_500_000, 250_000, 10_000_000);

    public static final ModConfigSpec.DoubleValue ADAPTIVE_EWMA_ALPHA = BUILDER
        .comment("Smoothing factor for adaptive timing controller (0..1). Lower = steadier, higher = more reactive.")
        .defineInRange("adaptiveEwmaAlpha", 0.15D, 0.01D, 1.0D);

    public static final ModConfigSpec.BooleanValue ONE_MAN_BAND_USE_ALL_GM_PROGRAMS = BUILDER
        .comment("If true, One Man Band uses raw MIDI programs for full GM playback.",
            "If false, One Man Band snaps to supported instrument families only.")
        .define("oneManBandUseAllGmPrograms", false);

    public static final ModConfigSpec.EnumValue<SoundSource> SYNTH_SOUND_SOURCE = BUILDER
        .comment("Which Minecraft volume slider controls instrument synth playback.")
        .defineEnum("synthSoundSource", SoundSource.RECORDS);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static void applyAudioTimingPreset(AudioTimingPreset preset) {
        switch (preset) {
            case STABLE -> {
                ADAPTIVE_MIN_SUBCHUNK_BYTES.set(4_096);
                ADAPTIVE_MAX_SUBCHUNK_BYTES.set(8_192);
                ADAPTIVE_TARGET_RENDER_NS.set(2_000_000);
                ADAPTIVE_EWMA_ALPHA.set(0.08D);
            }
            case BALANCED -> {
                ADAPTIVE_MIN_SUBCHUNK_BYTES.set(2_048);
                ADAPTIVE_MAX_SUBCHUNK_BYTES.set(8_192);
                ADAPTIVE_TARGET_RENDER_NS.set(1_500_000);
                ADAPTIVE_EWMA_ALPHA.set(0.15D);
            }
            case RESPONSIVE -> {
                ADAPTIVE_MIN_SUBCHUNK_BYTES.set(1_024);
                ADAPTIVE_MAX_SUBCHUNK_BYTES.set(6_144);
                ADAPTIVE_TARGET_RENDER_NS.set(1_000_000);
                ADAPTIVE_EWMA_ALPHA.set(0.30D);
            }
        }
        normalizeAdaptiveBounds();
    }

    public static void normalizeAdaptiveBounds() {
        int min = ADAPTIVE_MIN_SUBCHUNK_BYTES.get();
        int max = ADAPTIVE_MAX_SUBCHUNK_BYTES.get();
        if (max < min) {
            ADAPTIVE_MAX_SUBCHUNK_BYTES.set(min);
        }
    }

    public static int adaptiveMinSubchunkBytes() {
        normalizeAdaptiveBounds();
        return ADAPTIVE_MIN_SUBCHUNK_BYTES.get();
    }

    public static int adaptiveMaxSubchunkBytes() {
        normalizeAdaptiveBounds();
        return ADAPTIVE_MAX_SUBCHUNK_BYTES.get();
    }

    public static int adaptiveTargetRenderNs() {
        return ADAPTIVE_TARGET_RENDER_NS.get();
    }

    public static double adaptiveEwmaAlpha() {
        return ADAPTIVE_EWMA_ALPHA.get();
    }

    public static boolean oneManBandUsesAllGmPrograms() {
        return ONE_MAN_BAND_USE_ALL_GM_PROGRAMS.get();
    }

    public static SoundSource synthSoundSource() {
        return SYNTH_SOUND_SOURCE.get();
    }

    public static SynthSettings synthSettings() {
        return new SynthSettings(44_100f, 2, 16, MAX_VOICES.get(), RING_BUFFER_BYTES.get(), PUMP_CHUNK_BYTES.get());
    }
}
