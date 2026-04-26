package org.neonalig.createpolyphony.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.neonalig.createpolyphony.network.PlayInstrumentNotePayload;
import org.neonalig.createpolyphony.registry.CPSounds;
import org.neonalig.createpolyphony.registry.CPSounds.OctaveSample;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side handler for {@link PlayInstrumentNotePayload}.
 *
 * <h2>Lifecycle</h2>
 * <p>Translates incoming server packets into client-side
 * {@link PolyphonyNoteSoundInstance}s, one per (channel, note) pair. Tracking
 * active notes lets us actually <em>stop</em> notes on note-off events,
 * rather than just letting the sample play to completion.</p>
 *
 * <h2>Resource pack tolerance</h2>
 * <p>Resource packs are <b>partial</b> by design: a piano-only pack will only
 * supply samples for GM program 1 (Acoustic Grand Piano), and is expected to
 * be silent for any other program. Before we hand a sound to the sound
 * manager we therefore call {@link SoundManager#getSoundEvent(ResourceLocation)};
 * a {@code null} return means no resource pack defined that id, so we
 * silently drop the note rather than spamming "Unable to play unknown
 * soundEvent" warnings into the log.</p>
 *
 * <h2>Octave anchors</h2>
 * <p>Each program has up to three sample anchors (C2/C4/C6, see
 * {@link OctaveSample}). For every NoteOn we pick the anchor closest to the
 * played note - giving us pitch shifts of at most one octave - and fall back
 * to the next-nearest anchor if the chosen one isn't defined in the active
 * resource pack.</p>
 *
 * <h2>Threading</h2>
 * <p>All audio interaction happens on the client main thread. Network
 * decoding hands us off there via
 * {@link IPayloadContext#enqueueWork(Runnable)}; the {@link #ACTIVE} map is
 * therefore only ever touched from that thread and needs no synchronisation.</p>
 */
public final class PolyphonyClientNoteHandler {

    /**
     * Active notes keyed by (channel, midi-note). MIDI allows the same note
     * number to be on simultaneously across different channels, so the channel
     * is part of the key. We do <em>not</em> let the same note retrigger on
     * the same channel; a second NoteOn replaces the first (matching most GM
     * synths).
     */
    private static final Map<NoteKey, PolyphonyNoteSoundInstance> ACTIVE = new HashMap<>();

    private PolyphonyClientNoteHandler() {}

    /** Network entrypoint - safe to call on the network thread. */
    public static void handle(PlayInstrumentNotePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> playOrStop(payload));
    }

    private static void playOrStop(PlayInstrumentNotePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        NoteKey key = new NoteKey(payload.channel(), payload.note());

        if (payload.isNoteOff()) {
            PolyphonyNoteSoundInstance existing = ACTIVE.remove(key);
            if (existing != null) existing.stopNote();
            return;
        }

        if (!payload.isNoteOn()) {
            // Defensive: PolyphonyLinkManager already filters to NoteOn/NoteOff,
            // but if anything else arrives we just drop it.
            return;
        }

        // ---- NoteOn path -----------------------------------------------------------------------

        // Stop any prior instance on the same (channel, note) so we don't stack samples.
        PolyphonyNoteSoundInstance prior = ACTIVE.remove(key);
        if (prior != null) prior.stopNote();

        ResolvedSample sample = resolveSample(mc.getSoundManager(), payload.program(), payload.note());
        if (sample == null) {
            // No resource pack covers this (program, octave) - silently drop.
            return;
        }

        PolyphonyNoteSoundInstance instance = new PolyphonyNoteSoundInstance(
            sample.event(),
            payload.note(),
            sample.anchor().midiBaseNote,
            payload.velocity(),
            player
        );

        ACTIVE.put(key, instance);
        mc.getSoundManager().play(instance);
    }

    /**
     * Pick the best-available sample for {@code (program, midiNote)}.
     *
     * <p>The "best" anchor is the one closest to {@code midiNote} (smallest
     * pitch shift). If that anchor isn't defined by the active resource
     * pack(s), we fall back to the other two in increasing order of pitch
     * distance. If none of the three are defined, we return {@code null} -
     * meaning the resource pack hasn't supplied this program at all and the
     * caller should silently skip.</p>
     */
    private static ResolvedSample resolveSample(SoundManager sm, int program, int midiNote) {
        OctaveSample[] order = anchorsByDistance(midiNote);
        for (OctaveSample anchor : order) {
            ResourceLocation id = CPSounds.locationFor(program, anchor);
            if (id == null) continue; // out-of-range program
            WeighedSoundEvents defined = sm.getSoundEvent(id);
            if (defined == null) continue; // no pack defines this id
            SoundEvent ev = CPSounds.eventFor(program, anchor);
            if (ev != null) return new ResolvedSample(ev, anchor);
        }
        return null;
    }

    /**
     * Return the three octave anchors sorted by ascending distance from
     * {@code midiNote}. Allocates a small fixed-size array per call; that's
     * cheap enough on the note-on hot path (a few notes/sec at most for a
     * typical MIDI track).
     */
    private static OctaveSample[] anchorsByDistance(int midiNote) {
        OctaveSample[] all = OctaveSample.values();
        OctaveSample[] sorted = all.clone();
        // Simple insertion sort - 3 elements, no need for Arrays.sort overhead.
        for (int i = 1; i < sorted.length; i++) {
            OctaveSample x = sorted[i];
            int xd = Math.abs(midiNote - x.midiBaseNote);
            int j = i - 1;
            while (j >= 0 && Math.abs(midiNote - sorted[j].midiBaseNote) > xd) {
                sorted[j + 1] = sorted[j];
                j--;
            }
            sorted[j + 1] = x;
        }
        return sorted;
    }

    /**
     * Stop every active note. Called on disconnect / world unload to make sure
     * we don't leak stuck notes between worlds.
     */
    public static void stopAll() {
        for (PolyphonyNoteSoundInstance ins : ACTIVE.values()) ins.stopNote();
        ACTIVE.clear();
    }

    private record NoteKey(int channel, int note) { }

    private record ResolvedSample(SoundEvent event, OctaveSample anchor) { }
}
