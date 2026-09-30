package com.aaravlabs.engram.replay;

import com.aaravlabs.engram.proto.EngramProto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An immutable, queryable view of one recording file.
 *
 * <p>Built by {@link EngramRecordingReader}. Every event is decoded up front so
 * that time-range and point-in-time queries are a binary search rather than a
 * scan, which matters once a full match is loaded (~30k samples).
 *
 * <p><b>Ordering.</b> Events are stored in the order the file recorded them.
 * Because several publishing threads are in play, timestamps can be very
 * slightly out of order across topics -- that is faithful to the run, not a
 * defect. Per-topic series are sorted by timestamp so range queries behave
 * predictably.
 *
 * <p>Instances are thread-safe.
 */
public final class EngramRecording {

    /** Time range meaning "the whole recording". */
    public static final long FROM_START = 0;
    public static final long TO_END = Long.MAX_VALUE;

    private final EngramProto.RecordingHeader header;
    private final List<EngramProto.RecordingEvent> events;
    private final List<TopicInfo> topics;
    private final Map<Integer, TopicInfo> topicsById;
    private final Map<String, TopicInfo> topicsByName;
    private final List<Series> seriesById;
    private final long initTimeUs;
    private final long startTimeUs;
    private final long stopTimeUs;
    private final boolean truncated;
    private final String truncationReason;

    EngramRecording(EngramProto.RecordingHeader header,
                   List<EngramProto.RecordingEvent> events,
                   List<TopicInfo> topics,
                   List<Series> seriesById,
                   long initTimeUs,
                   long startTimeUs,
                   long stopTimeUs,
                   boolean truncated,
                   String truncationReason) {
        this.header = header;
        this.events = Collections.unmodifiableList(events);
        this.topics = Collections.unmodifiableList(topics);
        this.seriesById = seriesById;

        Map<Integer, TopicInfo> byId = new LinkedHashMap<>();
        Map<String, TopicInfo> byName = new LinkedHashMap<>();
        for (TopicInfo t : topics) {
            byId.put(t.id(), t);
            byName.put(t.name(), t);
        }
        this.topicsById = Collections.unmodifiableMap(byId);
        this.topicsByName = Collections.unmodifiableMap(byName);

        this.initTimeUs = initTimeUs;
        this.startTimeUs = startTimeUs;
        this.stopTimeUs = stopTimeUs;
        this.truncated = truncated;
        this.truncationReason = truncationReason;
    }

    // ---- metadata --------------------------------------------------------

    /** The OpMode name from the file header. */
    public String opModeName() {
        return header.getOpmodeName();
    }

    /** Wire format version of the file. */
    public int formatVersion() {
        return header.getFormatVersion();
    }

    /** Wall-clock start time in epoch milliseconds; for correlating with match logs only. */
    public long startEpochMs() {
        return header.getStartEpochMs();
    }

    /** Every topic, in declaration order (which is id order). */
    public List<TopicInfo> topics() {
        return topics;
    }

    /** Look up a topic by name. */
    public Optional<TopicInfo> topic(String name) {
        return Optional.ofNullable(topicsByName.get(name));
    }

    /** Look up a topic by id. */
    public Optional<TopicInfo> topicById(int id) {
        return Optional.ofNullable(topicsById.get(id));
    }

    // ---- lifecycle -------------------------------------------------------

    /** Timestamp of {@code LIFECYCLE_INIT}, or -1 if absent. */
    public long initTimeUs() {
        return initTimeUs;
    }

    /** Timestamp of {@code LIFECYCLE_START}, or -1 if the run never started. */
    public long startTimeUs() {
        return startTimeUs;
    }

    /** Timestamp of {@code LIFECYCLE_STOP}, or -1 if the run did not stop cleanly. */
    public long stopTimeUs() {
        return stopTimeUs;
    }

    /**
     * Duration in microseconds, measured from {@code LIFECYCLE_START} when
     * present and from {@code LIFECYCLE_INIT} otherwise.
     *
     * @return -1 if the recording has no usable end point
     */
    public long durationUs() {
        long end = stopTimeUs;
        if (end < 0) {
            end = events.isEmpty() ? -1 : events.get(events.size() - 1).getRelTimeUs();
        }
        long begin = startTimeUs >= 0 ? startTimeUs : initTimeUs;
        return (end < 0 || begin < 0) ? -1 : end - begin;
    }

    // ---- integrity -------------------------------------------------------

    /**
     * Whether the file ended mid-message.
     *
     * <p>Expected when a robot process dies before its final flush. Everything
     * decoded before the truncation is intact and safe to query; only the tail
     * is missing.
     */
    public boolean isTruncated() {
        return truncated;
    }

    /** Why parsing stopped early, or null if the file was complete. */
    public String truncationReason() {
        return truncationReason;
    }

    /**
     * Whether this recording is actually complete, with no expectations about
     * which topics should exist.
     *
     * @see CaptureReport
     */
    public CaptureReport captureReport() {
        return CaptureReport.of(this);
    }

    /**
     * Whether this recording is complete, additionally checking that every named
     * topic is present.
     *
     * <p>The file cannot say which topics it was supposed to contain, so this is
     * the only way to catch one that was never captured at all.
     *
     * @throws IllegalArgumentException if {@code expectedTopicNames} is null
     */
    public CaptureReport captureReport(List<String> expectedTopicNames) {
        return CaptureReport.of(this, expectedTopicNames);
    }

    /** Total events read, including lifecycle events and topic declarations. */
    public int eventCount() {
        return events.size();
    }

    /** Every event, in file order. Exposes the raw protobuf stream. */
    public List<EngramProto.RecordingEvent> events() {
        return events;
    }

    /** Events whose timestamp falls in {@code [fromUs, toUs]}, in file order. */
    public List<EngramProto.RecordingEvent> events(long fromUs, long toUs) {
        List<EngramProto.RecordingEvent> out = new ArrayList<>();
        for (EngramProto.RecordingEvent e : events) {
            long t = e.getRelTimeUs();
            if (t >= fromUs && t <= toUs) {
                out.add(e);
            }
        }
        return out;
    }

    // ---- samples ---------------------------------------------------------

    /**
     * Publishes to {@code topicName} within a time range, ordered by timestamp.
     *
     * <p>Bounds are inclusive.
     *
     * @throws IllegalArgumentException if the topic is not in this recording
     */
    public List<Sample> samples(String topicName, long fromUs, long toUs) {
        return samples(requireTopic(topicName).id(), fromUs, toUs);
    }

    /** Publishes to {@code topicId} within a time range, ordered by timestamp. */
    public List<Sample> samples(int topicId, long fromUs, long toUs) {
        Series series = seriesById(topicId);
        if (series == null) {
            return Collections.emptyList();
        }
        int lo = lowerBound(series.times, fromUs);
        int hi = upperBound(series.times, toUs);
        List<Sample> out = new ArrayList<>(Math.max(0, hi - lo));
        for (int i = lo; i < hi; i++) {
            out.add(new Sample(series.times[i], series.values[i]));
        }
        return out;
    }

    /** Every publish to {@code topicName}, ordered by timestamp. */
    public List<Sample> samples(String topicName) {
        return samples(topicName, FROM_START, TO_END);
    }

    /** Every publish to {@code topicId}, ordered by timestamp. */
    public List<Sample> samples(int topicId) {
        return samples(topicId, FROM_START, TO_END);
    }

    /**
     * The value {@code topicName} most recently held at or before {@code timeUs}.
     *
     * <p>This is the "latest value" semantic Synapse itself uses: the newest
     * publish at or before the requested moment, not the one immediately after
     * it.
     *
     * @throws IllegalArgumentException if the topic is not in this recording
     */
    public Optional<Object> valueAt(String topicName, long timeUs) {
        return valueAt(requireTopic(topicName).id(), timeUs);
    }

    /** The value {@code topicId} most recently held at or before {@code timeUs}. */
    public Optional<Object> valueAt(int topicId, long timeUs) {
        Series series = seriesById(topicId);
        if (series == null || series.times.length == 0) {
            return Optional.empty();
        }
        int idx = upperBound(series.times, timeUs) - 1;
        if (idx < 0) {
            return Optional.empty();
        }
        return Optional.of(series.values[idx]);
    }

    /** Statistics for a topic over the whole recording. */
    public TopicStats stats(String topicName) {
        return stats(requireTopic(topicName).id(), FROM_START, TO_END);
    }

    /** Statistics for a topic over the whole recording. */
    public TopicStats stats(int topicId) {
        return stats(topicId, FROM_START, TO_END);
    }

    /** Statistics for a topic over a time range. */
    public TopicStats stats(int topicId, long fromUs, long toUs) {
        List<Sample> inRange = samples(topicId, fromUs, toUs);
        int numeric = 0;
        int unrecorded = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0;
        long first = -1;
        long last = -1;

        for (Sample s : inRange) {
            if (first < 0) {
                first = s.timeUs();
            }
            last = s.timeUs();
            if (!s.hasValue()) {
                unrecorded++;
                continue;
            }
            if (s.value() instanceof Number) {
                double d = ((Number) s.value()).doubleValue();
                numeric++;
                sum += d;
                if (d < min) min = d;
                if (d > max) max = d;
            }
        }
        return new TopicStats(inRange.size(), numeric, unrecorded, first, last,
                numeric == 0 ? Double.NaN : min,
                numeric == 0 ? Double.NaN : max,
                sum);
    }

    // ---- internals -------------------------------------------------------

    static final class Series {
        final long[] times;
        final Object[] values;

        Series(List<Sample> sorted) {
            int n = sorted.size();
            times = new long[n];
            values = new Object[n];
            for (int i = 0; i < n; i++) {
                times[i] = sorted.get(i).timeUs();
                values[i] = sorted.get(i).value();
            }
        }
    }

    static Series buildSeries(List<Sample> samples) {
        // Stable sort keeps file order among equal timestamps, which makes
        // valueAt deterministic when two publishes share a microsecond.
        List<Sample> sorted = new ArrayList<>(samples);
        sorted.sort(Comparator.comparingLong(Sample::timeUs));
        return new Series(sorted);
    }

    private Series seriesById(int topicId) {
        if (topicId < 0 || topicId >= seriesById.size()) {
            return null;
        }
        return seriesById.get(topicId);
    }

    private TopicInfo requireTopic(String name) {
        TopicInfo info = topicsByName.get(name);
        if (info == null) {
            throw new IllegalArgumentException("no such topic in this recording: '" + name
                    + "' (known topics: " + topicsByName.keySet() + ")");
        }
        return info;
    }

    /** First index whose timestamp is >= {@code target}. */
    private static int lowerBound(long[] times, long target) {
        int lo = 0;
        int hi = times.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** First index whose timestamp is > {@code target}. */
    private static int upperBound(long[] times, long target) {
        int lo = 0;
        int hi = times.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    @Override
    public String toString() {
        return "EngramRecording{opMode=" + opModeName()
                + ", version=" + formatVersion()
                + ", topics=" + topics.size()
                + ", events=" + events.size()
                + ", durationUs=" + durationUs()
                + (truncated ? ", TRUNCATED" : "")
                + '}';
    }
}
