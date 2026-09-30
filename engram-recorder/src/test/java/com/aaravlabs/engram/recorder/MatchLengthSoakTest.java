package com.aaravlabs.engram.recorder;

import com.aaravlabs.engram.replay.EngramRecording;
import com.aaravlabs.engram.replay.EngramRecordingReader;
import com.aaravlabs.engram.replay.TopicInfo;
import com.aaravlabs.synapse.Orchestrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A match-length load test for the recorder.
 *
 * <p>Two claims in this project have no automated evidence behind them:
 * <b>recording never blocks the publishing thread</b>, and <b>recording never
 * allocates without bound</b>. Both are argued from the code's shape -- encode
 * to a byte array, offer to a queue, return; one writer thread owns the stream
 * -- which is a much weaker form of evidence than a measurement.
 *
 * <h2>Running it</h2>
 * Tagged {@code soak}, excluded from {@code test} and selected by
 * {@code ./gradlew soakTest}. A 150-second wall-clock test has no business
 * running inside {@code ./gradlew build}. The duration comes from
 * {@code -Pengram.soak.seconds=N} and defaults to 150, one real match.
 *
 * <h2>Traffic</h2>
 * Four publisher threads, modelled on where publishes actually come from on a
 * robot. The rates sit at or slightly above a typical team's, so a regression
 * shows up as a widening gap rather than as a number that happens to land on
 * the wrong side of a line.
 *
 * <ul>
 *   <li><b>opmode loop, 60 Hz</b> -- two gamepads, six values each: both
 *       sticks, both triggers, the stick clicks. A gamepad adaptor publishes
 *       per frame, and twelve values per frame across two players is the
 *       ordinary shape of that. 720/s.</li>
 *   <li><b>hardware loop, 100 Hz</b> -- {@code @RunPeriodically} control
 *       nodes, two topics per tick. 200/s.</li>
 *   <li><b>bulkRead callback, 20 Hz</b> -- one {@code bulkRead(50, ...)} every
 *       50 ms returning ten sensor values: the sensor path the decorator
 *       strategy has to be told about, and the burst shape the smooth streams
 *       do not produce. 200/s, in bursts of ten.</li>
 *   <li><b>telemetry, 5 Hz</b> -- six summary topics including a {@link String}
 *       and a {@code long}, because the encoder's non-primitive path allocates
 *       differently from the boxed one. 30/s.</li>
 * </ul>
 *
 * That is roughly 1,150 publishes per second, above the ~30k events per
 * 2.5-minute match the project's own file-size estimate assumes.
 *
 * <h2>What is asserted, and what is only reported</h2>
 * <b>Strict</b> -- invariants that must hold on any machine, however loaded,
 * and whose failure means a real defect:
 *
 * <ul>
 *   <li>no exception escaped into a publishing thread;</li>
 *   <li>nothing was lost: every attempted publish is either recorded or
 *       explicitly dropped, and no drop happened;</li>
 *   <li>the writer never latched a stream failure, so the file is whole;</li>
 *   <li>the queued-event depth never ran away -- the "does not allocate
 *       without bound" claim, observed rather than argued;</li>
 *   <li>retained heap did not grow across the run;</li>
 *   <li>the file reads back through the production reader with every recorded
 *       publish present, and the completeness report calls it complete.</li>
 * </ul>
 *
 * <b>Loose</b> -- p99 publish latency against a budget four orders of
 * magnitude above what the encoder actually costs. Generous on purpose: a
 * shared CI runner can stall a thread for milliseconds on a GC pause or a
 * scheduling hiccup without Engram being at fault, and a soak that fails for
 * that reason teaches people to ignore it. It still catches the failure this
 * test exists for, which is a per-publish cost that grows with load rather
 * than an occasional outlier.
 *
 * <b>Informational</b> -- p50, p99, p99.9, and max latency, heap growth, file
 * size, and event count. Printed, never asserted.
 */
@Tag("soak")
class MatchLengthSoakTest {

    /** A real match is 2.5 minutes. */
    private static final long DEFAULT_SECONDS = 150L;

    /**
     * Per-publish budget for the p99, in microseconds. The recorder encodes a
     * boxed double into a pre-sized array and hands it to a
     * {@code ConcurrentLinkedQueue}, which is sub-microsecond work; 5 ms leaves
     * four orders of magnitude of headroom, so passing means "recording is not
     * on the robot's critical path", not "recording is fast".
     */
    private static final long P99_BUDGET_US = 5_000L;

    /**
     * The queue must never exceed this. At 1,150 publishes per second and a
     * 100 ms flush interval the steady state is around 115 events, and the
     * default 500-event flush threshold caps it well below this anyway. A
     * run-away figure means the writer is not draining, which is the failure
     * that would eventually exhaust the heap.
     */
    private static final int MAX_QUEUE_DEPTH = 100_000;

    /** Retained-heap growth a run may show before it counts as a leak. */
    private static final long MAX_HEAP_GROWTH_BYTES = 64L * 1024 * 1024;

    private final AtomicInteger escapes = new AtomicInteger();
    private final AtomicInteger deadPublishers = new AtomicInteger();
    private final AtomicLong attempted = new AtomicLong();

    private final LatencyHistogram latency = new LatencyHistogram();

    private Orchestrator orchestrator;
    private Orchestrator bus;

    @BeforeEach
    void setUp() {
        orchestrator = Orchestrator.create("soak");
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    @Test
    void aMatchLengthRunDoesNotBlockOrOutgrowTheRecorder(@TempDir File dir) throws Exception {
        long seconds = seconds();
        File file = new File(dir, "soak.engram");

        // Settle before the baseline so the measurement is of the run rather
        // than of whatever the JVM was already holding.
        System.gc();
        Thread.sleep(250);
        long heapBefore = usedHeap();

        EngramSession session = EngramSession.start(
                "Soak", file, orchestrator,
                RecorderConfig.builder().withLog(m -> { }).build(),
                null);
        Recorder recorder = session.recorder();
        bus = session.orchestrator();
        long startedAt = System.nanoTime();
        session.markStart();

        CountDownLatch done = new CountDownLatch(4);
        AtomicLong loopTicks = new AtomicLong();
        AtomicLong hardwareTicks = new AtomicLong();
        AtomicLong bulkTicks = new AtomicLong();
        AtomicLong telemetryTicks = new AtomicLong();
        List<Thread> publishers = Arrays.asList(
                publisher("opmode-loop", 60, done, loopTicks, this::opModeTick),
                publisher("hardware-loop", 100, done, hardwareTicks, this::hardwareTick),
                publisher("bulk-read", 20, done, bulkTicks, this::bulkReadBurst),
                publisher("telemetry", 5, done, telemetryTicks, this::telemetryTick));

        // A watcher on the publishing threads' own account of the queue: the
        // number that would climb for as long as the writer fell behind.
        AtomicInteger maxQueueDepth = new AtomicInteger();
        Thread watcher = new Thread(() -> {
            while (done.getCount() > 0) {
                maxQueueDepth.accumulateAndGet(recorder.bufferedEventCount(), Math::max);
                LockSupport.parkNanos(2_000_000L);
            }
        }, "soak-watcher");
        watcher.setDaemon(true);

        for (Thread t : publishers) {
            t.start();
        }
        watcher.start();
        assertTrue(done.await(seconds + 60, TimeUnit.SECONDS),
                "the publishers should finish within the soak window");
        watcher.join(5_000);
        long elapsedNanos = System.nanoTime() - startedAt;

        session.close();
        bus = null;
        long heapAfter = usedHeap();

        // ---- what the run observed ----------------------------------------
        RecorderStats stats = session.stats();
        long published = attempted.get();
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;

        System.out.println();
        System.out.println("--- match-length soak ---------------------------------------");
        System.out.printf("duration            %.1f s (configured %,d s)%n", elapsedSeconds, seconds);
        System.out.printf("publishes attempted %,d (%.0f/s)%n", published, published / elapsedSeconds);
        System.out.printf("publisher loops     opmode %,d @60Hz | hardware %,d @100Hz | bulkRead %,d @20Hz"
                        + " | telemetry %,d @5Hz%n",
                loopTicks.get(), hardwareTicks.get(), bulkTicks.get(), telemetryTicks.get());
        System.out.printf("publish latency     p50 %.2f us | p99 %.2f us | p99.9 %.2f us | max %.2f us%n",
                micros(latency.percentile(0.50)),
                micros(latency.percentile(0.99)),
                micros(latency.percentile(0.999)),
                micros(latency.percentile(1.0)));
        System.out.printf("queue depth         max %d events%n", maxQueueDepth.get());
        System.out.printf("heap                %.1f MB before, %.1f MB after (%.1f MB growth)%n",
                heapBefore / 1_048_576.0, heapAfter / 1_048_576.0,
                (heapAfter - heapBefore) / 1_048_576.0);
        System.out.printf("recorder stats      %s%n", stats);
        System.out.printf("file                %.2f MB%n", file.length() / 1_048_576.0);
        System.out.println("--------------------------------------------------------------");

        // ---- strict: invariants that must hold on any machine --------------
        assertEquals(0, escapes.get(),
                "nothing the recorder does may throw into a publishing thread");
        assertEquals(0, deadPublishers.get(),
                "no publisher thread died; a dead publisher would mean the traffic model is wrong");
        assertEquals(0, stats.publishesDropped(),
                "the queue never exceeded its bound, so nothing should have been dropped");
        assertEquals(published, stats.publishesRecorded() + stats.publishesDropped(),
                "every attempted publish is accounted for");
        assertEquals(0, stats.unencodableValues(),
                "the soak only publishes types the encoder handles");
        assertTrue(stats.isHealthy(), "the writer must not have latched a stream failure: " + stats);
        assertTrue(stats.bytesWritten() > 0, "the run must actually have written something");
        assertTrue(stats.eventsWritten() >= published,
                "every recorded publish must reach the file");
        assertTrue(maxQueueDepth.get() < MAX_QUEUE_DEPTH,
                "the writer fell behind: the queue reached " + maxQueueDepth.get() + " events");
        assertTrue(heapAfter - heapBefore < MAX_HEAP_GROWTH_BYTES,
                "retained heap grew by " + (heapAfter - heapBefore) + " bytes over a soak");

        // ---- loose: a generous budget that still catches a regression -------
        long p99Us = latency.percentile(0.99) / 1_000;
        assertTrue(p99Us < P99_BUDGET_US,
                "p99 publish latency " + p99Us + " us exceeded the " + P99_BUDGET_US
                        + " us budget, which suggests the publish path is doing real work");

        // ---- the file, read back through the production reader ------------
        EngramRecording recording = EngramRecordingReader.read(file);
        assertFalse(recording.isTruncated(), "the run finished cleanly, so the file must be whole");
        assertEquals("Soak", recording.opModeName());
        assertTrue(recording.captureReport().isComplete(),
                "a clean close must produce a recording the completeness report calls complete: "
                        + recording.captureReport());
        assertEquals(published, totalPublishes(recording),
                "no recorded publish may be missing from the file");
    }

    // ---- traffic ---------------------------------------------------------

    /**
     * Runs {@code body} at {@code hz} for the soak window, pacing against
     * absolute deadlines rather than sleeping for a period after each
     * iteration, so one slow iteration cannot make the whole run drift.
     *
     * @param ticks receives the number of iterations this thread managed, which
     *               is how an achieved-versus-intended rate mismatch becomes
     *               visible instead of being averaged away
     */
    private Thread publisher(String name, int hz, CountDownLatch done,
                             AtomicLong ticks, Runnable body) {
        Thread thread = new Thread(() -> {
            long periodNanos = 1_000_000_000L / hz;
            long start = System.nanoTime();
            long deadline = start + seconds() * 1_000_000_000L;
            try {
                for (int tick = 0; ; tick++) {
                    // Anchored to `start`, never to "now": a deadline computed
                    // from the current time would drift by the overshoot of
                    // every iteration and the achieved rate would fall off a
                    // cliff within seconds.
                    long due = start + tick * periodNanos;
                    long wait = due - System.nanoTime();
                    if (wait > 0) {
                        LockSupport.parkNanos(wait);
                    }
                    if (System.nanoTime() > deadline) {
                        return;
                    }
                    ticks.incrementAndGet();
                    body.run();
                }
            } catch (Throwable t) {
                deadPublishers.incrementAndGet();
                t.printStackTrace();
            } finally {
                done.countDown();
            }
        }, "soak-" + name);
        thread.setDaemon(true);
        return thread;
    }

    /** Two gamepads' worth of axes and buttons, as a gamepad adaptor publishes them. */
    private void opModeTick() {
        for (int pad = 0; pad < 2; pad++) {
            double stick = Math.sin(attempted.get() * 0.01 + pad);
            publish("g" + pad + "/left_stick_x", stick);
            publish("g" + pad + "/left_stick_y", stick * 0.5);
            publish("g" + pad + "/right_stick_x", -stick);
            publish("g" + pad + "/right_stick_y", -stick * 0.5);
            publish("g" + pad + "/left_trigger", Math.abs(stick));
            publish("g" + pad + "/right_trigger", Math.abs(-stick));
        }
    }

    /** A {@code @RunPeriodically} control node. */
    private void hardwareTick() {
        double t = attempted.get() * 0.001;
        publish("drive/power", Math.sin(t));
        publish("arm/position", (long) (t * 1000));
    }

    /**
     * One {@code bulkRead} callback: several sensors arriving together rather
     * than spread out, which is what makes this worth measuring separately.
     */
    private void bulkReadBurst() {
        double t = attempted.get() * 0.002;
        for (int sensor = 0; sensor < 10; sensor++) {
            publish("sensor/odom-" + sensor, Math.cos(t + sensor));
        }
    }

    /** Summary topics, including the String and long paths through the encoder. */
    private void telemetryTick() {
        long tick = attempted.get() / 1_000L;
        publish("state/phase", tick % 3 == 0 ? "AUTO" : "TELEOP");
        publish("state/tick", tick);
        publish("state/heap_mb", Runtime.getRuntime().totalMemory() / 1_048_576L);
        publish("state/threads", Thread.activeCount());
        publish("state/latency", (double) tick / 1000.0);
        publish("state/connected", tick % 2 == 0);
    }

    /**
     * One publish, timed.
     *
     * <p>The recorder's contract is that this returns without touching the
     * filesystem. Timing it on the publishing thread is the only way to see
     * whether it keeps that promise, and the catch is here to prove the
     * stronger one: that nothing escapes at all.
     */
    private void publish(String topic, Object value) {
        long start = System.nanoTime();
        try {
            bus.publish(topic, value);
        } catch (Throwable t) {
            escapes.incrementAndGet();
            t.printStackTrace();
            return;
        } finally {
            latency.add(System.nanoTime() - start);
            attempted.incrementAndGet();
        }
    }

    // ---- measurement -----------------------------------------------------

    private static double micros(long nanos) {
        return nanos / 1_000.0;
    }

    /**
     * A fixed-bucket latency histogram.
     *
     * <p>Exact percentiles over a match-length run would mean retaining a
     * million-odd longs, which would have the soak measuring its own
     * allocation. A log-spaced histogram is a few kilobytes and is more honest
     * about what it is: resolution within a microsecond around the typical cost,
     * coarsening as the tail rises.
     */
    private static final class LatencyHistogram {

        private static final int BUCKETS = 2_048;

        private final AtomicLong[] counts = new AtomicLong[BUCKETS];
        private final AtomicLong observed = new AtomicLong();
        private final AtomicLong max = new AtomicLong();

        private LatencyHistogram() {
            // One AtomicLong per bucket, not Arrays.fill of a shared instance:
            // filling with one reference would make every bucket the same
            // counter and the histogram would report the first bucket forever.
            for (int i = 0; i < BUCKETS; i++) {
                counts[i] = new AtomicLong();
            }
        }

        private void add(long nanos) {
            observed.incrementAndGet();
            max.accumulateAndGet(nanos, Math::max);
            counts[bucket(nanos)].incrementAndGet();
        }

        /**
         * Three regions: 32 ns steps up to about 1 us where the real cost sits,
         * then 1 us steps to 64 us, then 64 us steps out past a minute.
         */
        private static int bucket(long nanos) {
            if (nanos < 1_024L) {
                return (int) (nanos / 32L);
            }
            if (nanos < 65_536L) {
                return 32 + (int) ((nanos - 1_024L) / 1_024L);
            }
            long steps = (nanos - 65_536L) / 65_536L;
            return (int) Math.min(96 + steps, BUCKETS - 1L);
        }

        /** Upper edge of a bucket, which is what a percentile reported from it really is. */
        private static long bucketCeiling(int index) {
            if (index < 32) {
                return (index + 1) * 32L;
            }
            if (index < 96) {
                return 1_024L + (index - 32 + 1) * 1_024L;
            }
            return 65_536L + (index - 96 + 1L) * 65_536L;
        }

        /** The quantile in nanoseconds, rounded up to its bucket's edge. */
        private long percentile(double quantile) {
            long count = observed.get();
            if (count == 0) {
                return 0L;
            }
            long target = (long) Math.ceil(quantile * count);
            long seen = 0L;
            for (int i = 0; i < BUCKETS; i++) {
                seen += counts[i].get();
                if (seen >= target) {
                    return bucketCeiling(i);
                }
            }
            return max.get();
        }
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static long totalPublishes(EngramRecording recording) {
        long sum = 0L;
        for (TopicInfo t : recording.topics()) {
            sum += t.publishCount();
        }
        return sum;
    }

    private static long seconds() {
        String configured = System.getProperty("engram.soak.seconds");
        if (configured == null || configured.trim().isEmpty()) {
            return DEFAULT_SECONDS;
        }
        return Long.parseLong(configured.trim());
    }
}
