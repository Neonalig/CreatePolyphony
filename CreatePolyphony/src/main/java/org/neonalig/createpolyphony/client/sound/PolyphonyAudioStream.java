package org.neonalig.createpolyphony.client.sound;

import net.minecraft.client.sounds.AudioStream;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.neonalig.createpolyphony.Config;
import org.neonalig.createpolyphony.synth.PolyphonySynthesizer;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
 * already PCM (produced live by Gervill); no decode step needed.</p>
 *
 * <h2>read() contract</h2>
 * <p>OpenAL's stream pump calls {@code read(size)} on a background thread
 * (the {@code SoundEngineExecutor}) at roughly the rate audio is consumed.
 * The {@link ByteBuffer} we return must be:</p>
 * <ul>
 *   <li>Direct or non-direct - both work; vanilla returns non-direct.</li>
 *   <li>Positioned at 0 with limit equal to the number of bytes written.
 *       (MC calls {@code buffer.flip()} on the result internally? No -
 *       it expects {@code position=0, limit=bytesAvailable}, ready to
 *       drain. Vanilla's {@code JOrbisAudioStream} confirms this.)</li>
 *   <li>Frame-aligned - returning a half-frame yields glitches. We
 *       round all returns down to the synth's frame size.</li>
 * </ul>
 *
 * <h2>Render model</h2>
 * <p>OpenAL asks for a chunk, we invoke the synth render block directly, and
 * return that PCM chunk immediately. No intermediate pump thread is used.</p>
 *
 * <h2>End-of-stream</h2>
 * <p>This stream is treated as <i>infinite</i>: we never return null or
 * a zero-length result that means "EOF". We close (and the stream ends)
 * only when {@link #close()} is invoked, which happens when MC drops the
 * sound instance, the channel, or the entire sound engine.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PolyphonyAudioStream implements AudioStream {

    // Fallbacks used if config bounds are temporarily invalid.
    private static final int DEFAULT_MIN_SUBCHUNK_BYTES = 2_048;
    private static final int DEFAULT_MAX_SUBCHUNK_BYTES = 8_192;

    private final PolyphonySynthesizer synth;
    private final AudioFormat format;
    /** Reusable scratch buffer to avoid per-read allocation. */
    private final byte[] scratch;

    private volatile boolean closed = false;
    private int adaptiveSubchunkBytes;
    private double renderNsPerByteEwma = -1.0D;

    /**
     * @param synth the live synthesizer whose ring buffer we drain. We do
     *              <b>not</b> own its lifecycle - closing this stream does
     *              not close the synth (the synth outlives any number of
     *              {@link AudioStream} instances and may be re-bridged on
     *              soundfont swaps).
     */
    public PolyphonyAudioStream(PolyphonySynthesizer synth) {
        this.synth = synth;
        this.format = synth.settings().toAudioFormat();
        this.scratch = new byte[Math.max(16_384, synth.settings().pumpChunkBytes() * 4)];
        this.adaptiveSubchunkBytes = 4_096;
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        int frame = synth.settings().frameSize();
        // Honor OpenAL's request size so the streaming queue stays full.
        int boundedSize = Math.min(size, scratch.length);
        int request = Math.max(frame, boundedSize);
        request -= request % frame;
        if (request <= 0) {
            request = frame;
        }

        if (!closed) {
            int offset = 0;
            int minSubchunkBytes = Math.max(frame, Config.adaptiveMinSubchunkBytes());
            int maxSubchunkBytes = Math.max(minSubchunkBytes, Config.adaptiveMaxSubchunkBytes());
            int targetRenderNs = Math.max(250_000, Config.adaptiveTargetRenderNs());
            double ewmaAlpha = Math.max(0.01D, Math.min(1.0D, Config.adaptiveEwmaAlpha()));
            int subchunkBytes = frameAlign(
                Math.max(minSubchunkBytes, Math.min(adaptiveSubchunkBytes, Math.min(maxSubchunkBytes, request))),
                frame);
            if (subchunkBytes <= 0) {
                subchunkBytes = frame;
            }

            while (offset < request) {
                int remaining = request - offset;
                int chunk = Math.min(subchunkBytes, remaining);
                chunk = frameAlign(chunk, frame);
                if (chunk <= 0) {
                    chunk = frame;
                }

                long t0 = System.nanoTime();
                int read = synth.renderPcm(scratch, offset, chunk);
                long elapsedNs = System.nanoTime() - t0;

                // On underrun, pad this chunk with silence so OpenAL always sees valid PCM.
                if (read < chunk) {
                    java.util.Arrays.fill(scratch, offset + Math.max(0, read), offset + chunk, (byte) 0);
                }

                int bytesForCost = Math.max(frame, chunk);
                double sampleNsPerByte = (double) elapsedNs / (double) bytesForCost;
                if (renderNsPerByteEwma < 0D) {
                    renderNsPerByteEwma = sampleNsPerByte;
                } else {
                    renderNsPerByteEwma += (sampleNsPerByte - renderNsPerByteEwma) * ewmaAlpha;
                }

                offset += chunk;
            }

            double nsPerByte = Math.max(1.0D, renderNsPerByteEwma);
            int targetSubchunk = (int) Math.round(targetRenderNs / nsPerByte);
            targetSubchunk = frameAlign(Math.max(minSubchunkBytes, Math.min(maxSubchunkBytes, targetSubchunk)), frame);
            if (targetSubchunk <= 0) {
                targetSubchunk = frame;
            }

            // Slew-limit chunk-size updates to avoid audible timing modulation.
            int blended = subchunkBytes + ((targetSubchunk - subchunkBytes) / 4);
            int clamped = Math.max(minSubchunkBytes, Math.min(maxSubchunkBytes, frameAlign(blended, frame)));
            if (clamped <= 0) {
                clamped = frameAlign(DEFAULT_MIN_SUBCHUNK_BYTES, frame);
            }
            if (clamped <= 0) {
                clamped = frameAlign(DEFAULT_MAX_SUBCHUNK_BYTES, frame);
            }
            adaptiveSubchunkBytes = Math.max(frame, clamped);
        } else {
            java.util.Arrays.fill(scratch, 0, request, (byte) 0);
        }

        // LWJGL OpenAL calls require a direct buffer backing store.
        ByteBuffer out = ByteBuffer.allocateDirect(request).order(ByteOrder.LITTLE_ENDIAN);
        out.put(scratch, 0, request);
        out.flip();
        return out;
    }

    private static int frameAlign(int bytes, int frame) {
        return bytes - (bytes % Math.max(1, frame));
    }

    @Override
    public void close() {
        // Idempotent. We do NOT close the synthesizer here - it's a shared
        // resource owned by the SoundFontManager and may be reused by
        // future sound instances.
        closed = true;
    }
}
