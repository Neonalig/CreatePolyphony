package org.neonalig.createpolyphony;

import net.neoforged.neoforge.common.ModConfigSpec;

import org.neonalig.createpolyphony.synth.SynthSettings;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue MAX_VOICES = BUILDER
        .comment("Maximum simultaneous active voices in the real-time synth. Oldest voices are culled first when the limit is exceeded.")
        .defineInRange("maxVoices", 32, 1, 256);

    public static final ModConfigSpec.IntValue RING_BUFFER_BYTES = BUILDER
        .comment("Size of the PCM ring buffer between the synth thread and Minecraft's audio thread, in bytes.")
        .defineInRange("ringBufferBytes", 44_100, 8_192, 1_048_576);

    public static final ModConfigSpec.IntValue PUMP_CHUNK_BYTES = BUILDER
        .comment("How many PCM bytes the synth pump thread requests from Gervill per read.")
        .defineInRange("pumpChunkBytes", 4_096, 512, 65_536);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static SynthSettings synthSettings() {
        return new SynthSettings(44_100f, 2, 16, MAX_VOICES.get(), RING_BUFFER_BYTES.get(), PUMP_CHUNK_BYTES.get());
    }
}
