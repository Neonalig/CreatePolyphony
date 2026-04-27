package org.neonalig.createpolyphony.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.client.sound.SoundFontManager;
import org.neonalig.createpolyphony.client.sound.PolyphonySynthSoundInstance;
import org.neonalig.createpolyphony.network.PlayInstrumentNotePayload;
import org.neonalig.createpolyphony.synth.PolyphonySynthesizer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side handler for {@link PlayInstrumentNotePayload}.
 *
 * <h2>Architecture (post-synth refactor)</h2>
 * <p>Previously this class translated each incoming MIDI event into a
 * per-note sound effect from a resource pack. Now it forwards
 * incoming events directly to a long-lived
 * {@link PolyphonySynthesizer} which renders PCM in real time, and
 * keeps a single {@link PolyphonySynthSoundInstance} alive to pipe that
 * PCM through Minecraft's audio engine.</p>
 *
 * <p>Conceptually the handler is now a thin MIDI router:</p>
 * <pre>{@code
 *   server packet -> programChange / noteOn / noteOff -> Synth -> PCM ring -> AudioStream -> OpenAL
 * }</pre>
 *
 * <h2>Why one stream, not one per note</h2>
 * <p>Each {@code SoundInstance} occupies an OpenAL source. A polyphonic
 * MIDI track may have 30+ simultaneous notes; allocating that many
 * sources per player would quickly exhaust OpenAL's limited pool
 * (~256 voices total, shared across <i>all</i> Minecraft sounds). The
 * synth handles polyphony internally on a single audio stream, so we
 * use exactly one source per player regardless of how busy the track
 * is.</p>
 *
 * <h2>Program-change tracking</h2>
 * <p>Most MIDI files set the channel program once at the start and
 * leave it. The server sends the program with every NoteOn anyway (so
 * we don't need state to be authoritative on the server), and we
 * de-dupe by tracking the last program assigned to each channel,
 * issuing {@code programChange} to the synth only when it actually
 * differs.</p>
 *
 * <h2>Threading</h2>
 * <p>{@link IPayloadContext#enqueueWork(Runnable)} hands us off to the
 * client main thread, so all access to {@link #lastProgram} and the
 * lazy synth init is single-threaded. {@link PolyphonySynthesizer}'s
 * own MIDI entry points are documented as thread-safe, but going
 * through the main thread also gives us deterministic ordering of
 * NoteOn/NoteOff for the same channel.</p>
 */
public final class PolyphonyClientNoteHandler {

    /**
     * Last program known to be active on each MIDI channel (0-15). {@code -1}
     * means "no program assigned yet"; the next NoteOn on that channel will
     * always issue a {@link PolyphonySynthesizer#programChange(int, int)}.
     */
    private static final int[] lastProgram = new int[16];

    private static final int BUS_IDLE_TIMEOUT_TICKS = 20 * 12;
    private static final int MAX_SOURCE_BUSES = 24;

    /** One independent synth/stream per holder source UUID for positional separation. */
    private static final Map<UUID, SourceBus> SOURCE_BUSES = new HashMap<>();
    private static int lastSoundfontGeneration = Integer.MIN_VALUE;

    /** Limited debug breadcrumbs to verify packet flow without flooding logs. */
    private static final AtomicInteger NOTE_DEBUG_BUDGET = new AtomicInteger(64);

    static {
        Arrays.fill(lastProgram, -1);
    }

    private PolyphonyClientNoteHandler() {}

    /** Network entrypoint - safe to call on the network thread. */
    public static void handle(PlayInstrumentNotePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> dispatch(payload));
    }

    private static void dispatch(PlayInstrumentNotePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        // Special admin broadcast: use command 0xF0 to mean "panic" and force local stop.
        if ((payload.command() & 0xF0) == 0xF0) {
            debugNote("panic", payload);
            panic();
            return;
        }

        SoundFontManager manager = SoundFontManager.get();
        if (manager == null || manager.active() == null || manager.isLoading()) {
            debugNote("drop:no-synth", payload);
            return;
        }

        int generation = manager.soundfontGeneration();
        if (generation != lastSoundfontGeneration) {
            stopAll();
            lastSoundfontGeneration = generation;
        }

        UUID sourceId = new UUID(payload.sourceMost(), payload.sourceLeast());
        boolean noteOn = payload.isNoteOn();
        SourceBus bus = SOURCE_BUSES.get(sourceId);
        if (bus == null && !noteOn) {
            debugNote("drop:no-bus", payload);
            return;
        }
        if (bus == null) {
            bus = createBus(manager, sourceId);
            if (bus == null) {
                debugNote("drop:bus-create-failed", payload);
                return;
            }
            SOURCE_BUSES.put(sourceId, bus);
        }

        if (noteOn) {
            bus.selfPlay = payload.selfPlay();
            if (!bus.selfPlay) {
                bus.srcX = payload.srcX();
                bus.srcY = payload.srcY();
                bus.srcZ = payload.srcZ();
            }
        }
        bus.maxDistanceBlocks = Math.max(16, payload.maxDistanceBlocks());
        bus.lastTouchedTick = player.tickCount;

        ensureStream(bus);
        debugNote("recv", payload);

        int channel = payload.channel() & 0x0F;
        int note = payload.note() & 0x7F;
        int velocity = payload.velocity() & 0x7F;
        int program = payload.program() & 0x7F;

        if (noteOn) {
            // Program is only meaningful for NoteOn; applying it here prevents NoteOff-only
            // safety packets from altering timbre state on the channel.
            if (bus.lastProgram[channel] != program) {
                bus.synth.programChange(channel, program);
                bus.lastProgram[channel] = program;
            }
            bus.synth.noteOn(channel, note, velocity);
            debugNote("note-on", payload);
        } else if (payload.isNoteOff()) {
            bus.synth.noteOff(channel, note);
            debugNote("note-off", payload);
        }
        pruneIdleBuses(player.tickCount);
        // Anything else (e.g. CC, pitch-bend) would be added here once the
        // server-side packet payload grows to carry them.
    }

    /**
     * Lazily start the streaming sound instance the first time we have
     * something to play. Subsequent NoteOns just feed the running synth.
     */
    private static void ensureStream(SourceBus bus) {
        if (bus.stream != null && !bus.stream.isStopped()) {
            if (bus.stream.maxDistanceBlocks() != bus.maxDistanceBlocks) {
                debugStream("restart:distance-changed");
                bus.stream.stopInstance();
                bus.stream = null;
            }
        }
        if (bus.stream != null && !bus.stream.isStopped()) {
            // Keep handler state aligned with the real SoundEngine channel state.
            // If OpenAL/SoundEngine dropped the channel, recreate on next packet.
            if (Minecraft.getInstance().getSoundManager().isActive(bus.stream)) {
                // Refresh source position on the running stream every packet.
                bus.stream.setSourcePosition(bus.srcX, bus.srcY, bus.srcZ, bus.selfPlay);
                return;
            }
            debugStream("restart:inactive");
            bus.stream = null;
        }
        // Either we never started one, or the previous stream got stopped
        // (synth swap, world change). Start a fresh instance.
        bus.stream = new PolyphonySynthSoundInstance(bus.synth, bus.maxDistanceBlocks);
        bus.stream.setSourcePosition(bus.srcX, bus.srcY, bus.srcZ, bus.selfPlay);
        debugStream("start");
        Minecraft.getInstance().getSoundManager().play(bus.stream);
    }

    /**
     * Stop every active note and retire the streaming sound. Called on
     * disconnect / world unload to make sure we don't leak voices or audio
     * sources between worlds.
     */
    public static void stopAll() {
        Arrays.fill(lastProgram, -1);
        for (SourceBus bus : SOURCE_BUSES.values()) {
            bus.close();
        }
        SOURCE_BUSES.clear();
    }

    private static void pruneIdleBuses(int nowTick) {
        if (SOURCE_BUSES.isEmpty()) return;
        Iterator<Map.Entry<UUID, SourceBus>> it = SOURCE_BUSES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, SourceBus> entry = it.next();
            SourceBus bus = entry.getValue();
            if ((nowTick - bus.lastTouchedTick) <= BUS_IDLE_TIMEOUT_TICKS) continue;
            bus.close();
            it.remove();
        }
        if (SOURCE_BUSES.size() <= MAX_SOURCE_BUSES) return;
        SourceBus oldest = null;
        UUID oldestId = null;
        for (Map.Entry<UUID, SourceBus> entry : SOURCE_BUSES.entrySet()) {
            if (oldest == null || entry.getValue().lastTouchedTick < oldest.lastTouchedTick) {
                oldest = entry.getValue();
                oldestId = entry.getKey();
            }
        }
        if (oldest != null && oldestId != null) {
            oldest.close();
            SOURCE_BUSES.remove(oldestId);
        }
    }

    private static SourceBus createBus(SoundFontManager manager, UUID sourceId) {
        PolyphonySynthesizer synth = manager.createIsolatedSynth();
        if (synth == null) return null;
        return new SourceBus(sourceId, synth);
    }

    /** Emergency hard-stop for all local synth output. */
    public static void panic() {
        stopAll();
    }

    // ---- Synth lookup ------------------------------------------------------------------------

    /**
     * The current active synthesizer, or {@code null} if no soundfont is
     * loaded. The {@link org.neonalig.createpolyphony.client.sound SoundFontManager}
     * (registered in a later setup hook) replaces this hook to point at
     * its own state. Keeping the lookup behind a function pointer means
     * this handler doesn't import the manager and can be tested in
     * isolation.
     */
    private static volatile java.util.function.Supplier<PolyphonySynthesizer> synthSupplier = () -> null;

    public static void setSynthSupplier(java.util.function.Supplier<PolyphonySynthesizer> supplier) {
        synthSupplier = supplier == null ? () -> null : supplier;
    }

    private static PolyphonySynthesizer currentSynth() {
        try {
            return synthSupplier.get();
        } catch (Throwable t) {
            CreatePolyphony.LOGGER.error("synthSupplier threw", t);
            return null;
        }
    }

    private static void debugNote(String phase, PlayInstrumentNotePayload payload) {
        if (!CreatePolyphony.LOGGER.isDebugEnabled()) return;
        if (NOTE_DEBUG_BUDGET.getAndDecrement() <= 0) return;
        CreatePolyphony.LOGGER.debug(
            "client:{} st=0x{} ch={} note={} vel={} prog={}",
            phase,
            Integer.toHexString(payload.command() & 0xFF),
            payload.channel() & 0x0F,
            payload.note() & 0x7F,
            payload.velocity() & 0x7F,
            payload.program() & 0x7F);
    }

    private static void debugStream(String phase) {
        if (!CreatePolyphony.LOGGER.isDebugEnabled()) return;
        CreatePolyphony.LOGGER.debug("client:stream:{}", phase);
    }

    private static final class SourceBus {
        private final UUID sourceId;
        private final PolyphonySynthesizer synth;
        private final int[] lastProgram = new int[16];
        private PolyphonySynthSoundInstance stream;
        private float srcX, srcY, srcZ;
        private boolean selfPlay = true;
        private int maxDistanceBlocks = 16 * 10;
        private int lastTouchedTick = 0;

        private SourceBus(UUID sourceId, PolyphonySynthesizer synth) {
            this.sourceId = sourceId;
            this.synth = synth;
            Arrays.fill(this.lastProgram, -1);
        }

        private void close() {
            try { synth.allNotesOff(); } catch (Throwable ignored) { }
            try { synth.close(); } catch (Throwable ignored) { }
            if (stream != null) {
                stream.stopInstance();
                stream = null;
            }
            if (CreatePolyphony.LOGGER.isDebugEnabled()) {
                CreatePolyphony.LOGGER.debug("client:bus:close:{}", sourceId);
            }
        }
    }
}
