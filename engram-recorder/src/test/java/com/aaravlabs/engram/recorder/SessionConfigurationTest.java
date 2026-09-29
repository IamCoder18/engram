package com.aaravlabs.engram.recorder;

import com.aaravlabs.engram.replay.EngramRecording;
import com.aaravlabs.engram.replay.EngramRecordingReader;
import com.aaravlabs.engram.recorder.annotation.Recorded;
import com.aaravlabs.synapse.Orchestrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the declarative configuration path and the value-object validation.
 *
 * <p>{@code EngramSession.start(Object, Orchestrator)} reads {@link Recorded}
 * reflectively, so a typo in an attribute name would silently do nothing rather
 * than fail loudly. These tests pin the values that actually reach the session.
 */
class SessionConfigurationTest {

    private Orchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = Orchestrator.create("config-test");
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    // ---- the @Recorded annotation ----------------------------------------

    @Recorded
    static final class Annotated {
    }

    @Recorded(label = "Custom Label", flushIntervalMs = 25, maxEventsPerFlush = 7, strategy = "decorator")
    static final class FullyAnnotated {
    }

    @Recorded(strategy = "auto")
    static final class AutoStrategy {
    }

    @Test
    void theAnnotationLabelBecomesTheRecordedOpModeName() throws IOException {
        // The two-argument overload is the one a real OpMode calls, and the
        // only one that reads @Recorded's label.
        EngramSession session = EngramSession.start(new FullyAnnotated(), orchestrator);
        try {
            assertEquals("Custom Label", session.recorder().opModeName());
            assertTrue(session.file().getName().startsWith("Custom_Label_"),
                    session.file().toString());
        } finally {
            session.close();
            session.file().delete();
        }
    }

    @Test
    void theAnnotationStrategyIsHonoured() throws IOException {
        EngramSession session = EngramSession.start(new FullyAnnotated(), orchestrator);
        try {
            assertEquals("decorator", session.strategyName());
        } finally {
            session.close();
            session.file().delete();
        }
    }

    @Test
    void anUnlabelledAnnotationFallsBackToTheClassName() throws IOException {
        EngramSession session = EngramSession.start(new Annotated(), orchestrator);
        try {
            assertEquals("Annotated", session.recorder().opModeName());
        } finally {
            session.close();
            session.file().delete();
        }
    }

    @Test
    void aNullContextIsToleratedByTheTwoArgumentOverload() throws IOException {
        // Teams that build an orchestrator by hand have no OpMode instance to
        // pass, and the session must still work.
        EngramSession session = EngramSession.start(null, orchestrator);
        try {
            assertEquals("opmode", session.recorder().opModeName());
            session.orchestrator().publish("a", 1.0);
        } finally {
            session.close();
            session.file().delete();
        }
    }

    @Test
    void annotationValuesReachTheRunningSession() throws IOException {
        // maxEventsPerFlush is one of the two numbers that decide how much data
        // a crash costs, so it must not be silently ignored.
        EngramSession session = EngramSession.start(new FullyAnnotated(), orchestrator);
        try {
            assertEquals(7, session.config().maxEventsPerFlush());
            assertEquals(25, session.config().flushIntervalMs());

            Recorder recorder = session.recorder();
            for (int i = 0; i < 20; i++) {
                recorder.onPublish("t", (double) i, System.nanoTime());
            }
            recorder.flush();
            EngramRecording recording = EngramRecordingReader.read(session.file());
            assertTrue(recording.samples("t").size() >= 15,
                    "a 7-event flush threshold should have written most samples already");
        } finally {
            session.close();
            session.file().delete();
        }
    }

    @Test
    void aSessionWithoutAnAnnotationStillWorks(@TempDir File dir) throws IOException {
        EngramSession session = EngramSession.start(
                new Plain(), orchestrator,
                RecorderConfig.builder()
                        .withFlushIntervalMs(20)
                        .withLog(m -> { })
                        .build());
        try {
            assertEquals("Plain", session.recorder().opModeName());
            session.orchestrator().publish("a", 1.0);
        } finally {
            session.close();
        }
        EngramRecording recording = EngramRecordingReader.read(session.file());
        assertEquals(1.0, (Double) recording.valueAt("a", Long.MAX_VALUE).orElseThrow());
    }

    @Test
    void aClassThatIsNotAnOpModeIsHandled(@TempDir File dir) throws IOException {
        EngramSession session = EngramSession.start(
                "a string, not an object graph", new File(dir, "weird.engram"),
                orchestrator, RecorderConfig.defaults(), "decorator");
        try {
            assertNotNull(session.file());
        } finally {
            session.close();
        }
    }

    // ---- RecorderConfig validation ---------------------------------------

    @Test
    void flushIntervalMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> RecorderConfig.builder().withFlushIntervalMs(0));
        assertThrows(IllegalArgumentException.class,
                () -> RecorderConfig.builder().withFlushIntervalMs(-1));
    }

    @Test
    void maxEventsMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> RecorderConfig.builder().withMaxEventsPerFlush(0));
        assertThrows(IllegalArgumentException.class,
                () -> RecorderConfig.builder().withMaxEventsPerFlush(-5));
    }

    @Test
    void aNullCodecIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RecorderConfig.builder().withCodec(null));
    }

    @Test
    void aNullLogSilentlyBecomesSilent() {
        RecorderConfig config = RecorderConfig.builder().withLog(null).build();
        assertNotNull(config);
        // A null log would NPE on the first warning; it must degrade instead.
        config.log().warn("should not throw");
    }

    @Test
    void defaultsAreExposedAndSane() {
        RecorderConfig config = RecorderConfig.defaults();
        assertEquals(RecorderConfig.DEFAULT_FLUSH_INTERVAL_MS, config.flushIntervalMs());
        assertEquals(RecorderConfig.DEFAULT_MAX_EVENTS_PER_FLUSH, config.maxEventsPerFlush());
        assertEquals(0, config.codecs().length);
        assertFalse(config.javaSerializationFallback());
    }

    @Test
    void theCodecArrayIsDefensivelyCopied() {
        RecorderConfig config = RecorderConfig.builder()
                .withCodec(new ValueEncoderTest.WidgetCodec())
                .build();
        ValueCodec[] first = config.codecs();
        assertEquals(1, first.length);
        first[0] = null;
        assertEquals(1, config.codecs().length, "callers must not be able to mutate the config");
    }

    // ---- RecorderLog -----------------------------------------------------

    @Test
    void theSilentLogDiscardsEverything() {
        RecorderLog.SILENT.warn("nothing happens");
        assertNotNull(RecorderLog.SILENT);
    }

    @Test
    void theStderrLogWritesToStandardError() throws java.io.UnsupportedEncodingException {
        java.io.PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new java.io.PrintStream(captured, true, "UTF-8"));
            RecorderLog.stderr().warn("a warning");
        } finally {
            System.setErr(original);
        }
        String text = captured.toString();
        assertTrue(text.contains("a warning"), text);
        assertTrue(text.contains("WARN"), text);
    }

    @Test
    void aCustomLogReceivesEncoderWarnings(@TempDir File dir) throws IOException {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        try (Recorder recorder = Recorder.open(new File(dir, "log.engram"), "LogOp",
                RecorderConfig.builder().withLog(warnings::add).build())) {
            recorder.onPublish("w", new ValueEncoderTest.Widget(1), System.nanoTime());
        }
        assertFalse(warnings.isEmpty(), "the encoder should have warned about the missing codec");
    }

    // ---- OutputLocation filenames ----------------------------------------

    @Test
    void aSessionFileNameEncodesTheLabelAndTimestamp() throws IOException {
        assertEquals("MyOp_2026-01-02_030405.engram",
                OutputLocation.fileName("MyOp", epoch(2026, 1, 2, 3, 4, 5)));
    }

    // ---- helpers ---------------------------------------------------------

    static final class Plain {
    }

    private static long epoch(int y, int m, int d, int h, int min, int s) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d, h, min, s);
        return c.getTimeInMillis();
    }
}
