package com.aaravlabs.engram.replay.cli;

import com.aaravlabs.engram.proto.EngramProto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainCliTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String stdout() {
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private String stderr() {
        return new String(err.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---- fixtures --------------------------------------------------------

    private Path recording(Path dir) throws IOException {
        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        events.add(lifecycle(0, EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT));
        events.add(declare(1, 0, "drive/power", "java.lang.Double",
                EngramProto.ValueType.VALUE_TYPE_DOUBLE));
        events.add(sample(100, 0, EngramProto.TopicValue.newBuilder().setDoubleVal(0.75).build()));
        events.add(sample(200, 0, EngramProto.TopicValue.newBuilder().setDoubleVal(0.5).build()));
        events.add(declare(2, 1, "g1/a", "java.lang.Boolean",
                EngramProto.ValueType.VALUE_TYPE_BOOL));
        events.add(sample(150, 1, EngramProto.TopicValue.newBuilder().setBoolVal(true).build()));
        events.add(lifecycle(200_000, EngramProto.LifecycleEvent.Type.LIFECYCLE_START));
        events.add(lifecycle(5_000_000, EngramProto.LifecycleEvent.Type.LIFECYCLE_STOP));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        EngramProto.RecordingHeader.newBuilder()
                .setFormatVersion(1).setOpmodeName("CliOp").setStartEpochMs(1_700_000_000_000L)
                .build().writeDelimitedTo(bytes);
        for (EngramProto.RecordingEvent e : events) {
            e.writeDelimitedTo(bytes);
        }

        Path file = dir.resolve("cli.engram");
        Files.write(file, bytes.toByteArray());
        return file;
    }

    private static EngramProto.RecordingEvent lifecycle(long t, EngramProto.LifecycleEvent.Type type) {
        return EngramProto.RecordingEvent.newBuilder().setRelTimeUs(t)
                .setLifecycle(EngramProto.LifecycleEvent.newBuilder().setType(type)).build();
    }

    private static EngramProto.RecordingEvent declare(long t, int id, String name, String type,
                                                      EngramProto.ValueType valueType) {
        return EngramProto.RecordingEvent.newBuilder().setRelTimeUs(t)
                .setTopicDeclaration(EngramProto.TopicDeclaration.newBuilder()
                        .setTopicId(id).setName(name).setJavaType(type).setValueType(valueType))
                .build();
    }

    private static EngramProto.RecordingEvent sample(long t, int id, EngramProto.TopicValue value) {
        return EngramProto.RecordingEvent.newBuilder().setRelTimeUs(t)
                .setPublish(EngramProto.TopicPublish.newBuilder().setTopicId(id).setValue(value)).build();
    }

    // ---- usage -----------------------------------------------------------

    @Test
    void noArgumentsPrintsUsageAndFails() {
        assertEquals(2, run());
        assertTrue(stdout().contains("USAGE"), stdout());
    }

    @Test
    void helpPrintsUsageAndSucceeds() {
        assertEquals(0, run("--help"));
        assertTrue(stdout().contains("engram <command>"), stdout());
        assertTrue(stdout().contains("inspect"), stdout());
    }

    @Test
    void unknownCommandFails() {
        assertEquals(2, run("frobnicate", "x.engram"));
        assertTrue(stderr().contains("unknown command"), stderr());
    }

    @Test
    void missingFileArgumentFails() {
        assertEquals(2, run("inspect"));
        assertTrue(stderr().contains("needs a recording file"), stderr());
    }

    @Test
    void unreadableFileFails(@TempDir Path dir) {
        assertEquals(1, run("inspect", dir.resolve("absent.engram").toString()));
        assertTrue(stderr().contains("cannot read"), stderr());
    }

    @Test
    void badOptionFails(@TempDir Path dir) throws IOException {
        assertEquals(2, run("query", recording(dir).toString(), "--nonsense"));
        assertTrue(stderr().contains("unknown option"), stderr());
    }

    @Test
    void optionMissingItsValueFails(@TempDir Path dir) throws IOException {
        assertEquals(2, run("query", recording(dir).toString(), "--topic"));
        assertTrue(stderr().contains("needs a value"), stderr());
    }

    @Test
    void nonNumericRangeFails(@TempDir Path dir) throws IOException {
        assertEquals(2, run("query", recording(dir).toString(), "--topic", "g1/a", "--from", "soon"));
        assertTrue(stderr().contains("whole number"), stderr());
    }

    // ---- inspect ---------------------------------------------------------

    @Test
    void inspectSummarisesTheRecording(@TempDir Path dir) throws IOException {
        assertEquals(0, run("inspect", recording(dir).toString()));
        String s = stdout();

        assertTrue(s.contains("CliOp"), s);
        assertTrue(s.contains("Format"), s);
        assertTrue(s.contains("Duration"), s);
        assertTrue(s.contains("TOPIC"), s);
        assertTrue(s.contains("drive/power"), s);
        assertTrue(s.contains("g1/a"), s);
        assertFalse(s.contains("WARNING"), "a clean file must not warn: " + s);
    }

    @Test
    void inspectFlagsATruncatedFile(@TempDir Path dir) throws IOException {
        byte[] complete = Files.readAllBytes(recording(dir));
        Path cut = dir.resolve("cut.engram");
        Files.write(cut, withTruncatedTail(complete, 24));

        assertEquals(0, run("inspect", cut.toString()));
        assertTrue(stdout().contains("WARNING"), stdout());
        assertTrue(stdout().contains("truncated"), stdout());
    }

    // ---- topics ----------------------------------------------------------

    @Test
    void topicsListsEveryTopicWithTypes(@TempDir Path dir) throws IOException {
        assertEquals(0, run("topics", recording(dir).toString()));
        String s = stdout();

        assertTrue(s.contains("drive/power"), s);
        assertTrue(s.contains("Double"), s);
        assertTrue(s.contains("g1/a"), s);
        assertTrue(s.contains("Boolean"), s);
        // The category prefix is stripped for readability.
        assertTrue(s.contains("DOUBLE"), s);
        assertFalse(s.contains("VALUE_TYPE_DOUBLE"), s);
    }

    @Test
    void topicsCanBeFiltered(@TempDir Path dir) throws IOException {
        assertEquals(0, run("topics", recording(dir).toString(), "--topic", "g1/a"));
        assertTrue(stdout().contains("g1/a"), stdout());
        assertFalse(stdout().contains("drive/power"), stdout());
    }

    @Test
    void topicsReportsAMissingFilterWithoutFailing(@TempDir Path dir) throws IOException {
        assertEquals(0, run("topics", recording(dir).toString(), "--topic", "nope"));
        assertTrue(stdout().contains("no topic named"), stdout());
    }

    // ---- query -----------------------------------------------------------

    @Test
    void queryPrintsSamplesWithATableHeader(@TempDir Path dir) throws IOException {
        assertEquals(0, run("query", recording(dir).toString(), "--topic", "drive/power"));
        String s = stdout();

        assertTrue(s.contains("TIME (us)"), s);
        assertTrue(s.contains("0.75"), s);
        assertTrue(s.contains("0.5"), s);
    }

    @Test
    void queryHonoursTheTimeRange(@TempDir Path dir) throws IOException {
        assertEquals(0, run("query", recording(dir).toString(),
                "--topic", "drive/power", "--from", "0", "--to", "150"));
        String s = stdout();

        assertTrue(s.contains("0.75"), s);
        assertFalse(s.contains("0.5"), s);
    }

    @Test
    void queryWithoutATopicFails(@TempDir Path dir) throws IOException {
        assertEquals(2, run("query", recording(dir).toString()));
        assertTrue(stderr().contains("needs --topic"), stderr());
    }

    @Test
    void queryOnAnUnknownTopicFails(@TempDir Path dir) throws IOException {
        assertEquals(2, run("query", recording(dir).toString(), "--topic", "nope"));
        assertTrue(stderr().contains("no such topic"), stderr());
    }

    @Test
    void queryOnAnEmptyRangeExplainsItself(@TempDir Path dir) throws IOException {
        assertEquals(0, run("query", recording(dir).toString(),
                "--topic", "drive/power", "--from", "9000", "--to", "9999"));
        assertTrue(stdout().contains("no samples"), stdout());
    }

    // ---- export ----------------------------------------------------------

    @Test
    void exportWritesJsonToStdoutByDefault(@TempDir Path dir) throws IOException {
        assertEquals(0, run("export", recording(dir).toString()));
        String s = stdout();
        assertTrue(s.contains("\"opMode\": \"CliOp\""), s);
        assertTrue(s.contains("\"samples\""), s);
    }

    @Test
    void exportSupportsEveryFormat(@TempDir Path dir) throws IOException {
        Path file = recording(dir);
        for (String format : new String[]{"json", "ndjson", "csv"}) {
            out.reset();
            assertEquals(0, run("export", file.toString(), "--format", format),
                    "format " + format + " should succeed");
            assertFalse(stdout().isEmpty(), "format " + format + " produced no output");
        }
    }

    @Test
    void exportWritesToAFileWhenAsked(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("out.json");
        assertEquals(0, run("export", recording(dir).toString(), "--out", target.toString()));

        assertTrue(Files.exists(target));
        String content = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
        assertTrue(content.contains("CliOp"), content);
        assertTrue(stderr().contains("wrote json"), stderr());
    }

    @Test
    void exportRejectsAnUnknownFormat(@TempDir Path dir) throws IOException {
        assertEquals(2, run("export", recording(dir).toString(), "--format", "yaml"));
        assertTrue(stderr().contains("unknown --format"), stderr());
    }

    // ---- play ------------------------------------------------------------

    @Test
    void playStreamsATopicInRealTime(@TempDir Path dir) throws IOException {
        long start = System.nanoTime();
        // Samples are 100us and 200us apart, so this returns almost instantly.
        assertEquals(0, run("play", recording(dir).toString(), "--topic", "drive/power"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(stdout().contains("0.75"), stdout());
        assertTrue(elapsedMs < 2_000, "play should not have waited, took " + elapsedMs + "ms");
    }

    @Test
    void playWithoutATopicFails(@TempDir Path dir) throws IOException {
        assertEquals(2, run("play", recording(dir).toString()));
        assertTrue(stderr().contains("needs --topic"), stderr());
    }

    @Test
    void playRejectsANonPositiveSpeed(@TempDir Path dir) throws IOException {
        assertEquals(2, run("play", recording(dir).toString(), "--topic", "g1/a", "--speed", "0"));
        assertTrue(stderr().contains("must be > 0"), stderr());
    }

    @Test
    void playOnAnEmptyRangeExplainsItself(@TempDir Path dir) throws IOException {
        assertEquals(0, run("play", recording(dir).toString(),
                "--topic", "g1/a", "--from", "9000", "--to", "9999"));
        assertTrue(stdout().contains("no samples"), stdout());
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

    // ---- exit codes ------------------------------------------------------

    @Test
    void exitCodesAreDistinctForDistinctFailures() {
        assertNotEquals(run("inspect", "/nope/absent.engram"), run("frobnicate", "x"));
        assertEquals(0, run("--help"));
    }

    @Test
    void durationFormattingIsReadable() {
        assertEquals("unknown", Main.formatDuration(-1));
        assertTrue(Main.formatDuration(1_500_000).endsWith("s"), Main.formatDuration(1_500_000));
        assertTrue(Main.formatDuration(90_000_000).contains("m"), Main.formatDuration(90_000_000));
    }
}
