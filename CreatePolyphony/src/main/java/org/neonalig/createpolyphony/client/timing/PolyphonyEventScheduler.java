package org.neonalig.createpolyphony.client.timing;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.neonalig.createpolyphony.CreatePolyphony;

import java.util.PriorityQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-resolution dispatcher that fires deferred {@link Runnable}s at exact
 * local-clock deadlines. The mod uses this to release MIDI events to the
 * synth at the precise nanosecond they should be heard, regardless of network
 * jitter, server tick wobble, or render-thread spikes.
 *
 * <h2>Design</h2>
 * <p>One daemon thread parks on {@link LockSupport#parkNanos(long)} until the
 * earliest queued deadline, then drains everything that has come due. Adding
 * a new earlier-deadline event {@link LockSupport#unpark(Thread) unparks} the
 * dispatcher so it re-evaluates without the typical 1-15&nbsp;ms OS scheduler
 * tick latency.</p>
 *
 * <p>Worst-case observed jitter on Windows is ~1-2&nbsp;ms (the OS timer
 * granularity), comfortably below the &lt;5&nbsp;ms perceptual threshold for
 * musical micro-timing. Combined with the synth's own ~1.5&nbsp;ms render
 * slice, total NoteOn-to-audible jitter is &lt;5&nbsp;ms in practice.</p>
 *
 * <h2>Cancellation</h2>
 * <p>{@link #flushAll()} drops every pending event without running it - used
 * for panic / world-unload. There is intentionally no per-event cancel API:
 * MIDI events do not need it (a NoteOff scheduled with a later deadline still
 * runs even if its NoteOn has already fired, which is correct).</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PolyphonyEventScheduler {

    /**
     * Monotonic event-insertion order tie-breaker so two events with identical
     * deadlines fire in submission order (preserves NoteOff-after-NoteOn
     * semantics when the server stamps both with identical nanos).
     */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final PriorityQueue<TimedTask> QUEUE = new PriorityQueue<>();
    private static volatile @Nullable Thread dispatcherThread;
    private static volatile boolean running;

    private PolyphonyEventScheduler() {}

    /** Idempotent: starts the dispatcher daemon if it isn't already running. */
    public static synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(PolyphonyEventScheduler::run, "CreatePolyphony-Scheduler");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY + 2);
        dispatcherThread = t;
        t.start();
    }

    /** Stops the dispatcher and clears any pending work. */
    public static synchronized void shutdown() {
        running = false;
        flushAll();
        Thread t = dispatcherThread;
        if (t != null) LockSupport.unpark(t);
        dispatcherThread = null;
    }

    /**
     * Schedule {@code task} to run at the given {@link System#nanoTime()} target.
     * If the deadline has already passed, the task runs on the next dispatcher
     * wake (typically within 1-2&nbsp;ms).
     */
    public static void scheduleAt(long deadlineNanos, Runnable task) {
        if (!running) start();
        TimedTask entry = new TimedTask(deadlineNanos, SEQUENCE.incrementAndGet(), task);
        long oldHead;
        LOCK.lock();
        try {
            TimedTask head = QUEUE.peek();
            oldHead = head == null ? Long.MAX_VALUE : head.deadlineNanos;
            QUEUE.add(entry);
        } finally {
            LOCK.unlock();
        }
        // If our new entry would become the new head, wake the dispatcher.
        if (deadlineNanos < oldHead) {
            Thread t = dispatcherThread;
            if (t != null) LockSupport.unpark(t);
        }
    }

    /** Run {@code task} immediately (still on the dispatcher thread, for ordering). */
    public static void runNow(Runnable task) {
        scheduleAt(System.nanoTime(), task);
    }

    /** Drop all pending tasks without executing them. */
    public static void flushAll() {
        LOCK.lock();
        try {
            QUEUE.clear();
        } finally {
            LOCK.unlock();
        }
    }

    public static int pendingCount() {
        LOCK.lock();
        try {
            return QUEUE.size();
        } finally {
            LOCK.unlock();
        }
    }

    private static void run() {
        while (running) {
            TimedTask due;
            long parkNanos;
            LOCK.lock();
            try {
                TimedTask head = QUEUE.peek();
                if (head == null) {
                    parkNanos = 1_000_000_000L; // 1 s; will be unparked when work arrives
                    due = null;
                } else {
                    long now = System.nanoTime();
                    if (head.deadlineNanos <= now) {
                        due = QUEUE.poll();
                        parkNanos = 0L;
                    } else {
                        due = null;
                        parkNanos = head.deadlineNanos - now;
                    }
                }
            } finally {
                LOCK.unlock();
            }

            if (due != null) {
                try {
                    due.task.run();
                } catch (Throwable t) {
                    CreatePolyphony.LOGGER.error("Scheduler task failed", t);
                }
                continue;
            }

            if (parkNanos > 0L) {
                LockSupport.parkNanos(parkNanos);
            }
        }
    }

    private record TimedTask(long deadlineNanos, long sequence, Runnable task) implements Comparable<TimedTask> {
        @Override public int compareTo(TimedTask o) {
            int c = Long.compare(this.deadlineNanos, o.deadlineNanos);
            return c != 0 ? c : Long.compare(this.sequence, o.sequence);
        }
    }
}

