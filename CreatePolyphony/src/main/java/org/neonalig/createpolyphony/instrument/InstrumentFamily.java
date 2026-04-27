package org.neonalig.createpolyphony.instrument;

import net.minecraft.resources.ResourceLocation;
import org.neonalig.createpolyphony.CreatePolyphony;

import java.util.Locale;

/**
 * The high-level "instrument family" buckets we expose as held items.
 *
 * <p>Each value corresponds to a single physical {@link InstrumentItem}. The
 * {@link #forGmProgram(int)} method maps a General-MIDI program number (0-127)
 * onto the family that should "naturally" play it - which is what the
 * channel-distribution logic uses to prefer a player whose held item matches
 * the channel's GM program.</p>
 *
 * <h2>GM Program Buckets</h2>
 * <p>The 128 GM melodic programs are grouped into 16 families of 8 programs each.
 * We collapse those families further into our nine end-user instruments roughly as
 * follows (see <a href="https://en.wikipedia.org/wiki/General_MIDI#Program_change_events">GM program list</a>):</p>
 * <ul>
 *   <li>0-7   Piano                -&gt; {@link #PIANO}</li>
 *   <li>8-15  Chromatic Percussion -&gt; {@link #PIANO} (closest melodic match)</li>
 *   <li>16-23 Organ                -&gt; {@link #ACCORDION} (reedy keyboard - SoS already covers organ pipes)</li>
 *   <li>24-31 Guitar               -&gt; {@link #ACOUSTIC_GUITAR} / {@link #ELECTRIC_GUITAR}</li>
 *   <li>32-39 Bass                 -&gt; {@link #BASS_GUITAR}</li>
 *   <li>40-47 Strings              -&gt; {@link #VIOLIN}</li>
 *   <li>48-55 Ensemble             -&gt; {@link #VIOLIN}</li>
 *   <li>56-63 Brass                -&gt; {@link #TRUMPET}</li>
 *   <li>64-71 Reed                 -&gt; {@link #ACCORDION}</li>
 *   <li>72-79 Pipe                 -&gt; {@link #FLUTE}</li>
 *   <li>80-95 Synth Lead/Pad       -&gt; {@link #ACCORDION} (sustained, key-driven)</li>
 *   <li>96-103 Synth Effects       -&gt; {@link #FLUTE}</li>
 *   <li>104-111 Ethnic             -&gt; {@link #ACOUSTIC_GUITAR}</li>
 *   <li>112-119 Percussive         -&gt; {@link #DRUM_KIT}</li>
 *   <li>120-127 Sound Effects      -&gt; {@link #FLUTE}</li>
 * </ul>
 *
 * <p>In addition, MIDI <b>channel 10</b> (1-indexed) is by GM convention always a
 * drum channel regardless of program, and is mapped via
 * {@link #forMidiChannelAndProgram(int, int)}.</p>
 */
public enum InstrumentFamily {
    PIANO("piano"),
    ACOUSTIC_GUITAR("acoustic_guitar"),
    ELECTRIC_GUITAR("electric_guitar"),
    BASS_GUITAR("bass_guitar"),
    VIOLIN("violin"),
    TRUMPET("trumpet"),
    FLUTE("flute"),
    ACCORDION("accordion"),
    DRUM_KIT("drum_kit"),
    /**
     * Special wildcard instrument: covers every MIDI channel (melodic and drums alike)
     * and uses the actual GM program from the MIDI file rather than a fixed family timbre.
     * Equivalent to a single performer playing "one man band" style.
     */
    ONE_MAN_BAND("one_man_band");

    /** Lower-case path-safe name used as the registry id and translation key fragment. */
    private final String id;

    InstrumentFamily(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    /**
     * Returns {@code true} for "wildcard" instruments (currently only
     * {@link #ONE_MAN_BAND}) that cover every MIDI channel regardless of family
     * and play the raw MIDI file program rather than a fixed canonical timbre.
     */
    public boolean isWildcard() {
        return this == ONE_MAN_BAND;
    }

    /** Resource location for this family's main item, e.g. {@code createpolyphony:piano}. */
    public ResourceLocation itemId() {
        return ResourceLocation.fromNamespaceAndPath(CreatePolyphony.MODID, id);
    }

    /** Translation key for this family's display name. */
    public String translationKey() {
        return "instrument." + CreatePolyphony.MODID + "." + id;
    }

    /**
     * Returns the family that should naturally play the given GM program number.
     * <p>This is used by the channel-distribution logic to <em>prefer</em>, not
     * to <em>require</em>, a particular family - any instrument can be used to
     * cover any channel if no preferred holder is available.</p>
     *
     * @param gmProgram General MIDI program number, 0-127. Out-of-range values
     *                  fall back to {@link #PIANO}.
     */
    public static InstrumentFamily forGmProgram(int gmProgram) {
        if (gmProgram < 0 || gmProgram > 127) return PIANO;
        // 16 GM families of 8 each:
        int gmFamily = gmProgram >>> 3; // /8
        return switch (gmFamily) {
            case 0 -> PIANO;            // 0-7   Piano
            case 1 -> PIANO;            // 8-15  Chromatic Percussion
            case 2 -> ACCORDION;        // 16-23 Organ
            case 3 -> {
                // 24-31 Guitar - split: 24-26 acoustic, 27-31 electric
                yield gmProgram <= 26 ? ACOUSTIC_GUITAR : ELECTRIC_GUITAR;
            }
            case 4 -> BASS_GUITAR;      // 32-39 Bass
            case 5, 6 -> VIOLIN;        // 40-55 Strings + Ensemble
            case 7 -> TRUMPET;          // 56-63 Brass
            case 8 -> ACCORDION;        // 64-71 Reed
            case 9 -> FLUTE;            // 72-79 Pipe
            case 10, 11 -> ACCORDION;   // 80-95 Synth lead/pad
            case 12 -> FLUTE;           // 96-103 Synth effects
            case 13 -> ACOUSTIC_GUITAR; // 104-111 Ethnic
            case 14 -> DRUM_KIT;        // 112-119 Percussive
            case 15 -> FLUTE;           // 120-127 Sound effects
            default -> PIANO;
        };
    }

    /**
     * Like {@link #forGmProgram(int)} but also honours the GM convention that
     * channel index 9 (the 10th channel, 0-indexed) is always a drum channel,
     * regardless of program.
     *
     * @param midiChannel 0-indexed MIDI channel (0-15).
     * @param gmProgram   GM program currently assigned to the channel.
     */
    public static InstrumentFamily forMidiChannelAndProgram(int midiChannel, int gmProgram) {
        if (midiChannel == 9) return DRUM_KIT;
        return forGmProgram(gmProgram);
    }

    /**
     * Canonical GM program to use when we intentionally force playback to this
     * held instrument family.
     */
    public int canonicalGmProgram() {
        return switch (this) {
            case PIANO -> 0;            // Acoustic Grand Piano
            case ACOUSTIC_GUITAR -> 24; // Acoustic Guitar (nylon)
            case ELECTRIC_GUITAR -> 27; // Electric Guitar (clean)
            case BASS_GUITAR -> 33;     // Electric Bass (finger)
            case VIOLIN -> 40;          // Violin
            case TRUMPET -> 56;         // Trumpet
            case FLUTE -> 73;           // Flute
            case ACCORDION -> 21;       // Accordion
            case DRUM_KIT -> 127;       // Channel 10 drum kit sentinel
            // ONE_MAN_BAND never uses canonicalGmProgram(); the caller should
            // fall back to the actual channel program from the MIDI file.
            case ONE_MAN_BAND -> 0;
        };
    }

    /** Convenience: case-insensitive lookup by {@link #getId()}. */
    public static InstrumentFamily byId(String id) {
        for (var f : values()) {
            if (f.id.equalsIgnoreCase(id)) return f;
        }
        throw new IllegalArgumentException("Unknown instrument family: " + id);
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
