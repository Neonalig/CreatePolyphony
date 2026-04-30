package org.neonalig.createpolyphony.client.sound;

import net.minecraft.client.sounds.AudioStream;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.neonalig.createpolyphony.synth.PolyphonySynthesizer;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.DoubleSupplier;

/**
 * Bridges PCM produced by {@link PolyphonySynthesizer} into Minecraft's {@link AudioStream} interface.
 *
 * <h2>Why this exists</h2>
 * <p>Minecraft's {@code SoundEngine} normally pulls streaming audio from
 * {@code SoundBufferLibrary.getStream(ResourceLocation, boolean)} which
 * decodes Vorbis/.ogg files into PCM. We bypass that path entirely by
 * overriding {@link net.minecraft.client.resources.sounds.SoundInstance#getStream}
 * on our {@link PolyphonySynthSoundInstance} so MC asks <em>us</em> for the
 * stream rather than the buffer library. The data this stream serves is
 * already PCM (produced live by our SoundFont synth); no decode step needed.</p>
 *
 * <h2>read() contract</h2>
 * <p>OpenAL's stream pump calls {@code read(size)} on a background thread
 * (the {@code SoundEngineExecutor}) at roughly the rate audio is consumed.
 * The {@link ByteBuffer} we return must be:</p>
 * <ul>
 *   <li>Direct or non-direct - both work; vanilla returns non-direct.</li>
 *   <li>Positioned at 0 with limit equal to the number of bytes written.</li>
 *   <li>Frame-aligned - returning a half-frame yields glitches. We round
 *       all returns down to the synth's frame size.</li>
 * </ul>
 *
 * <h2>Render model</h2>
 * <p>Single fixed render call per OpenAL pull. The previous adaptive subchunk
 * machinery (EWMA-driven slew-limited chunk-size adjustment) was deleted
 * because it actively <i>created</i> the audible warble it was trying to
 * mitigate: every change to the render quantum modulated when MIDI events got
 * applied inside the synth, producing the "fast then slow then catches up"
 * artefact users reported. Stable timing now comes from the upstream
 * {@code PolyphonyEventScheduler}; this class just produces audio at a
 * predictable, fixed cadence.</p>
 *
 * <h2>End-of-stream</h2>
 * <p>This stream is treated as <i>infinite</i>: we never return null or
 * a zero-length result that means "EOF". We close (and the stream ends)
 * only when {@link #close()} is invoked, which happens when MC drops the
 * sound instance, the channel, or the entire sound engine.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PolyphonyAudioStream implements AudioStream {

    private final PolyphonySynthesizer synth;
    private final AudioFormat format;
    /** Reusable scratch buffer to avoid per-read allocation. */
    private final byte[] scratch;
    /**
     * Per-read gain factor applied to PCM samples before they reach OpenAL.
     * Allows >1.0 amplification (with hard-clip protection) to compensate for
     * MC's downstream {@code clamp(volume * sourceSlider, 0, 1)}, which would
     * otherwise prevent the sound instance from ever exceeding unity gain
     * even when distance attenuation reduces perceived loudness.
     */
    private final DoubleSupplier gainSupplier;
    /** {@code true} only when format is 16-bit signed PCM (the gain stage assumes that layout). */
    private final boolean gainApplicable;

    private volatile boolean closed = false;

    /**
     * @param synth         the live synthesizer whose ring buffer we drain. We do
     *                      <b>not</b> own its lifecycle - closing this stream does
     *                      not close the synth (the synth outlives any number of
     *                      {@link AudioStream} instances and may be re-bridged on
     *                      soundfont swaps).
     * @param gainSupplier  per-read gain factor (1.0 = unchanged). Queried each
     *                      render so live config or self/non-self changes take
     *                      effect immediately without rebuilding the stream.
     */
    public PolyphonyAudioStream(PolyphonySynthesizer synth, DoubleSupplier gainSupplier) {
        this.synth = synth;
        this.format = synth.settings().toAudioFormat();
        this.scratch = new byte[Math.max(16_384, synth.settings().pumpChunkBytes() * 4)];
        this.gainSupplier = gainSupplier != null ? gainSupplier : () -> 1.0D;
        this.gainApplicable = format.getSampleSizeInBits() == 16 && !format.isBigEndian();
    }

    /** Backward-compatible constructor: unity gain (no amplification). */
    public PolyphonyAudioStream(PolyphonySynthesizer synth) {
        this(synth, () -> 1.0D);
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        int frame = synth.settings().frameSize();
        int boundedSize = Math.min(size, scratch.length);
        int request = Math.max(frame, boundedSize);
        request -= request % frame;
        if (request <= 0) {
            request = frame;
        }

        if (!closed) {
            int produced = synth.renderPcm(scratch, 0, request);
            if (produced < request) {
                // On underrun, pad the tail with silence so OpenAL always sees valid PCM.
                java.util.Arrays.fill(scratch, Math.max(0, produced), request, (byte) 0);
            }

            // Apply post-render gain so the result can exceed MC's downstream
            // [0..1] volume clamp without losing dynamic range.
            if (gainApplicable) {
                double gain = gainSupplier.getAsDouble();
                if (Double.isFinite(gain) && Math.abs(gain - 1.0D) > 1.0e-3D && gain >= 0.0D) {
                    applyGain16LE(scratch, 0, request, gain);
                }
            }
        } else {
            java.util.Arrays.fill(scratch, 0, request, (byte) 0);
        }

        // LWJGL OpenAL calls require a direct buffer backing store.
        ByteBuffer out = ByteBuffer.allocateDirect(request).order(ByteOrder.LITTLE_ENDIAN);
        out.put(scratch, 0, request);
        out.flip();
        return out;
    }

    /**
     * Multiplies signed 16-bit little-endian PCM samples by {@code gain} in
     * place, hard-clipping to the int16 range so amplification beyond unity
     * does not wrap negative.
     */
    private static void applyGain16LE(byte[] buf, int off, int len, double gain) {
        int end = off + (len & ~1); // 2-byte aligned
        for (int i = off; i < end; i += 2) {
            int lo = buf[i] & 0xFF;
            int hi = buf[i + 1]; // signed sign-extends
            int sample = (hi << 8) | lo;
            double scaled = sample * gain;
            int clipped = scaled >= 32767.0D ? 32767
                : scaled <= -32768.0D ? -32768
                : (int) scaled;
            buf[i] = (byte) (clipped & 0xFF);
            buf[i + 1] = (byte) ((clipped >> 8) & 0xFF);
        }
    }

    @Override
    public void close() {
        // Idempotent. We do NOT close the synthesizer here - it's a shared
        // resource owned by the SoundFontManager and may be reused by
        // future sound instances.
        closed = true;
    }
}
