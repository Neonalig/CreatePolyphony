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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    /**
     * Target size for the proactively-prewarmed synth pool. The first time a
     * client receives a NoteOn for a holder it has no {@link SourceBus} for,
     * we have to construct a {@link PolyphonySynthesizer} (which loads and
     * parses the active SoundFont). On a non-trivial SF2 (tens of MB, several
     * hundred presets) that is a hundreds-of-milliseconds blocking step, and
     * doing it from {@link #dispatch} - which runs on the Minecraft client
     * thread - manifests as a half-second hitch right when the player equips
     * their instrument mid-song. We keep this many warm synths ready in the
     * idle pool at all times so {@link #createBus} can pop one in O(1).
     */
    private static final int PREWARM_TARGET = 2;

    /**
     * Single dedicated background thread for SF2-loading prewarms. A daemon
     * thread so it can never keep the JVM alive past shutdown; serial because
     * concurrent SF2 parses just thrash the disk and contend over MeltySynth's
     * internal buffers without producing voices any faster.
     */
    private static final ExecutorService PREWARM_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Polyphony-SynthPrewarm");
        t.setDaemon(true);
        // Below normal so SF2 parsing yields to render/network/audio threads.
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    /** Synchronisation lock for {@link #IDLE_SYNTH_POOL}, now touched from the prewarm thread too. */
    private static final Object IDLE_POOL_LOCK = new Object();
    /** Coalescing flag so we don't pile up redundant prewarm tasks on the executor. */
    private static final AtomicInteger PENDING_PREWARM_REQUESTS = new AtomicInteger(0);

    /** One independent synth/stream per holder source UUID for positional separation. */
    private static final Map<UUID, SourceBus> SOURCE_BUSES = new HashMap<>();
    /**
     * Prewarmed loaded synths. Two producers feed it:
     * <ol>
     *   <li>{@link #recycleIdleBus} when a client-side bus times out without
     *       further packets.</li>
     *   <li>The background {@link #PREWARM_EXECUTOR} which proactively loads
     *       fresh synths from the active soundfont up to {@link #PREWARM_TARGET}.</li>
     * </ol>
     * Consumed by {@link #borrowPrewarmedSynth} on the client thread when a new
     * {@link SourceBus} is needed (i.e. first NoteOn for a fresh holder/hand).
     * All access goes through {@link #IDLE_POOL_LOCK} now that more than one
     * thread can touch it.
     */
    private static final ArrayDeque<PooledSynth> IDLE_SYNTH_POOL = new ArrayDeque<>();
    private static int lastSoundfontGeneration = Integer.MIN_VALUE;

    /**
     * Per-bus stop watermarks: source bus UUID -> server-side {@link System#nanoTime()}
     * stamp captured by {@link org.neonalig.createpolyphony.link.PolyphonyLinkManager}
     * at the moment it issued the bus-stop sentinel.
     *
     * <p>The point of this map is to discard <i>late</i> NoteOn/NoteOff packets
     * that the upstream MIDI sequencer emitted before the holder lost their
     * instrument but only arrived (or only fired off the scheduler) after the
     * stop. Without this filter, such an in-flight packet would happily build
     * a brand-new {@link SourceBus} for the same deterministic source UUID and
     * play a brief audible chunk right after we panicked the bus - exactly the
     * "audio hangs that fade out after putting away" symptom.</p>
     *
     * <p>An incoming non-panic event is dropped iff
     * {@code event.serverNanos() <= stop.serverNanos()} for the same bus.
     * Server-stop packets and force-stop NoteOffs both use a server-side
     * monotonic {@code System.nanoTime()} clock, so the comparison is well
     * defined.</p>
     *
     * <p>Soft-capped at {@link #STOP_WATERMARK_CAP} entries to avoid unbounded
     * growth; the oldest entry is evicted on overflow. Re-equipping a holder's
     * instrument naturally produces fresh server-emit times that compare
     * <i>greater</i> than the recorded watermark, so the bus comes back to
     * life automatically without any explicit clear.</p>
     */
    private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> STOP_WATERMARKS =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final int STOP_WATERMARK_CAP = 128;

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
            // Sub-command lives in the note field (cf. PlayInstrumentNotePayload):
            //   note == 1 -> per-bus stop: silence ONLY the SourceBus identified
            //                by (sourceMost, sourceLeast). Used when a holder loses
            //                their instrument (hand swap, drop, deployer extraction,
            //                etc.) and any voices ringing on that bus must cut off
            //                immediately, even if the server's per-(channel, note)
            //                NoteOff bookkeeping missed an owner.
            //   note == 0 (default) -> global panic: stopAll().
            int sub = payload.note() & 0x7F;
            if (sub == 1) {
                debugNote("panic-bus", payload);
                UUID busId = new UUID(payload.sourceMost(), payload.sourceLeast());
                // Record the stop watermark BEFORE the actual bus close so that any
                // late packet for the same bus that races us in on the network
                // thread (or fires off the scheduler on the main thread later) is
                // already filtered against the watermark.
                recordStopWatermark(busId, payload.serverNanos());
                Minecraft.getInstance().execute(() -> stopBus(busId));
                return;
            }
            debugNote("panic", payload);
            PolyphonyEventScheduler.flushAll();
            Minecraft.getInstance().execute(PolyphonyClientNoteHandler::panic);
            return;
        }

        // Drop late packets that the server emitted before the bus was stopped.
        // The scheduler look-ahead means a NoteOn captured a few ticks ago can
        // still be parked on the dispatch thread even after we panic the bus on
        // the client; without this filter it would happily build a fresh
        // SourceBus and play a brief audible tail right after unequip.
        if (isStaleAfterStop(payload)) {
            debugNote("drop:post-stop-stale", payload);
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
        // Re-check the stop watermark here too: ingest() filtered the packet at
        // arrival time, but this method is what runs after the scheduler delay,
        // so a late stop sentinel that landed AFTER ingest scheduled this event
        // could still try to spawn a fresh bus if we trusted the queued event
        // alone. The watermark map is the single source of truth.
        if (isStaleAfterStop(payload)) {
            debugNote("drop:post-stop-stale", payload);
            return;
        }
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
        synchronized (IDLE_POOL_LOCK) {
            while (!IDLE_SYNTH_POOL.isEmpty()) {
                closeQuietly(IDLE_SYNTH_POOL.removeFirst().synth());
            }
        }
        // Watermarks were keyed against the previous session's server-nanoTime
        // clock; on a fresh global stop (dimension change, world reload, panic)
        // a new server may produce smaller nanoTime values than the stale
        // watermarks we kept around, which would deadlock all subsequent buses
        // into "stale" territory. Drop them so the new session starts clean.
        STOP_WATERMARKS.clear();
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
        synchronized (IDLE_POOL_LOCK) {
            if (IDLE_SYNTH_POOL.size() >= MAX_IDLE_SYNTH_POOL) {
                closeQuietly(synth);
                return;
            }
            IDLE_SYNTH_POOL.addLast(new PooledSynth(lastSoundfontGeneration, synth));
        }
        if (CreatePolyphony.LOGGER.isDebugEnabled()) {
            CreatePolyphony.LOGGER.debug("client:bus:recycle:{}", bus.sourceId);
        }
    }

    private static SourceBus createBus(SoundFontManager manager, UUID sourceId) {
        PolyphonySynthesizer synth = borrowPrewarmedSynth(lastSoundfontGeneration);
        if (synth == null) {
            // Pool was empty - this is the cold-start case (first instrument
            // equipped this session, before the prewarmer finished its first
            // load). Pay the SF2 parse on the client thread once; subsequent
            // creates will draw from the prewarmed pool.
            synth = manager.createIsolatedSynth();
        }
        // Refill so the next holder/hand to come online doesn't re-pay the cost.
        requestPrewarm();
        if (synth == null) return null;
        return new SourceBus(sourceId, synth);
    }

    private static PolyphonySynthesizer borrowPrewarmedSynth(int expectedGeneration) {
        synchronized (IDLE_POOL_LOCK) {
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
    }

    /**
     * Schedule a background prewarm so the idle pool stays at
     * {@link #PREWARM_TARGET}. Coalesces redundant requests via
     * {@link #PENDING_PREWARM_REQUESTS}: any request that arrives while another
     * is already queued is satisfied by the existing task.
     *
     * <p>Public so {@link org.neonalig.createpolyphony.client.sound.SoundFontManager}
     * can call it whenever a soundfont finishes (re)loading - that is exactly
     * the moment we know the previous pool generation became invalid and we
     * want fresh synths ready before the player triggers the first NoteOn.</p>
     */
    public static void requestPrewarm() {
        // If a prewarm is already queued, it will observe the current pool size
        // when it runs and top it up - so we don't need to schedule another.
        if (PENDING_PREWARM_REQUESTS.getAndIncrement() > 0) return;
        try {
            PREWARM_EXECUTOR.execute(PolyphonyClientNoteHandler::runPrewarmTask);
        } catch (Throwable t) {
            // Executor rejected (e.g. JVM shutdown in progress): clear the flag
            // so a future request can try again. Don't propagate - prewarm is a
            // pure performance optimisation; the cold-start fallback in
            // createBus() will still produce audio.
            PENDING_PREWARM_REQUESTS.set(0);
            CreatePolyphony.LOGGER.debug("Prewarm executor rejected task", t);
        }
    }

    private static void runPrewarmTask() {
        // Drain the request counter; this single task handles all coalesced
        // requests by topping the pool up to PREWARM_TARGET.
        PENDING_PREWARM_REQUESTS.set(0);

        SoundFontManager manager = SoundFontManager.get();
        if (manager == null || manager.isLoading() || manager.active() == null) return;

        int activeGeneration = manager.soundfontGeneration();

        while (true) {
            // Re-check pool size each iteration: a concurrent borrow would have
            // dropped us below the target and we should refill again.
            int currentSize;
            synchronized (IDLE_POOL_LOCK) {
                // Drop any stale-generation entries while we have the lock so
                // we never count them toward the target.
                IDLE_SYNTH_POOL.removeIf(p -> {
                    if (p.generation() == activeGeneration) return false;
                    closeQuietly(p.synth());
                    return true;
                });
                currentSize = IDLE_SYNTH_POOL.size();
            }
            if (currentSize >= PREWARM_TARGET) return;

            // Soundfont may have changed underneath us between iterations; bail
            // immediately and let the next listener-fired requestPrewarm pick up
            // the new generation rather than waste cycles on the old one.
            if (manager.soundfontGeneration() != activeGeneration || manager.isLoading()) return;

            PolyphonySynthesizer synth = manager.createIsolatedSynth();
            if (synth == null) {
                // Couldn't load (e.g. soundfont was just deactivated). Stop
                // looping; the next requestPrewarm will retry once a sound is
                // available again.
                return;
            }

            synchronized (IDLE_POOL_LOCK) {
                if (manager.soundfontGeneration() != activeGeneration
                    || IDLE_SYNTH_POOL.size() >= MAX_IDLE_SYNTH_POOL) {
                    // Generation flipped while we were loading, or another
                    // producer raced us past the cap. Discard rather than keep
                    // a synth that will only be thrown away on next borrow.
                    closeQuietly(synth);
                    return;
                }
                IDLE_SYNTH_POOL.addLast(new PooledSynth(activeGeneration, synth));
            }
        }
    }

    private static void closeQuietly(PolyphonySynthesizer synth) {
        try { synth.allNotesOff(); } catch (Throwable ignored) { }
        try { synth.close(); } catch (Throwable ignored) { }
    }

    /**
     * Returns {@code true} when the given non-panic payload was emitted on the
     * server before the most recent stop watermark for its source bus, i.e. it
     * is an in-flight packet from before unequip and must not be allowed to
     * resurrect the silenced bus.
     *
     * <p>{@code payload.serverNanos() == 0} packets (immediate-delivery
     * server-initiated stops) are never considered stale - they have no
     * meaningful emit time and we always want them to take effect.</p>
     */
    private static boolean isStaleAfterStop(PlayInstrumentNotePayload payload) {
        long eventNanos = payload.serverNanos();
        if (eventNanos == 0L) return false;
        UUID busId = new UUID(payload.sourceMost(), payload.sourceLeast());
        Long stopNanos = STOP_WATERMARKS.get(busId);
        if (stopNanos == null) return false;
        // <= so that an event emitted in the same nanoTime tick as the stop
        // still loses (the stop wins ties; better to drop one borderline note
        // than to leak a hanging chunk).
        return eventNanos <= stopNanos;
    }

    /**
     * Record / refresh the per-bus stop watermark. If a watermark already
     * exists we keep the larger of the two, so out-of-order delivery of two
     * stop packets for the same bus can never lower the bar.
     */
    private static void recordStopWatermark(UUID busId, long serverNanos) {
        if (serverNanos == 0L) return; // legacy / fallback: nothing to compare against
        STOP_WATERMARKS.merge(busId, serverNanos, Math::max);
        // Soft cap. ConcurrentHashMap doesn't expose insertion order, so we use
        // a "evict any oldest-by-value" pass when over the cap. Stop watermarks
        // are monotonically increasing per bus, so smallest value across buses
        // is a reasonable approximation of "oldest stop".
        if (STOP_WATERMARKS.size() <= STOP_WATERMARK_CAP) return;
        UUID oldest = null;
        long oldestNanos = Long.MAX_VALUE;
        for (Map.Entry<UUID, Long> e : STOP_WATERMARKS.entrySet()) {
            if (e.getValue() < oldestNanos) {
                oldestNanos = e.getValue();
                oldest = e.getKey();
            }
        }
        if (oldest != null) {
            STOP_WATERMARKS.remove(oldest, oldestNanos);
        }
    }

    /** Emergency hard-stop for all local synth output. */
    public static void panic() {
        stopAll();
    }

    /**
     * Targeted stop for a single source bus. Used when the server signals that
     * a specific holder's hand has lost its instrument; we close that bus's
     * voices and stream so the synth stops producing audio for it
     * immediately. The bus entry itself is removed (rather than just released)
     * so a future re-acquisition gets a fresh synth state and doesn't inherit
     * stale program/voice context.
     */
    public static void stopBus(UUID sourceId) {
        SourceBus bus = SOURCE_BUSES.remove(sourceId);
        if (bus == null) {
            // Nothing to do - the client never had a bus with this id, or it was
            // already pruned. The panic packet is harmless in that case.
            return;
        }
        bus.closeFully();
        if (CreatePolyphony.LOGGER.isDebugEnabled()) {
            CreatePolyphony.LOGGER.debug("client:bus:stop:{}", sourceId);
        }
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
