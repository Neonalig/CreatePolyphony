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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
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

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
    /** Active note ownership per link: (channel,note) -> player UUID currently expected to receive NoteOff. */
    private static final Map<LinkKey, Map<ActiveNoteKey, UUID>> ACTIVE_NOTE_OWNERS = new HashMap<>();
    /** Last observed ProgramChange per tracker/channel, even while no players are linked. */
    private static final Map<LinkKey, int[]> CHANNEL_PROGRAM_SNAPSHOT = new HashMap<>();
    /** Expiration state for transient automation holders (deployer interactions, depot/ground stack targets). */
    private static final Map<UUID, TransientHolderState> TRANSIENT_HOLDER_EXPIRY = new HashMap<>();

    private record TransientHolderState(ServerLevel level, long expiryTick, @Nullable Vec3 holderPos) {}
    /** Snapshot of currently synced mob holders per level for cheap stale-holder cleanup. */
    private static final Map<String, Set<UUID>> ACTIVE_MOB_HOLDERS_BY_LEVEL = new HashMap<>();

    private static final int MOB_SYNC_INTERVAL_TICKS = 10;
    /**
     * Holder lifetime for deployer automation participation.
     * CONTINUOUS_POWERED uses a long grace to avoid play/pause stutter between sparse activations.
     * INTERACTION_ONLY keeps activation windows brief so playback is tied to full deploy interactions.
     */
    private static final int AUTOMATION_TTL_CONTINUOUS_TICKS = 20 * 60;
    private static final int AUTOMATION_TTL_INTERACTION_TICKS = 4;

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
        PacketDistributor.sendToPlayer(player, new PlayInstrumentNotePayload(0, 0, 0xF0, 0, 0, true, 0f, 0f, 0f,
            simulationDistanceBlocks(player.getServer()), id.getMostSignificantBits(), id.getLeastSignificantBits()));
        // Clear any tracked note-owners for this player so stale NoteOffs don't mis-route.
        ACTIVE_NOTE_OWNERS.forEach((key, byNote) -> byNote.values().removeIf(id::equals));
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

    /**
     * Force-stop any notes still tracked for this tracker when playback halts abruptly.
     * Called from tracker-stop mixin hooks as a safety net when sequencer NoteOffs are missing.
     */
    public static void onTrackerStopped(ServerLevel level, BlockPos pos) {
        LinkKey key = LinkKey.of(level, pos);
        Map<ActiveNoteKey, UUID> byNote = ACTIVE_NOTE_OWNERS.get(key);
        if (byNote == null || byNote.isEmpty()) return;

        for (Map.Entry<ActiveNoteKey, UUID> entry : Map.copyOf(byNote).entrySet()) {
            ActiveNoteKey active = entry.getKey();
            UUID owner = entry.getValue();
            Vec3 ownerPos = resolveHolderPosition(level, owner);
            sendNotePacket(level, pos, ownerPos, owner, 0, active.channel(), 0x80, active.note(), 0);
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
        int command = status & 0xF0;
        int channel = status & 0x0F;
        LinkKey key = LinkKey.of(level, pos);

        if (command == 0xC0 /* ProgramChange */) {
            rememberChannelProgram(key, channel, data1 & 0x7F);
        }

        int note     = data1 & 0x7F;
        int velocity = data2 & 0x7F;
        boolean noteOff = command == 0x80 || (command == 0x90 && velocity == 0);

        // Service tracked NoteOffs BEFORE the link-existence check so that notes
        // started by automation holders (deployers etc.) are properly stopped even
        // after their TTL has expired and their link participation has been removed.
        if (noteOff) {
            UUID recordedOwner = consumeTrackedOwner(key, new ActiveNoteKey(channel, note));
            if (recordedOwner != null) {
                Vec3 recordedOwnerPos = resolveHolderPosition(level, recordedOwner);
                if (sendNotePacket(level, pos, recordedOwnerPos, recordedOwner, 0, channel, 0x80, note, velocity)) {
                    debugRoute("sent:tracked-note-off", level, pos, status, data1, data2, recordedOwner);
                } else {
                    debugRoute("drop:tracked-owner-missing", level, pos, status, data1, data2, recordedOwner);
                }
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

        PolyphonyLink.ChannelAssignee assignee = link.assigneeFor(channel);
        if (assignee == null) {
            debugRoute("drop:no-assignee", level, pos, status, data1, data2, null);
            return;
        }

        UUID assigneePlayerId = assignee.playerId();

        ServerPlayer target = level.getServer().getPlayerList().getPlayer(assigneePlayerId);
        if (target != null && !holdsLinkedInstrument(target, key)) {
            // Don't hard-drop note routing here; assignment already came from held-link sync.
            debugRoute("warn:not-holding", level, pos, status, data1, data2, assigneePlayerId);
        }

        // Resolve the assignee's held instrument family and force playback timbre to it.
        // This keeps player output aligned with what they're physically holding.
        InstrumentFamily family = assignee.family();

        // Safety belt: even if assignment data got stale, keep drums isolated.
        // ONE_MAN_BAND (wildcard) is exempt - it intentionally covers all channels.
        if (!family.isWildcard()) {
            if (channel == 9 && family != InstrumentFamily.DRUM_KIT) {
                debugRoute("drop:drums-no-drum-kit", level, pos, status, data1, data2, assigneePlayerId);
                return;
            }
            if (channel != 9 && family == InstrumentFamily.DRUM_KIT) {
                debugRoute("drop:melodic-drum-kit", level, pos, status, data1, data2, assigneePlayerId);
                return;
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

        Vec3 holderPos = resolveHolderPosition(level, assigneePlayerId);
        if (sendNotePacket(level, pos, holderPos, assigneePlayerId, program, channel, command, note, velocity)) {
            if (!noteOff) {
                UUID previousOwner = trackNoteOwner(key, activeNoteKey, assigneePlayerId);
                if (previousOwner != null && !Objects.equals(previousOwner, assigneePlayerId)) {
                    Vec3 previousOwnerPos = resolveHolderPosition(level, previousOwner);
                    sendNotePacket(level, pos, previousOwnerPos, previousOwner, 0, channel, 0x80, note, 0);
                    debugRoute("sent:handoff-note-off", level, pos, status, data1, data2, previousOwner);
                }
            }
            debugRoute("sent", level, pos, status, data1, data2, assigneePlayerId);
        } else {
            debugRoute("drop:no-player", level, pos, status, data1, data2, assigneePlayerId);
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
     * <p><b>NoteOn</b> events are gated by:
     * <ol>
     *   <li>Same dimension as the tracker level (dimension guards prevent
     *       cross-dimension audio bleeding).</li>
     *   <li>Position within the server simulation distance radius of the
     *       tracker block position.</li>
     * </ol>
     * <b>NoteOff / stop events</b> always go through without range checks so
     * in-flight notes are never left stuck.</p>
     *
     * @param trackerLevel the level the tracker block lives in.
     * @param trackerPos   the tracker block position (used for distance checks).
     * @param holderPos    world position of the holder (mob / deployer).
     * @param holderId     UUID of the channel assignee (player UUID or non-player pseudo-UUID).
     */
    private static boolean sendNotePacket(ServerLevel trackerLevel, BlockPos trackerPos,
                                          @Nullable Vec3 holderPos, UUID holderId,
                                          int program, int channel, int command, int note, int velocity) {
        boolean isNoteOn = (command & 0xF0) == 0x90 && velocity > 0;
        int maxDistanceBlocksInt = simulationDistanceBlocks(trackerLevel.getServer());
        double maxDist = maxDistanceBlocksInt;
        double maxDistSq = maxDist * maxDist;

        // ---- Direct player recipient ----
        ServerPlayer directPlayer = trackerLevel.getServer().getPlayerList().getPlayer(holderId);
        if (directPlayer != null) {
            if (isNoteOn) {
                // Dimension check: only route NoteOn to players in the tracker's dimension.
                if (directPlayer.level() != trackerLevel) return false;
                // Distance check: stop delivering notes once the player wanders out of range.
                double distSq = directPlayer.distanceToSqr(trackerPos.getX() + 0.5,
                    trackerPos.getY() + 0.5, trackerPos.getZ() + 0.5);
                if (distSq > maxDistSq) return false;
            }
            // selfPlay=true: the receiving player IS the instrument holder.
            Vec3 pp = directPlayer.position();
            PacketDistributor.sendToPlayer(directPlayer, new PlayInstrumentNotePayload(
                program, channel, command, note, velocity,
                true, (float) pp.x, (float) pp.y, (float) pp.z, maxDistanceBlocksInt,
                holderId.getMostSignificantBits(), holderId.getLeastSignificantBits()));
            return true;
        }

        // Non-player holder: if we cannot resolve a physical source position, never
        // synthesize from tracker position. For NoteOn we drop with warning; NoteOffs
        // are still sent (with position ignored client-side) to prevent stuck voices.
        Vec3 srcPos = holderPos;
        if (srcPos == null) {
            if (isNoteOn) {
                warnMissingHolderPosition(trackerLevel, trackerPos, holderId, channel, note);
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
                holderId.getMostSignificantBits(), holderId.getLeastSignificantBits()));
            sent = true;
        }
        return sent;
    }

    private static int simulationDistanceBlocks(@Nullable MinecraftServer server) {
        if (server == null) return 16 * 10;
        return Math.max(1, server.getPlayerList().getSimulationDistance()) * 16;
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

    private static UUID trackNoteOwner(LinkKey key, ActiveNoteKey noteKey, UUID assignee) {
        return ACTIVE_NOTE_OWNERS
            .computeIfAbsent(key, ignored -> new HashMap<>())
            .put(noteKey, assignee);
    }

    private static void flushTrackedNotesForChannel(ServerLevel level, BlockPos trackerPos, int channel) {
        LinkKey key = LinkKey.of(level, trackerPos);
        Map<ActiveNoteKey, UUID> byNote = ACTIVE_NOTE_OWNERS.get(key);
        if (byNote == null || byNote.isEmpty()) return;

        for (Map.Entry<ActiveNoteKey, UUID> entry : Map.copyOf(byNote).entrySet()) {
            ActiveNoteKey active = entry.getKey();
            if ((active.channel() & 0x0F) != (channel & 0x0F)) continue;
            UUID owner = entry.getValue();
            Vec3 ownerPos = resolveHolderPosition(level, owner);
            sendNotePacket(level, trackerPos, ownerPos, owner, 0, active.channel(), 0x80, active.note(), 0);
            byNote.remove(active);
        }
        if (byNote.isEmpty()) ACTIVE_NOTE_OWNERS.remove(key);
    }

    @Nullable
    private static UUID consumeTrackedOwner(LinkKey key, ActiveNoteKey noteKey) {
        Map<ActiveNoteKey, UUID> byNote = ACTIVE_NOTE_OWNERS.get(key);
        if (byNote == null) return null;
        UUID owner = byNote.remove(noteKey);
        if (byNote.isEmpty()) ACTIVE_NOTE_OWNERS.remove(key);
        return owner;
    }

    private static void forceStopNotesOwnedBy(@Nullable ServerLevel level, LinkKey key, UUID holderId) {
        Map<ActiveNoteKey, UUID> byNote = ACTIVE_NOTE_OWNERS.get(key);
        if (byNote == null || byNote.isEmpty()) return;

        if (level != null) {
            for (Map.Entry<ActiveNoteKey, UUID> entry : Map.copyOf(byNote).entrySet()) {
                if (!holderId.equals(entry.getValue())) continue;
                ActiveNoteKey active = entry.getKey();
                Vec3 holderPos = resolveHolderPosition(level, holderId);
                sendNotePacket(level, key.pos(), holderPos, holderId, 0, active.channel(), 0x80, active.note(), 0);
                byNote.remove(active);
            }
        }

        byNote.values().removeIf(holderId::equals);
        if (byNote.isEmpty()) ACTIVE_NOTE_OWNERS.remove(key);
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
                    }
                }
            }
        }

        for (Map.Entry<LinkKey, HeldInstruments> entry : desired.entrySet()) {
            LinkKey key = entry.getKey();
            HeldInstruments held = entry.getValue();
            if (current != null && held.equals(current.get(key))) continue;

            PolyphonyLink link = LINKS.get(key);
            if (link == null) {
                link = new PolyphonyLink(key.levelPath(), key.pos());
                primeLinkProgramsFromSnapshot(key, link);
                LINKS.put(key, link);
            }
            link.addOrRefreshPlayer(holderId, held.mainHandFamily(), held.offHandFamily());
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
            }
        }
    }

    private static void pruneExpiredTransientHolders(ServerLevel level) {
        if (TRANSIENT_HOLDER_EXPIRY.isEmpty()) return;
        long now = currentServerTick(level.getServer());
        for (Map.Entry<UUID, TransientHolderState> entry : Map.copyOf(TRANSIENT_HOLDER_EXPIRY).entrySet()) {
            if (entry.getValue().expiryTick() > now) continue;
            UUID holderId = entry.getKey();
            TRANSIENT_HOLDER_EXPIRY.remove(holderId);
            // Soft-expire: remove channel assignment but do NOT force-stop notes.
            // Any in-flight notes are tracked in ACTIVE_NOTE_OWNERS; their NoteOffs
            // will arrive from the MIDI sequencer and route correctly via consumeTrackedOwner.
            removeHolderAssignmentOnly(holderId);
        }
    }

    /**
     * Removes a holder's channel-assignment participation without sending NoteOff packets.
     * Used for TTL-based soft expiry of automation (deployer) holders.
     * Contrast with {@link #removeHolder} which also force-stops all tracked notes.
     */
    private static void removeHolderAssignmentOnly(UUID holderId) {
        Map<LinkKey, HeldInstruments> current = ACTIVE_HAND_LINKS.remove(holderId);
        if (current == null) return;
        for (LinkKey key : current.keySet()) {
            PolyphonyLink link = LINKS.get(key);
            if (link == null) continue;
            link.removePlayer(holderId);
            if (link.isEmpty()) {
                LINKS.remove(key);
                // Intentionally keep ACTIVE_NOTE_OWNERS entries alive: NoteOffs that
                // arrive after the link goes empty still need to route to their owner
                // (via consumeTrackedOwner in dispatchNote) to stop client synth voices.
                CHANNEL_PROGRAM_SNAPSHOT.remove(key);
            }
        }
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
        if (prior == null) {
            out.put(key, mainHand
                ? new HeldInstruments(family, null)
                : new HeldInstruments(null, family));
        } else {
            out.put(key, mainHand
                ? prior.withMainHand(family)
                : prior.withOffHand(family));
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
                                   @Nullable InstrumentFamily offHandFamily) {
        private HeldInstruments withMainHand(InstrumentFamily family) {
            return new HeldInstruments(family, offHandFamily);
        }

        private HeldInstruments withOffHand(InstrumentFamily family) {
            return new HeldInstruments(mainHandFamily, family);
        }
    }

    private record ActiveNoteKey(int channel, int note) { }

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
