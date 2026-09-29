package com.aaravlabs.engram.replay.export;

import com.aaravlabs.engram.replay.EngramRecording;
import com.aaravlabs.engram.replay.Sample;
import com.aaravlabs.engram.replay.TopicInfo;
import com.aaravlabs.engram.replay.Values;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Writes samples as CSV for spreadsheet analysis.
 *
 * <p>With a topic filter the columns are {@code time_us,value}. Without one
 * they are {@code time_us,topic,value}, with all topics merged and sorted by
 * time -- the layout a pivot table or a plotting tool wants.
 *
 * <p>Values are quoted only when they need it, so numeric columns stay
 * numeric when a spreadsheet opens the file.
 */
public final class CsvExporter {

    private CsvExporter() {
    }

    /** Writes every topic as {@code time_us,topic,value}. */
    public static void export(EngramRecording recording, Writer out) throws IOException {
        export(recording, out, null, EngramRecording.FROM_START, EngramRecording.TO_END);
    }

    /**
     * Writes a recording as CSV.
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

        final boolean single = topicFilter != null;
        out.write(single ? "time_us,value\n" : "time_us,topic,value\n");

        if (single) {
            List<Sample> samples = recording.samples(topicFilter, fromUs, toUs);
            for (Sample s : samples) {
                out.write(Long.toString(s.timeUs()));
                out.write(',');
                out.write(field(s));
                out.write('\n');
            }
            return;
        }

        // Merge every topic into one time-ordered stream.
        List<Merged> all = new java.util.ArrayList<>();
        for (TopicInfo t : recording.topics()) {
            for (Sample s : recording.samples(t.id(), fromUs, toUs)) {
                all.add(new Merged(s.timeUs(), t.name(), s));
            }
        }
        all.sort((a, b) -> {
            int c = Long.compare(a.timeUs, b.timeUs);
            return c != 0 ? c : a.topic.compareTo(b.topic);
        });

        for (Merged m : all) {
            out.write(Long.toString(m.timeUs));
            out.write(',');
            out.write(csvField(m.topic));
            out.write(',');
            out.write(field(m.sample));
            out.write('\n');
        }
    }

    private static final class Merged {
        final long timeUs;
        final String topic;
        final Sample sample;

        Merged(long timeUs, String topic, Sample sample) {
            this.timeUs = timeUs;
            this.topic = topic;
            this.sample = sample;
        }
    }

    private static String field(Sample s) {
        Object v = s.value();
        if (v == Values.UNRECORDED) {
            return "";
        }
        if (v instanceof byte[]) {
            return csvField(Values.toText(v));
        }
        if (v instanceof String) {
            return csvField((String) v);
        }
        if (v instanceof Double) {
            return Json.number((Double) v);
        }
        if (v instanceof Float) {
            return Json.number(((Float) v).doubleValue());
        }
        return String.valueOf(v);
    }

    private static String csvField(String raw) {
        boolean needsQuotes = raw.indexOf(',') >= 0 || raw.indexOf('"') >= 0
                || raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return raw;
        }
        return '"' + raw.replace("\"", "\"\"") + '"';
    }
}
