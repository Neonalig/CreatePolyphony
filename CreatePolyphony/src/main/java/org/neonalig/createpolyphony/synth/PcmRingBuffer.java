package org.neonalig.createpolyphony.synth;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A simple, fixed-capacity byte ring buffer designed for the
 * single-producer / single-consumer pattern used between our
 * synthesizer pump thread (writer) and Minecraft's audio thread
 * (reader, via {@link net.minecraft.client.sounds.AudioStream}).
 *
 * <h2>Why a ring buffer (and not a {@link java.util.concurrent.BlockingQueue})?</h2>
 * <p>OpenAL pulls fairly large chunks (typically 4-16&nbsp;KiB) per call.
 * Re-allocating byte arrays on the audio thread is exactly the kind of
 * GC-pressure source that causes audible glitches; a fixed-size byte ring
 * lets both sides operate on a single backing buffer for the lifetime of
 * the synth instance.</p>
 *
 * <h2>Underrun policy</h2>
 * <p>If the consumer asks for more bytes than are currently available we
 * <b>do not block</b> - we serve what we have and return early. The
 * audio stream layer is then expected to either accept the short read or
 * (if zero bytes are available) treat it as "stream not ready" and try
 * again next pull. Blocking the OpenAL thread would freeze playback for
 * every Minecraft sound, not just ours.</p>
 *
 * <h2>Overrun policy</h2>
 * <p>If the producer outruns the consumer (game paused, Alt-Tabbed, GC
 * stall) it will block on {@link #write(byte[], int, int)} until space
 * frees up. The producer runs on its own daemon thread so this is
 * harmless - it just means we briefly stop synthesizing more PCM than
 * we'll actually consume, which is the correct back-pressure behaviour.</p>
 */
public final class PcmRingBuffer {

    private final byte[] buf;
    private final int capacity;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();

    private int readIdx = 0;
    private int writeIdx = 0;
    private int size = 0;
    private volatile boolean closed = false;

    public PcmRingBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.buf = new byte[capacity];
    }

    public int capacity() { return capacity; }

    /** Current number of bytes available to read. Cheap, not synchronized. */
    public int available() {
        lock.lock();
        try { return size; }
        finally { lock.unlock(); }
    }

    /**
     * Blocking write. Returns the number of bytes actually written; only
     * less than {@code len} if the buffer is closed mid-write.
     */
    public int write(byte[] src, int off, int len) throws InterruptedException {
        if (len == 0) return 0;
        int written = 0;
        lock.lock();
        try {
            while (written < len) {
                if (closed) return written;
                while (size == capacity && !closed) {
                    // Wait up to 50 ms then re-check; bounded so a closing flag is acted on promptly.
                    notFull.await(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
                if (closed) return written;
                int free = capacity - size;
                int chunk = Math.min(free, len - written);
                int firstSpan = Math.min(chunk, capacity - writeIdx);
                System.arraycopy(src, off + written, buf, writeIdx, firstSpan);
                if (chunk > firstSpan) {
                    System.arraycopy(src, off + written + firstSpan, buf, 0, chunk - firstSpan);
                }
                writeIdx = (writeIdx + chunk) % capacity;
                size += chunk;
                written += chunk;
            }
            return written;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Non-blocking read. Returns the number of bytes actually read, which
     * may be less than {@code len} (including zero) if the buffer is
     * underrunning. Callers should fill any remainder with silence.
     */
    public int read(byte[] dst, int off, int len) {
        if (len == 0) return 0;
        lock.lock();
        try {
            int avail = size;
            if (avail == 0) return 0;
            int chunk = Math.min(avail, len);
            int firstSpan = Math.min(chunk, capacity - readIdx);
            System.arraycopy(buf, readIdx, dst, off, firstSpan);
            if (chunk > firstSpan) {
                System.arraycopy(buf, 0, dst, off + firstSpan, chunk - firstSpan);
            }
            readIdx = (readIdx + chunk) % capacity;
            size -= chunk;
            notFull.signalAll();
            return chunk;
        } finally {
            lock.unlock();
        }
    }

    /** Drop everything currently buffered. Used on soundfont swaps / panic stop. */
    public void clear() {
        lock.lock();
        try {
            readIdx = 0;
            writeIdx = 0;
            size = 0;
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Mark closed; wakes any blocked writer so it can return. */
    public void close() {
        closed = true;
        lock.lock();
        try {
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isClosed() { return closed; }
}
