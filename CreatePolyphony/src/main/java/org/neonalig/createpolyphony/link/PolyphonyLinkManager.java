package org.neonalig.createpolyphony.link;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.instrument.InstrumentFamily;
import org.neonalig.createpolyphony.instrument.InstrumentItem;
import org.neonalig.createpolyphony.network.PlayInstrumentNotePayload;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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

    /** Reverse index from player UUID -&gt; the link they're linked to (max one). */
    private static final Map<UUID, LinkKey> PLAYER_LINK = new HashMap<>();

    private PolyphonyLinkManager() {}

    // ---- public API used by interaction handler -----------------------------------------------

    /**
     * Link {@code player} (holding {@code heldStack}) to the tracker at
     * {@code pos}. If the player was previously linked to a different tracker,
     * they are unlinked from it first.
     *
     * @return the resulting link, or {@code null} if the held item is not an
     *         {@link InstrumentItem}.
     */
    public static LinkAction linkOrToggle(ServerPlayer player, ServerLevel level, BlockPos pos, ItemStack heldStack) {
        InstrumentFamily family = InstrumentItem.familyOf(heldStack);
        if (family == null) return LinkAction.NOT_INSTRUMENT;

        UUID id = player.getUUID();
        LinkKey newKey = LinkKey.of(level, pos);
        boolean swapped = false;

        // Right-clicking the same tracker again toggles unlink.
        LinkKey prior = PLAYER_LINK.get(id);
        if (newKey.equals(prior)) {
            unlink(player);
            return LinkAction.UNLINKED;
        }

        // Drop any prior link if we are moving to a different tracker.
        if (prior != null && !prior.equals(newKey)) {
            swapped = true;
            PolyphonyLink old = LINKS.get(prior);
            if (old != null) {
                old.removePlayer(id);
                if (old.isEmpty()) LINKS.remove(prior);
            }
        }

        PolyphonyLink link = LINKS.computeIfAbsent(newKey, k -> new PolyphonyLink(k.levelPath, pos));
        link.addOrRefreshPlayer(player, heldStack);
        PLAYER_LINK.put(id, newKey);

        player.displayClientMessage(
            Component.translatable("message.createpolyphony.linked",
                "Tracker Bar @ " + pos.toShortString(),
                Component.translatable(family.translationKey())),
            true
        );
        CreatePolyphony.LOGGER.debug("Linked {} ({}) to {}", player.getName().getString(), family, newKey);
        return swapped ? LinkAction.SWAPPED : LinkAction.LINKED;
    }

    /**
     * Unlink the given player from whichever tracker they're currently linked
     * to (no-op if not linked).
     *
     * @return {@code true} if the player was unlinked.
     */
    public static boolean unlink(ServerPlayer player) {
        return unlink(player.getUUID(), player);
    }

    /** Internal: unlink by UUID, optionally messaging the player if online. */
    public static boolean unlink(UUID id, @Nullable ServerPlayer messageTarget) {
        LinkKey key = PLAYER_LINK.remove(id);
        if (key == null) return false;
        PolyphonyLink link = LINKS.get(key);
        if (link != null) {
            link.removePlayer(id);
            if (link.isEmpty()) LINKS.remove(key);
        }
        if (messageTarget != null) {
            messageTarget.displayClientMessage(
                Component.translatable("message.createpolyphony.unlinked"), true);
        }
        return true;
    }

    /** Get (without creating) the link at the given (level, pos), or {@code null}. */
    @Nullable
    public static PolyphonyLink get(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel sl)) return null;
        return LINKS.get(LinkKey.of(sl, pos));
    }

    /** True iff the given player is linked to <em>any</em> tracker. */
    public static boolean isLinked(UUID id) {
        return PLAYER_LINK.containsKey(id);
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
        PolyphonyLink link = get(level, pos);
        if (link == null || link.isEmpty()) return;

        int command = status & 0xF0;
        int channel = status & 0x0F;

        // Track the latest GM program per channel so the assignment algorithm
        // can prefer matching instrument families.
        if (command == 0xC0 /* ProgramChange */) {
            link.setChannelProgram(channel, data1 & 0x7F);
            return;
        }

        // We only deliver actual note events to clients.
        if (command != 0x80 /* NoteOff */ && command != 0x90 /* NoteOn */) return;

        UUID assignee = link.assigneeFor(channel);
        if (assignee == null) return;

        ServerPlayer target = level.getServer().getPlayerList().getPlayer(assignee);
        if (target == null) return;

        // Sample lookup is keyed on GM program. By convention, channel 10 (index 9)
        // is always percussion regardless of any ProgramChange it received - we
        // map that to GM program 128 (Standard Drum Kit, 1-indexed) here so the
        // client looks up the right "instruments.128.*" sound id.
        int program;
        if (channel == 9) {
            program = 127; // 0-indexed; the client's 1-indexed sound id will be "128"
        } else {
            program = link.channelProgram(channel) & 0x7F;
        }

        // Even though the assignee's held InstrumentFamily isn't on the wire,
        // we still resolve it server-side as a sanity check / debug breadcrumb.
        InstrumentFamily family = link.familyOf(assignee);
        if (family == null) return;

        PacketDistributor.sendToPlayer(target,
            new PlayInstrumentNotePayload(program, channel, command, data1 & 0x7F, data2 & 0x7F));
    }

    /**
     * Drop all links involving the given player UUID. Called on player logout
     * to keep state tidy.
     */
    public static void onPlayerLogout(UUID id) {
        unlink(id, null);
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
}
