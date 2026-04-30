package org.neonalig.createpolyphony.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.neonalig.createpolyphony.Config;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.client.sound.SoundFontManager;
import org.neonalig.createpolyphony.client.sound.PolyphonySynthSoundInstance;
import org.neonalig.createpolyphony.client.timing.PolyphonyClientClock;
import org.neonalig.createpolyphony.client.timing.PolyphonyEventScheduler;
import org.neonalig.createpolyphony.network.PlayInstrumentNotePayload;
import org.neonalig.createpolyphony.synth.PolyphonySynthesizer;

import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side handler for {@link PlayInstrumentNotePayload}.
 *
 * <h2>Architecture (post-timing refactor)</h2>
 * <p>The handler is now a thin <i>scheduler front-end</i>. Each incoming event
 * carries a {@code serverNanos} stamp captured at the upstream MIDI sequencer.
 * We translate that to a local play-time using the offset estimated by
 * {@link PolyphonyClientClock}, add a fixed {@linkplain Config#schedulingDelayMs() look-ahead},
 * and hand it to {@link PolyphonyEventScheduler}, which fires the event into
 * the synth at the precise local-clock instant. This decouples audible note
 * timing from network jitter, server tick wobble, and render-thread spikes -
 * so the warble that came from "play now whenever the packet happens to land"
 * is gone.</p>
 *
 * <p>Panic / dimension-stop events ({@code 0xF0}) and any event with
 * {@code serverNanos == 0} bypass the scheduler so they are felt instantly.</p>
 *
 * <p>Conceptually:</p>
 * <pre>{@code
 *   server packet (+serverNanos)
 *     -> serverToLocal()                 // PolyphonyClientClock
 *     -> + schedulingDelayMs             // configured look-ahead
 *     -> PolyphonyEventScheduler         // park-precise dispatch thread
 *     -> Synth.noteOn / noteOff / programChange
 *     -> PCM ring -> AudioStream -> OpenAL
 * }</pre>
 *
 * <h2>Why one stream per holder, not per note</h2>
 * <p>Each {@code SoundInstance} occupies an OpenAL source. A polyphonic
 * MIDI track may have 30+ simultaneous notes; allocating that many sources
 * per holder would quickly exhaust OpenAL's pool. The synth handles polyphony
 * internally on a single audio stream.</p>
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
    private static final int MAX_IDLE_SYNTH_POOL = 8;

    /** One independent synth/stream per holder source UUID for positional separation. */
    private static final Map<UUID, SourceBus> SOURCE_BUSES = new HashMap<>();
    /** Prewarmed loaded synths recycled from idle buses to avoid SF2 reload hitch on resume. */
    private static final ArrayDeque<PooledSynth> IDLE_SYNTH_POOL = new ArrayDeque<>();
    private static int lastSoundfontGeneration = Integer.MIN_VALUE;

    /** Limited debug breadcrumbs to verify packet flow without flooding logs. */
    private static final AtomicInteger NOTE_DEBUG_BUDGET = new AtomicInteger(64);

    static {
        Arrays.fill(lastProgram, -1);
    }

    private PolyphonyClientNoteHandler() {}

    /** Network entrypoint - safe to call on the network thread. */
    public static void handle(PlayInstrumentNotePayload payload, IPayloadContext context) {
        // We deliberately do NOT use enqueueWork() here. The scheduler runs on
        // its own daemon thread and synth event entry points are documented as
        // thread-safe; routing on the network thread shaves the client-tick
        // quantum (~50 ms worst case) off our scheduling latency budget.
        try {
            ingest(payload);
        } catch (Throwable t) {
            CreatePolyphony.LOGGER.error("Polyphony client ingest failed", t);
        }
    }

    /**
     * Translate an incoming payload into a (possibly deferred) synth event.
     * Panics and immediate-delivery packets ({@code serverNanos == 0}) run
     * synchronously on the calling thread; everything else is scheduled.
     */
    private static void ingest(PlayInstrumentNotePayload payload) {
        // Panic short-circuit: clear any queued events too, otherwise scheduled
        // NoteOns from an interrupted song would still fire after the panic.
        if ((payload.command() & 0xF0) == 0xF0) {
            debugNote("panic", payload);
            PolyphonyEventScheduler.flushAll();
            Minecraft.getInstance().execute(PolyphonyClientNoteHandler::panic);
            return;
        }

        long serverNanos = payload.serverNanos();
        if (serverNanos == 0L) {
            // Immediate-delivery (server-initiated stop without sub-tick precision).
            applyOnMain(payload, 0L);
            return;
        }

        long localTargetNanos = PolyphonyClientClock.serverToLocal(serverNanos)
            + (long) Math.max(0, Config.schedulingDelayMs()) * 1_000_000L;
        // Defensive: if our offset is wildly off (e.g. very first packet before
        // any sync reply), don't schedule infinitely far away or in the deep past.
        long now = System.nanoTime();
        long maxFuture = now + 5_000_000_000L; // 5 s
        if (localTargetNanos < now - 500_000_000L || localTargetNanos > maxFuture) {
            localTargetNanos = now;
        }

        long finalTarget = localTargetNanos;
        PolyphonyEventScheduler.scheduleAt(finalTarget, () -> applyOnMain(payload, finalTarget));
    }

    /**
     * Hand the event off to the Minecraft client thread so we can safely touch
     * sound-engine state. The note itself is enqueued into the synth with its
     * exact target nanos (Phase 5 sample-accurate dispatch); positional /
     * stream-lifecycle work happens immediately.
     *
     * @param targetNanos local-clock {@link System#nanoTime()} the note should
     *                    sound at, or {@code 0L} for immediate ("now") delivery
     *                    (panic, server-initiated force-stop).
     */
    private static void applyOnMain(PlayInstrumentNotePayload payload, long targetNanos) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> dispatch(payload, targetNanos));
    }

    private static void dispatch(PlayInstrumentNotePayload payload, long targetNanos) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

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
            // Both the program change and the note are stamped with the same
            // target nanos so the synth applies them at the exact same sample
            // boundary - no audible "wrong-timbre first attack" gap.
            if (bus.lastProgram[channel] != program) {
                bus.synth.programChangeAt(channel, program, targetNanos);
                bus.lastProgram[channel] = program;
            }
            bus.synth.noteOnAt(channel, note, velocity, targetNanos);
            debugNote("note-on", payload);
        } else if (payload.isNoteOff()) {
            bus.synth.noteOffAt(channel, note, targetNanos);
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
            bus.closeFully();
        }
        SOURCE_BUSES.clear();
        while (!IDLE_SYNTH_POOL.isEmpty()) {
            closeQuietly(IDLE_SYNTH_POOL.removeFirst().synth());
        }
    }

    private static void pruneIdleBuses(int nowTick) {
        if (SOURCE_BUSES.isEmpty()) return;
        Iterator<Map.Entry<UUID, SourceBus>> it = SOURCE_BUSES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, SourceBus> entry = it.next();
            SourceBus bus = entry.getValue();
            if ((nowTick - bus.lastTouchedTick) <= BUS_IDLE_TIMEOUT_TICKS) continue;
            recycleIdleBus(bus);
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
            recycleIdleBus(oldest);
            SOURCE_BUSES.remove(oldestId);
        }
    }

    private static void recycleIdleBus(SourceBus bus) {
        PolyphonySynthesizer synth = bus.detachForReuse();
        if (synth == null) return;
        if (IDLE_SYNTH_POOL.size() >= MAX_IDLE_SYNTH_POOL) {
            closeQuietly(synth);
            return;
        }
        IDLE_SYNTH_POOL.addLast(new PooledSynth(lastSoundfontGeneration, synth));
        if (CreatePolyphony.LOGGER.isDebugEnabled()) {
            CreatePolyphony.LOGGER.debug("client:bus:recycle:{}", bus.sourceId);
        }
    }

    private static SourceBus createBus(SoundFontManager manager, UUID sourceId) {
        PolyphonySynthesizer synth = borrowPrewarmedSynth(lastSoundfontGeneration);
        if (synth == null) {
            synth = manager.createIsolatedSynth();
        }
        if (synth == null) return null;
        return new SourceBus(sourceId, synth);
    }

    private static PolyphonySynthesizer borrowPrewarmedSynth(int expectedGeneration) {
        while (!IDLE_SYNTH_POOL.isEmpty()) {
            PooledSynth pooled = IDLE_SYNTH_POOL.removeLast();
            if (pooled.generation() != expectedGeneration) {
                closeQuietly(pooled.synth());
                continue;
            }
            return pooled.synth();
        }
        return null;
    }

    private static void closeQuietly(PolyphonySynthesizer synth) {
        try { synth.allNotesOff(); } catch (Throwable ignored) { }
        try { synth.close(); } catch (Throwable ignored) { }
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

        private PolyphonySynthesizer detachForReuse() {
            try { synth.allNotesOff(); } catch (Throwable ignored) { }
            if (stream != null) {
                stream.stopInstance();
                stream = null;
            }
            return synth;
        }

        private void closeFully() {
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

    private record PooledSynth(int generation, PolyphonySynthesizer synth) { }
}
