package org.neonalig.createpolyphony.client.sound;

import net.minecraft.client.sounds.AudioStream;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.neonalig.createpolyphony.synth.PcmRingBuffer;
import org.neonalig.createpolyphony.synth.PolyphonySynthesizer;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Bridges PCM produced by {@link PolyphonySynthesizer} into Minecraft's
 * {@link AudioStream} interface.
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
 * <h2>Underrun handling</h2>
 * <p>If the synth hasn't generated enough PCM yet (e.g. first NoteOn
 * fired this tick) we may have nothing to return. We <b>do not block</b>
 * (that would freeze every Minecraft sound on the audio thread). We
 * return whatever we have - even an empty buffer - and let OpenAL's
 * underrun handling deal with it. In practice the synth's ring buffer
 * is large enough (~250 ms by default) that startup-only underruns are
 * inaudible.</p>
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
    /** Reusable scratch buffer to avoid per-read allocation. Sized at construct time. */
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
        // Reasonable upper bound for any single read() call. OpenAL typically
        // requests 4-16 KiB at a time; the ring buffer is larger so we'll
        // never need a scratch bigger than the ring itself.
        this.scratch = new byte[Math.max(4_096, synth.ringBuffer().capacity())];
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        if (closed) {
            // Returning an empty buffer is the cleanest "nothing to play" signal
            // that doesn't trip MC's null-handling assertions.
            return ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN);
        }

        int frame = synth.settings().frameSize();
        int request = Math.min(size, scratch.length);
        // Always frame-align the request - returning a partial frame yields
        // popping artifacts because OpenAL re-interprets the byte alignment.
        request -= request % frame;
        if (request <= 0) {
            return ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN);
        }

        PcmRingBuffer ring = synth.ringBuffer();
        int read = ring.read(scratch, 0, request);
        if (read <= 0) {
            // Underrun - nothing in the ring yet. Hand back an empty buffer;
            // OpenAL will retry on its next pump cycle.
            return ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN);
        }

        // Frame-align the actual return too (defensive - PcmRingBuffer should
        // never give us a partial frame because writes are always aligned,
        // but a soundfont swap mid-buffer could theoretically leave one).
        read -= read % frame;
        if (read <= 0) {
            return ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN);
        }

        // Allocate a fresh buffer per call. MC's audio thread holds onto the
        // returned buffer until it's been fully copied into OpenAL, so we
        // can't reuse a shared buffer across calls. The allocation is small
        // (a few KiB) and happens at audio-pump rate (~tens of Hz), well
        // within the GC's tolerance.
        ByteBuffer out = ByteBuffer.allocate(read).order(ByteOrder.LITTLE_ENDIAN);
        out.put(scratch, 0, read);
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
