package org.neonalig.createpolyphony.client.sound;

import net.minecraft.client.sounds.AudioStream;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
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

    private static final int TARGET_RENDER_CHUNK_BYTES = 4_096;

    private final PolyphonySynthesizer synth;
    private final AudioFormat format;
    /** Reusable scratch buffer to avoid per-read allocation. */
    private final byte[] scratch;

    private volatile boolean closed = false;

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
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        int frame = synth.settings().frameSize();
        // Keep chunks modest to reduce event batching latency on tracker playback.
        int boundedSize = Math.min(size, TARGET_RENDER_CHUNK_BYTES);
        int request = Math.max(frame, Math.min(boundedSize, scratch.length));
        request -= request % frame;
        if (request <= 0) {
            request = frame;
        }

        if (!closed) {
            int read = synth.renderPcm(scratch, request);
            // On underrun, pad with silence so OpenAL always sees valid PCM.
            if (read < request) {
                java.util.Arrays.fill(scratch, Math.max(0, read), request, (byte) 0);
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

    @Override
    public void close() {
        // Idempotent. We do NOT close the synthesizer here - it's a shared
        // resource owned by the SoundFontManager and may be reused by
        // future sound instances.
        closed = true;
    }
}
