package com.aaravlabs.engram.recorder;

import com.aaravlabs.engram.proto.EngramProto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecorderTest {

    private static final long MICROS = 1_000L;

    // ---- file structure --------------------------------------------------

    @Test
    void writesAHeaderAsTheVeryFirstMessage(@TempDir File dir) throws IOException {
        File file = new File(dir, "a.engram");
        try (Recorder recorder = Recorder.open(file, "MyTeleOp")) {
            recorder.onPublish("a", 1.0, System.nanoTime());
        }

        List<Object> messages = readAll(file);
        EngramProto.RecordingHeader header = (EngramProto.RecordingHeader) messages.get(0);

        assertEquals("MyTeleOp", header.getOpmodeName());
        assertEquals(Recorder.FORMAT_VERSION, header.getFormatVersion());
        assertTrue(header.getStartEpochMs() > 0, "start time should be stamped");
    }

    @Test
    void writesExactlyOneHeaderEvenWithManyPublishes(@TempDir File dir) throws IOException {
        File file = new File(dir, "b.engram");
        try (Recorder recorder = Recorder.open(file, "Op")) {
            for (int i = 0; i < 50; i++) {
                recorder.onPublish("a", 1.0, System.nanoTime());
            }
        }

        long headers = readAll(file).stream()
                .filter(m -> m instanceof EngramProto.RecordingHeader)
                .count();
        assertEquals(1, headers, "the header must not be duplicated per event");
    }

    @Test
    void emitsInitThenStopAroundTheRun(@TempDir File dir) throws IOException {
        File file = new File(dir, "c.engram");
        try (Recorder recorder = Recorder.open(file, "Op")) {
            recorder.onPublish("a", 1.0, System.nanoTime());
        }

        List<Lifecycle> lifecycle = lifecycleEvents(readAll(file));

        assertEquals(2, lifecycle.size());
        assertEquals(EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT, lifecycle.get(0).type);
        assertEquals(0, lifecycle.get(0).timeUs, "INIT is the time origin");
        assertEquals(EngramProto.LifecycleEvent.Type.LIFECYCLE_STOP, lifecycle.get(1).type);
    }

    @Test
    void recordsStartWhenAskedAndOnlyOnce(@TempDir File dir) throws IOException {
        File file = new File(dir, "d.engram");
        try (Recorder recorder = Recorder.open(file, "Op")) {
            recorder.recordStart();
            recorder.recordStart();
            recorder.recordStart();
        }

        List<Lifecycle> lifecycle = lifecycleEvents(readAll(file));

        assertEquals(3, lifecycle.size());
        assertEquals(EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT, lifecycle.get(0).type);
        assertEquals(EngramProto.LifecycleEvent.Type.LIFECYCLE_START, lifecycle.get(1).type);
        assertEquals(EngramProto.LifecycleEvent.Type.LIFECYCLE_STOP, lifecycle.get(2).type);
    }

    @Test
    void startIsAbsentWhenNeverRecorded(@TempDir File dir) throws IOException {
        File file = new File(dir, "e.engram");
        // Deliberately no recordStart(): the point is what that omits.
        Recorder.open(file, "Op").close();

        for (Lifecycle l : lifecycleEvents(readAll(file))) {
            assertFalse(l.type == EngramProto.LifecycleEvent.Type.LIFECYCLE_START,
                    "START must not appear unless requested");
        }
    }

    // ---- topic declaration ----------------------------------------------

    @Test
    void declaresEachTopicOnceWithItsNameAndType(@TempDir File dir) throws IOException {
        File file = new File(dir, "f.engram");
        try (Recorder recorder = Recorder.open(file, "Op")) {
            for (int i = 0; i < 20; i++) {
                recorder.onPublish("drive/power", 0.5, System.nanoTime());
            }
            recorder.onPublish("g1/a", true, System.nanoTime());
        }

        List<EngramProto.TopicDeclaration> declarations = declarations(readAll(file));

        assertEquals(2, declarations.size());
        EngramProto.TopicDeclaration power = byName(declarations, "drive/power");
        assertNotNull(power);
        assertEquals("java.lang.Double", power.getJavaType());
        assertEquals(EngramProto.ValueType.VALUE_TYPE_DOUBLE, power.getValueType());

        EngramProto.TopicDeclaration button = byName(declarations, "g1/a");
        assertNotNull(button);
        assertEquals(EngramProto.ValueType.VALUE_TYPE_BOOL, button.getValueType());
    }

    @Test
    void declarationImmediatelyPrecedesThePublishThatTriggeredIt(@TempDir File dir) throws IOException {
        File file = new File(dir, "g.engram");
        try (Recorder recorder = Recorder.open(file, "Op")) {
            recorder.onPublish("first", 1.0, System.nanoTime());
        }

        List<Object> messages = readAll(file);
        // header, INIT, declaration, publish, STOP
        assertTrue(messages.get(2) instanceof EngramProto.RecordingEvent);
        EngramProto.RecordingEvent declaration = (EngramProto.RecordingEvent) messages.get(2);
        assertEquals(EngramProto.RecordingEvent.EventCase.TOPIC_DECLARATION, declaration.getEventCase());
        assertEquals("first", declaration.getTopicDeclaration().getName());

        EngramProto.RecordingEvent publish = (EngramProto.RecordingEvent) messages.get(3);
        assertEquals(EngramProto.RecordingEvent.EventCase.PUBLISH, publish.getEventCase());
        assertEquals(declaration.getTopicDeclaration().getTopicId(), publish.getPublish().getTopicId());
    }

    @Test
    void idsAreDenseAndReusedAcrossPublishes(@TempDir File dir) throws IOException {
        File file = new File(dir, "h.engram");
        try (Recorder recorder = Recorder.open(file, "Op")) {
            for (int i = 0; i < 5; i++) {
                recorder.onPublish("a", 1.0, System.nanoTime());
                recorder.onPublish("b", 2.0, System.nanoTime());
            }
        }

        List<Object> messages = readAll(file);
        List<Integer> publishIds = new ArrayList<>();
        for (Object m : messages) {
            if (m instanceof EngramProto.RecordingEvent) {
                EngramProto.RecordingEvent e = (EngramProto.RecordingEvent) m;
                if (e.getEventCase() == EngramProto.RecordingEvent.EventCase.PUBLISH) {
                    publishIds.add(e.getPublish().getTopicId());
                }
            }
        }

        assertEquals(10, publishIds.size());
        assertEquals(0, (int) publishIds.get(0));
        assertEquals(1, (int) publishIds.get(1));
        for (int i = 0; i < publishIds.size(); i++) {
            assertEquals(i % 2, (int) publishIds.get(i), "publish " + i);
        }
    }

    // ---- values ----------------------------------------------------------

    @Test
    void roundTripsEverySupportedValueType(@TempDir File dir) throws IOException {
        File file = new File(dir, "i.engram");
        try (Recorder recorder = Recorder.open(file, "Op")) {
            recorder.onPublish("d", 3.25, System.nanoTime());
            recorder.onPublish("f", 1.5f, System.nanoTime());
            recorder.onPublish("i", -42, System.nanoTime());
            recorder.onPublish("l", 9_000_000_000L, System.nanoTime());
            recorder.onPublish("b", true, System.nanoTime());
            recorder.onPublish("s", "hello", System.nanoTime());
            recorder.onPublish("e", Season.QUALIFIER, System.nanoTime());
        }

        java.util.Map<String, Object> values = publishedValues(readAll(file));

        assertEquals(3.25, (Double) values.get("d"), 0.0);
        assertEquals(1.5f, (Float) values.get("f"), 0.0f);
        assertEquals(-42, (Integer) values.get("i"));
        assertEquals(9_000_000_000L, values.get("l"));
        assertEquals(Boolean.TRUE, values.get("b"));
        assertEquals("hello", values.get("s"));
        assertEquals("QUALIFIER", values.get("e"), "enums are stored by name");
    }

    @Test
    void recordsUnencodableValuesAsEmptyAndCountsThem(@TempDir File dir) throws IOException {
        File file = new File(dir, "j.engram");
        Recorder recorder = Recorder.open(file, "Op", RecorderConfig.builder()
                .withLog(m -> { })
                .build());
        recorder.onPublish("w", new Widget(1), System.nanoTime());
        recorder.onPublish("w", new Widget(2), System.nanoTime());
        RecorderStats stats = recorder.stats();
        recorder.close();

        assertEquals(2, stats.unencodableValues());
        assertEquals(2, stats.publishesRecorded(), "the publish itself is still recorded");
        assertEquals(0, stats.publishesDropped());
        assertTrue(stats.isHealthy());

        // The topic is still declared and both publishes are still present, so
        // the replay tool shows that the topic existed and was active.
        List<EngramProto.TopicDeclaration> declared = declarations(readAll(file));
        assertEquals(1, declared.size());
        assertEquals("w", declared.get(0).getName());
        assertEquals(EngramProto.ValueType.VALUE_TYPE_BYTES, declared.get(0).getValueType());
        assertEquals(2, publishTimes(readAll(file)).size());
        assertTrue(firstPublishValue(readAll(file)).getBytesVal().isEmpty(),
                "an unencodable value is recorded as empty bytes, not dropped");
    }

    @Test
    void usesARegisteredCodecForCustomTypes(@TempDir File dir) throws IOException {
        File file = new File(dir, "k.engram");
        try (Recorder recorder = Recorder.open(file, "Op", RecorderConfig.builder()
                .withCodec(new WidgetCodec())
                .build())) {
            recorder.onPublish("w", new Widget(77), System.nanoTime());
        }

        EngramProto.TopicValue value = firstPublishValue(readAll(file));
        assertEquals(EngramProto.ValueType.VALUE_TYPE_BYTES, declaredType(readAll(file)));
        assertTrue(value.getBytesVal().size() > 0);
        assertEquals(4, value.getBytesVal().size());
    }

    // ---- timestamps ------------------------------------------------------

    @Test
    void timestampsAreRelativeToTheRecorderStart(@TempDir File dir) throws IOException {
        File file = new File(dir, "l.engram");
        long base;
        try (Recorder recorder = Recorder.open(file, "Op")) {
            base = System.nanoTime();
            recorder.onPublish("a", 1.0, base);
            recorder.onPublish("a", 2.0, base + 50 * MICROS * 1000);
        }

        List<Long> times = publishTimes(readAll(file));

        assertEquals(2, times.size());
        assertTrue(times.get(0) >= 0 && times.get(0) < 10_000,
                "the first publish should land within a few ms of origin, was " + times.get(0));
        assertTrue(times.get(1) >= 50, "the second publish is 50ms later, was " + times.get(1));
        assertTrue(times.get(1) - times.get(0) >= 45,
                "elapsed time should be preserved, delta was " + (times.get(1) - times.get(0)));
    }

    @Test
    void clampsTimestampsThatPrecedeTheStart(@TempDir File dir) throws IOException {
        File file = new File(dir, "m.engram");
        try (Recorder recorder = Recorder.open(file, "Op")) {
            // A timestamp from before the recorder existed must not produce a
            // negative offset.
            recorder.onPublish("a", 1.0, 1L);
        }

        for (long t : publishTimes(readAll(file))) {
            assertTrue(t >= 0, "timestamps must never be negative, was " + t);
        }
    }

    @Test
    void samplesTheClockWhenGivenNoTimestamp(@TempDir File dir) throws IOException {
        File file = new File(dir, "n.engram");
        Recorder recorder = Recorder.open(file, "Op");
        recorder.onPublish("a", 1.0, 0);
        recorder.onPublish("a", 2.0, -1);

        assertEquals(2, recorder.stats().publishesRecorded());
        recorder.close();

        assertEquals(2, publishTimes(readAll(file)).size());
        assertEquals(2, recorder.stats().publishesRecorded());
    }

    // ---- lifecycle of the object ----------------------------------------

    @Test
    void closeIsIdempotent(@TempDir File dir) throws IOException {
        File file = new File(dir, "o.engram");
        Recorder recorder = Recorder.open(file, "Op");
        recorder.onPublish("a", 1.0, System.nanoTime());

        recorder.close();
        recorder.close();
        recorder.close();

        assertEquals(1, lifecycleEvents(readAll(file)).stream()
                .filter(l -> l.type == EngramProto.LifecycleEvent.Type.LIFECYCLE_STOP)
                .count(), "STOP must be written exactly once");
    }

    @Test
    void publishesAfterCloseAreCountedAsDropped(@TempDir File dir) throws IOException {
        File file = new File(dir, "p.engram");
        Recorder recorder = Recorder.open(file, "Op");
        recorder.close();

        recorder.onPublish("a", 1.0, System.nanoTime());

        assertEquals(1, recorder.stats().publishesDropped());
        assertEquals(0, publishTimes(readAll(file)).size());
    }

    @Test
    void topicCountReflectsDeclaredTopics(@TempDir File dir) throws IOException {
        File file = new File(dir, "q.engram");
        try (Recorder recorder = Recorder.open(file, "Op")) {
            assertEquals(0, recorder.topicCount());
            recorder.onPublish("a", 1.0, System.nanoTime());
            recorder.onPublish("b", 1.0, System.nanoTime());
            recorder.onPublish("a", 2.0, System.nanoTime());
            assertEquals(2, recorder.topicCount());
        }
    }

    @Test
    void exposesTheFileAndOpModeName(@TempDir File dir) throws IOException {
        File file = new File(dir, "r.engram");
        try (Recorder recorder = Recorder.open(file, "NamedOp")) {
            assertEquals(file, recorder.file());
            assertEquals("NamedOp", recorder.opModeName());
        }
    }

    @Test
    void createsMissingParentDirectories(@TempDir File dir) throws IOException {
        File nested = new File(dir, "a/b/c/deep.engram");
        try (Recorder recorder = Recorder.open(nested, "Op")) {
            recorder.onPublish("a", 1.0, System.nanoTime());
        }
        assertTrue(nested.isFile());
    }

    @Test
    void rejectsNullArguments(@TempDir File dir) {
        assertThrows(IllegalArgumentException.class, () -> Recorder.open(null, "Op"));
        assertThrows(IllegalArgumentException.class, () -> Recorder.open(new File(dir, "x"), null));
    }

    @Test
    void propagatesAnUnwritablePath(@TempDir File dir) throws IOException {
        // A directory cannot be opened as a file.
        File dirAsFile = new File(dir, "adir.engram");
        assertTrue(dirAsFile.mkdirs(), "fixture setup");
        assertThrows(IOException.class, () -> Recorder.open(dirAsFile, "Op"));
    }

    // ---- concurrency -----------------------------------------------------

    @Test
    void recordsEveryPublishFromManyThreads(@TempDir File dir) throws Exception {
        File file = new File(dir, "s.engram");
        int threads = 6;
        int perThread = 500;

        AtomicInteger failures = new AtomicInteger();
        try (Recorder recorder = Recorder.open(file, "Op")) {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                final int id = t;
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            recorder.onPublish("shared/topic", 1.0, System.nanoTime());
                            recorder.onPublish("own/" + id, 1.0, System.nanoTime());
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
            assertTrue(done.await(60, TimeUnit.SECONDS));
            assertEquals(0, failures.get());
        }

        List<Object> messages = readAll(file);
        // "shared/topic" plus one "own/<id>" topic per thread.
        assertEquals(threads + 1, declarations(messages).size(),
                "each distinct topic declared once: " + declarations(messages));
        assertEquals(threads * perThread * 2, publishTimes(messages).size(),
                "no publish may be lost under contention");
    }

    // ---- parsing helpers -------------------------------------------------

    private static List<Object> readAll(File file) throws IOException {
        List<Object> messages = new ArrayList<>();
        try (InputStream in = new java.io.FileInputStream(file)) {
            // One header, then a run of events. Reading the header repeatedly
            // would feed events to the wrong parser.
            Object message = EngramProto.RecordingHeader.parseDelimitedFrom(in);
            while (message != null) {
                messages.add(message);
                message = EngramProto.RecordingEvent.parseDelimitedFrom(in);
            }
        }
        return messages;
    }

    private static final class Lifecycle {
        final EngramProto.LifecycleEvent.Type type;
        final long timeUs;

        Lifecycle(EngramProto.LifecycleEvent.Type type, long timeUs) {
            this.type = type;
            this.timeUs = timeUs;
        }
    }

    private static List<Lifecycle> lifecycleEvents(List<Object> messages) {
        List<Lifecycle> out = new ArrayList<>();
        for (Object m : messages) {
            if (m instanceof EngramProto.RecordingEvent) {
                EngramProto.RecordingEvent e = (EngramProto.RecordingEvent) m;
                if (e.getEventCase() == EngramProto.RecordingEvent.EventCase.LIFECYCLE) {
                    out.add(new Lifecycle(e.getLifecycle().getType(), e.getRelTimeUs()));
                }
            }
        }
        return out;
    }

    private static List<EngramProto.TopicDeclaration> declarations(List<Object> messages) {
        List<EngramProto.TopicDeclaration> out = new ArrayList<>();
        for (Object m : messages) {
            if (m instanceof EngramProto.RecordingEvent) {
                EngramProto.RecordingEvent e = (EngramProto.RecordingEvent) m;
                if (e.getEventCase() == EngramProto.RecordingEvent.EventCase.TOPIC_DECLARATION) {
                    out.add(e.getTopicDeclaration());
                }
            }
        }
        return out;
    }

    private static EngramProto.TopicDeclaration byName(List<EngramProto.TopicDeclaration> declarations, String name) {
        for (EngramProto.TopicDeclaration d : declarations) {
            if (d.getName().equals(name)) {
                return d;
            }
        }
        return null;
    }

    private static List<Long> publishTimes(List<Object> messages) {
        List<Long> out = new ArrayList<>();
        for (Object m : messages) {
            if (m instanceof EngramProto.RecordingEvent) {
                EngramProto.RecordingEvent e = (EngramProto.RecordingEvent) m;
                if (e.getEventCase() == EngramProto.RecordingEvent.EventCase.PUBLISH) {
                    out.add(e.getRelTimeUs());
                }
            }
        }
        return out;
    }
    private static java.util.Map<String, Object> publishedValues(List<Object> messages) {
        // Declarations travel inside a RecordingEvent, so id -> name has to be
        // built while walking the same stream the publishes appear in.
        java.util.Map<String, String> byIdValue = new java.util.HashMap<>();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Object m : messages) {
            if (!(m instanceof EngramProto.RecordingEvent)) {
                continue;
            }
            EngramProto.RecordingEvent e = (EngramProto.RecordingEvent) m;
            if (e.getEventCase() == EngramProto.RecordingEvent.EventCase.TOPIC_DECLARATION) {
                byIdValue.put(String.valueOf(e.getTopicDeclaration().getTopicId()),
                        e.getTopicDeclaration().getName());
            } else if (e.getEventCase() == EngramProto.RecordingEvent.EventCase.PUBLISH) {
                String name = byIdValue.get(String.valueOf(e.getPublish().getTopicId()));
                out.put(name, decode(e.getPublish().getValue()));
            }
        }
        return out;
    }

    private static EngramProto.TopicValue firstPublishValue(List<Object> messages) {
        for (Object m : messages) {
            if (m instanceof EngramProto.RecordingEvent) {
                EngramProto.RecordingEvent e = (EngramProto.RecordingEvent) m;
                if (e.getEventCase() == EngramProto.RecordingEvent.EventCase.PUBLISH) {
                    return e.getPublish().getValue();
                }
            }
        }
        return null;
    }

    private static EngramProto.ValueType declaredType(List<Object> messages) {
        for (Object m : messages) {
            if (m instanceof EngramProto.RecordingEvent) {
                EngramProto.RecordingEvent e = (EngramProto.RecordingEvent) m;
                if (e.getEventCase() == EngramProto.RecordingEvent.EventCase.TOPIC_DECLARATION) {
                    return e.getTopicDeclaration().getValueType();
                }
            }
        }
        return null;
    }

    private static Object decode(EngramProto.TopicValue v) {
        switch (v.getValCase()) {
            case DOUBLE_VAL: return v.getDoubleVal();
            case FLOAT_VAL: return v.getFloatVal();
            case INT32_VAL: return v.getInt32Val();
            case INT64_VAL: return v.getInt64Val();
            case BOOL_VAL: return v.getBoolVal();
            case STRING_VAL: return v.getStringVal();
            case BYTES_VAL: return v.getBytesVal().toByteArray();
            default: return null;
        }
    }

    // ---- fixtures --------------------------------------------------------

    enum Season { QUALIFIER, MEET }

    static final class Widget {
        final int id;

        Widget(int id) {
            this.id = id;
        }
    }

    static final class WidgetCodec implements ValueCodec {
        @Override
        public boolean supports(Class<?> type) {
            return Widget.class.isAssignableFrom(type);
        }

        @Override
        public void encode(Object value, ByteArrayOutputStream out) throws IOException {
            out.write(new byte[]{(byte) ((Widget) value).id, 0, 0, 0});
        }
    }
}
