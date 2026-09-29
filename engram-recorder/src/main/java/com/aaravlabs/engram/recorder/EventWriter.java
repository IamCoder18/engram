package com.aaravlabs.engram.recorder;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Buffers pre-framed events in memory and drains them to a stream on a
 * background thread.
 *
 * <p>Callers hand {@link #offer(byte[])} a message that is already
 * length-delimited, so the writer thread only concatenates and writes. The
 * synchronous cost of recording a publish is therefore a queue append and an
 * atomic increment -- no serialization and no I/O on the publishing thread.
 *
 * <p>Three conditions cause a drain: the queue reaches
 * {@code maxEventsPerFlush}, {@code flushIntervalMs} elapses, or
 * {@link #close()} is called. Only the transition that reaches the threshold
 * exactly notifies the writer, so a busy loop costs one uncontended lock per
 * batch rather than per event.
 *
 * <p>If the underlying stream throws, the first failure is latched, the queue
 * is discarded, and the writer thread exits. Later publishes are dropped
 * cheaply rather than retried into a broken stream; {@link #failure()} reports
 * what happened.
 */
final class EventWriter implements Closeable {

    private static final long JOIN_TIMEOUT_MS = 5_000;

    private final OutputStream out;
    private final long flushIntervalMs;
    private final int maxEventsPerFlush;
    private final Queue<byte[]> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicLong writtenEvents = new AtomicLong();
    private final AtomicLong writtenBytes = new AtomicLong();
    private final Object signal = new Object();
    private final Thread thread;

    private volatile boolean closing;
    private volatile boolean stopped;
    private volatile IOException failure;

    EventWriter(OutputStream out, long flushIntervalMs, int maxEventsPerFlush) {
        this.out = out;
        this.flushIntervalMs = flushIntervalMs;
        this.maxEventsPerFlush = maxEventsPerFlush;
        this.thread = new Thread(this::run, "engram-writer");
        // Daemon so a wedged stream can never hold up JVM or process exit.
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** Queues one already-framed event. Never blocks, never throws. */
    void offer(byte[] framed) {
        if (failure != null || stopped) {
            return;
        }
        queue.add(framed);
        // Notify only on the exact crossing; the writer drains everything, so
        // further growth needs no further signal.
        if (pending.incrementAndGet() == maxEventsPerFlush) {
            synchronized (signal) {
                signal.notifyAll();
            }
        }
    }

    /** Blocks until the queue is empty and the stream has been flushed. */
    void flushNow() {
        synchronized (signal) {
            signal.notifyAll();
        }
        // Bounded spin: the writer wakes on notify, but a stream that is
        // blocked on write must not hang a caller forever.
        long deadline = System.nanoTime() + JOIN_TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (pending.get() == 0 || failure != null) {
                return;
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void run() {
        try {
            while (failure == null) {
                awaitWork();
                drain();
                if (closing && pending.get() == 0) {
                    return;
                }
            }
        } finally {
            stopped = true;
            try {
                out.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                }
            }
        }
    }

    private void awaitWork() {
        synchronized (signal) {
            if (closing || failure != null || pending.get() >= maxEventsPerFlush) {
                return;
            }
            try {
                signal.wait(flushIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void drain() {
        if (failure != null) {
            return;
        }
        try {
            byte[] framed;
            while ((framed = queue.poll()) != null) {
                out.write(framed);
                pending.decrementAndGet();
                writtenEvents.incrementAndGet();
                writtenBytes.addAndGet(framed.length);
            }
            out.flush();
        } catch (IOException e) {
            failure = e;
            // Stop accepting work rather than retrying into a broken stream.
            queue.clear();
            pending.set(0);
        }
    }

    /** The first I/O failure, or null. */
    IOException failure() {
        return failure;
    }

    int pendingCount() {
        return pending.get();
    }

    long writtenEventCount() {
        return writtenEvents.get();
    }

    long writtenByteCount() {
        return writtenBytes.get();
    }

    /**
     * Flushes, stops the writer, and closes the stream. Idempotent; a failure
     * during the final flush is latched rather than thrown.
     */
    @Override
    public void close() {
        if (closing) {
            return;
        }
        closing = true;
        synchronized (signal) {
            signal.notifyAll();
        }
        try {
            thread.join(JOIN_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            failure = new IOException("engram writer did not stop within " + JOIN_TIMEOUT_MS + "ms");
        }
        stopped = true;
    }
}
