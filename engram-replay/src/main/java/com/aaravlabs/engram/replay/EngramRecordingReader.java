package com.aaravlabs.engram.replay;

import com.aaravlabs.engram.proto.EngramProto;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Reads a {@code .engram} recording into an immutable {@link EngramRecording}.
 *
 * <p>Handles two conditions the Robot Controller can produce:
 *
 * <ul>
 *   <li><b>Gzip.</b> A file beginning with the gzip magic bytes is decompressed
 *       transparently, which is worth roughly 10x on a match-length recording.</li>
 *   <li><b>Truncation.</b> A file that ends mid-message -- what a robot process
 *       that dies before its final flush leaves behind -- is read up to the last
 *       complete message. The result is flagged
 *       {@link EngramRecording#isTruncated()} rather than rejected, because
 *       diagnosing a crashed run is exactly when the partial data is most
 *       valuable.</li>
 * </ul>
 *
 * <p>The whole recording is held in memory. A full match is a few megabytes.
 */
public final class EngramRecordingReader {

    private static final int GZIP_MAGIC_0 = 0x1f;
    private static final int GZIP_MAGIC_1 = 0x8b;

    private EngramRecordingReader() {
    }

    /** Reads a recording from a file. */
    public static EngramRecording read(Path file) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file), 64 * 1024)) {
            return read(raw, file.toString());
        }
    }

    /**
     * Reads a recording from a {@link File}.
     *
     * <p>Convenience for callers holding a {@code File} — notably
     * {@code Recorder#file()}, which returns one.
     */
    public static EngramRecording read(File file) throws IOException {
        try (InputStream raw = new BufferedInputStream(new FileInputStream(file), 64 * 1024)) {
            return read(raw, file.toString());
        }
    }

    /**
     * Reads a recording from any stream. The caller owns the stream and is
     * responsible for closing it.
     *
     * @param source description used in error messages
     */
    public static EngramRecording read(InputStream in, String source) throws IOException {
        InputStream stream = maybeGunzip(in);

        EngramProto.RecordingHeader header;
        try {
            header = EngramProto.RecordingHeader.parseDelimitedFrom(stream);
        } catch (EOFException | com.google.protobuf.InvalidProtocolBufferException e) {
            throw new IOException("not a valid engram recording (" + source + "): the file is empty or"
                    + " its header is unreadable", e);
        }
        if (header == null) {
            throw new IOException("not a valid engram recording (" + source + "): the file is empty");
        }

        List<EngramProto.RecordingEvent> events = new ArrayList<>();
        boolean truncated = false;
        String reason = null;
        try {
            EngramProto.RecordingEvent event;
            while ((event = EngramProto.RecordingEvent.parseDelimitedFrom(stream)) != null) {
                events.add(event);
            }
        } catch (EOFException | com.google.protobuf.InvalidProtocolBufferException
                 | IndexOutOfBoundsException e) {
            // A partial trailing message. Everything before it is intact.
            truncated = true;
            reason = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }

        return build(header, events, truncated, reason);
    }

    private static InputStream maybeGunzip(InputStream in) throws IOException {
        in.mark(2);
        int b0 = in.read();
        int b1 = in.read();
        in.reset();
        if (b0 == GZIP_MAGIC_0 && b1 == GZIP_MAGIC_1) {
            return new GZIPInputStream(in, 64 * 1024);
        }
        return in;
    }

    private static EngramRecording build(EngramProto.RecordingHeader header,
                                         List<EngramProto.RecordingEvent> events,
                                         boolean truncated,
                                         String reason) {

        Map<Integer, EngramProto.TopicDeclaration> declarations = new HashMap<>();
        Map<Integer, List<Sample>> perTopic = new HashMap<>();
        long initTimeUs = -1;
        long startTimeUs = -1;
        long stopTimeUs = -1;

        for (EngramProto.RecordingEvent event : events) {
            long t = event.getRelTimeUs();
            switch (event.getEventCase()) {
                case LIFECYCLE:
                    switch (event.getLifecycle().getType()) {
                        case LIFECYCLE_INIT:
                            initTimeUs = t;
                            break;
                        case LIFECYCLE_START:
                            startTimeUs = t;
                            break;
                        case LIFECYCLE_STOP:
                            stopTimeUs = t;
                            break;
                        default:
                            break;
                    }
                    break;

                case TOPIC_DECLARATION:
                    declarations.put(event.getTopicDeclaration().getTopicId(), event.getTopicDeclaration());
                    break;

                case PUBLISH:
                    int id = event.getPublish().getTopicId();
                    perTopic.computeIfAbsent(id, k -> new ArrayList<>())
                            .add(new Sample(t, Values.decode(event.getPublish().getValue())));
                    break;

                case EVENT_NOT_SET:
                default:
                    break;
            }
        }

        int maxId = -1;
        for (int id : declarations.keySet()) {
            maxId = Math.max(maxId, id);
        }
        for (int id : perTopic.keySet()) {
            maxId = Math.max(maxId, id);
        }

        List<EngramRecording.Series> seriesById = new ArrayList<>(maxId + 1);
        for (int i = 0; i <= maxId; i++) {
            seriesById.add(null);
        }
        for (Map.Entry<Integer, List<Sample>> entry : perTopic.entrySet()) {
            seriesById.set(entry.getKey(), EngramRecording.buildSeries(entry.getValue()));
        }

        // Build the manifest, ordered by id. A topic could in principle be
        // published to before its declaration is seen, so synthesise an entry
        // rather than dropping the topic.
        List<TopicInfo> topics = new ArrayList<>(declarations.size());
        for (int id = 0; id <= maxId; id++) {
            EngramProto.TopicDeclaration d = declarations.get(id);
            EngramRecording.Series series = id < seriesById.size() ? seriesById.get(id) : null;
            if (d == null && series == null) {
                continue;
            }
            String name = d != null ? d.getName() : "topic-" + id;
            String javaType = d != null ? d.getJavaType() : "unknown";
            EngramProto.ValueType valueType =
                    d != null ? d.getValueType() : EngramProto.ValueType.VALUE_TYPE_UNSPECIFIED;

            int count = series == null ? 0 : series.times.length;
            long first = count == 0 ? -1 : series.times[0];
            long last = count == 0 ? -1 : series.times[count - 1];
            int unrecorded = 0;
            if (series != null) {
                for (Object v : series.values) {
                    if (v == Values.UNRECORDED) {
                        unrecorded++;
                    }
                }
            }
            topics.add(new TopicInfo(id, name, javaType, valueType, count, first, last, unrecorded,
                    d != null));
        }

        return new EngramRecording(header, events, topics, seriesById,
                initTimeUs, startTimeUs, stopTimeUs, truncated, reason);
    }
}
