package org.neonalig.createpolyphony.synth;

import javax.sound.sampled.AudioFormat;

/**
 * Tunable knobs for {@link PolyphonySynthesizer}.
 *
 * <p>Defaults are chosen to give responsive, low-glitch playback on a
 * mid-range CPU while keeping memory use modest:</p>
 * <ul>
 *   <li><b>44.1 kHz / 16-bit / mono PCM</b> - OpenAL only spatializes mono
 *       sources, so this enables holder-position panning and distance falloff.</li>
 *   <li><b>32-voice polyphony cap</b> - generous for hand-played music,
 *       low enough that even a busy MIDI track never spikes a single
 *       core. Configurable via {@link org.neonalig.createpolyphony.Config}.</li>
 *   <li><b>~250 ms ring buffer</b> - long enough to absorb a GC pause
 *       on a typical client, short enough that NoteOn -> first audible
 *       sample latency stays under a frame.</li>
 * </ul>
 *
 * @param sampleRate         PCM sample rate in Hz.
 * @param channels           Number of audio channels (1 = mono, 2 = stereo).
 * @param sampleSizeInBits   Bit depth per sample (8 or 16).
 * @param maxVoices          Soft cap on simultaneously-active voices; the
 *                           oldest voice is culled before exceeding it.
 * @param ringBufferBytes    Capacity of the PCM ring buffer between the
 *                           synth render pipeline and the audio reader.
 * @param pumpChunkBytes     Target bytes generated per synth render loop
 *                           iteration. Smaller = lower latency but more
 *                           thread wake-ups; larger = more efficient but
 *                           more startup latency on the first NoteOn.
 */
public record SynthSettings(
    float sampleRate,
    int channels,
    int sampleSizeInBits,
    int maxVoices,
    int ringBufferBytes,
    int pumpChunkBytes
) {

    public static SynthSettings defaults() {
        return new SynthSettings(
            44_100f,
            1,
            16,
            32,
            // 250 ms of mono 16-bit @ 44.1 kHz =~ 44100 * 1 * 2 * 0.25 = 22050 bytes
            22_050,
            // 4 KiB chunks =~ 11 ms at the above format
            4_096
        );
    }

    public AudioFormat toAudioFormat() {
        // PCM_SIGNED, little-endian (matches OpenAL's preferred byte order on x86/x64).
        return new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            sampleRate,
            sampleSizeInBits,
            channels,
            channels * (sampleSizeInBits / 8),
            sampleRate,
            false
        );
    }

    /** Bytes per sample frame (one sample per channel). */
    public int frameSize() {
        return channels * (sampleSizeInBits / 8);
    }
}
