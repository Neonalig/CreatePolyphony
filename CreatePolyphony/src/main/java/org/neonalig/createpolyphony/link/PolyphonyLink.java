package org.neonalig.createpolyphony.link;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;
import org.neonalig.createpolyphony.instrument.InstrumentFamily;

import java.nio.charset.StandardCharsets;
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
 *   <li>Otherwise, for each MIDI channel 0-15, <b>every eligible participant</b>
 *       is added to the channel's assignee list. Eligibility is the existing
 *       drum-vs-melodic gate plus any tracker frequency-key item filter. The
 *       server dispatches a separate note packet per assignee, so each linked
 *       instrument produces audible sound simultaneously - even on a single
 *       MIDI channel - which is the long-standing user expectation when
 *       multiple instruments are linked at once.</li>
 * </ol>
 *
 * <p>Each {@link Participant} carries its own <em>source bus UUID</em> derived
 * from {@code (realHolderId, hand-slot)} so the client keys an independent
 * audio bus per held instrument. That is what lets a player hear their main
 * hand and off hand simultaneously (or a player and a deployer at the same
 * time): different bus IDs land in different client {@code SourceBus}
 * entries, each with its own synth + stream, so timbres mix at the OpenAL
 * layer instead of fighting over a single per-holder bus.</p>
 *
 * <p>The previous "one channel = one assignee" routing assigned every channel
 * to whichever participant's family matched the channel's GM program - which
 * with two same-family holders (e.g. two pianos) or with default piano on a
 * single-channel song meant one holder claimed everything and the others were
 * silent. Sending each note to every eligible participant fixes that without
 * regressing drum/melodic separation: drums still only fire on channel 9,
 * melodic instruments still skip channel 9.</p>
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

    /**
     * Cached assignment: channel index -&gt; every participant that should render it.
     * Empty list (never {@code null}) when no participant is eligible for the channel.
     */
    @SuppressWarnings("unchecked")
    private final List<ChannelAssignee>[] channelAssignments = (List<ChannelAssignee>[]) new List<?>[16];
    /** Optional per-channel instrument item filter copied from tracker frequency slots. */
    private final Item[] channelFilterItems = new Item[16];

    public PolyphonyLink(String levelKey, BlockPos pos) {
        this.levelKey = levelKey;
        this.pos = pos.immutable();
        Arrays.fill(channelPrograms, -1);
        Arrays.fill(channelAssignments, List.of());
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
     * Returns every participant currently assigned to play the given MIDI
     * channel. Empty list when no linked player is eligible for the channel.
     * The returned list is immutable and safe to iterate without external
     * synchronisation; callers should iterate it once per dispatched event.
     */
    public List<ChannelAssignee> assigneesFor(int channel) {
        if (channel < 0 || channel > 15) return List.of();
        List<ChannelAssignee> list = channelAssignments[channel];
        return list != null ? list : List.of();
    }

    // ---- internals -----------------------------------------------------------------------------

    /**
     * Recompute {@link #channelAssignments} from {@link #players} and
     * {@link #channelPrograms}. O(16 * P) where P = participant count, so
     * effectively constant.
     *
     * <p>For each channel, every {@linkplain #isEligibleForChannel eligible}
     * participant is assigned. The note dispatcher then sends one packet per
     * assignee, each tagged with the participant's distinct {@code sourceBusId}
     * so the client renders them on independent synths - which is what makes
     * multiple held instruments audible simultaneously.</p>
     */
    private void recomputeAssignments() {
        Arrays.fill(channelAssignments, List.of());
        if (players.isEmpty()) return;

        List<Participant> participants = orderedParticipants();
        if (participants.isEmpty()) return;

        for (int ch = 0; ch < 16; ch++) {
            List<ChannelAssignee> list = null;
            for (Participant p : participants) {
                if (!isEligibleForChannel(p, ch)) continue;
                if (list == null) list = new ArrayList<>(participants.size());
                list.add(p.assignee());
            }
            channelAssignments[ch] = list == null ? List.of() : List.copyOf(list);
        }
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
                out.add(new Participant(player.id(),
                    deriveSourceBusId(player.id(), HandSlot.MAIN_HAND),
                    player.mainHandFamily(),
                    channelMaskForItem(player.mainHandItem())));
            }
            if (player.offHandFamily() != null) {
                out.add(new Participant(player.id(),
                    deriveSourceBusId(player.id(), HandSlot.OFF_HAND),
                    player.offHandFamily(),
                    channelMaskForItem(player.offHandItem())));
            }
        }
        return out;
    }

    /**
     * Stable, deterministic per-(holder, hand) UUID for client bus keying.
     * Using {@link UUID#nameUUIDFromBytes(byte[])} keeps the value
     * reproducible across sessions and crash-safe (no transient counters)
     * while ensuring main and off hand of the same holder never collide on
     * the same client {@code SourceBus}, which would coalesce both into one
     * synth and silence one of the timbres.
     */
    private static UUID deriveSourceBusId(UUID realHolderId, HandSlot slot) {
        String key = realHolderId.toString() + ":hand:" + slot.name();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
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

    /**
     * Immutable assignee metadata used by the note dispatcher.
     *
     * @param realHolderId the actual player/mob/automation holder UUID, used
     *                    server-side for player resolution and selfPlay flag.
     * @param sourceBusId  per-(holder, hand) derived UUID transmitted to the
     *                    client as the audio bus key so independent timbres
     *                    don't collide on a single per-holder synth.
     * @param family       the held instrument family driving the playback timbre.
     */
    public record ChannelAssignee(UUID realHolderId, UUID sourceBusId, InstrumentFamily family) { }

    private record Participant(UUID realHolderId,
                               UUID sourceBusId,
                               InstrumentFamily family,
                               int filteredMask) {
        private ChannelAssignee assignee() {
            return new ChannelAssignee(realHolderId, sourceBusId, family);
        }
    }

    private enum HandSlot { MAIN_HAND, OFF_HAND }
}
