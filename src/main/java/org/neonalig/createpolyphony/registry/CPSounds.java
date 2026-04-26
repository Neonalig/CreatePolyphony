package org.neonalig.createpolyphony.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.neonalig.createpolyphony.CreatePolyphony;

/**
 * Registers a fixed grid of {@link SoundEvent}s covering every General-MIDI
 * program (0-127) at every supported sample octave.
 *
 * <h2>Sound id schema</h2>
 * <p>Each sound is named:</p>
 * <pre>{@code instruments.<NNN>.<octaveKey>}</pre>
 * where:
 * <ul>
 *   <li>{@code NNN} is the GM program number, zero-padded to three digits and
 *       <b>1-indexed</b> (so program 0 = "001", program 127 = "128"). This
 *       matches the conventional 1-indexed presentation of the GM program
 *       list and the format produced by the companion sf2/sfz extraction
 *       tool.</li>
 *   <li>{@code octaveKey} is one of {@code c2}, {@code c4}, {@code c6} -
 *       the three sample octaves we expect resource packs to ship.</li>
 * </ul>
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>{@code createpolyphony:instruments.001.c4} - Acoustic Grand Piano @ C4</li>
 *   <li>{@code createpolyphony:instruments.025.c2} - Acoustic Guitar (nylon) @ C2</li>
 *   <li>{@code createpolyphony:instruments.057.c6} - Trumpet @ C6</li>
 * </ul>
 *
 * <h2>Why this layout?</h2>
 * <p>Resource packs are produced by an external tool that converts an sf2/sfz
 * sound font into a set of {@code .ogg} samples plus a single
 * {@code sounds.json} file mapping these ids to the extracted assets. By
 * registering all 384 events up front, we let the resource pack <em>fill in</em>
 * whatever subset of the GM program list it has samples for; absent ids are
 * detected at playback time and treated as silent.</p>
 *
 * <h2>Sample octaves</h2>
 * <p>We use three sample anchors (C2, C4, C6) so any played MIDI note can be
 * pitched within at most one octave of its anchor sample - keeping playback
 * audio quality reasonable with cheap pitch-shifting. The
 * {@link OctaveSample} enum knows the MIDI base note for each anchor and
 * exposes {@link OctaveSample#nearest(int)} for note-to-anchor selection.</p>
 *
 * <h2>Missing-sound tolerance</h2>
 * <p>A registered {@link SoundEvent} doesn't require an entry in
 * {@code sounds.json}; Minecraft will only complain when something tries to
 * <em>play</em> a sound it can't resolve. The client handler avoids that by
 * pre-checking {@code SoundManager#getSoundEvent(ResourceLocation)} and simply
 * skipping notes whose program isn't covered by the active resource pack.</p>
 */
public final class CPSounds {

    /** Number of GM programs (0-127). */
    public static final int GM_PROGRAM_COUNT = 128;

    /** Three anchor octaves whose samples we expect resource packs to provide. */
    public enum OctaveSample {
        /** Sample anchored at C2 (MIDI note 36). Covers low MIDI notes. */
        C2(36, "c2"),
        /** Sample anchored at C4 (MIDI note 60, "middle C"). Covers mid notes. */
        C4(60, "c4"),
        /** Sample anchored at C6 (MIDI note 84). Covers high notes. */
        C6(84, "c6");

        public final int midiBaseNote;
        public final String key;

        OctaveSample(int midiBaseNote, String key) {
            this.midiBaseNote = midiBaseNote;
            this.key = key;
        }

        /**
         * Pick the anchor sample whose base note is closest to {@code midiNote}.
         * Ties (e.g. note 48, equidistant from C2 and C4) prefer the lower
         * anchor, which matches the way most sample libraries are authored
         * (it's cheaper to pitch <em>up</em> than down without artefacts).
         */
        public static OctaveSample nearest(int midiNote) {
            OctaveSample best = C4;
            int bestDist = Math.abs(midiNote - C4.midiBaseNote);
            for (OctaveSample s : values()) {
                int d = Math.abs(midiNote - s.midiBaseNote);
                // Strict < so ties prefer the earlier (lower) value via iteration order C2,C4,C6.
                if (d < bestDist) {
                    bestDist = d;
                    best = s;
                }
            }
            return best;
        }
    }

    public static final DeferredRegister<SoundEvent> SOUNDS =
        DeferredRegister.create(Registries.SOUND_EVENT, CreatePolyphony.MODID);

    /**
     * Indexed lookup: {@code SOUND_EVENTS[program][octave.ordinal()]} -&gt;
     * deferred holder for {@code instruments.<NNN>.<octaveKey>}.
     */
    @SuppressWarnings("unchecked")
    public static final DeferredHolder<SoundEvent, SoundEvent>[][] SOUND_EVENTS =
        (DeferredHolder<SoundEvent, SoundEvent>[][]) new DeferredHolder<?, ?>[GM_PROGRAM_COUNT][OctaveSample.values().length];

    static {
        for (int prog = 0; prog < GM_PROGRAM_COUNT; prog++) {
            // GM is conventionally 1-indexed in literature and sf2 program lists,
            // so emit "001"..."128" rather than "000"..."127".
            String programKey = String.format("%03d", prog + 1);
            for (OctaveSample oct : OctaveSample.values()) {
                String name = "instruments." + programKey + "." + oct.key;
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(CreatePolyphony.MODID, name);
                SOUND_EVENTS[prog][oct.ordinal()] = SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id));
            }
        }
    }

    /**
     * Resolve the sound event for the given GM program (0-127) and anchor octave.
     * Returns {@code null} only if {@code program} is out of range.
     */
    public static SoundEvent eventFor(int program, OctaveSample octave) {
        if (program < 0 || program >= GM_PROGRAM_COUNT) return null;
        return SOUND_EVENTS[program][octave.ordinal()].get();
    }

    /**
     * Resource-location form of the same lookup, useful for
     * {@code SoundManager#getSoundEvent} pre-flight checks (which take a
     * {@link ResourceLocation} rather than a {@link SoundEvent}).
     */
    public static ResourceLocation locationFor(int program, OctaveSample octave) {
        if (program < 0 || program >= GM_PROGRAM_COUNT) return null;
        SoundEvent ev = SOUND_EVENTS[program][octave.ordinal()].get();
        return ev.getLocation();
    }

    public static void register(IEventBus modEventBus) {
        SOUNDS.register(modEventBus);
    }

    private CPSounds() {}
}
