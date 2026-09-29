package com.aaravlabs.engram.recorder;

import com.aaravlabs.engram.replay.EngramRecording;
import com.aaravlabs.engram.replay.EngramRecordingReader;
import com.aaravlabs.engram.replay.Sample;
import com.aaravlabs.engram.replay.TopicInfo;
import com.aaravlabs.engram.replay.TopicStats;
import com.aaravlabs.engram.replay.Values;
import com.aaravlabs.engram.replay.export.CsvExporter;
import com.aaravlabs.engram.replay.export.JsonExporter;
import com.aaravlabs.engram.replay.export.NdjsonExporter;
import com.aaravlabs.synapse.Orchestrator;
import com.aaravlabs.synapse.annotation.RunPeriodically;
import com.aaravlabs.synapse.annotation.SubscribedTo;
import com.aaravlabs.synapse.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Records with the production writer and reads back with the production
 * reader. This is the test that matters most: it is the only one that proves
 * the two halves of the format actually agree, and it drives a real Synapse
 * orchestrator and a real {@link Node} doing a {@code bulkRead}-shaped
 * publish loop rather than calling the recorder directly.
 */
class RoundTripTest {

    private Orchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = Orchestrator.create("round-trip");
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    @Test
    void aRecordedRunReadsBackWithEveryValueIntact(@TempDir File dir) throws IOException {
        EngramSession session = EngramSession.start(
                "RoundTrip", new File(dir, "run.engram"), orchestrator, RecorderConfig.defaults(), null);
        try {
            Orchestrator bus = session.orchestrator();
            session.markStart();

            bus.publish("drive/power", 0.25);
            bus.publish("drive/power", 0.5);
            bus.publish("drive/power", -0.75);
            bus.publish("arm/position", 1234);
            bus.publish("arm/position", -5);
            bus.publish("g1/a", true);
            bus.publish("g1/a", false);
            bus.publish("state/mode", DriveMode.AUTONOMOUS);
            bus.publish("nav/label", "go to the depot");
            bus.publish("nav/waypoint", 9_000_000_000L);
        } finally {
            session.close();
        }

        EngramRecording recording = EngramRecordingReader.read(new File(dir, "run.engram"));

        assertFalse(recording.isTruncated());
        assertEquals("RoundTrip", recording.opModeName());
        assertEquals(Recorder.FORMAT_VERSION, recording.formatVersion());
        assertEquals(6, recording.topics().size(), recording.topics().toString());

        assertEquals(List.of(0.25, 0.5, -0.75), values(recording, "drive/power"));
        assertEquals(List.of(1234, -5), values(recording, "arm/position"));
        assertEquals(List.of(true, false), values(recording, "g1/a"));
        assertEquals(List.of("AUTONOMOUS"), values(recording, "state/mode"));
        assertEquals(List.of("go to the depot"), values(recording, "nav/label"));
        assertEquals(List.of(9_000_000_000L), values(recording, "nav/waypoint"));

        assertTrue(recording.initTimeUs() == 0);
        assertTrue(recording.startTimeUs() >= 0);
        assertTrue(recording.stopTimeUs() >= recording.startTimeUs());
    }

    @Test
    void topicManifestDescribesEveryRecordedTopic(@TempDir File dir) throws IOException {
        EngramSession session = EngramSession.start(
                "Manifest", new File(dir, "m.engram"), orchestrator, RecorderConfig.defaults(), null);
        try {
            session.orchestrator().publish("motor/left/vel", 120.5);
            session.orchestrator().publish("count", 7);
        } finally {
            session.close();
        }

        EngramRecording recording = EngramRecordingReader.read(new File(dir, "m.engram"));

        TopicInfo vel = recording.topic("motor/left/vel").orElseThrow();
        assertEquals("java.lang.Double", vel.javaType());
        assertEquals(1, vel.publishCount());
        assertTrue(vel.firstTimeUs() >= 0);
        assertEquals(vel.firstTimeUs(), vel.lastTimeUs());

        TopicInfo count = recording.topic("count").orElseThrow();
        assertEquals("java.lang.Integer", count.javaType());
        assertEquals(7, (Integer) recording.valueAt("count", Long.MAX_VALUE).orElseThrow());
    }

    @Test
    void valuesFromANodeDrivingARealPublishLoopAreCaptured(@TempDir File dir) throws Exception {
        EngramSession session = EngramSession.start(
                "NodeRun", new File(dir, "node.engram"), orchestrator, RecorderConfig.defaults(), null);
        Orchestrator bus = session.orchestrator();

        // A real Node with an annotated periodic loop, registered through the
        // wrapper -- the same path team code uses.
        bus.registerNode("driver", new DriverNode(bus));
        bus.publish("cmd/target", 3.0);
        session.markStart();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (bus.getLatestValue("driver/ticks", Long.class).orElse(0L) >= 5) {
                break;
            }
            Thread.sleep(5);
        }
        session.close();

        EngramRecording recording = EngramRecordingReader.read(new File(dir, "node.engram"));

        long ticks = recording.topic("driver/ticks")
                .orElseThrow(() -> new AssertionError("the node's publishes were not recorded"))
                .publishCount();
        assertTrue(ticks >= 5, "expected at least 5 recorded ticks, got " + ticks);

        // The node also received publishes; those must be captured too.
        assertTrue(recording.topic("cmd/target").isPresent(),
                "a topic the node subscribed to and published from should be recorded");
    }

    @Test
    void pointInTimeQueriesFollowTheTimeline(@TempDir File dir) throws IOException {
        File file = new File(dir, "timeline.engram");
        EngramSession session = EngramSession.start(
                "Timeline", file, orchestrator, RecorderConfig.defaults(), null);
        long base;
        try {
            base = System.nanoTime();
            session.recorder().onPublish("ramp", 1.0, base);
            session.recorder().onPublish("ramp", 2.0, base + 100_000_000L);          // +100ms
            session.recorder().onPublish("ramp", 3.0, base + 250_000_000L);          // +250ms
            session.recorder().onPublish("ramp", 4.0, base + 400_000_000L);          // +400ms
        } finally {
            session.close();
        }

        EngramRecording recording = EngramRecordingReader.read(file);
        List<Sample> samples = recording.samples("ramp");
        assertEquals(4, samples.size());

        // Timestamps are relative to INIT, which precedes the first publish, so
        // resolve the offset from the first sample rather than assuming zero.
        long first = samples.get(0).timeUs();
        long t1 = samples.get(1).timeUs() - first;
        long t2 = samples.get(2).timeUs() - first;
        long t3 = samples.get(3).timeUs() - first;

        assertTrue(Math.abs(t1 - 100_000) < 15_000, "expected ~100ms, got " + t1);
        assertTrue(Math.abs(t2 - 250_000) < 15_000, "expected ~250ms, got " + t2);
        assertTrue(Math.abs(t3 - 400_000) < 15_000, "expected ~400ms, got " + t3);

        // valueAt returns the newest publish at or before the requested time.
        assertEquals(1.0, recording.valueAt("ramp", first + 50_000).orElseThrow());
        assertEquals(2.0, recording.valueAt("ramp", first + 150_000).orElseThrow());
        assertEquals(3.0, recording.valueAt("ramp", first + 300_000).orElseThrow());
        assertEquals(4.0, recording.valueAt("ramp", first + 10_000_000).orElseThrow());
        assertFalse(recording.valueAt("ramp", 0).isPresent(),
                "before the first publish there is no value");
    }

    @Test
    void timeRangeQueriesSelectTheRightSamples(@TempDir File dir) throws IOException {
        File file = new File(dir, "range.engram");
        EngramSession session = EngramSession.start(
                "Range", file, orchestrator, RecorderConfig.defaults(), null);
        long base;
        try {
            base = System.nanoTime();
            for (int i = 0; i < 10; i++) {
                session.recorder().onPublish("s", (double) i, base + i * 50_000_000L);
            }
        } finally {
            session.close();
        }

        EngramRecording recording = EngramRecordingReader.read(file);
        long first = recording.samples("s").get(0).timeUs();

        assertEquals(10, recording.samples("s", 0, Long.MAX_VALUE).size());
        assertEquals(3, recording.samples("s", first + 100_000, first + 200_000).size(),
                "inclusive bounds at 100ms, 150ms, 200ms");
        assertEquals(1, recording.samples("s", first + 450_000, first + 450_000).size());
        assertEquals(0, recording.samples("s", first + 10_000_000, Long.MAX_VALUE).size());
    }

    @Test
    void statisticsSummariseATopic(@TempDir File dir) throws IOException {
        File file = new File(dir, "stats.engram");
        EngramSession session = EngramSession.start(
                "Stats", file, orchestrator, RecorderConfig.defaults(), null);
        long base;
        try {
            base = System.nanoTime();
            for (int i = 1; i <= 5; i++) {
                session.recorder().onPublish("v", (double) i, base + i * 100_000_000L);
            }
        } finally {
            session.close();
        }

        TopicStats stats = EngramRecordingReader.read(file).stats("v");

        assertEquals(5, stats.publishCount());
        assertEquals(5, stats.numericCount());
        assertEquals(0, stats.unrecordedCount());
        assertEquals(1.0, stats.min().orElseThrow());
        assertEquals(5.0, stats.max().orElseThrow());
        assertEquals(3.0, stats.mean().orElseThrow());
    }

    @Test
    void aGzippedRecordingReadsIdentically(@TempDir File dir) throws IOException {
        File plain = new File(dir, "plain.engram");
        EngramSession session = EngramSession.start(
                "Gzip", plain, orchestrator, RecorderConfig.defaults(), null);
        for (int i = 0; i < 200; i++) {
            session.orchestrator().publish("g", 0.5);
        }
        session.close();

        File gzipped = new File(dir, "zipped.engram.gz");
        try (GZIPOutputStream out = new GZIPOutputStream(new java.io.FileOutputStream(gzipped))) {
            out.write(readAllBytes(plain));
        }

        EngramRecording fromPlain = EngramRecordingReader.read(plain);
        EngramRecording fromGzip = EngramRecordingReader.read(gzipped);

        assertEquals(fromPlain.topics().size(), fromGzip.topics().size());
        assertEquals(fromPlain.samples("g").size(), fromGzip.samples("g").size());
        assertEquals(fromPlain.valueAt("g", Long.MAX_VALUE), fromGzip.valueAt("g", Long.MAX_VALUE));
        assertTrue(gzipped.length() < plain.length(),
                "gzip should shrink a repetitive recording");
    }

    @Test
    void everyExporterProducesOutputFromARealRecording(@TempDir File dir) throws Exception {
        File file = new File(dir, "export.engram");
        EngramSession session = EngramSession.start(
                "Export", file, orchestrator, RecorderConfig.defaults(), null);
        try {
            session.orchestrator().publish("a", 1.0);
            session.orchestrator().publish("b", "text");
            session.orchestrator().publish("a", 2.0);
        } finally {
            session.close();
        }

        EngramRecording recording = EngramRecordingReader.read(file);

        String json = render(w -> JsonExporter.export(recording, w));
        assertTrue(json.contains("\"opMode\": \"Export\""), json);
        assertTrue(json.contains("\"name\": \"a\""), json);
        assertTrue(json.contains("\"value\": 1.0"), json);
        assertTrue(json.contains("\"value\": \"text\""), json);

        String ndjson = render(w -> NdjsonExporter.export(recording, w));
        assertTrue(ndjson.contains("\"kind\":\"header\""), ndjson);
        assertTrue(ndjson.contains("\"kind\":\"sample\""), ndjson);
        // header + 2 topics + 2 lifecycle (INIT, STOP) + 3 samples = 8 lines
        assertEquals(8, ndjson.trim().split("\n").length, ndjson);

        String csv = render(w -> CsvExporter.export(recording, w));
        assertTrue(csv.startsWith("time_us,topic,value\n"), csv);
        assertEquals(4, csv.trim().split("\n").length, csv);

        String filtered = render(w -> CsvExporter.export(recording, w, "a", 0, Long.MAX_VALUE));
        assertTrue(filtered.startsWith("time_us,value\n"), filtered);
        assertEquals(3, filtered.trim().split("\n").length, filtered);
    }

    @Test
    void unknownTopicsAreRejectedRatherThanSilentlyEmpty(@TempDir File dir) throws IOException {
        File file = new File(dir, "unknown.engram");
        EngramSession session = EngramSession.start(
                "Unknown", file, orchestrator, RecorderConfig.defaults(), null);
        session.orchestrator().publish("real", 1.0);
        session.close();

        EngramRecording recording = EngramRecordingReader.read(file);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> recording.samples("typo", 0, Long.MAX_VALUE));
        assertFalse(recording.topic("typo").isPresent());
    }

    @Test
    void concurrentPublishingAcrossThreadsSurvivesTheRoundTrip(@TempDir File dir) throws Exception {
        File file = new File(dir, "concurrent.engram");
        EngramSession session = EngramSession.start(
                "Concurrent", file, orchestrator, RecorderConfig.defaults(), null);
        Orchestrator bus = session.orchestrator();

        int threads = 6;
        int perThread = 400;
        try {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                final int id = t;
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            bus.publish("shared", (double) i);
                            bus.publish("lane/" + id, (double) i);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS));
        } finally {
            session.close();
        }

        EngramRecording recording = EngramRecordingReader.read(file);

        assertFalse(recording.isTruncated());
        assertEquals(threads + 1, recording.topics().size(), recording.topics().toString());
        assertEquals(threads * perThread, recording.topic("shared").orElseThrow().publishCount(),
                "no publish may be lost");
        for (int t = 0; t < threads; t++) {
            assertEquals(perThread, recording.topic("lane/" + t).orElseThrow().publishCount());
        }
        assertTrue(session.stats().isHealthy(),
                "the run should not have hit a stream failure: " + session.stats());
    }

    // ---- helpers ---------------------------------------------------------

    private static List<Object> values(EngramRecording recording, String topic) {
        List<Object> out = new ArrayList<>();
        for (Sample s : recording.samples(topic)) {
            out.add(s.value());
        }
        return out;
    }

    private static byte[] readAllBytes(File file) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String render(WriterConsumer body) throws IOException {
        StringWriter writer = new StringWriter();
        body.write(writer);
        return writer.toString();
    }

    private interface WriterConsumer {
        void write(java.io.Writer out) throws IOException;
    }

    enum DriveMode { AUTONOMOUS, TELEOP }

    /** A real Node: subscribes to a command and publishes a tick counter. */
    static final class DriverNode extends Node {

        private long ticks;

        DriverNode(Orchestrator orchestrator) {
            super(orchestrator);
        }

        @SubscribedTo(topic = "cmd/target")
        public void onCommand(Double target) {
            orchestrator.publish("cmd/applied", target);
        }

        @RunPeriodically(hz = 200)
        public void tick() {
            orchestrator.publish("driver/ticks", ++ticks);
        }
    }

    @Test
    void unrecordedValuesSurfaceAsTheSentinelRatherThanNull(@TempDir File dir) throws IOException {
        File file = new File(dir, "unrecorded.engram");
        EngramSession session = EngramSession.start(
                "Unrecorded", file, orchestrator,
                RecorderConfig.builder().withLog(m -> { }).build(), null);
        try {
            session.orchestrator().publish("mystery", new Object());
        } finally {
            session.close();
        }

        EngramRecording recording = EngramRecordingReader.read(file);
        Sample sample = recording.samples("mystery").get(0);

        assertFalse(sample.hasValue());
        assertEquals(Values.UNRECORDED, sample.value());
        assertEquals(1, recording.topic("mystery").orElseThrow().unrecordedCount());
    }
}
