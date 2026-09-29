package com.aaravlabs.engram.replay.export;

import com.aaravlabs.engram.replay.EngramRecording;
import com.aaravlabs.engram.replay.Sample;
import com.aaravlabs.engram.replay.TopicInfo;
import com.aaravlabs.engram.replay.Values;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Writes a recording as newline-delimited JSON: one event per line, each a
 * complete JSON object with no enclosing array.
 *
 * <pre>{@code
 * {"kind":"header","opMode":"MyTeleOp","formatVersion":1}
 * {"kind":"topic","id":0,"name":"drive/power","javaType":"java.lang.Double","valueType":"DOUBLE"}
 * {"kind":"lifecycle","type":"LIFECYCLE_INIT","t":0}
 * {"kind":"sample","t":31600,"topicId":0,"topic":"drive/power","value":0.75}
 * }</pre>
 *
 * <p>Suits piping into {@code jq}, log pipelines, and incremental browser
 * ingestion, none of which want a multi-megabyte array held in memory first.
 */
public final class NdjsonExporter {

    private NdjsonExporter() {
    }

    /** Writes the whole recording. */
    public static void export(EngramRecording recording, Writer out) throws IOException {
        export(recording, out, null, EngramRecording.FROM_START, EngramRecording.TO_END);
    }

    /**
     * Writes a recording, optionally narrowed to one topic and a time range.
     *
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

        out.write("{\"kind\":\"header\",\"formatVersion\":" + Json.number(recording.formatVersion())
                + ",\"opMode\":" + Json.quote(recording.opModeName())
                + ",\"startEpochMs\":" + Json.number(recording.startEpochMs())
                + ",\"initTimeUs\":" + Json.number(recording.initTimeUs())
                + ",\"startTimeUs\":" + Json.number(recording.startTimeUs())
                + ",\"stopTimeUs\":" + Json.number(recording.stopTimeUs())
                + ",\"truncated\":" + recording.isTruncated()
                + "}\n");

        for (TopicInfo t : recording.topics()) {
            if (topicFilter != null && !topicFilter.equals(t.name())) {
                continue;
            }
            out.write("{\"kind\":\"topic\",\"id\":" + Json.number(t.id())
                    + ",\"name\":" + Json.quote(t.name())
                    + ",\"javaType\":" + Json.quote(t.javaType())
                    + ",\"valueType\":" + Json.quote(t.declaredValueType().name())
                    + ",\"publishes\":" + Json.number(t.publishCount())
                    + ",\"unrecorded\":" + Json.number(t.unrecordedCount())
                    + "}\n");
        }

        if (recording.initTimeUs() >= 0) {
            writeLifecycle(out, "LIFECYCLE_INIT", recording.initTimeUs());
        }
        if (recording.startTimeUs() >= 0) {
            writeLifecycle(out, "LIFECYCLE_START", recording.startTimeUs());
        }
        if (recording.stopTimeUs() >= 0) {
            writeLifecycle(out, "LIFECYCLE_STOP", recording.stopTimeUs());
        }

        for (TopicInfo t : recording.topics()) {
            if (topicFilter != null && !topicFilter.equals(t.name())) {
                continue;
            }
            List<Sample> samples = recording.samples(t.id(), fromUs, toUs);
            for (Sample s : samples) {
                out.write("{\"kind\":\"sample\",\"t\":" + Json.number(s.timeUs())
                        + ",\"topicId\":" + Json.number(t.id())
                        + ",\"topic\":" + Json.quote(t.name())
                        + ",\"value\":" + render(s) + "}\n");
            }
        }
    }

    private static void writeLifecycle(Writer out, String type, long t) throws IOException {
        out.write("{\"kind\":\"lifecycle\",\"type\":" + Json.quote(type)
                + ",\"t\":" + Json.number(t) + "}\n");
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
