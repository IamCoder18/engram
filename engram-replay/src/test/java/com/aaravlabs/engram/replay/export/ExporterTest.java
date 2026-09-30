package com.aaravlabs.engram.replay.export;

import com.aaravlabs.engram.proto.EngramProto;
import com.aaravlabs.engram.replay.EngramRecording;
import com.aaravlabs.engram.replay.EngramRecordingReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExporterTest {

    private static final long ALL = Long.MAX_VALUE;

    private static EngramRecording recording() throws IOException {
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(EngramProto.RecordingEvent.newBuilder()
                .setRelTimeUs(0)
                .setLifecycle(EngramProto.LifecycleEvent.newBuilder()
                        .setType(EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT))
                .build());
        events.add(EngramProto.RecordingEvent.newBuilder().setRelTimeUs(10)
                .setTopicDeclaration(EngramProto.TopicDeclaration.newBuilder()
                        .setTopicId(0).setName("drive/power").setJavaType("java.lang.Double")
                        .setValueType(EngramProto.ValueType.VALUE_TYPE_DOUBLE))
                .build());
        events.add(sample(100, 0, EngramProto.TopicValue.newBuilder().setDoubleVal(0.75).build()));
        events.add(sample(200, 0, EngramProto.TopicValue.newBuilder().setInt32Val(-42).build()));
        events.add(EngramProto.RecordingEvent.newBuilder().setRelTimeUs(150)
                .setTopicDeclaration(EngramProto.TopicDeclaration.newBuilder()
                        .setTopicId(1).setName("state/mode").setJavaType("DriveMode")
                        .setValueType(EngramProto.ValueType.VALUE_TYPE_STRING))
                .build());
        events.add(sample(150, 1, EngramProto.TopicValue.newBuilder().setStringVal("AUTO").build()));
        events.add(sample(300, 1, EngramProto.TopicValue.newBuilder()
                .setStringVal("quote\"and,comma\nand newline").build()));
        events.add(EngramProto.RecordingEvent.newBuilder().setRelTimeUs(400)
                .setLifecycle(EngramProto.LifecycleEvent.newBuilder()
                        .setType(EngramProto.LifecycleEvent.Type.LIFECYCLE_STOP))
                .build());

        byte[] bytes = file(events);
        return EngramRecordingReader.read(new ByteArrayInputStream(bytes), "test");
    }

    private static EngramProto.RecordingEvent sample(long t, int id, EngramProto.TopicValue v) {
        return EngramProto.RecordingEvent.newBuilder().setRelTimeUs(t)
                .setPublish(EngramProto.TopicPublish.newBuilder().setTopicId(id).setValue(v)).build();
    }

    private static byte[] file(List<EngramProto.RecordingEvent> events) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        EngramProto.RecordingHeader.newBuilder()
                .setFormatVersion(1).setOpmodeName("Exporter").setStartEpochMs(1_700_000_000_000L)
                .build().writeDelimitedTo(out);
        for (EngramProto.RecordingEvent e : events) {
            e.writeDelimitedTo(out);
        }
        return out.toByteArray();
    }

    private interface Body {
        void write(Writer out) throws IOException;
    }

    private static String render(Body body) throws IOException {
        StringWriter w = new StringWriter();
        body.write(w);
        return w.toString();
    }

    // ---- JSON ------------------------------------------------------------

    @Test
    void jsonContainsMetadataTopicsAndSamples() throws IOException {
        String json = render(w -> JsonExporter.export(recording(), w));

        assertTrue(json.contains("\"formatVersion\": 1"), json);
        assertTrue(json.contains("\"opMode\": \"Exporter\""), json);
        assertTrue(json.contains("\"startEpochMs\": 1700000000000"), json);
        assertTrue(json.contains("\"initTimeUs\": 0"), json);
        assertTrue(json.contains("\"stopTimeUs\": 400"), json);
        assertTrue(json.contains("\"truncated\": false"), json);

        assertTrue(json.contains("\"name\": \"drive/power\""), json);
        assertTrue(json.contains("\"valueType\": \"VALUE_TYPE_DOUBLE\""), json);
        assertTrue(json.contains("\"javaType\": \"DriveMode\""), json);

        assertTrue(json.contains("\"value\": 0.75"), json);
        assertTrue(json.contains("\"value\": -42"), json); // an int stays an int in JSON
        assertTrue(json.contains("\"value\": \"AUTO\""), json);
    }

    @Test
    void jsonIsWellFormedForAwkwardStrings() throws IOException {
        String json = render(w -> JsonExporter.export(recording(), w));

        // A raw quote, comma, and newline inside a value must be escaped, not
        // emitted literally -- an unescaped newline would break the document.
        assertTrue(json.contains("quote\\\"and,comma\\nand newline"), json);
        assertFalse(json.contains("comma\nand"), "a literal newline must not survive");
    }

    @Test
    void jsonCanBeNarrowedToOneTopicAndRange() throws IOException {
        EngramRecording r = recording();
        String json = render(w -> JsonExporter.export(r, w, "drive/power", 0, 150));

        assertTrue(json.contains("\"name\": \"drive/power\""), json);
        assertFalse(json.contains("\"name\": \"state/mode\""), json);
        assertTrue(json.contains("\"value\": 0.75"), json);
        assertFalse(json.contains("-42"), "the t=200 sample is outside the range");
    }

    @Test
    void jsonRejectsAnUnknownTopic() throws IOException {
        EngramRecording r = recording();
        assertThrows(IllegalArgumentException.class,
                () -> JsonExporter.export(r, new StringWriter(), "nope", 0, ALL));
    }

    @Test
    void jsonOfAnEmptyRecordingIsStillValid() throws IOException {
        EngramRecording r = EngramRecordingReader.read(
                new ByteArrayInputStream(file(new ArrayList<>())), "test");
        String json = render(w -> JsonExporter.export(r, w));

        assertTrue(json.contains("\"topics\": []"), json);
        assertTrue(json.contains("\"samples\": []"), json);
        assertTrue(json.trim().endsWith("}"), json);
    }

    // ---- NDJSON ----------------------------------------------------------

    @Test
    void ndjsonEmitsOneObjectPerLine() throws IOException {
        String ndjson = render(w -> NdjsonExporter.export(recording(), w));
        String[] lines = ndjson.trim().split("\n");

        for (String line : lines) {
            assertTrue(line.startsWith("{") && line.endsWith("}"), "not a complete object: " + line);
        }
        assertTrue(ndjson.contains("\"kind\":\"header\""), ndjson);
        assertTrue(ndjson.contains("\"kind\":\"topic\""), ndjson);
        assertTrue(ndjson.contains("\"kind\":\"sample\""), ndjson);
        assertTrue(ndjson.contains("\"kind\":\"lifecycle\""), ndjson);
        // header + 2 topics + 2 lifecycle + 4 samples
        assertEquals(9, lines.length, ndjson);
    }

    @Test
    void ndjsonEscapesNewlinesInsideValues() throws IOException {
        String ndjson = render(w -> NdjsonExporter.export(recording(), w));
        // The value containing a newline must stay on one line.
        for (String line : ndjson.trim().split("\n")) {
            assertTrue(line.indexOf('\r') < 0, "carriage return in: " + line);
        }
        assertTrue(ndjson.contains("quote\\\"and,comma\\nand newline"), ndjson);
    }

    @Test
    void ndjsonCanBeNarrowed() throws IOException {
        EngramRecording r = recording();
        String ndjson = render(w -> NdjsonExporter.export(r, w, "state/mode", 0, 200));

        assertTrue(ndjson.contains("\"name\":\"state/mode\""), ndjson);
        assertFalse(ndjson.contains("drive/power"), ndjson);
    }

    // ---- CSV -------------------------------------------------------------

    @Test
    void csvWithoutAFilterMergesTopicsInTimeOrder() throws IOException {
        String csv = render(w -> CsvExporter.export(recording(), w));
        String[] lines = csv.trim().split("\n");

        assertEquals("time_us,topic,value", lines[0]);

        // A quoted field may contain a newline, so physical lines are not
        // records. Count rows by their leading timestamp instead.
        List<String> rows = new ArrayList<>();
        long previous = -1;
        for (String line : lines) {
            if (line.isEmpty() || !Character.isDigit(line.charAt(0))) {
                continue;
            }
            rows.add(line);
            long t = Long.parseLong(line.substring(0, line.indexOf(',')));
            assertTrue(t >= previous, "rows must be time ordered: " + csv);
            previous = t;
        }
        assertEquals(4, rows.size(), "4 samples: " + csv);
    }

    @Test
    void csvWithAFilterDropsTheTopicColumn() throws IOException {
        String csv = render(w -> CsvExporter.export(recording(), w, "drive/power", 0, ALL));
        String[] lines = csv.trim().split("\n");

        assertEquals("time_us,value", lines[0]);
        assertEquals("100,0.75", lines[1]);
        assertEquals("200,-42", lines[2], "an int stays an int in CSV");
    }

    @Test
    void csvQuotesValuesContainingSeparatorsOrQuotes() throws IOException {
        String csv = render(w -> CsvExporter.export(recording(), w, "state/mode", 0, ALL));

        assertTrue(csv.contains("\"quote\"\"and,comma"), "quotes double up: " + csv);
        // The embedded newline inside a quoted field is legal CSV but must not
        // break the header line.
        assertTrue(csv.startsWith("time_us,value\n"), csv);
    }

    @Test
    void csvRejectsAnUnknownTopic() throws IOException {
        EngramRecording r = recording();
        assertThrows(IllegalArgumentException.class,
                () -> CsvExporter.export(r, new StringWriter(), "nope", 0, ALL));
    }

    @Test
    void csvRendersUnrecordedValuesAsEmpty() throws IOException {
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(EngramProto.RecordingEvent.newBuilder().setRelTimeUs(0)
                .setLifecycle(EngramProto.LifecycleEvent.newBuilder()
                        .setType(EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT)).build());
        events.add(EngramProto.RecordingEvent.newBuilder().setRelTimeUs(1)
                .setTopicDeclaration(EngramProto.TopicDeclaration.newBuilder()
                        .setTopicId(0).setName("x").setJavaType("Widget")
                        .setValueType(EngramProto.ValueType.VALUE_TYPE_BYTES)).build());
        events.add(sample(10, 0, EngramProto.TopicValue.getDefaultInstance()));

        EngramRecording r = EngramRecordingReader.read(
                new ByteArrayInputStream(file(events)), "test");
        String csv = render(w -> CsvExporter.export(r, w, "x", 0, ALL));

        assertEquals("time_us,value\n10,\n", csv, "an unrecorded value renders as empty, not a literal");
    }

    @Test
    void csvRendersByteValuesAsBase64() throws IOException {
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(EngramProto.RecordingEvent.newBuilder().setRelTimeUs(0)
                .setLifecycle(EngramProto.LifecycleEvent.newBuilder()
                        .setType(EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT)).build());
        events.add(EngramProto.RecordingEvent.newBuilder().setRelTimeUs(1)
                .setTopicDeclaration(EngramProto.TopicDeclaration.newBuilder()
                        .setTopicId(0).setName("raw").setJavaType("byte[]")
                        .setValueType(EngramProto.ValueType.VALUE_TYPE_BYTES)).build());
        events.add(sample(10, 0, EngramProto.TopicValue.newBuilder()
                .setBytesVal(com.google.protobuf.ByteString.copyFrom(new byte[]{1, 2, 3})).build()));

        EngramRecording r = EngramRecordingReader.read(
                new ByteArrayInputStream(file(events)), "test");
        String csv = render(w -> CsvExporter.export(r, w, "raw", 0, ALL));

        assertTrue(csv.contains("base64:AQID"), csv);
    }

    @Test
    void jsonCarriesTheCaptureVerdictSoConsumersNeedNotReimplementIt() throws IOException {
        // A killed robot is the case where a consumer most needs to know it is
        // looking at an incomplete capture: the topics array looks perfectly
        // ordinary, and without the verdict a dashboard shows a clean run.
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(EngramProto.RecordingEvent.newBuilder()
                .setRelTimeUs(0)
                .setLifecycle(EngramProto.LifecycleEvent.newBuilder()
                        .setType(EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT))
                .build());
        events.add(EngramProto.RecordingEvent.newBuilder().setRelTimeUs(1)
                .setTopicDeclaration(EngramProto.TopicDeclaration.newBuilder()
                        .setTopicId(0).setName("drive/power").setJavaType("java.lang.Double")
                        .setValueType(EngramProto.ValueType.VALUE_TYPE_DOUBLE))
                .build());
        events.add(sample(100, 0, EngramProto.TopicValue.newBuilder().setDoubleVal(1.0).build()));

        EngramRecording r = EngramRecordingReader.read(new ByteArrayInputStream(
                withTruncatedTail(file(events), 24)), "test");

        String json = render(w -> JsonExporter.export(r, w));
        assertTrue(json.contains("\"complete\": false"), json);
        assertTrue(json.contains("\"finalized\": false"), json);
        assertTrue(json.contains("\"code\": \"unfinalized\""), json);
        assertTrue(json.contains("\"code\": \"truncated\""), json);
        assertTrue(json.contains("\"declaredTopics\": 1"), json);
        assertTrue(json.contains("\"observedTopics\": 1"), json);
    }

    @Test
    void aHealthyExportSaysItIsCompleteWithNoProblems() throws IOException {
        String json = render(w -> JsonExporter.export(recording(), w));

        assertTrue(json.contains("\"complete\": true"), json);
        assertTrue(json.contains("\"finalized\": true"), json);
        assertTrue(json.contains("\"problems\": []"), json);
    }

    @Test
    void jsonReportsATruncatedRecordingWithItsReason() throws IOException {
        // A crashed robot is exactly when this matters, so the reason has to
        // survive into the export rather than being dropped silently.
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(EngramProto.RecordingEvent.newBuilder().setRelTimeUs(0)
                .setLifecycle(EngramProto.LifecycleEvent.newBuilder()
                        .setType(EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT)).build());
        events.add(sample(100, 0, EngramProto.TopicValue.newBuilder().setDoubleVal(1.0).build()));

        byte[] truncated = withTruncatedTail(file(events), 24);

        EngramRecording r = EngramRecordingReader.read(
                new ByteArrayInputStream(truncated), "test");
        assertTrue(r.isTruncated());

        String json = render(w -> JsonExporter.export(r, w));
        assertTrue(json.contains("\"truncated\": true"), json);
        assertTrue(json.contains("\"truncationReason\":"), json);
    }

    @Test
    void ndjsonReportsTruncationToo() throws IOException {
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(EngramProto.RecordingEvent.newBuilder().setRelTimeUs(0)
                .setLifecycle(EngramProto.LifecycleEvent.newBuilder()
                        .setType(EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT)).build());
        events.add(sample(100, 0, EngramProto.TopicValue.newBuilder().setDoubleVal(1.0).build()));

        EngramRecording r = EngramRecordingReader.read(
                new ByteArrayInputStream(withTruncatedTail(file(events), 24)),
                "test");

        String ndjson = render(w -> NdjsonExporter.export(r, w));
        assertTrue(ndjson.contains("\"truncated\":true"), ndjson);
    }

    @Test
    void exportersTolerateTheMissingStopEventOfAnUncleanRun() throws IOException {
        // A run that ended without LIFECYCLE_STOP: startTimeUs is -1 and must
        // not be rendered as if it were a real timestamp.
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(EngramProto.RecordingEvent.newBuilder().setRelTimeUs(0)
                .setLifecycle(EngramProto.LifecycleEvent.newBuilder()
                        .setType(EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT)).build());
        events.add(sample(100, 0, EngramProto.TopicValue.newBuilder().setDoubleVal(1.0).build()));

        EngramRecording r = EngramRecordingReader.read(
                new ByteArrayInputStream(file(events)), "test");
        assertEquals(-1, r.stopTimeUs());

        String json = render(w -> JsonExporter.export(r, w));
        assertTrue(json.contains("\"stopTimeUs\": -1"), json);
        assertTrue(json.contains("\"durationUs\": 100"), json);
        assertTrue(json.contains("\"value\": 1.0"), json);

        String ndjson = render(w -> NdjsonExporter.export(r, w));
        assertFalse(ndjson.contains("LIFECYCLE_STOP"), ndjson);
        assertTrue(ndjson.contains("LIFECYCLE_INIT"), ndjson);
    }

/**
     * Appends a deliberately partial message: a length prefix promising more
     * bytes than actually follow. Deterministic where slicing at an arbitrary
     * offset is not, since a cut can land on a message boundary -- which is a
     * clean end of stream, not truncation.
     */
    private static byte[] withTruncatedTail(byte[] complete, int promised) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(complete, 0, complete.length);
        out.write(promised);
        for (int i = 0; i < promised - 1; i++) {
            out.write(0x00);
        }
        return out.toByteArray();
    }

    // ---- JSON escaping helper -------------------------------------------

    @Test
    void jsonQuoteEscapesControlCharacters() {
        assertEquals("\"a\\nb\"", Json.quote("a\nb"));
        assertEquals("\"a\\tb\"", Json.quote("a\tb"));
        assertEquals("\"a\\\\b\"", Json.quote("a\\b"));
        assertEquals("\"\\u0000\"", Json.quote("\u0000"));
        assertEquals("null", Json.quote(null));
    }

    @Test
    void jsonNumberRejectsNonFiniteValues() {
        assertEquals("null", Json.number(Double.NaN));
        assertEquals("null", Json.number(Double.POSITIVE_INFINITY));
        assertEquals("1.0", Json.number(1.0));
        assertEquals("1.5", Json.number(1.5));
        assertEquals("42", Json.number(42L));
    }
}
