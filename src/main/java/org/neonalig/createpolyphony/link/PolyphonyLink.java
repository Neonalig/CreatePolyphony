package org.neonalig.createpolyphony.link;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.neonalig.createpolyphony.instrument.InstrumentFamily;
import org.neonalig.createpolyphony.instrument.InstrumentItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The set of players currently linked to a single Sound-of-Steam Tracker Bar
 * block, plus the per-MIDI-channel routing decision derived from those players'
 * held instruments.
 *
 * <p><b>Channel distribution algorithm</b> (recomputed every time the membership
 * or instrument-program state changes):</p>
 * <ol>
 *   <li>If exactly <em>one</em> player is linked, that player receives every
 *       channel regardless of held instrument. (Solo mode.)</li>
 *   <li>Otherwise, for each MIDI channel 0-15:
 *       <ol type="a">
 *         <li>Determine the channel's "preferred" {@link InstrumentFamily} from
 *             its current GM program (or {@link InstrumentFamily#DRUM_KIT} for
 *             channel 9 by GM convention).</li>
 *         <li>Among the linked players, pick one whose held instrument's family
 *             matches that preferred family. Ties are broken by least-loaded
 *             player (fewest channels assigned so far) to keep load balanced.</li>
 *         <li>If no holder matches, fall back to round-robin across <em>all</em>
 *             linked players, again preferring least-loaded.</li>
 *       </ol>
 *   </li>
 * </ol>
 *
 * <p>This class is <b>logical-server-side only</b>. It is not Mixin-state; it
 * lives in {@link PolyphonyLinkManager} and is mutated only on the server
 * thread (Minecraft's main thread for that level).</p>
 *
 * <p>Channel-program state ({@link #channelPrograms}) is updated externally
 * via {@link #setChannelProgram(int, int)} as we observe ProgramChange events
 * passing through the tracker.</p>
 */
public final class PolyphonyLink {

    /** The level (dimension) the tracker is in - identified by its dimension key path. */
    private final String levelKey;
    /** The tracker block position. */
    private final BlockPos pos;

    /**
     * Linked players, keyed by UUID. {@link LinkedHashMap} so iteration order is
     * stable (= link order), which makes round-robin assignment deterministic.
     */
    private final LinkedHashMap<UUID, LinkedPlayer> players = new LinkedHashMap<>();

    /** Last seen GM program number per MIDI channel (0-15). -1 means "unknown / not yet seen". */
    private final int[] channelPrograms = new int[16];

    /** Cached assignment: channel index -&gt; UUID of player who plays it (or null = silent). */
    private final UUID[] channelAssignments = new UUID[16];

    public PolyphonyLink(String levelKey, BlockPos pos) {
        this.levelKey = levelKey;
        this.pos = pos.immutable();
        Arrays.fill(channelPrograms, -1);
        Arrays.fill(channelAssignments, null);
    }

    public String levelKey() { return levelKey; }
    public BlockPos pos() { return pos; }

    /** Returns the live (mutable) map of linked players. Caller must not mutate. */
    public Map<UUID, LinkedPlayer> players() {
        return Collections.unmodifiableMap(players);
    }

    /** True if the link has zero players and can be discarded by the manager. */
    public boolean isEmpty() {
        return players.isEmpty();
    }

    /**
     * Add or refresh a player on this link. If the player was already linked,
     * their held-instrument record is just updated. Triggers reassignment.
     *
     * @return {@code true} if this was a new link, {@code false} if it was a refresh.
     */
    public boolean addOrRefreshPlayer(ServerPlayer player, ItemStack heldStack) {
        InstrumentFamily family = InstrumentItem.familyOf(heldStack);
        if (family == null) {
            // Refusing to link an instrumentless player keeps the data model honest -
            // the dispatcher should never call us with one anyway.
            return false;
        }
        UUID id = player.getUUID();
        boolean isNew = !players.containsKey(id);
        players.put(id, new LinkedPlayer(id, family));
        recomputeAssignments();
        return isNew;
    }

    /**
     * Remove a player from this link.
     * @return {@code true} if the player was actually linked.
     */
    public boolean removePlayer(UUID id) {
        boolean removed = players.remove(id) != null;
        if (removed) recomputeAssignments();
        return removed;
    }

    /** Record the latest GM program for a channel (from a ProgramChange MIDI message). */
    public void setChannelProgram(int channel, int program) {
        if (channel < 0 || channel > 15) return;
        if (channelPrograms[channel] == program) return;
        channelPrograms[channel] = program;
        recomputeAssignments();
    }

    /**
     * Returns the UUID of the player currently assigned to play the given MIDI
     * channel, or {@code null} if no linked player is best suited (shouldn't
     * happen while at least one player is linked, since the round-robin
     * fallback always picks someone).
     */
    @Nullable
    public UUID assigneeFor(int channel) {
        if (channel < 0 || channel > 15) return null;
        return channelAssignments[channel];
    }

    /** Resolve the held-instrument family of a linked player, if any. */
    @Nullable
    public InstrumentFamily familyOf(UUID id) {
        LinkedPlayer lp = players.get(id);
        return lp == null ? null : lp.family();
    }

    // ---- internals -----------------------------------------------------------------------------

    /**
     * Recompute {@link #channelAssignments} from {@link #players} and
     * {@link #channelPrograms}. O(16 * P) where P = linked-player count, so
     * effectively constant.
     */
    private void recomputeAssignments() {
        Arrays.fill(channelAssignments, null);
        if (players.isEmpty()) return;

        // Solo shortcut: one player gets everything.
        if (players.size() == 1) {
            UUID only = players.keySet().iterator().next();
            Arrays.fill(channelAssignments, only);
            return;
        }

        // Multi-player path: per-channel preference with round-robin fallback.
        // Track per-player load so we balance unmatched (round-robin) channels evenly.
        Map<UUID, Integer> load = new LinkedHashMap<>();
        for (UUID id : players.keySet()) load.put(id, 0);

        // Stable iteration list for tie-breaks.
        List<LinkedPlayer> order = new ArrayList<>(players.values());

        for (int ch = 0; ch < 16; ch++) {
            InstrumentFamily preferred = InstrumentFamily.forMidiChannelAndProgram(
                ch,
                // -1 (unknown program) defaults to GM 0 = Acoustic Grand Piano.
                channelPrograms[ch] >= 0 ? channelPrograms[ch] : 0
            );

            UUID winner = pickLeastLoadedMatching(order, load, preferred);
            if (winner == null) {
                // No instrument-family match - fall back to global least-loaded.
                winner = pickLeastLoaded(order, load);
            }
            channelAssignments[ch] = winner;
            if (winner != null) load.merge(winner, 1, Integer::sum);
        }
    }

    @Nullable
    private static UUID pickLeastLoadedMatching(List<LinkedPlayer> order,
                                                Map<UUID, Integer> load,
                                                InstrumentFamily preferred) {
        UUID best = null;
        int bestLoad = Integer.MAX_VALUE;
        for (LinkedPlayer lp : order) {
            if (lp.family() != preferred) continue;
            int l = load.get(lp.id());
            if (l < bestLoad) {
                bestLoad = l;
                best = lp.id();
            }
        }
        return best;
    }

    @Nullable
    private static UUID pickLeastLoaded(List<LinkedPlayer> order, Map<UUID, Integer> load) {
        UUID best = null;
        int bestLoad = Integer.MAX_VALUE;
        for (LinkedPlayer lp : order) {
            int l = load.get(lp.id());
            if (l < bestLoad) {
                bestLoad = l;
                best = lp.id();
            }
        }
        return best;
    }

    /**
     * Snapshot of a linked player's relevant state.
     *
     * <p>We record only the {@link InstrumentFamily} (not the full ItemStack)
     * because that's the only thing the distribution algorithm cares about,
     * and it lets us avoid keeping references to potentially-stale ItemStacks.</p>
     */
    public record LinkedPlayer(UUID id, InstrumentFamily family) { }
}
