package org.neonalig.createpolyphony.link;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
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

    /** Active held-link participation: player UUID -> links currently in hand/off-hand. */
    private static final Map<UUID, Map<LinkKey, InstrumentFamily>> ACTIVE_HAND_LINKS = new HashMap<>();
    /** Active note ownership per link: (channel,note) -> player UUID currently expected to receive NoteOff. */
    private static final Map<LinkKey, Map<ActiveNoteKey, UUID>> ACTIVE_NOTE_OWNERS = new HashMap<>();
    /** Last observed ProgramChange per tracker/channel, even while no players are linked. */
    private static final Map<LinkKey, int[]> CHANNEL_PROGRAM_SNAPSHOT = new HashMap<>();

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
        UUID id = player.getUUID();
        Map<LinkKey, InstrumentFamily> desired = desiredHeldLinks(player);
        Map<LinkKey, InstrumentFamily> current = ACTIVE_HAND_LINKS.get(id);

        if (current != null) {
            for (LinkKey key : Set.copyOf(current.keySet())) {
                if (desired.containsKey(key)) continue;
                PolyphonyLink link = LINKS.get(key);
                if (link != null) {
                    forceStopNotesOwnedBy(slFrom(player), key, id);
                    link.removePlayer(id);
                    if (link.isEmpty()) {
                        LINKS.remove(key);
                        ACTIVE_NOTE_OWNERS.remove(key);
                        CHANNEL_PROGRAM_SNAPSHOT.remove(key);
                    }
                }
            }
        }

        for (Map.Entry<LinkKey, InstrumentFamily> entry : desired.entrySet()) {
            LinkKey key = entry.getKey();
            InstrumentFamily family = entry.getValue();
            if (current != null && family == current.get(key)) continue;

            PolyphonyLink link = LINKS.get(key);
            if (link == null) {
                link = new PolyphonyLink(key.levelPath(), key.pos());
                primeLinkProgramsFromSnapshot(key, link);
                LINKS.put(key, link);
            }
            ItemStack stack = findHeldStackFor(player, key, family);
            if (!stack.isEmpty()) {
                link.addOrRefreshPlayer(player, stack);
            }
        }

        if (desired.isEmpty()) {
            ACTIVE_HAND_LINKS.remove(id);
        } else {
            ACTIVE_HAND_LINKS.put(id, desired);
        }
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
        if (command != 0x80 /* NoteOff */ && command != 0x90 /* NoteOn */) {
            debugRoute("drop:non-note", level, pos, status, data1, data2, null);
            return;
        }

        int note = data1 & 0x7F;
        int velocity = data2 & 0x7F;
        boolean noteOff = command == 0x80 || velocity == 0;
        ActiveNoteKey activeNoteKey = new ActiveNoteKey(channel, note);

        if (noteOff) {
            UUID recordedOwner = consumeTrackedOwner(key, activeNoteKey);
            if (recordedOwner != null) {
                if (sendNotePacket(level, recordedOwner, 0, channel, 0x80, note, velocity)) {
                    debugRoute("sent:tracked-note-off", level, pos, status, data1, data2, recordedOwner);
                } else {
                    debugRoute("drop:tracked-owner-missing", level, pos, status, data1, data2, recordedOwner);
                }
                return;
            }
        }

        UUID assignee = link.assigneeFor(channel);
        if (assignee == null) {
            debugRoute("drop:no-assignee", level, pos, status, data1, data2, null);
            return;
        }

        ServerPlayer target = level.getServer().getPlayerList().getPlayer(assignee);
        if (target == null) {
            debugRoute("drop:no-player", level, pos, status, data1, data2, assignee);
            return;
        }

        if (!holdsLinkedInstrument(target, key)) {
            // Don't hard-drop note routing here; assignment already came from held-link sync.
            debugRoute("warn:not-holding", level, pos, status, data1, data2, assignee);
        }

        // Resolve the assignee's held instrument family and force playback timbre to it.
        // This keeps player output aligned with what they're physically holding.
        InstrumentFamily family = link.familyOf(assignee);
        if (family == null) {
            debugRoute("drop:no-family", level, pos, status, data1, data2, assignee);
            return;
        }

        // Safety belt: even if assignment data got stale, keep drums isolated.
        // ONE_MAN_BAND (wildcard) is exempt - it intentionally covers all channels.
        if (!family.isWildcard()) {
            if (channel == 9 && family != InstrumentFamily.DRUM_KIT) {
                debugRoute("drop:drums-no-drum-kit", level, pos, status, data1, data2, assignee);
                return;
            }
            if (channel != 9 && family == InstrumentFamily.DRUM_KIT) {
                debugRoute("drop:melodic-drum-kit", level, pos, status, data1, data2, assignee);
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

        if (sendNotePacket(level, assignee, program, channel, command, note, velocity)) {
            if (!noteOff) {
                UUID previousOwner = trackNoteOwner(key, activeNoteKey, assignee);
                if (previousOwner != null && !Objects.equals(previousOwner, assignee)) {
                    sendNotePacket(level, previousOwner, 0, channel, 0x80, note, 0);
                    debugRoute("sent:handoff-note-off", level, pos, status, data1, data2, previousOwner);
                }
            }
            debugRoute("sent", level, pos, status, data1, data2, assignee);
        } else {
            debugRoute("drop:no-player", level, pos, status, data1, data2, assignee);
        }
    }

    /** Drop active held-link participation for a logging-out player. */
    public static void onPlayerLogout(ServerPlayer player) {
        UUID id = player.getUUID();
        Map<LinkKey, InstrumentFamily> current = ACTIVE_HAND_LINKS.remove(id);
        if (current == null) return;
        for (LinkKey key : current.keySet()) {
            forceStopNotesOwnedBy(slFrom(player), key, id);
            PolyphonyLink link = LINKS.get(key);
            if (link == null) continue;
            link.removePlayer(id);
            if (link.isEmpty()) {
                LINKS.remove(key);
                ACTIVE_NOTE_OWNERS.remove(key);
                CHANNEL_PROGRAM_SNAPSHOT.remove(key);
            }
        }
    }

    private static boolean sendNotePacket(ServerLevel level, UUID assignee, int program, int channel, int command, int note, int velocity) {
        ServerPlayer target = level.getServer().getPlayerList().getPlayer(assignee);
        if (target == null) return false;
        PacketDistributor.sendToPlayer(target,
            new PlayInstrumentNotePayload(program, channel, command, note, velocity));
        return true;
    }

    private static UUID trackNoteOwner(LinkKey key, ActiveNoteKey noteKey, UUID assignee) {
        return ACTIVE_NOTE_OWNERS
            .computeIfAbsent(key, ignored -> new HashMap<>())
            .put(noteKey, assignee);
    }

    @Nullable
    private static UUID consumeTrackedOwner(LinkKey key, ActiveNoteKey noteKey) {
        Map<ActiveNoteKey, UUID> byNote = ACTIVE_NOTE_OWNERS.get(key);
        if (byNote == null) return null;
        UUID owner = byNote.remove(noteKey);
        if (byNote.isEmpty()) ACTIVE_NOTE_OWNERS.remove(key);
        return owner;
    }

    private static void forceStopNotesOwnedBy(@Nullable ServerLevel level, LinkKey key, UUID playerId) {
        Map<ActiveNoteKey, UUID> byNote = ACTIVE_NOTE_OWNERS.get(key);
        if (byNote == null || byNote.isEmpty()) return;

        if (level != null) {
            ServerPlayer target = level.getServer().getPlayerList().getPlayer(playerId);
            if (target != null) {
                for (Map.Entry<ActiveNoteKey, UUID> entry : Map.copyOf(byNote).entrySet()) {
                    if (!playerId.equals(entry.getValue())) continue;
                    ActiveNoteKey active = entry.getKey();
                    PacketDistributor.sendToPlayer(target,
                        new PlayInstrumentNotePayload(0, active.channel(), 0x80, active.note(), 0));
                    byNote.remove(active);
                }
            }
        }

        byNote.values().removeIf(playerId::equals);
        if (byNote.isEmpty()) ACTIVE_NOTE_OWNERS.remove(key);
    }

    @Nullable
    private static ServerLevel slFrom(ServerPlayer player) {
        return player.level() instanceof ServerLevel sl ? sl : null;
    }

    private static boolean holdsLinkedInstrument(ServerPlayer player, LinkKey key) {
        ItemStack main = player.getItemBySlot(EquipmentSlot.MAINHAND);
        if (InstrumentItem.familyOf(main) != null && InstrumentLinkData.matches(main, key)) return true;

        ItemStack off = player.getItemBySlot(EquipmentSlot.OFFHAND);
        return InstrumentItem.familyOf(off) != null && InstrumentLinkData.matches(off, key);
    }

    private static Map<LinkKey, InstrumentFamily> desiredHeldLinks(ServerPlayer player) {
        Map<LinkKey, InstrumentFamily> desired = new HashMap<>();
        // Off-hand takes precedence when both hands are linked to the same tracker.
        collectHeldLink(desired, player.getItemBySlot(EquipmentSlot.OFFHAND), false);
        collectHeldLink(desired, player.getItemBySlot(EquipmentSlot.MAINHAND), true);
        return desired;
    }

    private static void collectHeldLink(Map<LinkKey, InstrumentFamily> out, ItemStack stack, boolean onlyIfAbsent) {
        InstrumentFamily family = InstrumentItem.familyOf(stack);
        if (family == null) return;
        InstrumentLinkData.LinkTarget target = InstrumentLinkData.target(stack);
        if (target == null) return;
        LinkKey key = new LinkKey(target.levelPath(), target.pos().immutable());
        if (onlyIfAbsent) {
            out.putIfAbsent(key, family);
        } else {
            out.put(key, family);
        }
    }

    private static ItemStack findHeldStackFor(ServerPlayer player, LinkKey key, InstrumentFamily family) {
        ItemStack main = player.getItemBySlot(EquipmentSlot.MAINHAND);
        if (InstrumentItem.familyOf(main) == family && InstrumentLinkData.matches(main, key)) return main;

        ItemStack off = player.getItemBySlot(EquipmentSlot.OFFHAND);
        if (InstrumentItem.familyOf(off) == family && InstrumentLinkData.matches(off, key)) return off;

        return ItemStack.EMPTY;
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
