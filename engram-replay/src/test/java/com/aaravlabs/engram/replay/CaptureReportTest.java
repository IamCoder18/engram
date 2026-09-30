package com.aaravlabs.engram.replay;

import com.aaravlabs.engram.proto.EngramProto;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what "complete" can honestly mean for a recording.
 *
 * <p>Every finding asserted here is derived from something the wire format
 * genuinely records. The class documentation names the one thing it cannot
 * see -- a topic the capture path never observed at all -- and the
 * caller-supplied expectation is the only defence against it, so that is
 * tested too.
 */
class CaptureReportTest {

    // ---- healthy ---------------------------------------------------------

    @Test
    void aFullyCapturedRecordingIsComplete() throws IOException {
        CaptureReport report = CaptureReport.of(healthy());

        assertTrue(report.isComplete(), report.toString());
        assertTrue(report.isFinalized());
        assertFalse(report.isTruncated());
        assertEquals(2, report.declaredTopicCount());
        assertEquals(2, report.observedTopicCount());
        assertTrue(report.findings().isEmpty());
        assertEquals("CaptureReport{complete}", report.toString());
    }

    @Test
    void completenessIsAlsoReachableFromTheRecording() throws IOException {
        EngramRecording recording = healthy();
        assertTrue(recording.captureReport().isComplete());
        assertTrue(recording.captureReport(Arrays.asList("drive/power", "g1/a")).isComplete());
        assertEquals(1, recording.captureReport(Collections.singletonList("absent"))
                .findings().size());
    }

    // ---- missing topics --------------------------------------------------

    @Test
    void anExpectedTopicThatIsAbsentIsReported() throws IOException {
        // The bulkRead gap, from the only angle the file can see it: the caller
        // says a topic should be there and it is not.
        CaptureReport report = CaptureReport.of(healthy(),
                Arrays.asList("drive/power", "sensor/odom-left", "sensor/imu"));

        assertFalse(report.isComplete());
        assertEquals(Collections.singletonList(CaptureReport.MISSING_EXPECTED_TOPIC), report.codes());
        assertEquals(Arrays.asList("sensor/odom-left", "sensor/imu"), report.missingExpectedTopics());
        assertEquals(3, report.expectedTopics().size());
    }

    @Test
    void withoutExpectationsAMissingTopicIsInvisibleAndSaysSo() throws IOException {
        // A topic the recorder never saw leaves no trace: no declaration, no
        // publish, no counter. This is the limitation, pinned by a test so it
        // cannot be quietly forgotten.
        CaptureReport report = CaptureReport.of(healthy());

        assertTrue(report.isComplete(),
                "the format cannot detect a topic that was never captured; --expect-topic is the only defence");
    }

    @Test
    void aTopicDeclaredButNeverPublishedIsIncomplete() throws IOException {
        // The recorder queues a declaration immediately before the publish that
        // triggers it, so this pairing means the stream stopped between the two.
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(lifecycle(0, EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT));
        events.add(declare(0, "drive/power"));
        events.add(declare(1, "sensor/odom"));
        events.add(sample(100, 0, EngramProto.TopicValue.newBuilder().setDoubleVal(1.0).build()));
        events.add(lifecycle(200, EngramProto.LifecycleEvent.Type.LIFECYCLE_STOP));

        CaptureReport report = CaptureReport.of(read(events));

        assertFalse(report.isComplete());
        assertEquals(Collections.singletonList(CaptureReport.DECLARED_NEVER_PUBLISHED), report.codes());
        assertEquals(Collections.singletonList("sensor/odom"),
                report.findings().get(0).topics());
        assertEquals(2, report.declaredTopicCount());
        assertEquals(1, report.observedTopicCount());
    }

    @Test
    void aPublishWithNoDeclarationIsReported() throws IOException {
        // Not something the recorder can produce: declarations are queued first.
        // A file like this is damaged, and the report must say so rather than
        // presenting a synthesised manifest entry as normal.
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(lifecycle(0, EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT));
        events.add(declare(0, "drive/power"));
        events.add(sample(100, 0, EngramProto.TopicValue.newBuilder().setDoubleVal(1.0).build()));
        events.add(sample(110, 7, EngramProto.TopicValue.newBuilder().setDoubleVal(1.0).build()));
        events.add(lifecycle(200, EngramProto.LifecycleEvent.Type.LIFECYCLE_STOP));

        EngramRecording recording = read(events);
        assertEquals(2, recording.topics().size());
        assertFalse(recording.topicById(7).orElseThrow().isDeclared());
        assertTrue(recording.topics().get(0).isDeclared());

        CaptureReport report = CaptureReport.of(recording);
        assertEquals(Collections.singletonList(CaptureReport.UNDECLARED_TOPIC), report.codes());
        assertEquals(Collections.singletonList("topic-7"), report.findings().get(0).topics());
    }

    // ---- finalization ----------------------------------------------------

    @Test
    void aRecordingWithNoStopEventWasNeverFinalized() throws IOException {
        // The strongest signal the format has. A robot killed mid-match leaves
        // a well-formed file that simply stops.
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(lifecycle(0, EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT));
        events.add(declare(0, "drive/power"));
        events.add(sample(100, 0, EngramProto.TopicValue.newBuilder().setDoubleVal(1.0).build()));

        CaptureReport report = CaptureReport.of(read(events));

        assertFalse(report.isComplete());
        assertFalse(report.isFinalized());
        assertFalse(report.isTruncated(), "a clean end of stream is not truncation");
        assertEquals(Collections.singletonList(CaptureReport.UNFINALIZED), report.codes());
        assertTrue(report.findings().get(0).message().contains("LIFECYCLE_STOP"),
                report.findings().get(0).message());
    }

    @Test
    void aTruncatedRecordingIsReportedAsTruncated() throws IOException {
        byte[] truncated = TestRecordings.withTruncatedTail(TestRecordings.sampleFile(), 24);
        EngramRecording recording = EngramRecordingReader.read(
                new ByteArrayInputStream(truncated), "test");

        CaptureReport report = CaptureReport.of(recording);

        assertFalse(report.isComplete());
        assertTrue(report.isTruncated());
        assertTrue(report.isFinalized(), "the STOP landed before the cut, so it was still being written");
        assertEquals(Collections.singletonList(CaptureReport.TRUNCATED), report.codes());
        assertTrue(report.findings().get(0).message().contains(report.truncationReason()),
                report.findings().get(0).message());
    }

    @Test
    void aCrashMidMatchReportsBothUnfinalizedAndTruncated() throws IOException {
        // Killed while writing: the tail is a partial message and the STOP never
        // made it to disk. Two independent signals, both true.
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(lifecycle(0, EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT));
        events.add(declare(0, "drive/power"));
        events.add(sample(100, 0, EngramProto.TopicValue.newBuilder().setDoubleVal(1.0).build()));

        byte[] crashed = withPartialTail(file(events), 24);
        CaptureReport report = CaptureReport.of(
                EngramRecordingReader.read(new ByteArrayInputStream(crashed), "test"));

        assertEquals(2, report.findings().size(), report.toString());
        assertTrue(report.codes().contains(CaptureReport.UNFINALIZED));
        assertTrue(report.codes().contains(CaptureReport.TRUNCATED));
    }

    @Test
    void aRecordingWithNoInitHasNoTimeOrigin() throws IOException {
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(declare(0, "drive/power"));
        events.add(sample(100, 0, EngramProto.TopicValue.newBuilder().setDoubleVal(1.0).build()));
        events.add(lifecycle(200, EngramProto.LifecycleEvent.Type.LIFECYCLE_STOP));

        CaptureReport report = CaptureReport.of(read(events));

        assertFalse(report.isComplete());
        assertEquals(Collections.singletonList(CaptureReport.MISSING_INIT), report.codes());
    }

    @Test
    void aRecordingWithNoTopicsAtAllIsReportedRatherThanLookingEmpty() throws IOException {
        CaptureReport report = CaptureReport.of(read(Collections.singletonList(
                lifecycle(0, EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT))));

        assertFalse(report.isComplete());
        assertTrue(report.codes().contains(CaptureReport.NO_TOPICS));
    }

    @Test
    void everyWayOfBeingIncompleteCanBeReportedAtOnce() throws IOException {
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(declare(0, "drive/power"));
        events.add(sample(100, 0, EngramProto.TopicValue.newBuilder().setDoubleVal(1.0).build()));
        events.add(sample(110, 9, EngramProto.TopicValue.newBuilder().setDoubleVal(1.0).build()));

        CaptureReport report = CaptureReport.of(read(events), Arrays.asList("sensor/odom"));

        assertEquals(Arrays.asList(
                CaptureReport.UNFINALIZED,
                CaptureReport.MISSING_INIT,
                CaptureReport.UNDECLARED_TOPIC,
                CaptureReport.MISSING_EXPECTED_TOPIC), report.codes());
    }

    @Test
    void nullExpectationsAreRejected() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> CaptureReport.of(healthy(), null));
    }

    @Test
    void findingsRenderReadablyInToString() throws IOException {
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(lifecycle(0, EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT));
        events.add(declare(0, "drive/power"));
        events.add(declare(1, "sensor/odom"));
        events.add(sample(100, 0, EngramProto.TopicValue.newBuilder().setDoubleVal(1.0).build()));

        CaptureReport report = CaptureReport.of(read(events));
        String text = report.toString();

        assertTrue(text.startsWith("CaptureReport{"), text);
        assertTrue(text.contains(CaptureReport.UNFINALIZED), text);
        assertTrue(text.contains("sensor/odom"), text);
        assertTrue(report.findings().get(1).toString().contains("sensor/odom"));
    }

    // ---- helpers ---------------------------------------------------------

    /** A recording that finished cleanly with two healthy topics. */
    private static EngramRecording healthy() throws IOException {
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(lifecycle(0, EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT));
        events.add(declare(0, "drive/power"));
        events.add(declare(1, "g1/a"));
        events.add(sample(100, 0, EngramProto.TopicValue.newBuilder().setDoubleVal(0.5).build()));
        events.add(sample(200, 1, EngramProto.TopicValue.newBuilder().setBoolVal(true).build()));
        events.add(lifecycle(300, EngramProto.LifecycleEvent.Type.LIFECYCLE_STOP));
        return read(events);
    }

    private static EngramRecording read(List<EngramProto.RecordingEvent> events) throws IOException {
        return EngramRecordingReader.read(new ByteArrayInputStream(file(events)), "test");
    }

    private static byte[] file(List<EngramProto.RecordingEvent> events) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EngramProto.RecordingHeader.newBuilder()
                .setFormatVersion(1).setOpmodeName("Capture").setStartEpochMs(1_700_000_000_000L)
                .build().writeDelimitedTo(out);
        for (EngramProto.RecordingEvent e : events) {
            e.writeDelimitedTo(out);
        }
        return out.toByteArray();
    }

    private static EngramProto.RecordingEvent lifecycle(long t, EngramProto.LifecycleEvent.Type type) {
        return EngramProto.RecordingEvent.newBuilder().setRelTimeUs(t)
                .setLifecycle(EngramProto.LifecycleEvent.newBuilder().setType(type)).build();
    }

    private static EngramProto.RecordingEvent declare(int id, String name) {
        return EngramProto.RecordingEvent.newBuilder().setRelTimeUs(1)
                .setTopicDeclaration(EngramProto.TopicDeclaration.newBuilder()
                        .setTopicId(id).setName(name).setJavaType("java.lang.Double")
                        .setValueType(EngramProto.ValueType.VALUE_TYPE_DOUBLE)).build();
    }

    private static EngramProto.RecordingEvent sample(long t, int id, EngramProto.TopicValue value) {
        return EngramProto.RecordingEvent.newBuilder().setRelTimeUs(t)
                .setPublish(EngramProto.TopicPublish.newBuilder().setTopicId(id).setValue(value)).build();
    }

    /**
     * Appends a length prefix promising more bytes than follow, which is what a
     * process killed mid-flush leaves behind. Deliberately not a slice at an
     * arbitrary offset: that can land on a message boundary and produce a
     * clean end of stream, which is a different failure entirely.
     */
    private static byte[] withPartialTail(byte[] complete, int promised) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(complete, 0, complete.length);
        out.write(promised);
        for (int i = 0; i < promised - 1; i++) {
            out.write(0x00);
        }
        return out.toByteArray();
    }
}
