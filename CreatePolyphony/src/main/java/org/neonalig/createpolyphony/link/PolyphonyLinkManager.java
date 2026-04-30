package org.neonalig.createpolyphony.link;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import org.neonalig.createpolyphony.Config;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.instrument.InstrumentFamily;
import org.neonalig.createpolyphony.instrument.InstrumentItem;
import org.neonalig.createpolyphony.network.PlayInstrumentNotePayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-side registry of every active {@link PolyphonyLink}, keyed by
 * (level, block position).
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Look-up / create-on-demand of links when a player interacts with a
 *       tracker bar.</li>
 *   <li>Garbage-collection of empty links (e.g. when their last player
 *       unlinks or logs out).</li>
 *   <li>The dispatch entry point used by the Mixin in
 *       {@link org.neonalig.createpolyphony.mixin.TrackerBarBlockEntityMixin}:
 *       given a tracker pos and a MIDI message, route it to the right linked
 *       player by sending a {@link PlayInstrumentNotePayload}.</li>
 * </ul>
 *
 * <p>State is stored in process memory only. If the server restarts, links are
 * lost - which is fine, since the players' "linked" status is itself transient
 * (you re-link by right-clicking the tracker again). This avoids us having to
 * persist UUIDs on the SoS BlockEntity, which we don't own.</p>
 *
 * <p>All access must be on the server thread.</p>
 */
public final class PolyphonyLinkManager {

    public enum LinkAction {
        NOT_INSTRUMENT,
        LINKED,
        SWAPPED,
        UNLINKED
    }

    /** Map from (level dimension key path, BlockPos) -&gt; link. */
    private static final Map<LinkKey, PolyphonyLink> LINKS = new HashMap<>();

    /** Limited debug breadcrumbs for note-routing diagnosis without log spam. */
    private static final AtomicInteger NOTE_ROUTE_DEBUG_BUDGET = new AtomicInteger(64);
    /** Throttled warnings when a non-player holder has no resolvable world position. */
    private static final AtomicInteger MISSING_HOLDER_POS_WARN_BUDGET = new AtomicInteger(32);

    /** Active held-link participation: player UUID -> links currently in hand/off-hand. */
    private static final Map<UUID, Map<LinkKey, HeldInstruments>> ACTIVE_HAND_LINKS = new HashMap<>();
    /**
     * Active note ownership per link: (channel, note) -&gt; every per-assignee
     * client bus currently expecting a matching NoteOff. With each linked
     * instrument routed to its own client bus, a single (channel, note) can
     * have multiple owners (e.g. main hand + off hand both playing the same
     * note simultaneously) and each one needs its own NoteOff packet to free
     * the corresponding voice.
     */
    private static final Map<LinkKey, Map<ActiveNoteKey, List<TrackedOwner>>> ACTIVE_NOTE_OWNERS = new HashMap<>();
    /** Last observed ProgramChange per tracker/channel, even while no players are linked. */
    private static final Map<LinkKey, int[]> CHANNEL_PROGRAM_SNAPSHOT = new HashMap<>();
    /** Per-tracker guard window that drops delayed NoteOn events immediately after transport stop. */
    private static final Map<LinkKey, Long> TRACKER_STOP_SUPPRESSION_UNTIL = new HashMap<>();
    /**
     * Per-tracker last-activity tick. Updated for every MIDI event passing
     * through {@link #dispatchNote} (NoteOn, NoteOff, ProgramChange, CC -
     * literally anything). The level-tick watchdog
     * {@link #pruneAbandonedTrackers} compares this against {@code currentTick}
     * and, if the tracker has gone silent while still owning in-flight notes,
     * fires {@link #onTrackerStopped} so mobs/deployers/etc. that received
     * sustaining NoteOns get explicit NoteOffs instead of holding the voice
     * forever (which is the user-visible symptom: "stop the tracker, mob still
     * rings the last note").
     */
    private static final Map<LinkKey, Long> LAST_TRACKER_ACTIVITY_TICK = new HashMap<>();
    /**
     * Window of inactivity, in ticks, after which a tracker that still owns
     * tracked notes is presumed stopped and its tracked notes are released.
     *
     * <p>Tuning rationale: a normal MIDI stream emits something (CC, tempo
     * meta, drum hits, melody NoteOn/Off) far more frequently than this. A
     * legitimately-sustained chord with zero accompanying events for a full
     * 1.5 s is rare even on sparse tracks, while a player pressing "Stop" on
     * a tracker bar produces exactly that signature: an in-flight note and no
     * subsequent events at all. Erring on this side keeps the recovery
     * responsive without falsely chopping long-tail pads.</p>
     */
    private static final long TRACKER_INACTIVITY_FLUSH_TICKS = 30;
    /** Expiration state for transient automation holders (deployer interactions, depot/ground stack targets). */
    private static final Map<UUID, TransientHolderState> TRANSIENT_HOLDER_EXPIRY = new HashMap<>();

    private record TransientHolderState(ServerLevel level, long expiryTick, @Nullable Vec3 holderPos) {}
    /** Snapshot of currently synced mob holders per level for cheap stale-holder cleanup. */
    private static final Map<String, Set<UUID>> ACTIVE_MOB_HOLDERS_BY_LEVEL = new HashMap<>();

    private static final String TRACKER_BE_CLASS = "com.finchy.pipeorgans.content.midi.trackerBar.TrackerBarBlockEntity";
    private static Field trackerMidiSourceField;
    private static Field midiSourceGhostInvField;
    private static boolean trackerReflectionInitialized;

    private static final int MOB_SYNC_INTERVAL_TICKS = 10;
    /**
     * Holder lifetime for deployer automation participation.
     * CONTINUOUS_POWERED uses a long grace to avoid play/pause stutter between sparse activations.
     * INTERACTION_ONLY keeps activation windows brief so playback is tied to full deploy interactions.
     */
    private static final int AUTOMATION_TTL_CONTINUOUS_TICKS = 20 * 60;
    private static final int AUTOMATION_TTL_INTERACTION_TICKS = 4;
    private static final int TRACKER_STOP_SUPPRESSION_TICKS = 3;

    private PolyphonyLinkManager() {}

    // ---- public API used by interaction handler -----------------------------------------------

    /**
     * Toggle-link the held instrument stack to the tracker at {@code pos}.
     *
     * <p>Links are stored on each item stack, so a player can keep multiple
     * linked instruments prepared at once.</p>
     */
    public static LinkAction linkOrToggle(ServerPlayer player, ServerLevel level, BlockPos pos, ItemStack heldStack) {
        InstrumentFamily family = InstrumentItem.familyOf(heldStack);
        if (family == null) return LinkAction.NOT_INSTRUMENT;

        LinkKey newKey = LinkKey.of(level, pos);
        if (InstrumentLinkData.matches(heldStack, newKey)) {
            InstrumentLinkData.clearLinked(heldStack);
            syncPlayerHeldLinks(player);
            player.displayClientMessage(Component.translatable("message.createpolyphony.unlinked"), true);
            return LinkAction.UNLINKED;
        }

        InstrumentLinkData.setLinked(heldStack, newKey);
        syncPlayerHeldLinks(player);
        player.displayClientMessage(
            Component.translatable("message.createpolyphony.linked",
                "Tracker Bar @ " + pos.toShortString(),
                Component.translatable(family.translationKey())),
            true
        );
        CreatePolyphony.LOGGER.debug("Linked {} ({}) to {}", player.getName().getString(), family, newKey);
        return LinkAction.LINKED;
    }

    /** Explicit unlink action for the currently used instrument stack. */
    public static boolean unlinkHeldInstrument(ServerPlayer player, ItemStack heldStack) {
        if (InstrumentItem.familyOf(heldStack) == null) return false;
        if (!InstrumentLinkData.isLinked(heldStack)) return false;
        InstrumentLinkData.clearLinked(heldStack);
        syncPlayerHeldLinks(player);
        player.displayClientMessage(Component.translatable("message.createpolyphony.unlinked"), true);
        return true;
    }

    /** Reconcile active link membership from linked instruments in main/off hand. */
    public static void syncPlayerHeldLinks(ServerPlayer player) {
        syncHolderLinks(player.getUUID(), desiredHeldLinks(player), slFrom(player));
    }

    /** Called from level ticks: refresh mob holders and retire transient automation holders. */
    public static void onLevelTick(ServerLevel level) {
        pruneExpiredTransientHolders(level);
        pruneExpiredStopSuppression(level);
        pruneAbandonedTrackers(level);
        long gameTime = level.getGameTime();
        if (gameTime % MOB_SYNC_INTERVAL_TICKS != 0) return;
        syncMobHolders(level);
    }

    /** Remove all link participation state for a non-player holder that left the level. */
    public static void onNonPlayerHolderRemoved(UUID holderId) {
        if (holderId == null) return;
        TransientHolderState state = TRANSIENT_HOLDER_EXPIRY.remove(holderId);
        removeHolder(holderId, state != null ? state.level() : null);
    }

    /** Send all-notes-off to a player and clear their note-ownership entries (used on dimension change). */
    public static void onPlayerChangedDimension(ServerPlayer player) {
        // Panic packet: command 0xF0 is the client-side "stop everything" sentinel.
        UUID id = player.getUUID();
        // Use the player's real UUID as the bus id for the panic - the client
        // panic path stops every active SourceBus regardless of key, so this
        // reaches all of the player's per-hand buses.
        PacketDistributor.sendToPlayer(player, new PlayInstrumentNotePayload(0, 0, 0xF0, 0, 0, true, 0f, 0f, 0f,
            simulationDistanceBlocks(player.getServer()), id.getMostSignificantBits(), id.getLeastSignificantBits(), 0L));
        // Clear any tracked note-owners for this player so stale NoteOffs don't mis-route.
        ACTIVE_NOTE_OWNERS.forEach((key, byNote) -> {
            byNote.values().forEach(owners -> owners.removeIf(o -> id.equals(o.realHolderId())));
            byNote.entrySet().removeIf(e -> e.getValue().isEmpty());
        });
        ACTIVE_NOTE_OWNERS.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    /**
     * Feed deployer activity into the link manager so powered automation can
     * participate as a non-player performer.
     */
    public static void registerAutomationActivation(ServerLevel level,
                                                    UUID holderId,
                                                    @Nullable ItemStack deployerHeld,
                                                    @Nullable BlockPos targetPos,
                                                    @Nullable Vec3 deployerPos) {
        if (holderId == null) return;

        Map<LinkKey, HeldInstruments> desired = new HashMap<>();
        if (deployerHeld != null) {
            collectHeldLink(desired, deployerHeld, true);
        }

        if (targetPos != null) {
            if (level.getBlockEntity(targetPos) instanceof DepotBlockEntity depot) {
                collectHeldLink(desired, depot.getHeldItem(), false);
            }

            AABB targetBox = new AABB(targetPos).inflate(0.6D);
            for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, targetBox)) {
                collectHeldLink(desired, itemEntity.getItem(), false);
            }
        }

        if (desired.isEmpty()) {
            // Deployer no longer has any linked instrument context (e.g. item taken out).
            // Remove stale channel assignment and stop any in-flight notes owned by it.
            TRANSIENT_HOLDER_EXPIRY.remove(holderId);
            removeHolder(holderId, level);
            return;
        }
        syncHolderLinks(holderId, desired, level);
        Vec3 holderPosSnap = deployerPos != null
            ? deployerPos
            : (targetPos != null ? Vec3.atCenterOf(targetPos) : null);
        TRANSIENT_HOLDER_EXPIRY.put(holderId,
            new TransientHolderState(level, currentServerTick(level.getServer()) + automationHolderTtlTicks(), holderPosSnap));
    }

    /** Explicitly unregister an automation holder and stop any notes it still owns. */
    public static void unregisterAutomationHolder(ServerLevel level, UUID holderId) {
        if (holderId == null) return;
        TRANSIENT_HOLDER_EXPIRY.remove(holderId);
        removeHolder(holderId, level);
    }

    /**
     * Force-stop any notes still tracked for this tracker when playback halts abruptly.
     * Called from tracker-stop mixin hooks as a safety net when sequencer NoteOffs are missing.
     */
    public static void onTrackerStopped(ServerLevel level, BlockPos pos) {
        LinkKey key = LinkKey.of(level, pos);
        TRACKER_STOP_SUPPRESSION_UNTIL.put(key, currentServerTick(level.getServer()) + TRACKER_STOP_SUPPRESSION_TICKS);
        Map<ActiveNoteKey, List<TrackedOwner>> byNote = ACTIVE_NOTE_OWNERS.get(key);
        if (byNote == null || byNote.isEmpty()) return;

        for (Map.Entry<ActiveNoteKey, List<TrackedOwner>> entry : Map.copyOf(byNote).entrySet()) {
            ActiveNoteKey active = entry.getKey();
            for (TrackedOwner owner : List.copyOf(entry.getValue())) {
                Vec3 ownerPos = resolveHolderPosition(level, owner.realHolderId());
                sendNotePacket(level, pos, ownerPos, owner.realHolderId(), owner.sourceBusId(),
                    0, active.channel(), 0x80, active.note(), 0, 0L);
            }
            byNote.remove(active);
        }
        if (byNote.isEmpty()) ACTIVE_NOTE_OWNERS.remove(key);
    }

    private static int automationHolderTtlTicks() {
        return Config.deployerPlaybackMode() == Config.DeployerPlaybackMode.INTERACTION_ONLY
            ? AUTOMATION_TTL_INTERACTION_TICKS
            : AUTOMATION_TTL_CONTINUOUS_TICKS;
    }

    /** Get (without creating) the link at the given (level, pos), or {@code null}. */
    @Nullable
    public static PolyphonyLink get(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel sl)) return null;
        return LINKS.get(LinkKey.of(sl, pos));
    }


    // ---- dispatch path (called from the TrackerBar mixin) -------------------------------------

    /**
     * Dispatch a single MIDI short-message that just passed through the tracker
     * at {@code pos} to whichever linked player is responsible for its channel.
     *
     * <p>This is the hot path - it must be cheap. We do exactly one HashMap
     * lookup, one channel-program update (if applicable), and one packet send
     * for note on/off events.</p>
     *
     * @param level     the server level the tracker is in.
     * @param pos       the tracker block position.
     * @param status    the MIDI status byte (already including channel, e.g. 0x90 = note-on ch 0).
     * @param data1     MIDI data byte 1 (note number for NoteOn/Off, program for ProgramChange).
     * @param data2     MIDI data byte 2 (velocity for NoteOn/Off; ignored for ProgramChange).
     */
    public static void dispatchNote(ServerLevel level, BlockPos pos, int status, int data1, int data2) {
        dispatchNote(level, pos, status, data1, data2, System.nanoTime());
    }

    /**
     * Variant that accepts the {@link System#nanoTime()} stamp captured at the
     * moment the upstream sequencer emitted this event. The stamp is forwarded
     * to clients so they can schedule the event for sample-accurate playback,
     * decoupling audible timing from network/render jitter.
     */
    public static void dispatchNote(ServerLevel level, BlockPos pos, int status, int data1, int data2, long eventNanos) {
        int command = status & 0xF0;
        int channel = status & 0x0F;
        LinkKey key = LinkKey.of(level, pos);

        // Stamp activity for the abandoned-tracker watchdog. Any event from
        // the tracker (NoteOn, NoteOff, CC, ProgramChange, system) counts as
        // "still alive". When this stops being updated while we still own
        // in-flight notes, pruneAbandonedTrackers releases them.
        LAST_TRACKER_ACTIVITY_TICK.put(key, currentServerTick(level.getServer()));

        if (command == 0xC0 /* ProgramChange */) {
            rememberChannelProgram(key, channel, data1 & 0x7F);
        }

        int note     = data1 & 0x7F;
        int velocity = data2 & 0x7F;
        boolean noteOff = command == 0x80 || (command == 0x90 && velocity == 0);

        if (!noteOff && isPostStopNoteOnSuppressed(level, key, command, velocity)) {
            debugRoute("drop:post-stop-note-on", level, pos, status, data1, data2, null);
            return;
        }

        // Service tracked NoteOffs BEFORE the link-existence check so that notes
        // started by automation holders (deployers etc.) are properly stopped even
        // after their TTL has expired and their link participation has been removed.
        if (noteOff) {
            List<TrackedOwner> recordedOwners = consumeTrackedOwners(key, new ActiveNoteKey(channel, note));
            if (recordedOwners != null && !recordedOwners.isEmpty()) {
                // Multiple buses can hold the same (channel, note) simultaneously
                // when several linked instruments are routed to the same channel
                // (e.g. main hand + off hand both playing it). Each bus needs its
                // own NoteOff packet so the corresponding voice is freed.
                for (TrackedOwner owner : recordedOwners) {
                    Vec3 recordedOwnerPos = resolveHolderPosition(level, owner.realHolderId());
                    if (sendNotePacket(level, pos, recordedOwnerPos, owner.realHolderId(), owner.sourceBusId(),
                        0, channel, 0x80, note, velocity, eventNanos)) {
                        debugRoute("sent:tracked-note-off", level, pos, status, data1, data2, owner.realHolderId());
                    } else {
                        debugRoute("drop:tracked-owner-missing", level, pos, status, data1, data2, owner.realHolderId());
                    }
                }
                return;
            }
            // Optimization: once no tracked notes remain on this tracker, ignore
            // unmatched NoteOff noise until a new NoteOn is tracked again.
            if (!hasTrackedNotes(key)) {
                debugRoute("drop:untracked-note-off", level, pos, status, data1, data2, null);
                return;
            }
        }

        PolyphonyLink link = get(level, pos);
        if (link == null || link.isEmpty()) {
            if (command == 0xC0) {
                debugRoute("program:cached", level, pos, status, data1, data2, null);
                return;
            }
            debugRoute("drop:no-link", level, pos, status, data1, data2, null);
            return;
        }

        // Keep optional per-channel item-filter constraints in sync with tracker ghost slots.
        syncTrackerFrequencyFilters(level, pos, link);

        // Track the latest GM program per channel so the assignment algorithm
        // can prefer matching instrument families.
        if (command == 0xC0 /* ProgramChange */) {
            link.setChannelProgram(channel, data1 & 0x7F);
            debugRoute("program", level, pos, status, data1, data2, null);
            return;
        }

        // We only deliver actual note events to clients.
        if (command == 0xB0 /* ControlChange */) {
            // MIDI All Sound Off / All Notes Off can arrive when transport stops abruptly.
            if ((data1 & 0x7F) == 120 || (data1 & 0x7F) == 123) {
                flushTrackedNotesForChannel(level, pos, channel);
                debugRoute("sent:cc-all-notes-off", level, pos, status, data1, data2, null);
            }
            return;
        }
        if (command != 0x80 /* NoteOff */ && command != 0x90 /* NoteOn */) {
            debugRoute("drop:non-note", level, pos, status, data1, data2, null);
            return;
        }

        // noteOff / note / velocity already computed above.
        ActiveNoteKey activeNoteKey = new ActiveNoteKey(channel, note);

        // (Tracked NoteOff already handled above; this branch only reached if no prior
        //  track entry existed — fall through to current-assignee delivery as normal.)

        List<PolyphonyLink.ChannelAssignee> assignees = link.assigneesFor(channel);
        if (assignees.isEmpty()) {
            debugRoute("drop:no-assignee", level, pos, status, data1, data2, null);
            return;
        }

        // Hand off any voices owned by buses that are no longer assigned to
        // this (channel, note) before introducing the new ones, so dropped
        // hands/instruments don't leak hanging voices on the client.
        if (!noteOff) {
            List<TrackedOwner> previousOwners = ACTIVE_NOTE_OWNERS.getOrDefault(key, Map.of()).get(activeNoteKey);
            if (previousOwners != null && !previousOwners.isEmpty()) {
                for (TrackedOwner prev : List.copyOf(previousOwners)) {
                    boolean stillAssigned = false;
                    for (PolyphonyLink.ChannelAssignee a : assignees) {
                        if (prev.sourceBusId().equals(a.sourceBusId())) {
                            stillAssigned = true;
                            break;
                        }
                    }
                    if (stillAssigned) continue;
                    Vec3 prevPos = resolveHolderPosition(level, prev.realHolderId());
                    sendNotePacket(level, pos, prevPos, prev.realHolderId(), prev.sourceBusId(),
                        0, channel, 0x80, note, 0, 0L);
                    untrackNoteOwner(key, activeNoteKey, prev);
                    debugRoute("sent:handoff-note-off", level, pos, status, data1, data2, prev.realHolderId());
                }
            }
        }

        // Dispatch to every eligible participant so each held instrument
        // produces simultaneous, independently-bused audio on the client.
        boolean anySent = false;
        for (PolyphonyLink.ChannelAssignee assignee : assignees) {
            UUID assigneeRealId = assignee.realHolderId();
            UUID assigneeBusId = assignee.sourceBusId();

            ServerPlayer target = level.getServer().getPlayerList().getPlayer(assigneeRealId);
            if (target != null && !holdsLinkedInstrument(target, key)) {
                // Don't hard-drop note routing here; assignment already came from held-link sync.
                debugRoute("warn:not-holding", level, pos, status, data1, data2, assigneeRealId);
            }

            // Resolve the assignee's held instrument family and force playback timbre to it.
            // This keeps player output aligned with what they're physically holding.
            InstrumentFamily family = assignee.family();

            // Safety belt: even if assignment data got stale, keep drums isolated.
            // ONE_MAN_BAND (wildcard) is exempt - it intentionally covers all channels.
            if (!family.isWildcard()) {
                if (channel == 9 && family != InstrumentFamily.DRUM_KIT) {
                    debugRoute("drop:drums-no-drum-kit", level, pos, status, data1, data2, assigneeRealId);
                    continue;
                }
                if (channel != 9 && family == InstrumentFamily.DRUM_KIT) {
                    debugRoute("drop:melodic-drum-kit", level, pos, status, data1, data2, assigneeRealId);
                    continue;
                }
            }

            // ONE_MAN_BAND can either use raw MIDI programs or be constrained to our
            // supported instrument families, controlled by config.
            int program;
            if (family.isWildcard()) {
                int trackedProgram = trackedProgramFor(key, channel, link);
                if (Config.oneManBandUsesAllGmPrograms()) {
                    program = trackedProgram;
                } else {
                    InstrumentFamily mapped = InstrumentFamily.forMidiChannelAndProgram(channel, trackedProgram);
                    program = (channel == 9) ? 127 : mapped.canonicalGmProgram();
                }
            } else {
                program = (channel == 9) ? 127 : family.canonicalGmProgram();
            }

            Vec3 holderPos = resolveHolderPosition(level, assigneeRealId);
            if (sendNotePacket(level, pos, holderPos, assigneeRealId, assigneeBusId,
                program, channel, command, note, velocity, eventNanos)) {
                anySent = true;
                if (!noteOff) {
                    trackNoteOwner(key, activeNoteKey, new TrackedOwner(assigneeRealId, assigneeBusId));
                }
                debugRoute("sent", level, pos, status, data1, data2, assigneeRealId);
            } else {
                debugRoute("drop:no-player", level, pos, status, data1, data2, assigneeRealId);
            }
        }
        if (!anySent && !noteOff) {
            debugRoute("drop:no-player-any", level, pos, status, data1, data2, null);
        }
    }

    /** Drop active held-link participation for a logging-out player. */
    public static void onPlayerLogout(ServerPlayer player) {
        removeHolder(player.getUUID(), slFrom(player));
    }

    /**
     * Sends a MIDI note packet to the appropriate recipients with dimension and
     * distance checks.
     *
     * <p>The audible-distance budget is interpreted as listener-to-source distance.
     * <b>NoteOn</b> events are gated as follows:
     * <ul>
     *   <li>For a <em>player</em> holder: the holder always receives their own
     *       selfPlay packet (they are co-located with the source). Cross-dimension
     *       holders are skipped entirely. Watcher broadcasts are gated by each
     *       watcher's distance to the holder's world position.</li>
     *   <li>For a <em>non-player</em> holder (mob / deployer): each watcher is
     *       gated by their distance to the holder's world position.</li>
     * </ul>
     * <b>NoteOff / stop events</b> always go through without range checks so
     * in-flight notes are never left stuck.</p>
     *
     * @param trackerLevel the level the tracker block lives in.
     * @param trackerPos   the tracker block position (kept for diagnostic context).
     * @param holderPos    world position of the holder (mob / deployer); ignored when
     *                     the holder resolves to a live ServerPlayer.
     * @param realHolderId UUID of the actual holder (player / mob / automation), used
     *                     for player resolution and selfPlay flag determination.
     * @param sourceBusId  per-(holder, hand) UUID transmitted to the client as the audio
     *                     bus key. Distinct from {@code realHolderId} so a single player
     *                     holding two instruments produces two independent client buses.
     */
    private static boolean sendNotePacket(ServerLevel trackerLevel, BlockPos trackerPos,
                                          @Nullable Vec3 holderPos, UUID realHolderId, UUID sourceBusId,
                                          int program, int channel, int command, int note, int velocity,
                                          long eventNanos) {
        boolean isNoteOn = (command & 0xF0) == 0x90 && velocity > 0;
        int maxDistanceBlocksInt = simulationDistanceBlocks(trackerLevel.getServer());
        double maxDist = maxDistanceBlocksInt;
        double maxDistSq = maxDist * maxDist;

        // ---- Direct player recipient ----
        ServerPlayer directPlayer = trackerLevel.getServer().getPlayerList().getPlayer(realHolderId);
        if (directPlayer != null) {
            // Dimension gate: a holder in another dimension is not participating in this
            // tracker's audio at all (their world-space position is meaningless to listeners
            // here). NoteOffs still pass through so any in-flight notes don't stick.
            if (isNoteOn && directPlayer.level() != trackerLevel) return false;

            // selfPlay=true: the receiving player IS the instrument holder. The audible-distance
            // budget is about listener-to-source distance, and the listener IS the source here,
            // so distance is always zero - holders always hear themselves regardless of how far
            // they have wandered from the tracker that's driving the MIDI feed.
            Vec3 pp = directPlayer.position();
            PacketDistributor.sendToPlayer(directPlayer, new PlayInstrumentNotePayload(
                program, channel, command, note, velocity,
                true, (float) pp.x, (float) pp.y, (float) pp.z, maxDistanceBlocksInt,
                sourceBusId.getMostSignificantBits(), sourceBusId.getLeastSignificantBits(), eventNanos));

            // Also broadcast to nearby observers so they can hear the player positionally.
            // Mirrors the non-player holder branch: gate by each watcher's distance to the
            // holder's position, not by holder-to-tracker distance.
            for (ServerPlayer watcher : trackerLevel.players()) {
                if (watcher == directPlayer) continue; // already sent selfPlay above
                if (isNoteOn) {
                    double watcherDistSq = watcher.distanceToSqr(pp.x, pp.y, pp.z);
                    if (watcherDistSq > maxDistSq) continue;
                }
                PacketDistributor.sendToPlayer(watcher, new PlayInstrumentNotePayload(
                    program, channel, command, note, velocity,
                    false, (float) pp.x, (float) pp.y, (float) pp.z, maxDistanceBlocksInt,
                    sourceBusId.getMostSignificantBits(), sourceBusId.getLeastSignificantBits(), eventNanos));
            }
            return true;
        }

        // Non-player holder: if we cannot resolve a physical source position, never
        // synthesize from tracker position. For NoteOn we drop with warning; NoteOffs
        // are still sent (with position ignored client-side) to prevent stuck voices.
        Vec3 srcPos = holderPos;
        if (srcPos == null) {
            if (isNoteOn) {
                warnMissingHolderPosition(trackerLevel, trackerPos, realHolderId, channel, note);
                return false;
            }
            srcPos = Vec3.ZERO;
        }

        // ---- Non-player holder: broadcast to nearby players in the same level ----
        // level.players() already returns only players in trackerLevel, so the
        // dimension check is implicit; we only need the distance check.
        boolean sent = false;
        for (ServerPlayer watcher : trackerLevel.players()) {
            if (isNoteOn) {
                double distSq = watcher.distanceToSqr(srcPos.x, srcPos.y, srcPos.z);
                if (distSq > maxDistSq) continue;
            }
            PacketDistributor.sendToPlayer(watcher, new PlayInstrumentNotePayload(
                program, channel, command, note, velocity,
                false, (float) srcPos.x, (float) srcPos.y, (float) srcPos.z, maxDistanceBlocksInt,
                sourceBusId.getMostSignificantBits(), sourceBusId.getLeastSignificantBits(), eventNanos));
            sent = true;
        }
        return sent;
    }

    /**
     * Maximum audible-distance, in blocks, used both for routing-eligibility
     * gating and as the falloff scale forwarded to clients in the
     * {@link PlayInstrumentNotePayload}.
     *
     * <p>Decoupled from server simulation distance so playback range is not
     * implicitly tied to chunk loading - a small simulation distance no longer
     * means instruments have a tiny audible carry, and a large simulation
     * distance no longer means notes are routed across the whole world.</p>
     */
    private static int simulationDistanceBlocks(@Nullable MinecraftServer server) {
        // Server-applicable config: read on the server side. Clients receive the
        // resulting block count in the packet and apply their own falloff curve.
        return Math.max(16, Config.audibleDistanceBlocks());
    }

    private static void warnMissingHolderPosition(ServerLevel level, BlockPos trackerPos, UUID holderId, int channel, int note) {
        if (MISSING_HOLDER_POS_WARN_BUDGET.getAndDecrement() <= 0) return;
        CreatePolyphony.LOGGER.warn(
            "Dropping NoteOn: unresolved holder position. dim={} tracker={} holder={} ch={} note={}",
            level.dimension().location(), trackerPos.toShortString(), holderId, channel & 0x0F, note & 0x7F);
    }

    /**
     * Resolves the world position of a holder for use as the sound-source
     * position in the outgoing packet.
     *
     * <p>Lookup order:
     * <ol>
     *   <li>Direct server player (same UUID, any dimension).</li>
     *   <li>Entity by UUID within {@code level} (covers mobs).</li>
     *   <li>Stored automation position from {@link #TRANSIENT_HOLDER_EXPIRY}
     *       (covers deployer/contraption pseudo-UUIDs).</li>
     * </ol>
     * Returns {@code null} if none of the above applies; callers fall back to
     * the tracker position in that case.</p>
     */
    @Nullable
    private static Vec3 resolveHolderPosition(ServerLevel level, UUID holderId) {
        // Player
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(holderId);
        if (player != null) return player.position();
        // Mob / living entity in the level
        Entity entity = level.getEntity(holderId);
        if (entity != null) return entity.position();
        // Automation (deployer) – stored at registration time
        TransientHolderState state = TRANSIENT_HOLDER_EXPIRY.get(holderId);
        if (state != null && state.holderPos() != null) return state.holderPos();
        return null;
    }

    private static void trackNoteOwner(LinkKey key, ActiveNoteKey noteKey, TrackedOwner owner) {
        Map<ActiveNoteKey, List<TrackedOwner>> byNote = ACTIVE_NOTE_OWNERS.computeIfAbsent(key, ignored -> new HashMap<>());
        List<TrackedOwner> owners = byNote.computeIfAbsent(noteKey, ignored -> new ArrayList<>(2));
        // Keep the list de-duplicated on bus id; a re-trigger of the same note
        // by the same bus shouldn't grow the owner list unboundedly.
        for (TrackedOwner existing : owners) {
            if (existing.sourceBusId().equals(owner.sourceBusId())) return;
        }
        owners.add(owner);
    }

    private static void untrackNoteOwner(LinkKey key, ActiveNoteKey noteKey, TrackedOwner owner) {
        Map<ActiveNoteKey, List<TrackedOwner>> byNote = ACTIVE_NOTE_OWNERS.get(key);
        if (byNote == null) return;
        List<TrackedOwner> owners = byNote.get(noteKey);
        if (owners == null) return;
        owners.removeIf(o -> o.sourceBusId().equals(owner.sourceBusId()));
        if (owners.isEmpty()) byNote.remove(noteKey);
        if (byNote.isEmpty()) ACTIVE_NOTE_OWNERS.remove(key);
    }

    private static void flushTrackedNotesForChannel(ServerLevel level, BlockPos trackerPos, int channel) {
        LinkKey key = LinkKey.of(level, trackerPos);
        Map<ActiveNoteKey, List<TrackedOwner>> byNote = ACTIVE_NOTE_OWNERS.get(key);
        if (byNote == null || byNote.isEmpty()) return;

        for (Map.Entry<ActiveNoteKey, List<TrackedOwner>> entry : Map.copyOf(byNote).entrySet()) {
            ActiveNoteKey active = entry.getKey();
            if ((active.channel() & 0x0F) != (channel & 0x0F)) continue;
            for (TrackedOwner owner : List.copyOf(entry.getValue())) {
                Vec3 ownerPos = resolveHolderPosition(level, owner.realHolderId());
                sendNotePacket(level, trackerPos, ownerPos, owner.realHolderId(), owner.sourceBusId(),
                    0, active.channel(), 0x80, active.note(), 0, 0L);
            }
            byNote.remove(active);
        }
        if (byNote.isEmpty()) ACTIVE_NOTE_OWNERS.remove(key);
    }

    @Nullable
    private static List<TrackedOwner> consumeTrackedOwners(LinkKey key, ActiveNoteKey noteKey) {
        Map<ActiveNoteKey, List<TrackedOwner>> byNote = ACTIVE_NOTE_OWNERS.get(key);
        if (byNote == null) return null;
        List<TrackedOwner> owners = byNote.remove(noteKey);
        if (byNote.isEmpty()) ACTIVE_NOTE_OWNERS.remove(key);
        return owners;
    }

    private static boolean hasTrackedNotes(LinkKey key) {
        Map<ActiveNoteKey, List<TrackedOwner>> byNote = ACTIVE_NOTE_OWNERS.get(key);
        return byNote != null && !byNote.isEmpty();
    }

    private static void forceStopNotesOwnedBy(@Nullable ServerLevel level, LinkKey key, UUID realHolderId) {
        Map<ActiveNoteKey, List<TrackedOwner>> byNote = ACTIVE_NOTE_OWNERS.get(key);
        if (byNote != null && !byNote.isEmpty()) {
            for (Map.Entry<ActiveNoteKey, List<TrackedOwner>> entry : Map.copyOf(byNote).entrySet()) {
                ActiveNoteKey active = entry.getKey();
                List<TrackedOwner> owners = entry.getValue();
                for (TrackedOwner owner : List.copyOf(owners)) {
                    if (!realHolderId.equals(owner.realHolderId())) continue;
                    if (level != null) {
                        Vec3 holderPos = resolveHolderPosition(level, realHolderId);
                        sendNotePacket(level, key.pos(), holderPos, realHolderId, owner.sourceBusId(),
                            0, active.channel(), 0x80, active.note(), 0, 0L);
                    }
                    owners.remove(owner);
                }
                if (owners.isEmpty()) byNote.remove(active);
            }
            if (byNote.isEmpty()) ACTIVE_NOTE_OWNERS.remove(key);
        }

        // Belt-and-suspenders: even if note tracking missed an owner (e.g. due to a
        // dispatch that took a different code path, a desync between LinkKey
        // identities, or a client bus that received an early NoteOn from before
        // the server registered the owner), explicitly tell the client to silence
        // every voice on this holder's per-hand source buses. The client treats
        // command 0xF0 with note=1 as "stop the SourceBus identified by
        // (sourceMost, sourceLeast)" - so any ringing voice on either hand is
        // cut immediately. Without this, voices could persist until the bus's
        // 12s idle timeout (which itself only fires off the back of an unrelated
        // incoming packet), producing the "indefinitely held note" symptom.
        if (level != null) {
            sendBusStopPacket(level, key.pos(), realHolderId, PolyphonyLink.mainHandBusId(realHolderId));
            sendBusStopPacket(level, key.pos(), realHolderId, PolyphonyLink.offHandBusId(realHolderId));
        }
    }

    /**
     * Broadcasts a per-bus stop sentinel ({@code command=0xF0, note=1}) to the
     * holder (selfPlay) and every player in {@code level} so all clients with
     * a matching {@code SourceBus} silence it at once.
     *
     * <p>The packet carries {@code serverNanos = System.nanoTime()} (the
     * server-side emit time of the stop, <i>not</i> {@code 0L}). Any in-flight
     * NoteOn/NoteOff that the upstream MIDI sequencer emitted before this stop
     * therefore has a strictly smaller {@code serverNanos}, which lets the
     * client identify and drop those late packets instead of letting them
     * spawn a fresh {@link org.neonalig.createpolyphony.client.PolyphonyClientNoteHandler
     * SourceBus} after the panic. (The 0xF0 path on the client bypasses the
     * scheduler regardless of the timestamp, so encoding the emit time here
     * doesn't delay the silence.)</p>
     */
    private static void sendBusStopPacket(ServerLevel level, BlockPos trackerPos, UUID realHolderId, UUID sourceBusId) {
        int maxDistanceBlocksInt = simulationDistanceBlocks(level.getServer());
        long stopNanos = System.nanoTime();
        PlayInstrumentNotePayload payload = new PlayInstrumentNotePayload(
            0, 0, 0xF0, 1, 0,
            false, (float) trackerPos.getX(), (float) trackerPos.getY(), (float) trackerPos.getZ(),
            maxDistanceBlocksInt,
            sourceBusId.getMostSignificantBits(), sourceBusId.getLeastSignificantBits(), stopNanos);

        // Direct send to the holder if they're an online player so they hear their
        // own bus stop instantly, regardless of where they wandered.
        ServerPlayer holderPlayer = level.getServer().getPlayerList().getPlayer(realHolderId);
        if (holderPlayer != null) {
            PacketDistributor.sendToPlayer(holderPlayer, payload);
        }
        // Broadcast to everyone in the level: any of them may hold a positional
        // bus for this holder/hand from prior NoteOns and need to silence it too.
        for (ServerPlayer watcher : level.players()) {
            if (watcher == holderPlayer) continue;
            PacketDistributor.sendToPlayer(watcher, payload);
        }
    }

    @Nullable
    private static ServerLevel slFrom(ServerPlayer player) {
        return player.level() instanceof ServerLevel sl ? sl : null;
    }

    private static void syncHolderLinks(UUID holderId, Map<LinkKey, HeldInstruments> desired, @Nullable ServerLevel levelHint) {
        Map<LinkKey, HeldInstruments> current = ACTIVE_HAND_LINKS.get(holderId);

        if (current != null) {
            for (LinkKey key : Set.copyOf(current.keySet())) {
                if (desired.containsKey(key)) continue;
                // Send NoteOff for any notes this holder has in flight before removing them.
                forceStopNotesOwnedBy(levelHint, key, holderId);
                PolyphonyLink link = LINKS.get(key);
                if (link != null) {
                    link.removePlayer(holderId);
                    if (link.isEmpty()) {
                        LINKS.remove(key);
                        ACTIVE_NOTE_OWNERS.remove(key);
                        CHANNEL_PROGRAM_SNAPSHOT.remove(key);
                        TRACKER_STOP_SUPPRESSION_UNTIL.remove(key);
                    }
                }
            }
        }

        for (Map.Entry<LinkKey, HeldInstruments> entry : desired.entrySet()) {
            LinkKey key = entry.getKey();
            HeldInstruments held = entry.getValue();
            HeldInstruments prior = current != null ? current.get(key) : null;
            if (held.equals(prior)) continue;

            // Held-instrument state changed for an already-linked holder (e.g. main<->off
            // swap, instrument item replaced, family changed). The per-(holder, hand)
            // sourceBusId derives from the hand slot, so the new participant lands on a
            // different client bus than the one currently holding any in-flight notes.
            // Force-stop everything this holder still owns on this link before
            // re-registering, so the stale bus doesn't keep ringing the old timbre.
            if (prior != null) {
                forceStopNotesOwnedBy(levelHint, key, holderId);
            }

            PolyphonyLink link = LINKS.get(key);
            if (link == null) {
                link = new PolyphonyLink(key.levelPath(), key.pos());
                primeLinkProgramsFromSnapshot(key, link);
                if (levelHint != null && key.levelPath().equals(levelHint.dimension().location().toString())) {
                    syncTrackerFrequencyFilters(levelHint, key.pos(), link);
                }
                LINKS.put(key, link);
            }
            link.addOrRefreshPlayer(holderId,
                held.mainHandFamily(), held.offHandFamily(),
                held.mainHandItem(), held.offHandItem());
        }

        if (desired.isEmpty()) {
            ACTIVE_HAND_LINKS.remove(holderId);
        } else {
            ACTIVE_HAND_LINKS.put(holderId, desired);
        }
    }

    private static void removeHolder(UUID holderId, @Nullable ServerLevel levelHint) {
        Map<LinkKey, HeldInstruments> current = ACTIVE_HAND_LINKS.remove(holderId);
        if (current == null) return;
        for (LinkKey key : current.keySet()) {
            forceStopNotesOwnedBy(levelHint, key, holderId);
            PolyphonyLink link = LINKS.get(key);
            if (link == null) continue;
            link.removePlayer(holderId);
            if (link.isEmpty()) {
                LINKS.remove(key);
                ACTIVE_NOTE_OWNERS.remove(key);
                CHANNEL_PROGRAM_SNAPSHOT.remove(key);
                TRACKER_STOP_SUPPRESSION_UNTIL.remove(key);
            }
        }
    }

    private static void pruneExpiredTransientHolders(ServerLevel level) {
        if (TRANSIENT_HOLDER_EXPIRY.isEmpty()) return;
        long now = currentServerTick(level.getServer());
        for (Map.Entry<UUID, TransientHolderState> entry : Map.copyOf(TRANSIENT_HOLDER_EXPIRY).entrySet()) {
            if (entry.getValue().expiryTick() > now) continue;
            UUID holderId = entry.getKey();
            TransientHolderState state = TRANSIENT_HOLDER_EXPIRY.remove(holderId);
            // Hard-expire: the deployer (or other transient holder) has stopped activating
            // / is no longer holding a linked instrument. Per spec, any swap of a holder's
            // instrument state must immediately stop everything it was playing - waiting for
            // a possibly-distant sequencer NoteOff would leave audible notes ringing well
            // past the moment the holder ceased to participate.
            ServerLevel removeLevel = state != null ? state.level() : level;
            removeHolder(holderId, removeLevel);
        }
    }

    private static void pruneExpiredStopSuppression(ServerLevel level) {
        if (TRACKER_STOP_SUPPRESSION_UNTIL.isEmpty()) return;
        long now = currentServerTick(level.getServer());
        String levelPath = level.dimension().location().toString();
        TRACKER_STOP_SUPPRESSION_UNTIL.entrySet().removeIf(entry ->
            levelPath.equals(entry.getKey().levelPath()) && entry.getValue() <= now);
    }

    /**
     * Watchdog: any tracker in this level whose last MIDI activity was more
     * than {@link #TRACKER_INACTIVITY_FLUSH_TICKS} ago AND that still owns
     * in-flight tracked notes is presumed to have stopped without sending the
     * matching NoteOffs (player pressed stop, sequencer was halted from a UI,
     * tracker was de-powered, etc.). Run {@link #onTrackerStopped} to release
     * those tracked notes so non-player holders (mobs, deployers) don't ring
     * the last NoteOn forever.
     *
     * <p>The activity timestamp is also forgotten for trackers that no longer
     * own any tracked notes - keeping the map from growing without bound when
     * many one-shot trackers come and go around the world.</p>
     */
    private static void pruneAbandonedTrackers(ServerLevel level) {
        if (LAST_TRACKER_ACTIVITY_TICK.isEmpty()) return;
        long now = currentServerTick(level.getServer());
        String levelPath = level.dimension().location().toString();

        for (Map.Entry<LinkKey, Long> entry : Map.copyOf(LAST_TRACKER_ACTIVITY_TICK).entrySet()) {
            LinkKey key = entry.getKey();
            if (!levelPath.equals(key.levelPath())) continue;

            long lastActivity = entry.getValue();
            if (now - lastActivity < TRACKER_INACTIVITY_FLUSH_TICKS) continue;

            // No tracked notes: this tracker isn't holding anything, just retire
            // its activity record so we don't scan it again.
            Map<ActiveNoteKey, List<TrackedOwner>> byNote = ACTIVE_NOTE_OWNERS.get(key);
            if (byNote == null || byNote.isEmpty()) {
                LAST_TRACKER_ACTIVITY_TICK.remove(key);
                continue;
            }

            // Has tracked notes AND has gone silent: presume stopped and flush.
            // onTrackerStopped sends explicit NoteOff packets for every tracked
            // owner, which is what makes mobs/deployers/etc. stop ringing.
            onTrackerStopped(level, key.pos());
            LAST_TRACKER_ACTIVITY_TICK.remove(key);
        }
    }


    private static boolean isPostStopNoteOnSuppressed(ServerLevel level, LinkKey key, int command, int velocity) {
        if ((command & 0xF0) != 0x90 || (velocity & 0x7F) == 0) return false;
        Long until = TRACKER_STOP_SUPPRESSION_UNTIL.get(key);
        if (until == null) return false;
        long now = currentServerTick(level.getServer());
        if (now >= until) {
            TRACKER_STOP_SUPPRESSION_UNTIL.remove(key);
            return false;
        }
        return true;
    }

    private static void syncMobHolders(ServerLevel level) {
        String levelPath = level.dimension().location().toString();
        Set<UUID> previous = ACTIVE_MOB_HOLDERS_BY_LEVEL.getOrDefault(levelPath, Set.of());
        Set<UUID> seen = new java.util.HashSet<>();

        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof Mob mob)) continue;

            Map<LinkKey, HeldInstruments> desired = desiredHeldLinks(
                mob.getItemBySlot(EquipmentSlot.MAINHAND),
                mob.getItemBySlot(EquipmentSlot.OFFHAND));
            if (desired.isEmpty()) continue;

            UUID holderId = mob.getUUID();
            syncHolderLinks(holderId, desired, level);
            seen.add(holderId);
        }

        for (UUID stale : previous) {
            if (seen.contains(stale)) continue;
            removeHolder(stale, level);
        }

        if (seen.isEmpty()) {
            ACTIVE_MOB_HOLDERS_BY_LEVEL.remove(levelPath);
        } else {
            ACTIVE_MOB_HOLDERS_BY_LEVEL.put(levelPath, seen);
        }
    }

    private static long currentServerTick(MinecraftServer server) {
        return server.getTickCount();
    }

    private static boolean holdsLinkedInstrument(ServerPlayer player, LinkKey key) {
        ItemStack main = player.getItemBySlot(EquipmentSlot.MAINHAND);
        if (InstrumentItem.familyOf(main) != null && InstrumentLinkData.matches(main, key)) return true;

        ItemStack off = player.getItemBySlot(EquipmentSlot.OFFHAND);
        return InstrumentItem.familyOf(off) != null && InstrumentLinkData.matches(off, key);
    }

    private static Map<LinkKey, HeldInstruments> desiredHeldLinks(ServerPlayer player) {
        return desiredHeldLinks(
            player.getItemBySlot(EquipmentSlot.MAINHAND),
            player.getItemBySlot(EquipmentSlot.OFFHAND));
    }

    private static Map<LinkKey, HeldInstruments> desiredHeldLinks(ItemStack mainHand, ItemStack offHand) {
        Map<LinkKey, HeldInstruments> desired = new HashMap<>();
        collectHeldLink(desired, mainHand, true);
        collectHeldLink(desired, offHand, false);
        return desired;
    }

    private static void collectHeldLink(Map<LinkKey, HeldInstruments> out, ItemStack stack, boolean mainHand) {
        InstrumentFamily family = InstrumentItem.familyOf(stack);
        if (family == null) return;
        InstrumentLinkData.LinkTarget target = InstrumentLinkData.target(stack);
        if (target == null) return;
        LinkKey key = new LinkKey(target.levelPath(), target.pos().immutable());
        HeldInstruments prior = out.get(key);
        Item item = stack.getItem();
        if (prior == null) {
            out.put(key, mainHand
                ? new HeldInstruments(family, null, item, null)
                : new HeldInstruments(null, family, null, item));
        } else {
            out.put(key, mainHand
                ? prior.withMainHand(family, item)
                : prior.withOffHand(family, item));
        }
    }

    private static void syncTrackerFrequencyFilters(ServerLevel level, BlockPos pos, PolyphonyLink link) {
        link.setChannelInstrumentFilters(readTrackerChannelInstrumentFilters(level, pos));
    }

    @Nullable
    private static Item[] readTrackerChannelInstrumentFilters(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !TRACKER_BE_CLASS.equals(be.getClass().getName())) {
            return null;
        }

        if (!ensureTrackerReflection(be.getClass())) {
            return null;
        }

        Object midiSourceObj = getFieldValue(trackerMidiSourceField, be);
        if (midiSourceObj == null) {
            return null;
        }

        Object ghostInvObj = getFieldValue(midiSourceGhostInvField, midiSourceObj);
        if (!(ghostInvObj instanceof ItemStackHandler ghostInv)) {
            return null;
        }

        Item[] channelFilters = new Item[16];
        boolean any = false;
        for (int ch = 0; ch < 16; ch++) {
            ItemStack filterStack = ghostInv.getStackInSlot(ch);
            if (InstrumentItem.familyOf(filterStack) == null) {
                channelFilters[ch] = null;
                continue;
            }
            channelFilters[ch] = filterStack.getItem();
            any = true;
        }
        return any ? channelFilters : null;
    }

    private static boolean ensureTrackerReflection(Class<?> trackerClass) {
        if (trackerReflectionInitialized) {
            return trackerMidiSourceField != null && midiSourceGhostInvField != null;
        }
        trackerReflectionInitialized = true;
        try {
            trackerMidiSourceField = trackerClass.getDeclaredField("midiSourceBehaviour");
            trackerMidiSourceField.setAccessible(true);

            Class<?> midiSourceClass = Class.forName("com.finchy.pipeorgans.content.midi.MidiSourceBehaviour");
            midiSourceGhostInvField = midiSourceClass.getDeclaredField("storedGhostInv");
            midiSourceGhostInvField.setAccessible(true);
            return true;
        } catch (Throwable t) {
            trackerMidiSourceField = null;
            midiSourceGhostInvField = null;
            return false;
        }
    }

    @Nullable
    private static Object getFieldValue(@Nullable Field field, Object owner) {
        if (field == null || owner == null) return null;
        try {
            return field.get(owner);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private static int trackedProgramFor(LinkKey key, int channel, PolyphonyLink link) {
        if (channel < 0 || channel > 15) return 0;
        int live = link.channelProgramRaw(channel);
        if (live >= 0) {
            return live & 0x7F;
        }
        int[] snapshot = CHANNEL_PROGRAM_SNAPSHOT.get(key);
        if (snapshot != null) {
            int cached = snapshot[channel];
            if (cached >= 0) {
                return cached & 0x7F;
            }
        }
        return 0;
    }

    private static void rememberChannelProgram(LinkKey key, int channel, int program) {
        if (channel < 0 || channel > 15) return;
        int[] snapshot = CHANNEL_PROGRAM_SNAPSHOT.computeIfAbsent(key, ignored -> {
            int[] arr = new int[16];
            Arrays.fill(arr, -1);
            return arr;
        });
        snapshot[channel] = program & 0x7F;
    }

    private static void primeLinkProgramsFromSnapshot(LinkKey key, PolyphonyLink link) {
        int[] snapshot = CHANNEL_PROGRAM_SNAPSHOT.get(key);
        if (snapshot == null) return;
        for (int ch = 0; ch < 16; ch++) {
            int p = snapshot[ch];
            if (p >= 0) {
                link.setChannelProgram(ch, p & 0x7F);
            }
        }
    }

    /**
     * Iterate every active link (read-only). Useful for debug commands.
     */
    public static Map<LinkKey, PolyphonyLink> snapshot() {
        return new LinkedHashMap<>(LINKS);
    }

    // ---- key type ----------------------------------------------------------------------------

    /**
     * Composite map key. We store the dimension as its registry-path string
     * rather than the raw {@link ResourceKey} to avoid keeping references
     * to long-lived registry objects across reloads.
     */
    public record LinkKey(String levelPath, BlockPos pos) {
        public static LinkKey of(ServerLevel level, BlockPos pos) {
            return new LinkKey(level.dimension().location().toString(), pos.immutable());
        }
        @Override public String toString() {
            return levelPath + "@" + pos.toShortString();
        }
    }

    private record HeldInstruments(@Nullable InstrumentFamily mainHandFamily,
                                   @Nullable InstrumentFamily offHandFamily,
                                   @Nullable Item mainHandItem,
                                   @Nullable Item offHandItem) {
        private HeldInstruments withMainHand(InstrumentFamily family, Item item) {
            return new HeldInstruments(family, offHandFamily, item, offHandItem);
        }

        private HeldInstruments withOffHand(InstrumentFamily family, Item item) {
            return new HeldInstruments(mainHandFamily, family, mainHandItem, item);
        }
    }

    private record ActiveNoteKey(int channel, int note) { }

    /**
     * One in-flight note's bus-level owner. {@code realHolderId} is the actual
     * player/mob/automation holder used for player resolution and selfPlay
     * gating; {@code sourceBusId} is the per-(holder, hand) bus key transmitted
     * to the client so the matching NoteOff stops the correct synth voice.
     */
    private record TrackedOwner(UUID realHolderId, UUID sourceBusId) { }

    private static void debugRoute(String phase, ServerLevel level, BlockPos pos,
                                   int status, int data1, int data2, @Nullable UUID assignee) {
        if (!CreatePolyphony.LOGGER.isDebugEnabled()) return;
        if (NOTE_ROUTE_DEBUG_BUDGET.getAndDecrement() <= 0) return;
        CreatePolyphony.LOGGER.debug(
            "route:{} dim={} pos={} st=0x{} d1={} d2={} assignee={}",
            phase, level.dimension().location(), pos.toShortString(),
            Integer.toHexString(status & 0xFF), data1 & 0x7F, data2 & 0x7F, assignee);
    }
}
