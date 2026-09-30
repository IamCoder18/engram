package com.aaravlabs.engram.replay.export;

import com.aaravlabs.engram.replay.CaptureReport;
import com.aaravlabs.engram.replay.EngramRecording;
import com.aaravlabs.engram.replay.Sample;
import com.aaravlabs.engram.replay.TopicInfo;
import com.aaravlabs.engram.replay.Values;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Writes a recording as a single JSON document.
 *
 * <p>Shape:
 * <pre>{@code
 * {
 *   "formatVersion": 1, "opMode": "...", "startEpochMs": 0,
 *   "initTimeUs": 0, "startTimeUs": 150000, "stopTimeUs": 5010000,
 *   "truncated": false,
 *   "capture": { "complete": true, "finalized": true, "problems": [] },
 *   "topics":   [ { "id": 0, "name": "...", "javaType": "...", "publishes": 1800 }, ... ],
 *   "samples":  [ { "t": 0, "topicId": 0, "topic": "...", "value": 0.0 }, ... ]
 * }
 * }</pre>
 *
 * <p>This is the format intended for a web visualizer: self-contained, no
 * streaming needed, and easy to fetch with {@code fetch()}.
 */
public final class JsonExporter {

    private JsonExporter() {
    }

    /** Writes the whole recording. */
    public static void export(EngramRecording recording, Writer out) throws IOException {
        export(recording, out, null, EngramRecording.FROM_START, EngramRecording.TO_END);
    }

    /**
     * Writes a recording, optionally narrowed to one topic and a time range.
     *
     * @param topicFilter topic name to include, or null for every topic. An
     *                   unknown name is an error rather than silently empty.
     * @throws IllegalArgumentException if {@code topicFilter} is not in the recording
     */
    public static void export(EngramRecording recording,
                              Writer out,
                              String topicFilter,
                              long fromUs,
                              long toUs) throws IOException {

        if (topicFilter != null && !recording.topic(topicFilter).isPresent()) {
            throw new IllegalArgumentException("no such topic: '" + topicFilter + "'");
        }

        out.write("{\n");
        out.write("  \"formatVersion\": " + Json.number(recording.formatVersion()) + ",\n");
        out.write("  \"opMode\": " + Json.quote(recording.opModeName()) + ",\n");
        out.write("  \"startEpochMs\": " + Json.number(recording.startEpochMs()) + ",\n");
        out.write("  \"initTimeUs\": " + Json.number(recording.initTimeUs()) + ",\n");
        out.write("  \"startTimeUs\": " + Json.number(recording.startTimeUs()) + ",\n");
        out.write("  \"stopTimeUs\": " + Json.number(recording.stopTimeUs()) + ",\n");
        out.write("  \"durationUs\": " + Json.number(recording.durationUs()) + ",\n");
        out.write("  \"truncated\": " + recording.isTruncated());
        if (recording.isTruncated()) {
            out.write(",\n  \"truncationReason\": " + Json.quote(recording.truncationReason()));
        }
        out.write(",\n");

        // ---- capture completeness ----
        // A consumer rendering this document cannot tell an empty topics array
        // from a robot that was killed mid-match, and that difference is the
        // whole reason the file exists.
        CaptureReport capture = recording.captureReport();
        out.write("  \"capture\": {\"complete\": " + capture.isComplete()
                + ", \"finalized\": " + capture.isFinalized()
                + ", \"declaredTopics\": " + Json.number(capture.declaredTopicCount())
                + ", \"observedTopics\": " + Json.number(capture.observedTopicCount())
                + ", \"problems\": [");
        boolean firstProblem = true;
        for (CaptureReport.Finding finding : capture.findings()) {
            out.write(firstProblem ? "\n" : ",\n");
            firstProblem = false;
            out.write("    {\"code\": " + Json.quote(finding.code())
                    + ", \"message\": " + Json.quote(finding.message())
                    + ", \"topics\": [");
            boolean firstTopic = true;
            for (String topic : finding.topics()) {
                out.write(firstTopic ? "" : ", ");
                firstTopic = false;
                out.write(Json.quote(topic));
            }
            out.write("]}");
        }
        out.write(firstProblem ? "]},\n" : "\n  ]},\n");

        // ---- topics ----
        out.write("  \"topics\": [");
        boolean first = true;
        for (TopicInfo t : recording.topics()) {
            if (topicFilter != null && !topicFilter.equals(t.name())) {
                continue;
            }
            out.write(first ? "\n" : ",\n");
            first = false;
            out.write("    {\"id\": " + Json.number(t.id())
                    + ", \"name\": " + Json.quote(t.name())
                    + ", \"javaType\": " + Json.quote(t.javaType())
                    + ", \"valueType\": " + Json.quote(t.declaredValueType().name())
                    + ", \"publishes\": " + Json.number(t.publishCount())
                    + ", \"unrecorded\": " + Json.number(t.unrecordedCount())
                    + "}");
        }
        out.write(first ? "],\n" : "\n  ],\n");

        // ---- samples ----
        out.write("  \"samples\": [");
        first = true;
        for (TopicInfo t : recording.topics()) {
            if (topicFilter != null && !topicFilter.equals(t.name())) {
                continue;
            }
            List<Sample> samples = recording.samples(t.id(), fromUs, toUs);
            for (Sample s : samples) {
                out.write(first ? "\n" : ",\n");
                first = false;
                out.write("    {\"t\": " + Json.number(s.timeUs())
                        + ", \"topicId\": " + Json.number(t.id())
                        + ", \"topic\": " + Json.quote(t.name())
                        + ", \"value\": " + render(s) + "}");
            }
        }
        out.write(first ? "]\n" : "\n  ]\n");

        out.write("}\n");
    }

    private static String render(Sample s) {
        Object v = s.value();
        if (v == Values.UNRECORDED) {
            return "null";
        }
        if (v instanceof String) {
            return Json.quote((String) v);
        }
        if (v instanceof byte[]) {
            return Json.quote(Values.toText(v));
        }
        if (v instanceof Double) {
            return Json.number((Double) v);
        }
        if (v instanceof Float) {
            return Json.number(((Float) v).doubleValue());
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        return Json.quote(String.valueOf(v));
    }
}
