package com.aaravlabs.engram.replay;

import com.aaravlabs.engram.proto.EngramProto;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngramRecordingQueryTest {

    private static EngramRecording ramp() throws IOException {
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(TestRecordings.lifecycle(0, EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT));
        events.add(TestRecordings.declaration(TestRecordings.declare(0, "ramp", "java.lang.Double",
                EngramProto.ValueType.VALUE_TYPE_DOUBLE), 1));
        for (int i = 0; i < 10; i++) {
            events.add(TestRecordings.sample(i * 100L, 0, TestRecordings.d(i)));
        }
        events.add(TestRecordings.lifecycle(1000, EngramProto.LifecycleEvent.Type.LIFECYCLE_STOP));
        return read(events);
    }

    private static EngramRecording read(List<EngramProto.RecordingEvent> events) throws IOException {
        byte[] bytes = TestRecordings.file(
                TestRecordings.header("Q", 1, 1L), events);
        return EngramRecordingReader.read(new ByteArrayInputStream(bytes), "test");
    }

    // ---- samples ---------------------------------------------------------

    @Test
    void returnsAllSamplesWhenUnbounded() throws IOException {
        assertEquals(10, ramp().samples("ramp").size());
        assertEquals(10, ramp().samples(0).size());
    }

    @Test
    void rangeBoundsAreInclusive() throws IOException {
        EngramRecording r = ramp();

        assertEquals(3, r.samples("ramp", 200, 400).size(), "200, 300, 400");
        assertEquals(1, r.samples("ramp", 300, 300).size());
        assertEquals(0, r.samples("ramp", 950, 1000).size());
    }

    @Test
    void unknownTopicIsAnError() throws IOException {
        EngramRecording r = ramp();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> r.samples("nope", 0, Long.MAX_VALUE));
        assertTrue(e.getMessage().contains("nope"), e.getMessage());
    }

    @Test
    void samplesForADeclaredButNeverPublishedTopicAreEmpty() throws IOException {
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(TestRecordings.lifecycle(0, EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT));
        events.add(TestRecordings.declaration(TestRecordings.declare(0, "quiet", "java.lang.Double",
                EngramProto.ValueType.VALUE_TYPE_DOUBLE), 1));
        events.add(TestRecordings.lifecycle(100, EngramProto.LifecycleEvent.Type.LIFECYCLE_STOP));

        EngramRecording r = read(events);
        TopicInfo info = r.topic("quiet").orElseThrow();

        assertEquals(0, info.publishCount());
        assertEquals(-1, info.firstTimeUs());
        assertEquals(-1, info.lastTimeUs());
        assertEquals(0, info.activeSpanUs());
        assertTrue(r.samples("quiet").isEmpty());
    }

    // ---- point in time ---------------------------------------------------

    @Test
    void valueAtReturnsTheNewestPublishAtOrBeforeTheTime() throws IOException {
        EngramRecording r = ramp();

        // Samples sit at t = 0, 100, 200 ... with values 0.0, 1.0, 2.0 ...
        assertEquals(0.0, r.valueAt("ramp", 0).orElseThrow());
        assertEquals(0.0, r.valueAt("ramp", 99).orElseThrow(), "still the t=0 sample");
        assertEquals(1.0, r.valueAt("ramp", 100).orElseThrow(), "the t=100 sample is now current");
        assertEquals(1.0, r.valueAt("ramp", 150).orElseThrow());
        assertEquals(5.0, r.valueAt("ramp", 500).orElseThrow());
        assertEquals(9.0, r.valueAt("ramp", Long.MAX_VALUE).orElseThrow());
    }

    @Test
    void valueAtBeforeTheFirstPublishIsEmpty() throws IOException {
        assertFalse(ramp().valueAt("ramp", -1).isPresent());
    }

    @Test
    void valueAtAnUnpublishedTopicIsEmpty() throws IOException {
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(TestRecordings.lifecycle(0, EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT));
        events.add(TestRecordings.declaration(TestRecordings.declare(0, "quiet", "java.lang.Double",
                EngramProto.ValueType.VALUE_TYPE_DOUBLE), 1));
        EngramRecording r = read(events);
        assertFalse(r.valueAt("quiet", 1000).isPresent());
    }

    // ---- statistics ------------------------------------------------------

    @Test
    void statisticsCoverNumericTopics() throws IOException {
        TopicStats stats = ramp().stats("ramp");

        assertEquals(10, stats.publishCount());
        assertEquals(10, stats.numericCount());
        assertEquals(0, stats.unrecordedCount());
        assertEquals(0.0, stats.min().orElseThrow());
        assertEquals(9.0, stats.max().orElseThrow());
        assertEquals(4.5, stats.mean().orElseThrow());
        assertEquals(0, stats.firstTimeUs().orElseThrow());
        assertEquals(900, stats.lastTimeUs().orElseThrow());
        assertEquals(900, stats.activeSpanUs());
        assertEquals(10 * 1_000_000.0 / 900, stats.averageRateHz(), 0.001);
    }

    @Test
    void statisticsHonourTheTimeRange() throws IOException {
        TopicStats stats = ramp().stats(0, 200, 500);

        assertEquals(4, stats.publishCount(), "200, 300, 400, 500");
        assertEquals(3.5, stats.mean().orElseThrow());
        assertEquals(200, stats.firstTimeUs().orElseThrow());
        assertEquals(500, stats.lastTimeUs().orElseThrow());
    }

    @Test
    void statisticsOnAnEmptyRangeAreEmptyNotZero() throws IOException {
        TopicStats stats = ramp().stats(0, 10_000, 20_000);

        assertEquals(0, stats.publishCount());
        assertFalse(stats.min().isPresent());
        assertFalse(stats.max().isPresent());
        assertFalse(stats.mean().isPresent());
        assertFalse(stats.firstTimeUs().isPresent());
        assertEquals(0.0, stats.averageRateHz());
    }

    @Test
    void statisticsIgnoreUnrecordedValues() throws IOException {
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(TestRecordings.lifecycle(0, EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT));
        events.add(TestRecordings.declaration(TestRecordings.declare(0, "mixed", "Widget",
                EngramProto.ValueType.VALUE_TYPE_BYTES), 1));
        events.add(TestRecordings.sample(10, 0, TestRecordings.d(4.0)));
        events.add(TestRecordings.sample(20, 0, TestRecordings.unset()));
        events.add(TestRecordings.sample(30, 0, TestRecordings.s("text")));
        events.add(TestRecordings.lifecycle(100, EngramProto.LifecycleEvent.Type.LIFECYCLE_STOP));

        TopicStats stats = read(events).stats("mixed");

        assertEquals(3, stats.publishCount());
        assertEquals(1, stats.numericCount(), "only the double counts as numeric");
        assertEquals(1, stats.unrecordedCount());
        assertEquals(4.0, stats.mean().orElseThrow());
    }

    @Test
    void singleSampleHasNoMeaningfulRate() throws IOException {
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(TestRecordings.lifecycle(0, EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT));
        events.add(TestRecordings.declaration(TestRecordings.declare(0, "once", "java.lang.Double",
                EngramProto.ValueType.VALUE_TYPE_DOUBLE), 1));
        events.add(TestRecordings.sample(10, 0, TestRecordings.d(1.0)));

        TopicStats stats = read(events).stats("once");
        assertEquals(1, stats.publishCount());
        assertEquals(0.0, stats.averageRateHz(), "a zero-length span has no rate");
    }

    @Test
    void topicInfoSummarisesActivity() throws IOException {
        TopicInfo info = ramp().topic("ramp").orElseThrow();
        assertEquals(0, info.id());
        assertEquals("ramp", info.name());
        assertEquals("java.lang.Double", info.javaType());
        assertEquals(EngramProto.ValueType.VALUE_TYPE_DOUBLE, info.declaredValueType());
        assertEquals(10, info.publishCount());
        assertEquals(0, info.firstTimeUs());
        assertEquals(900, info.lastTimeUs());
        assertEquals(900, info.activeSpanUs());
        assertTrue(info.toString().contains("ramp"));
    }

    // ---- events ----------------------------------------------------------

    @Test
    void eventsCanBeFilteredByTime() throws IOException {
        EngramRecording r = ramp();
        List<EngramProto.RecordingEvent> window = r.events(200, 400);
        assertEquals(3, window.size());
        for (EngramProto.RecordingEvent e : window) {
            assertTrue(e.getRelTimeUs() >= 200 && e.getRelTimeUs() <= 400);
        }
    }

    @Test
    void eventsAreImmutableToCallers() throws IOException {
        EngramRecording r = ramp();
        assertThrows(UnsupportedOperationException.class, () -> r.events().clear());
        assertThrows(UnsupportedOperationException.class, () -> r.topics().clear());
    }

    @Test
    void declaredTypeIsAdvisoryAndTheOneofIsAuthoritative() throws IOException {
        // A topic declared as Number can carry an Integer then a Double; the
        // per-event encoding is what a reader must trust.
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(TestRecordings.lifecycle(0, EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT));
        events.add(TestRecordings.declaration(TestRecordings.declare(0, "num", "java.lang.Number",
                EngramProto.ValueType.VALUE_TYPE_INT32), 1));
        events.add(TestRecordings.sample(10, 0, TestRecordings.i(5)));
        events.add(TestRecordings.sample(20, 0, TestRecordings.d(5.5)));

        EngramRecording r = read(events);
        List<Sample> samples = r.samples("num");

        assertEquals(EngramProto.ValueType.VALUE_TYPE_INT32, r.topic("num").orElseThrow().declaredValueType());
        assertEquals(5, samples.get(0).value());
        assertEquals(5.5, samples.get(1).value(), "the second value decodes as a double regardless");
    }

    @Test
    void toStringIsInformative() throws IOException {
        String s = ramp().toString();
        assertTrue(s.contains("opMode=Q"), s);
        assertTrue(s.contains("topics=1"), s);
    }

    @Test
    void topicStatsToStringIsInformative() throws IOException {
        String s = ramp().stats("ramp").toString();
        assertTrue(s.contains("publishes=10"), s);
        assertTrue(s.contains("min=0.0"), s);
    }

    @Test
    void valuesToStringRendersTheSentinelAndBytes() {
        assertEquals("<unrecorded>", Values.UNRECORDED.toString());
        assertEquals("base64:AQID", Values.toText(new byte[]{1, 2, 3}));
        assertEquals("1.5", Values.toText(1.5));
        assertEquals("", Values.toText(null));
        assertEquals("hello", Values.bytesAsText("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void asDoubleHandlesNonNumbers() {
        assertEquals(1.5, Values.asDouble(1.5));
        assertEquals(2.0, Values.asDouble(2L));
        assertTrue(Double.isNaN(Values.asDouble("text")));
        assertTrue(Double.isNaN(Values.asDouble(Values.UNRECORDED)));
    }

    @Test
    void valuesDecodesEveryOneofCase() {
        assertEquals(1.5, Values.decode(TestRecordings.d(1.5)));
        assertEquals(1.5f, Values.decode(TestRecordings.f(1.5f)));
        assertEquals(3, Values.decode(TestRecordings.i(3)));
        assertEquals(4L, Values.decode(TestRecordings.l(4L)));
        assertEquals(Boolean.TRUE, Values.decode(TestRecordings.b(true)));
        assertEquals("s", Values.decode(TestRecordings.s("s")));
        assertEquals(Values.UNRECORDED, Values.decode(TestRecordings.unset()));
    }

    @Test
    void sampleExposesTimeValueAndPresence() throws IOException {
        Sample s = ramp().samples("ramp").get(3);
        assertEquals(300, s.timeUs());
        assertEquals(3.0, s.value());
        assertTrue(s.hasValue());
        assertTrue(s.toString().contains("300"));
        assertEquals(Arrays.asList(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0),
                ramp().samples("ramp").stream().map(Sample::value).collect(java.util.stream.Collectors.toList()));
    }
}
