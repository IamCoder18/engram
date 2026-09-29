package com.aaravlabs.engram.recorder;

import com.aaravlabs.engram.replay.EngramRecording;
import com.aaravlabs.engram.replay.EngramRecordingReader;
import com.aaravlabs.synapse.Orchestrator;
import com.aaravlabs.synapse.ftc.HardwareView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises session wiring against a real Synapse orchestrator, which is the
 * only way to be confident the decorator behaves correctly with respect to
 * topics, subscriptions, and the hardware facade.
 */
class EngramSessionTest {

    private Orchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = Orchestrator.create("engram-test");
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    @Test
    void decoratorStrategyRecordsPublishesAndWrapsTheOrchestrator(@TempDir File dir) throws IOException {
        EngramSession session = start(dir, "decorator");
        try {
            assertEquals("decorator", session.strategyName());
            assertNotSame(orchestrator, session.orchestrator(),
                    "the decorator must hand back a wrapper to use");

            session.orchestrator().publish("drive/power", 0.75);
            session.orchestrator().publish("g1/a", true);
        } finally {
            session.close();
        }

        EngramRecording recording = read(dir);
        assertEquals(0.75, (Double) recording.valueAt("drive/power", Long.MAX_VALUE).orElseThrow());
        assertEquals(Boolean.TRUE, recording.valueAt("g1/a", Long.MAX_VALUE).orElseThrow());
    }

    @Test
    void publishingStillReachesSubscribersThroughTheWrapper(@TempDir File dir) throws Exception {
        EngramSession session = start(dir, "decorator");
        try {
            List<Double> received = new ArrayList<>();
            session.orchestrator().subscribe("s", Double.class, received::add);

            session.orchestrator().publish("s", 1.5);

            long deadline = System.nanoTime() + 5_000_000_000L;
            while (received.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertEquals(List.of(1.5), received, "the wrapper must not break delivery");
        } finally {
            session.close();
        }
    }

    @Test
    void latestValueCacheStillWorksThroughTheWrapper(@TempDir File dir) throws IOException {
        EngramSession session = start(dir, "decorator");
        try {
            session.orchestrator().publish("cached", 7.0);
            assertEquals(7.0,
                    session.orchestrator().getLatestValue("cached", Double.class).orElseThrow());
        } finally {
            session.close();
        }
    }

    @Test
    void lifecycleIsRecordedAcrossStartAndStop(@TempDir File dir) throws IOException {
        EngramSession session = start(dir, "decorator");
        session.markStart();
        session.orchestrator().publish("a", 1.0);
        session.close();

        EngramRecording recording = read(dir);
        assertEquals(0, recording.initTimeUs());
        assertTrue(recording.startTimeUs() >= 0, "START should be recorded");
        assertTrue(recording.stopTimeUs() >= recording.startTimeUs(), "STOP should follow START");
        assertTrue(recording.durationUs() > 0);
    }

    @Test
    void closeIsIdempotentAndFinalizesTheFile(@TempDir File dir) throws IOException {
        EngramSession session = start(dir, "decorator");
        session.orchestrator().publish("a", 1.0);

        session.close();
        session.close();
        session.close();

        EngramRecording recording = read(dir);
        assertFalse(recording.isTruncated());
        assertEquals(1, recording.samples("a").size());
    }

    @Test
    void publishingAfterCloseIsNotRecorded(@TempDir File dir) throws IOException {
        EngramSession session = start(dir, "decorator");
        Orchestrator wrapped = session.orchestrator();
        session.close();

        wrapped.publish("late", 1.0);

        EngramRecording recording = read(dir);
        assertFalse(recording.topic("late").isPresent(),
                "a publish after close must not introduce a topic");
        assertEquals(1, session.stats().publishesDropped());
    }

    @Test
    void closeLeavesTheOrchestratorOpenBecauseTheSessionDoesNotOwnIt(@TempDir File dir) throws IOException {
        EngramSession session = start(dir, "decorator");
        assertFalse(orchestrator.isClosed());
        session.close();
        // SafeOpMode.stop() closes the orchestrator after onSafeStop() runs, so
        // closing it here would break the team's shutdown ordering.
        assertFalse(orchestrator.isClosed(),
                "the session must not close an orchestrator it does not own");
    }

    // ---- the bulkRead gap ------------------------------------------------

    @Test
    void sensorPublishesThroughTheOriginalHardwareViewAreNotRecorded(@TempDir File dir) throws IOException {
        // This documents a real limitation rather than a bug: Synapse binds
        // HardwareView to the concrete orchestrator inside SafeOpMode.init(),
        // before Engram runs, so the decorator cannot observe those publishes.
        EngramSession session = start(dir, "decorator");
        try {
            // A HardwareView over the real orchestrator, as bulkRead creates.
            new HardwareView(orchestrator).publish("sensor/odom", 1.0);

            EngramRecording recording = readAfterPartial(session);
            assertFalse(recording.topic("sensor/odom").isPresent(),
                    "documents the decorator's known gap");
        } finally {
            session.close();
        }
    }

    @Test
    void sensorPublishesThroughARecordingViewAreRecorded(@TempDir File dir) throws IOException {
        EngramSession session = start(dir, "decorator");
        try {
            // What EngramSession#recording does for a bulkRead callback.
            HardwareView recordingView = new HardwareView(session.orchestrator());
            recordingView.publish("sensor/odom", 42.0);

            EngramRecording recording = readAfterPartial(session);
            assertEquals(1, recording.samples("sensor/odom").size(),
                    "the recording() workaround must close the gap");
            assertEquals(42.0, (Double) recording.valueAt("sensor/odom", Long.MAX_VALUE).orElseThrow());
        } finally {
            session.close();
        }
    }

    @Test
    void recordingReturnsTheReaderUnchangedWhenSensorCaptureIsAutomatic(@TempDir File dir) throws IOException {
        EngramSession session = start(dir, "auto");
        try {
            com.aaravlabs.synapse.ftc.BulkReader reader = view -> { };
            if (session.isSensorCaptureAutomatic()) {
                assertSame(reader, session.recording(reader));
            } else {
                assertNotSame(reader, session.recording(reader));
            }
        } finally {
            session.close();
        }
    }

    @Test
    void recordingRejectsANullReader(@TempDir File dir) throws IOException {
        EngramSession session = start(dir, "decorator");
        try {
            assertThrows(IllegalArgumentException.class, () -> session.recording(null));
        } finally {
            session.close();
        }
    }

    // ---- strategy selection ---------------------------------------------

    @Test
    void aPlainOrchestratorStillFallsBackToTheDecorator(@TempDir File dir) throws IOException {
        // The PublishListener interface is on the test classpath (see the stub
        // in com.aaravlabs.synapse), but a stock Synapse 0.4.0 orchestrator has
        // no addPublishListener method. Selection must key off the
        // orchestrator's actual capability, not the mere presence of the
        // interface, or a partially-updated classpath would record nothing.
        EngramSession session = start(dir, "auto");
        try {
            assertEquals("decorator", session.strategyName());
            assertNotSame(orchestrator, session.orchestrator());
        } finally {
            session.close();
        }
    }

    @Test
    void forcingAnUnavailableStrategyFailsLoudly(@TempDir File dir) {
        assertThrows(IllegalStateException.class, () -> EngramSession.start(
                "Op", new File(dir, "forced.engram"), orchestrator, RecorderConfig.defaults(),
                "publish-listener"));
    }

    @Test
    void unknownStrategyIsARejectedArgument(@TempDir File dir) {
        assertThrows(IllegalArgumentException.class, () -> EngramSession.start(
                "Op", new File(dir, "bad.engram"), orchestrator, RecorderConfig.defaults(), "telepathy"));
    }

    @Test
    void startRejectsANullOrchestrator(@TempDir File dir) {
        assertThrows(IllegalArgumentException.class, () -> EngramSession.start(
                "Op", new File(dir, "null.engram"), null, RecorderConfig.defaults(), null));
    }

    @Test
    void sessionExposesItsFileAndRecorder(@TempDir File dir) throws IOException {
        File file = new File(dir, "exposed.engram");
        EngramSession session = EngramSession.start(
                "Op", file, orchestrator, RecorderConfig.defaults(), null);
        try {
            assertEquals(file, session.file());
            assertNotNull(session.recorder());
            assertNotNull(session.location());
        } finally {
            session.close();
        }
    }

    @Test
    void statsAreAvailableBeforeAndAfterClose(@TempDir File dir) throws IOException {
        EngramSession session = start(dir, "decorator");
        session.orchestrator().publish("a", 1.0);
        assertTrue(session.stats().publishesRecorded() >= 1);
        session.close();
        assertTrue(session.stats().isHealthy());
    }

    // ---- helpers ---------------------------------------------------------

    private EngramSession start(File dir, String strategy) throws IOException {
        return EngramSession.start(
                "TestOpMode", new File(dir, "session-" + strategy + ".engram"),
                orchestrator, RecorderConfig.defaults(), strategy);
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        // Best effort: a leftover temp file must not fail a test.
        file.delete();
    }

    private static EngramRecording read(File dir) throws IOException {
        return EngramRecordingReader.read(new File(dir, "session-decorator.engram"));
    }

    /** Reads mid-session by flushing queued events and parsing what is there. */
    private static EngramRecording readAfterPartial(EngramSession session) throws IOException {
        session.recorder().flush();
        // The STOP event has not been written yet, so the file has no trailing
        // marker; parseDelimitedFrom handles a clean end of stream.
        return EngramRecordingReader.read(session.file());
    }
}
