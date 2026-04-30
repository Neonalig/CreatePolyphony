package org.neonalig.createpolyphony.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.neonalig.createpolyphony.Config;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.client.screen.SoundFontPickerScreen;
import org.neonalig.createpolyphony.client.sound.SoundFontManager;
import org.neonalig.createpolyphony.client.timing.PolyphonyClientClock;
import org.neonalig.createpolyphony.client.timing.PolyphonyEventScheduler;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Client-side debug/testing commands for soundfont UI and synth config.
 */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = CreatePolyphony.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class PolyphonyClientTestCommands {

    private PolyphonyClientTestCommands() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(literal("cpoly")
            .then(literal("gui")
                .executes(ctx -> openGui()))
            .then(literal("config")
                .then(literal("maxVoices")
                    .then(argument("value", IntegerArgumentType.integer(1, 256))
                        .executes(ctx -> updateConfig("maxVoices", IntegerArgumentType.getInteger(ctx, "value")))))
                .then(literal("ringBufferBytes")
                    .then(argument("value", IntegerArgumentType.integer(8_192, 1_048_576))
                        .executes(ctx -> updateConfig("ringBufferBytes", IntegerArgumentType.getInteger(ctx, "value")))))
                .then(literal("pumpChunkBytes")
                    .then(argument("value", IntegerArgumentType.integer(512, 65_536))
                        .executes(ctx -> updateConfig("pumpChunkBytes", IntegerArgumentType.getInteger(ctx, "value")))))
                .then(literal("schedulingDelayMs")
                    .then(argument("value", IntegerArgumentType.integer(0, 1000))
                        .executes(ctx -> updateConfig("schedulingDelayMs", IntegerArgumentType.getInteger(ctx, "value")))))
                .then(literal("clockSyncIntervalMs")
                    .then(argument("value", IntegerArgumentType.integer(500, 60_000))
                        .executes(ctx -> updateConfig("clockSyncIntervalMs", IntegerArgumentType.getInteger(ctx, "value"))))))
            .then(literal("timing")
                .executes(ctx -> reportTiming()))
            .then(literal("reloadSynth")
                .executes(ctx -> reloadSynth()))
            .then(literal("panic")
                .executes(ctx -> panic())));
    }

    private static int openGui() {
        SoundFontManager manager = SoundFontManager.get();
        if (manager == null) {
            tell("Failed to initialize SoundFont manager.");
            return 0;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new SoundFontPickerScreen(manager)));
        if (!manager.synthesisAvailable()) {
            tell("Opened SoundFont settings screen (synth backend unavailable; playback currently muted).");
        } else {
            tell("Opened SoundFont settings screen.");
        }
        return 1;
    }

    private static int updateConfig(String key, int value) {
        boolean reload = true;
        switch (key) {
            case "maxVoices" -> Config.MAX_VOICES.set(value);
            case "ringBufferBytes" -> Config.RING_BUFFER_BYTES.set(value);
            case "pumpChunkBytes" -> Config.PUMP_CHUNK_BYTES.set(value);
            case "schedulingDelayMs" -> {
                Config.SCHEDULING_DELAY_MS.set(value);
                reload = false; // pure scheduler tweak; no synth rebuild needed
            }
            case "clockSyncIntervalMs" -> {
                Config.CLOCK_SYNC_INTERVAL_MS.set(value);
                reload = false;
            }
            default -> {
                tell("Unknown config key: " + key);
                return 0;
            }
        }
        if (reload) reloadSynth();
        tell("Updated " + key + " = " + value + (reload ? " and reloaded synth." : "."));
        return 1;
    }

    private static int reportTiming() {
        long offsetUs = PolyphonyClientClock.currentOffsetNanos() / 1_000L;
        long bestRttUs = PolyphonyClientClock.currentBestRttNanos();
        bestRttUs = bestRttUs < 0 ? -1 : bestRttUs / 1_000L;
        boolean primed = PolyphonyClientClock.isPrimed();
        int pending = PolyphonyEventScheduler.pendingCount();
        tell(String.format(
            "timing: primed=%s offset=%dus bestRtt=%s pending=%d lookAhead=%dms",
            primed, offsetUs, bestRttUs < 0 ? "n/a" : (bestRttUs + "us"),
            pending, Config.schedulingDelayMs()));
        return 1;
    }

    private static int reloadSynth() {
        PolyphonyClientNoteHandler.stopAll();
        SoundFontManager manager = SoundFontManager.get();
        if (manager != null) {
            manager.close();
        }
        SoundFontManager reloaded = SoundFontManager.get();
        if (reloaded == null) {
            tell("Failed to re-create SoundFont manager after reload.");
            return 0;
        }
        if (!reloaded.synthesisAvailable()) {
            tell("Manager reloaded, but synth backend is still unavailable.");
        } else {
            tell("Synth reloaded from current config values.");
        }
        return 1;
    }

    private static int panic() {
        PolyphonyEventScheduler.flushAll();
        PolyphonyClientNoteHandler.panic();
        tell("Panic triggered: stopped all active notes.");
        return 1;
    }

    private static void tell(String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            Player player = mc.player;
            if (player != null) {
                player.displayClientMessage(Component.literal("[CreatePolyphony] " + message), false);
            }
        });
    }
}

