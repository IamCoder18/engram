package com.aaravlabs.engram.replay;

import com.aaravlabs.engram.proto.EngramProto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngramRecordingReaderTest {

    private static EngramRecording read(byte[] bytes) throws IOException {
        return EngramRecordingReader.read(new ByteArrayInputStream(bytes), "test");
    }

    // ---- happy path ------------------------------------------------------

    @Test
    void readsHeaderMetadata() throws IOException {
        EngramRecording r = read(TestRecordings.sampleFile());

        assertEquals("Fixture", r.opModeName());
        assertEquals(1, r.formatVersion());
        assertEquals(1_700_000_000_000L, r.startEpochMs());
        assertFalse(r.isTruncated());
    }

    @Test
    void readsLifecycleTimestamps() throws IOException {
        EngramRecording r = read(TestRecordings.sampleFile());

        assertEquals(0, r.initTimeUs());
        assertEquals(500, r.startTimeUs());
        assertEquals(1000, r.stopTimeUs());
        assertEquals(500, r.durationUs(), "duration is measured from START to STOP");
    }

    @Test
    void rebuildsTheTopicManifestFromDeclarations() throws IOException {
        EngramRecording r = read(TestRecordings.sampleFile());

        assertEquals(2, r.topics().size());
        assertEquals("drive/power", r.topics().get(0).name());
        assertEquals(0, r.topics().get(0).id(), "ids are dense and assigned in declaration order");
        assertEquals("g1/a", r.topics().get(1).name());
        assertEquals("java.lang.Boolean", r.topics().get(1).javaType());
    }

    @Test
    void decodesEveryValueKind() throws IOException {
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(lifecycleInit());
        events.addAll(declareAll());
        events.add(TestRecordings.sample(10, 0, TestRecordings.d(1.5)));
        events.add(TestRecordings.sample(20, 1, TestRecordings.f(2.5f)));
        events.add(TestRecordings.sample(30, 2, TestRecordings.i(-7)));
        events.add(TestRecordings.sample(40, 3, TestRecordings.l(9_000_000_000L)));
        events.add(TestRecordings.sample(50, 4, TestRecordings.b(true)));
        events.add(TestRecordings.sample(60, 5, TestRecordings.s("text")));
        events.add(TestRecordings.sample(70, 6, TestRecordings.bytes(new byte[]{1, 2, 3})));
        events.add(TestRecordings.sample(80, 7, TestRecordings.unset()));
        events.add(lifecycleStop());

        EngramRecording r = read(TestRecordings.file(header(), events));

        assertEquals(1.5, (Double) valueAt(r, 0, 10));
        assertEquals(2.5f, (Float) valueAt(r, 1, 20));
        assertEquals(-7, (Integer) valueAt(r, 2, 30));
        assertEquals(9_000_000_000L, valueAt(r, 3, 40));
        assertEquals(Boolean.TRUE, valueAt(r, 4, 50));
        assertEquals("text", valueAt(r, 5, 60));
        assertArrayEqualsBytes(new byte[]{1, 2, 3}, (byte[]) valueAt(r, 6, 70));
        assertEquals(Values.UNRECORDED, valueAt(r, 7, 80));
        assertEquals(1, r.topicById(7).orElseThrow().unrecordedCount());
    }

    @Test
    void preservesPerThreadPublishOrder() throws IOException {
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(lifecycleInit());
        events.addAll(declareAll());
        // Two threads publishing concurrently: timestamps interleave and are
        // not globally sorted, which is faithful and must not be "fixed".
        events.add(TestRecordings.sample(300, 0, TestRecordings.d(1.0)));
        events.add(TestRecordings.sample(100, 0, TestRecordings.d(2.0)));
        events.add(TestRecordings.sample(200, 0, TestRecordings.d(3.0)));
        events.add(lifecycleStop());

        EngramRecording r = read(TestRecordings.file(header(), events));

        // Per-topic samples are sorted, so range queries behave.
        List<Sample> sorted = r.samples(0);
        assertEquals(100, sorted.get(0).timeUs());
        assertEquals(200, sorted.get(1).timeUs());
        assertEquals(300, sorted.get(2).timeUs());
        assertEquals(2.0, (Double) sorted.get(0).value());
    }

    // ---- gzip ------------------------------------------------------------

    @Test
    void readsGzippedRecordings(@TempDir Path dir) throws IOException {
        byte[] plain = TestRecordings.sampleFile();
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(plain);
        }
        assertTrue(gz.size() < plain.length, "the fixture should compress");

        Path file = dir.resolve("z.engram.gz");
        Files.write(file, gz.toByteArray());

        EngramRecording fromGz = EngramRecordingReader.read(file);
        EngramRecording fromPlain = EngramRecordingReader.read(write(dir, "p.engram", plain));

        assertEquals(fromPlain.topics().size(), fromGz.topics().size());
        assertEquals(fromPlain.samples("drive/power").size(), fromGz.samples("drive/power").size());
        assertEquals(fromPlain.valueAt("drive/power", Long.MAX_VALUE),
                fromGz.valueAt("drive/power", Long.MAX_VALUE));
    }

    // ---- truncation ------------------------------------------------------

    @Test
    void readsUpToATruncatedTrailingMessage(@TempDir Path dir) throws IOException {
        byte[] complete = TestRecordings.sampleFile();
        byte[] truncated = TestRecordings.withTruncatedTail(complete, 24);

        Path file = write(dir, "t.engram", truncated);
        EngramRecording r = EngramRecordingReader.read(file);

        assertTrue(r.isTruncated(), "a partial trailing message must be reported");
        assertNotNull(r.truncationReason());
        // Every real event precedes the appended partial message, so all of
        // them survive: 3 on drive/power plus 2 on g1/a.
        assertEquals("Fixture", r.opModeName());
        assertEquals(2, r.topics().size());
        assertEquals(3, r.samples("drive/power").size());
        assertEquals(2, r.samples("g1/a").size());
    }

    @Test
    void aCompleteFileIsNotReportedAsTruncated(@TempDir Path dir) throws IOException {
        EngramRecording r = EngramRecordingReader.read(write(dir, "c.engram", TestRecordings.sampleFile()));
        assertFalse(r.isTruncated());
    }

    @Test
    void aFileWithNoTrailingStopEventIsCompleteNotTruncated(@TempDir Path dir) throws IOException {
        // A process killed between the last publish and its final flush leaves
        // a well-formed file that simply has no STOP.
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(lifecycleInit());
        events.addAll(declareAll());
        events.add(TestRecordings.sample(10, 0, TestRecordings.d(1.0)));

        EngramRecording r = EngramRecordingReader.read(
                write(dir, "nostop.engram", TestRecordings.file(header(), events)));

        assertFalse(r.isTruncated(), "a clean end of stream is not truncation");
        assertEquals(-1, r.stopTimeUs());
        assertEquals(10, r.durationUs(),
                "with no STOP, duration falls back to the last event (10us) minus INIT (0us)");
    }

    // ---- malformed input -------------------------------------------------

    @Test
    void rejectsAnEmptyFile(@TempDir Path dir) {
        IOException e = assertThrows(IOException.class,
                () -> EngramRecordingReader.read(write(dir, "empty.engram", new byte[0])));
        assertTrue(e.getMessage().contains("not a valid engram recording"), e.getMessage());
    }

    @Test
    void rejectsAHeaderThatCannotBeParsed(@TempDir Path dir) {
        // A length prefix promising 100 bytes, followed by garbage.
        byte[] garbage = new byte[102];
        garbage[0] = 100;
        IOException e = assertThrows(IOException.class,
                () -> EngramRecordingReader.read(write(dir, "garbage.engram", garbage)));
        assertNotNull(e.getMessage());
    }

    @Test
    void rejectsAMissingFile() {
        assertThrows(IOException.class, () -> EngramRecordingReader.read(java.nio.file.Paths.get("/nope/absent.engram")));
    }

    @Test
    void survivesAnEmptyRecordingWithOnlyAHeader(@TempDir Path dir) throws IOException {
        EngramRecording r = EngramRecordingReader.read(
                write(dir, "hdronly.engram", TestRecordings.file(header(), Collections.emptyList())));

        assertEquals(0, r.eventCount());
        assertTrue(r.topics().isEmpty());
        assertEquals(-1, r.initTimeUs());
        assertEquals(-1, r.durationUs());
    }

    @Test
    void synthesisesAManifestEntryForAPublishWithNoDeclaration(@TempDir Path dir) throws IOException {
        // Malformed but recoverable: a publish whose declaration never arrived.
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(lifecycleInit());
        events.add(TestRecordings.sample(10, 4, TestRecordings.d(1.0)));

        EngramRecording r = EngramRecordingReader.read(
                write(dir, "orphan.engram", TestRecordings.file(header(), events)));

        assertEquals(1, r.topics().size());
        assertEquals("topic-4", r.topics().get(0).name());
        assertEquals(1, r.topics().get(0).publishCount());
    }

    @Test
    void ignoresEventsWithNoCaseSet(@TempDir Path dir) throws IOException {
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(lifecycleInit());
        events.add(EngramProto.RecordingEvent.newBuilder().setRelTimeUs(5).build());
        events.add(TestRecordings.sample(10, 0, TestRecordings.d(1.0)));

        EngramRecording r = EngramRecordingReader.read(
                write(dir, "nocase.engram", TestRecordings.file(header(), events)));

        assertEquals(3, r.eventCount());
        assertEquals(1, r.samples(0).size());
    }

    // ---- helpers ---------------------------------------------------------

    private static Path write(Path dir, String name, byte[] bytes) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    private static Object valueAt(EngramRecording r, int topicId, long timeUs) {
        return r.valueAt(topicId, timeUs).orElseThrow(() -> new AssertionError("no value at " + timeUs));
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        assertTrue(Arrays.equals(expected, actual),
                "expected " + Arrays.toString(expected) + " but was " + Arrays.toString(actual));
    }

    private static EngramProto.RecordingEvent lifecycleInit() {
        return TestRecordings.lifecycle(0, EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT);
    }

    private static EngramProto.RecordingEvent lifecycleStop() {
        return TestRecordings.lifecycle(10_000, EngramProto.LifecycleEvent.Type.LIFECYCLE_STOP);
    }

    private static List<EngramProto.RecordingEvent> declareAll() {
        EngramProto.TopicDeclaration a = TestRecordings.declare(0, "d", "java.lang.Double",
                EngramProto.ValueType.VALUE_TYPE_DOUBLE);
        EngramProto.TopicDeclaration b = TestRecordings.declare(1, "f", "java.lang.Float",
                EngramProto.ValueType.VALUE_TYPE_FLOAT);
        EngramProto.TopicDeclaration c = TestRecordings.declare(2, "i", "java.lang.Integer",
                EngramProto.ValueType.VALUE_TYPE_INT32);
        EngramProto.TopicDeclaration d = TestRecordings.declare(3, "l", "java.lang.Long",
                EngramProto.ValueType.VALUE_TYPE_INT64);
        EngramProto.TopicDeclaration e = TestRecordings.declare(4, "b", "java.lang.Boolean",
                EngramProto.ValueType.VALUE_TYPE_BOOL);
        EngramProto.TopicDeclaration f = TestRecordings.declare(5, "s", "java.lang.String",
                EngramProto.ValueType.VALUE_TYPE_STRING);
        EngramProto.TopicDeclaration g = TestRecordings.declare(6, "raw", "byte[]",
                EngramProto.ValueType.VALUE_TYPE_BYTES);
        EngramProto.TopicDeclaration h = TestRecordings.declare(7, "none", "Widget",
                EngramProto.ValueType.VALUE_TYPE_BYTES);
        List<EngramProto.RecordingEvent> out = new ArrayList<>();
        for (EngramProto.TopicDeclaration declaration : Arrays.asList(a, b, c, d, e, f, g, h)) {
            out.add(TestRecordings.declaration(declaration, 1));
        }
        return out;
    }

    private static EngramProto.RecordingHeader header() {
        return TestRecordings.header("Fixture", 1, 1_700_000_000_000L);
    }
}
