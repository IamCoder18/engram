package com.aaravlabs.engram.recorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventWriterTest {

    @Test
    void drainsEverythingOnClose() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (EventWriter writer = new EventWriter(sink, 60_000, 500)) {
            for (int i = 0; i < 10; i++) {
                writer.offer(("e" + i).getBytes(StandardCharsets.UTF_8));
            }
        }
        String written = sink.toString(StandardCharsets.UTF_8);
        assertEquals("e0e1e2e3e4e5e6e7e8e9", written);
    }

    @Test
    void flushesOnTheIntervalWithoutNeedingClose() throws Exception {
        CountingSink sink = new CountingSink();
        EventWriter writer = new EventWriter(sink, 20, 1_000_000);
        try {
            writer.offer("hello".getBytes(StandardCharsets.UTF_8));
            assertTrue(sink.awaitBytes(5, 5, TimeUnit.SECONDS),
                    "the interval should have flushed the event without close()");
        } finally {
            writer.close();
        }
    }

    @Test
    void flushesImmediatelyWhenTheThresholdIsReached() throws Exception {
        CountingSink sink = new CountingSink();
        // A long interval, so only the threshold can explain an early write.
        EventWriter writer = new EventWriter(sink, 60_000, 5);
        try {
            for (int i = 0; i < 5; i++) {
                writer.offer(("e" + i).getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(sink.awaitBytes(10, 5, TimeUnit.SECONDS),
                    "reaching the threshold should trigger a flush without waiting for the interval");
        } finally {
            writer.close();
        }
    }

    @Test
    void preservesOrder() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (EventWriter writer = new EventWriter(sink, 60_000, 7)) {
            for (int i = 0; i < 500; i++) {
                writer.offer(String.format("%04d", i).getBytes(StandardCharsets.UTF_8));
            }
        }
        String written = sink.toString(StandardCharsets.UTF_8);
        assertEquals(2000, written.length());
        for (int i = 0; i < 500; i++) {
            assertEquals(String.format("%04d", i), written.substring(i * 4, i * 4 + 4));
        }
    }

    @Test
    void survivesConcurrentProducersWithoutLosingOrReorderingEvents() throws Exception {
        int threads = 8;
        int perThread = 2000;
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        try (EventWriter writer = new EventWriter(sink, 5, 200)) {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger failures = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                final int id = t;
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            // Fixed width: 1 thread id + 4 sequence digits + 1
                            // marker = 6 bytes for every event.
                            writer.offer(String.format("%d%04dX", id, i).getBytes(StandardCharsets.UTF_8));
                        }
                    } catch (Throwable e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }

            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "producers should finish");
            assertEquals(0, failures.get());
        }

        String written = sink.toString(StandardCharsets.UTF_8);
        int eventSize = 6;
        assertEquals(threads * perThread * eventSize, written.length(),
                "no event may be lost or duplicated");

        // Each thread's own events must appear in submission order.
        for (int t = 0; t < threads; t++) {
            int cursor = 0;
            for (int i = 0; i < perThread; i++) {
                String expected = String.format("%d%04dX", t, i);
                int at = written.indexOf(expected, cursor);
                assertTrue(at >= 0, "missing " + expected);
                cursor = at + eventSize;
            }
        }
    }

    @Test
    void latchesStreamFailuresAndDropsFurtherWork() {
        FailingSink sink = new FailingSink();
        EventWriter writer = new EventWriter(sink, 5, 10);
        try {
            for (int i = 0; i < 100; i++) {
                writer.offer(("e" + i).getBytes(StandardCharsets.UTF_8));
            }
            writer.flushNow();

            assertNotNull(writer.failure(), "the stream failure should be latched");
            assertTrue(writer.failure() instanceof IOException);
        } finally {
            writer.close();
            // close() must stay quiet even after a failure.
        }
    }

    @Test
    void closeIsIdempotent() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        EventWriter writer = new EventWriter(sink, 60_000, 500);
        writer.offer("x".getBytes(StandardCharsets.UTF_8));

        writer.close();
        writer.close();
        writer.close();

        assertEquals("x", sink.toString(StandardCharsets.UTF_8));
    }

    @Test
    void offersAfterCloseAreIgnoredRatherThanThrowing() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        EventWriter writer = new EventWriter(sink, 60_000, 500);
        writer.offer("a".getBytes(StandardCharsets.UTF_8));
        writer.close();

        writer.offer("b".getBytes(StandardCharsets.UTF_8));

        assertEquals("a", sink.toString(StandardCharsets.UTF_8));
    }

    @Test
    void reportsWrittenCountsAndBytes(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("counts.bin");
        EventWriter writer = new EventWriter(
                java.nio.file.Files.newOutputStream(out), 60_000, 500);
        writer.offer("abc".getBytes(StandardCharsets.UTF_8));
        writer.offer("de".getBytes(StandardCharsets.UTF_8));
        writer.close();

        assertEquals(2, writer.writtenEventCount());
        assertEquals(5, writer.writtenByteCount());
        assertEquals(0, writer.pendingCount());
        assertFalse(Files.exists(out) && Files.size(out) == 0, "content should be flushed");
    }

    // ---- stream fixtures -------------------------------------------------

    /** A sink that reports when it has received at least {@code n} bytes. */
    private static final class CountingSink extends OutputStream {
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        private final java.util.concurrent.Semaphore signal = new java.util.concurrent.Semaphore(0);

        @Override
        public synchronized void write(int b) {
            buffer.write(b);
            signal.release();
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            buffer.write(b, off, len);
            for (int i = 0; i < len; i++) {
                signal.release();
            }
        }

        boolean awaitBytes(int n, long timeout, TimeUnit unit) throws InterruptedException {
            return signal.tryAcquire(n, timeout, unit);
        }
    }

    /** A sink that throws on the first write. */
    private static final class FailingSink extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            throw new IOException("disk full");
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            throw new IOException("disk full");
        }
    }
}
