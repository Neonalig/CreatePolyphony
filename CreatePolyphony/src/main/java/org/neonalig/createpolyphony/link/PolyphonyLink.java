package org.neonalig.createpolyphony.link;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;
import org.neonalig.createpolyphony.instrument.InstrumentFamily;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The set of players currently linked to a single Sound-of-Steam Tracker Bar
 * block, plus the per-MIDI-channel routing decision derived from those players'
 * held instruments (main and off hand can both participate).
 *
 * <p><b>Channel distribution algorithm</b> (recomputed every time the membership
 * or instrument-program state changes):</p>
 * <ol>
 *   <li>If exactly <em>one</em> participant is linked:
 *       <ul>
 *         <li>Non-drum holder: receives every <em>melodic</em> channel (all except 9).</li>
 *         <li>Drum-kit holder: receives only channel 9.</li>
 *       </ul>
 *   </li>
 *   <li>Otherwise, for each MIDI channel 0-15:
 *       <ol type="a">
 *         <li>Determine the channel's "preferred" {@link InstrumentFamily} from
 *             its current GM program (or {@link InstrumentFamily#DRUM_KIT} for
 *             channel 9 by GM convention).</li>
 *       <li>Among linked participants, first claim channels whose preferred
 *             family is directly matched.</li>
 *         <li>After all preferred claims, assign any unclaimed channels via
 *             deterministic round-robin over eligible participants so remaining
 *             channels are split evenly.</li>
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
     * Linked players, keyed by UUID. {@link LinkedHashMap} keeps insertion order
     * stable, which makes participant ordering deterministic.
     */
    private final LinkedHashMap<UUID, LinkedPlayer> players = new LinkedHashMap<>();

    /** Last seen GM program number per MIDI channel (0-15). -1 means "unknown / not yet seen". */
    private final int[] channelPrograms = new int[16];

    /** Cached assignment: channel index -&gt; participant that should render it. */
    private final ChannelAssignee[] channelAssignments = new ChannelAssignee[16];
    /** Optional per-channel instrument item filter copied from tracker frequency slots. */
    private final Item[] channelFilterItems = new Item[16];

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
     * Add or refresh a player on this link from their current main/off-hand
     * linked instruments. Triggers reassignment.
     *
     * @return {@code true} if this was a new link, {@code false} if it was a refresh.
     */
    public boolean addOrRefreshPlayer(UUID holderId,
                                      @Nullable InstrumentFamily mainHandFamily,
                                      @Nullable InstrumentFamily offHandFamily,
                                      @Nullable Item mainHandItem,
                                      @Nullable Item offHandItem) {
        if (mainHandFamily == null && offHandFamily == null) {
            return false;
        }
        boolean isNew = !players.containsKey(holderId);
        players.put(holderId, new LinkedPlayer(holderId, mainHandFamily, offHandFamily, mainHandItem, offHandItem));
        recomputeAssignments();
        return isNew;
    }

    /**
     * Copies tracker frequency item filters for channels 0-15.
     *
     * <p>Only Create: Polyphony instrument items should be supplied; channels with
     * {@code null} stay unrestricted and continue using default channel assignment.</p>
     */
    public void setChannelInstrumentFilters(@Nullable Item[] filters) {
        boolean changed = false;
        for (int ch = 0; ch < 16; ch++) {
            Item next = (filters != null && ch < filters.length) ? filters[ch] : null;
            if (channelFilterItems[ch] != next) {
                channelFilterItems[ch] = next;
                changed = true;
            }
        }
        if (changed) {
            recomputeAssignments();
        }
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
     * Returns the most recently observed GM program (0-127) for the given
     * channel, or 0 (Acoustic Grand Piano) if the channel has not yet
     * received a ProgramChange. By GM convention, channel 9 is always a
     * percussion channel - we still return whatever ProgramChange (if any)
     * was sent for it; the caller is responsible for the "channel 10 is
     * drums" semantic if it cares.
     */
    public int channelProgram(int channel) {
        if (channel < 0 || channel > 15) return 0;
        int p = channelPrograms[channel];
        return p < 0 ? 0 : p;
    }

    /** Raw channel program state: -1 when unknown, else 0..127. */
    public int channelProgramRaw(int channel) {
        if (channel < 0 || channel > 15) return -1;
        return channelPrograms[channel];
    }

    /**
     * Returns the participant currently assigned to play the given MIDI
     * channel, or {@code null} if no linked players exist.
     */
    @Nullable
    public ChannelAssignee assigneeFor(int channel) {
        if (channel < 0 || channel > 15) return null;
        return channelAssignments[channel];
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

        List<Participant> participants = orderedParticipants();
        if (participants.isEmpty()) return;

        // Solo policy with optional channel-filter overrides from tracker frequency keys.
        if (participants.size() == 1) {
            Participant only = participants.get(0);
            for (int ch = 0; ch < 16; ch++) {
                channelAssignments[ch] = isEligibleForChannel(only, ch) ? only.assignee() : null;
            }
            return;
        }

        // Phase 1: preferred-family claims.
        int[] load = new int[participants.size()];
        boolean[] claimed = new boolean[16];
        for (int ch = 0; ch < 16; ch++) {
            InstrumentFamily preferred = InstrumentFamily.forMidiChannelAndProgram(
                ch,
                // -1 (unknown program) defaults to GM 0 = Acoustic Grand Piano.
                channelPrograms[ch] >= 0 ? channelPrograms[ch] : 0
            );

            int winner = pickLeastLoadedMatching(participants, load, preferred, ch);
            if (winner >= 0) {
                channelAssignments[ch] = participants.get(winner).assignee();
                load[winner]++;
                claimed[ch] = true;
            }
        }

        // Phase 2: deterministic round-robin for channels left unclaimed.
        int fallbackCursor = 0;
        for (int ch = 0; ch < 16; ch++) {
            if (claimed[ch]) continue;
            int winner = pickRoundRobinEligible(participants, fallbackCursor, ch);
            if (winner < 0) continue;
            channelAssignments[ch] = participants.get(winner).assignee();
            load[winner]++;
            fallbackCursor = (winner + 1) % participants.size();
        }
    }

    private static int pickLeastLoadedMatching(List<Participant> participants,
                                               int[] load,
                                               InstrumentFamily preferred,
                                               int channel) {
        int best = -1;
        int bestLoad = Integer.MAX_VALUE;
        for (int i = 0; i < participants.size(); i++) {
            Participant lp = participants.get(i);
            if (lp.family != preferred) continue;
            if (!isEligibleForChannel(lp, channel)) continue;
            int l = load[i];
            if (l < bestLoad) {
                bestLoad = l;
                best = i;
            }
        }
        return best;
    }

    private static int pickRoundRobinEligible(List<Participant> participants,
                                              int cursor,
                                               int channel) {
        if (participants.isEmpty()) return -1;
        int size = participants.size();
        for (int i = 0; i < size; i++) {
            int idx = (cursor + i) % size;
            Participant p = participants.get(idx);
            if (!isEligibleForChannel(p, channel)) continue;
            return idx;
        }
        return -1;
    }

    private static boolean isEligibleForChannel(Participant p, int channel) {
        if (!p.family.isWildcard()) {
            if (p.family == InstrumentFamily.DRUM_KIT) {
                if (channel != 9) return false;
            } else if (channel == 9) {
                return false;
            }
        }

        if (p.filteredMask == 0) return true;
        return (p.filteredMask & (1 << channel)) != 0;
    }

    private List<Participant> orderedParticipants() {
        List<Participant> out = new ArrayList<>(players.size() * 2);
        for (LinkedPlayer player : players.values()) {
            if (player.mainHandFamily() != null) {
                out.add(new Participant(player.id(), player.mainHandFamily(), channelMaskForItem(player.mainHandItem())));
            }
            if (player.offHandFamily() != null) {
                out.add(new Participant(player.id(), player.offHandFamily(), channelMaskForItem(player.offHandItem())));
            }
        }
        return out;
    }

    private int channelMaskForItem(@Nullable Item instrumentItem) {
        if (instrumentItem == null) return 0;
        int mask = 0;
        for (int ch = 0; ch < 16; ch++) {
            if (channelFilterItems[ch] == instrumentItem) {
                mask |= (1 << ch);
            }
        }
        return mask;
    }

    /**
     * Snapshot of a linked player's relevant state.
     *
     * <p>We record only the {@link InstrumentFamily} (not the full ItemStack)
     * because that's the only thing the distribution algorithm cares about,
     * and it lets us avoid keeping references to potentially-stale ItemStacks.</p>
     */
    public record LinkedPlayer(UUID id,
                               @Nullable InstrumentFamily mainHandFamily,
                               @Nullable InstrumentFamily offHandFamily,
                               @Nullable Item mainHandItem,
                               @Nullable Item offHandItem) {
        public LinkedPlayer {
            Objects.requireNonNull(id, "id");
        }
    }

    /** Immutable assignee metadata used by the note dispatcher. */
    public record ChannelAssignee(UUID playerId, InstrumentFamily family) { }

    private record Participant(UUID playerId, InstrumentFamily family, int filteredMask) {
        private ChannelAssignee assignee() {
            return new ChannelAssignee(playerId, family);
        }
    }
}
