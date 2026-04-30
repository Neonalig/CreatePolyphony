package org.neonalig.createpolyphony.synth.meltysynth;

/**
 * Lock-protected ring buffer of pending MIDI events for the synth render thread.
 *
 * <p>Each event optionally carries a {@code nanos} target (via
 * {@link System#nanoTime()}) so the render loop can apply the event at the
 * exact sample position within the next render block. {@code nanos == 0L}
 * means "deliver immediately at the start of the next render slice" -
 * the legacy semantic, used for panic and engine-internal events that have no
 * sub-block timing requirement.</p>
 *
 * <p>Single producer (the scheduler / network handoff path) and single
 * consumer (the audio render thread) in practice, but synchronized methods
 * keep correctness if more producers ever appear.</p>
 */
final class MidiEventQueue {

    private final int[] type;
    private final int[] channel;
    private final int[] data1;
    private final int[] data2;
    private final long[] nanos;
    private final int capacity;

    private int head;
    private int tail;
    private int size;

    MidiEventQueue(int capacity) {
        this.capacity = Math.max(32, capacity);
        this.type = new int[this.capacity];
        this.channel = new int[this.capacity];
        this.data1 = new int[this.capacity];
        this.data2 = new int[this.capacity];
        this.nanos = new long[this.capacity];
    }

    /** Enqueue an event flagged for immediate delivery. */
    synchronized void offer(int eventType, int eventChannel, int eventData1, int eventData2) {
        offer(eventType, eventChannel, eventData1, eventData2, 0L);
    }

    /**
     * Enqueue an event with a target {@link System#nanoTime()} stamp.
     * {@code 0L} preserves legacy "apply immediately" semantics.
     */
    synchronized void offer(int eventType, int eventChannel, int eventData1, int eventData2, long eventNanos) {
        if (size == capacity) {
            head = (head + 1) % capacity;
            size--;
        }
        type[tail] = eventType;
        channel[tail] = eventChannel;
        data1[tail] = eventData1;
        data2[tail] = eventData2;
        nanos[tail] = eventNanos;
        tail = (tail + 1) % capacity;
        size++;
    }

    /**
     * Returns the head event's target nanos, or {@link Long#MAX_VALUE} if the
     * queue is empty. Callers use this to decide whether the head event is
     * eligible to be applied within the current render slice without paying
     * the cost of materialising the full event tuple.
     */
    synchronized long peekNanos() {
        if (size == 0) return Long.MAX_VALUE;
        return nanos[head];
    }

    synchronized boolean poll(int[] out) {
        if (size == 0) {
            return false;
        }
        out[0] = type[head];
        out[1] = channel[head];
        out[2] = data1[head];
        out[3] = data2[head];
        head = (head + 1) % capacity;
        size--;
        return true;
    }

    synchronized void clear() {
        head = 0;
        tail = 0;
        size = 0;
    }
}
