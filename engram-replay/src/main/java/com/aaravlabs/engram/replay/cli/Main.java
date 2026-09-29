package com.aaravlabs.engram.replay.cli;

import com.aaravlabs.engram.replay.EngramRecording;
import com.aaravlabs.engram.replay.EngramRecordingReader;
import com.aaravlabs.engram.replay.Sample;
import com.aaravlabs.engram.replay.TopicInfo;
import com.aaravlabs.engram.replay.TopicStats;
import com.aaravlabs.engram.replay.Values;
import com.aaravlabs.engram.replay.export.CsvExporter;
import com.aaravlabs.engram.replay.export.JsonExporter;
import com.aaravlabs.engram.replay.export.NdjsonExporter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point for reading recordings.
 *
 * <p>Deliberately dependency-free beyond the protobuf runtime, so a team can
 * run it with nothing but a JRE and a jar.
 */
public final class Main {

    private static final String USAGE = String.join(System.lineSeparator(),
            "engram — read and inspect Synapse recordings",
            "",
            "USAGE",
            "  engram <command> <file.engram> [options]",
            "",
            "COMMANDS",
            "  inspect <file>                       Summary: OpMode, duration, topic count, per-topic rates",
            "  topics <file>                        List every topic with its type and publish count",
            "  query <file> --topic <name>          Print one topic's samples, or a time range of them",
            "  export <file> --format <fmt>         Write the recording as json, ndjson, or csv",
            "  play <file> --topic <name>           Stream a topic to stdout at (or faster than) real time",
            "",
            "OPTIONS",
            "  --topic <name>    Restrict to one topic",
            "  --from <micros>   Range start, microseconds since recording start (inclusive)",
            "  --to <micros>     Range end, microseconds since recording start (inclusive)",
            "  --format <fmt>    json | ndjson | csv   (export only, default json)",
            "  --out <file>      Write to a file instead of stdout",
            "  --speed <x>       Playback rate multiplier (play only, default 1.0)",
            "  -h, --help        Show this help",
            "",
            "NOTES",
            "  Gzipped recordings are read transparently.",
            "  A recording that ends mid-message is reported as truncated; the",
            "  data before the truncation is still queried normally.");

    private static final java.util.Set<String> KNOWN_COMMANDS =
            java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(
                    java.util.Arrays.asList("inspect", "topics", "query", "export", "play")));

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs one command.
     *
     * @return a process exit code: 0 on success, 1 on error, 2 on bad usage
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            return runGuarded(args, out, err);
        } catch (InterruptedException e) {
            // Ctrl-C during `play`. Restore the flag so the JVM can actually
            // wind down instead of swallowing the interrupt.
            Thread.currentThread().interrupt();
            err.println("interrupted");
            return 130;
        }
    }

    private static int runGuarded(String[] args, PrintStream out, PrintStream err)
            throws InterruptedException {
        if (args.length == 0 || isHelp(args[0])) {
            out.println(USAGE);
            return args.length == 0 ? 2 : 0;
        }

        String command = args[0];
        // Validate the command before touching the filesystem, so a typo'd
        // command reports itself rather than a confusing "cannot read file".
        if (!KNOWN_COMMANDS.contains(command)) {
            err.println("error: unknown command '" + command + "'");
            err.println();
            err.println(USAGE);
            return 2;
        }

        Options opts;
        String path;
        try {
            opts = Options.parse(args, 1, err);
            if (opts.positional.isEmpty()) {
                err.println("error: '" + command + "' needs a recording file");
                err.println();
                err.println(USAGE);
                return 2;
            }
            path = opts.positional.get(0);
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 2;
        }

        Path file = Paths.get(path);
        EngramRecording recording;
        try {
            recording = EngramRecordingReader.read(file);
        } catch (IOException e) {
            err.println("error: cannot read '" + path + "': " + e.getMessage());
            return 1;
        }

        try {
            switch (command) {
                case "inspect":
                    inspect(recording, out);
                    return 0;
                case "topics":
                    topics(recording, opts, out);
                    return 0;
                case "query":
                    return query(recording, opts, out, err);
                case "export":
                    return export(recording, opts, out, err);
                default:
                    return play(recording, opts, out, err);
            }
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    // ---- commands --------------------------------------------------------

    private static void inspect(EngramRecording r, PrintStream out) {
        out.printf(Locale.ROOT, "OpMode       %s%n", r.opModeName());
        out.printf(Locale.ROOT, "Format       v%d%n", r.formatVersion());
        out.printf(Locale.ROOT, "Started      %s%n", epochToLocal(r.startEpochMs()));
        out.printf(Locale.ROOT, "Duration     %s%n", formatDuration(r.durationUs()));
        out.printf(Locale.ROOT, "Events       %d%n", r.eventCount());
        out.printf(Locale.ROOT, "Topics       %d%n", r.topics().size());
        if (r.initTimeUs() >= 0) {
            out.printf(Locale.ROOT, "init at      %s%n", formatMicros(r.initTimeUs()));
        }
        if (r.startTimeUs() >= 0) {
            out.printf(Locale.ROOT, "start at     %s%n", formatMicros(r.startTimeUs()));
        }
        if (r.stopTimeUs() >= 0) {
            out.printf(Locale.ROOT, "stop at      %s%n", formatMicros(r.stopTimeUs()));
        }
        if (r.isTruncated()) {
            out.printf(Locale.ROOT, "WARNING      file is truncated (%s);%n", r.truncationReason());
            out.printf(Locale.ROOT, "             the robot likely died before its final flush%n");
        }

        if (r.topics().isEmpty()) {
            return;
        }
        out.println();
        out.printf(Locale.ROOT, "%-32s %-24s %8s %10s %s%n", "TOPIC", "TYPE", "PUBLISHES", "RATE (Hz)", "RANGE");
        for (TopicInfo t : r.topics()) {
            TopicStats s = r.stats(t.id());
            String range = s.min().isPresent()
                    ? String.format(Locale.ROOT, "%.4g .. %.4g", s.min().get(), s.max().get())
                    : "-";
            out.printf(Locale.ROOT, "%-32s %-24s %8d %10.2f %s%n",
                    t.name(), shortType(t.javaType()), t.publishCount(), s.averageRateHz(), range);
            if (t.unrecordedCount() > 0) {
                out.printf(Locale.ROOT, "%-32s %d value(s) had no registered codec%n",
                        "", t.unrecordedCount());
            }
        }
    }

    private static void topics(EngramRecording r, Options opts, PrintStream out) {
        if (opts.topic != null && !r.topic(opts.topic).isPresent()) {
            out.println("(no topic named '" + opts.topic + "')");
            return;
        }
        out.printf(Locale.ROOT, "%5s  %-32s %-28s %-12s %8s%n",
                "ID", "NAME", "JAVA TYPE", "ENC AS", "PUBLISHES");
        for (TopicInfo t : r.topics()) {
            if (opts.topic != null && !opts.topic.equals(t.name())) {
                continue;
            }
            out.printf(Locale.ROOT, "%5d  %-32s %-28s %-12s %8d%n",
                    t.id(), t.name(), shortType(t.javaType()),
                    shortEnum(t.declaredValueType().name()), t.publishCount());
        }
    }

    private static int query(EngramRecording r, Options opts, PrintStream out, PrintStream err) {
        if (opts.topic == null) {
            err.println("error: query needs --topic <name>");
            return 2;
        }
        List<Sample> samples = r.samples(opts.topic, opts.from, opts.to);
        if (samples.isEmpty()) {
            out.println("(no samples for '" + opts.topic + "' in the requested range)");
            return 0;
        }
        TopicStats stats = r.stats(r.topic(opts.topic).get().id(), opts.from, opts.to);
        out.printf(Locale.ROOT, "%s: %d sample(s), rate %.2f Hz%s%n",
                opts.topic, samples.size(), stats.averageRateHz(),
                stats.min().isPresent()
                        ? String.format(Locale.ROOT, ", min %.6g, max %.6g, mean %.6g",
                                stats.min().get(), stats.max().get(), stats.mean().get())
                        : "");
        out.println();
        out.printf(Locale.ROOT, "%14s  %s%n", "TIME (us)", "VALUE");
        for (Sample s : samples) {
            out.printf(Locale.ROOT, "%14d  %s%n", s.timeUs(), Values.toText(s.value()));
        }
        return 0;
    }

    private static int export(EngramRecording r, Options opts, PrintStream out, PrintStream err)
            throws IOException {
        String format = opts.format == null ? "json" : opts.format.toLowerCase(Locale.ROOT);
        switch (format) {
            case "json":
            case "ndjson":
            case "csv":
                break;
            default:
                err.println("error: unknown --format '" + opts.format + "' (expected json, ndjson, or csv)");
                return 2;
        }

        try (Writer writer = openWriter(opts.out, out)) {
            switch (format) {
                case "json":
                    JsonExporter.export(r, writer, opts.topic, opts.from, opts.to);
                    break;
                case "ndjson":
                    NdjsonExporter.export(r, writer, opts.topic, opts.from, opts.to);
                    break;
                default:
                    CsvExporter.export(r, writer, opts.topic, opts.from, opts.to);
                    break;
            }
        }

        if (opts.out != null) {
            err.println("wrote " + format + " to " + opts.out);
        }
        return 0;
    }

    private static int play(EngramRecording r, Options opts, PrintStream out, PrintStream err)
            throws InterruptedException {
        if (opts.topic == null) {
            err.println("error: play needs --topic <name>");
            return 2;
        }
        List<Sample> samples = r.samples(opts.topic, opts.from, opts.to);
        if (samples.isEmpty()) {
            out.println("(no samples for '" + opts.topic + "')");
            return 0;
        }
        long start = System.nanoTime();
        long first = samples.get(0).timeUs();
        for (Sample s : samples) {
            long targetMicros = (s.timeUs() - first);
            long elapsedMicros = (System.nanoTime() - start) / 1_000L;
            long waitMicros = (long) (targetMicros / opts.speed) - elapsedMicros;
            if (waitMicros > 0) {
                Thread.sleep(waitMicros / 1000L, (int) ((waitMicros % 1000L) * 1000L));
            }
            out.printf(Locale.ROOT, "%14d  %s%n", s.timeUs(), Values.toText(s.value()));
        }
        return 0;
    }

    // ---- helpers ---------------------------------------------------------

    private static Writer openWriter(String outPath, PrintStream stdout) throws IOException {
        if (outPath == null) {
            return new BufferedWriter(new OutputStreamWriter(stdout, StandardCharsets.UTF_8), 64 * 1024);
        }
        return Files.newBufferedWriter(Paths.get(outPath), StandardCharsets.UTF_8);
    }

    private static String shortType(String javaType) {
        if (javaType == null) {
            return "unknown";
        }
        int dot = javaType.lastIndexOf('.');
        return dot < 0 ? javaType : javaType.substring(dot + 1);
    }

    /**
     * Drops a generated enum's category prefix: VALUE_TYPE_DOUBLE becomes
     * DOUBLE, LIFECYCLE_INIT becomes INIT. Uses the last underscore, since
     * these names carry two-segment prefixes.
     */
    private static String shortEnum(String name) {
        int underscore = name.lastIndexOf('_');
        return underscore < 0 ? name : name.substring(underscore + 1);
    }

    private static String formatMicros(long micros) {
        return String.format(Locale.ROOT, "%.3f s", micros / 1_000_000.0);
    }

    static String formatDuration(long micros) {
        if (micros < 0) {
            return "unknown";
        }
        double seconds = micros / 1_000_000.0;
        if (seconds < 60) {
            return String.format(Locale.ROOT, "%.3f s", seconds);
        }
        long minutes = (long) (seconds / 60);
        return String.format(Locale.ROOT, "%d m %.2f s", minutes, seconds - minutes * 60);
    }

    private static String epochToLocal(long epochMs) {
        if (epochMs <= 0) {
            return "unknown";
        }
        return java.time.Instant.ofEpochMilli(epochMs)
                .atZone(java.time.ZoneId.systemDefault())
                .format(DateTimeFormatters.STAMP);
    }

    private static boolean isHelp(String arg) {
        return "-h".equals(arg) || "--help".equals(arg) || "help".equals(arg);
    }

    // ---- option parsing --------------------------------------------------

    private static final class Options {
        final java.util.List<String> positional = new java.util.ArrayList<>();
        String topic;
        long from = EngramRecording.FROM_START;
        long to = EngramRecording.TO_END;
        String format;
        String out;
        double speed = 1.0;

        static Options parse(String[] args, int start, PrintStream err) {
            Options o = new Options();
            for (int i = start; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--topic":
                        o.topic = need(args, ++i, "--topic", err);
                        break;
                    case "--from":
                        o.from = parseLong(need(args, ++i, "--from", err), "--from", err);
                        break;
                    case "--to":
                        o.to = parseLong(need(args, ++i, "--to", err), "--to", err);
                        break;
                    case "--format":
                        o.format = need(args, ++i, "--format", err);
                        break;
                    case "--out":
                        o.out = need(args, ++i, "--out", err);
                        break;
                    case "--speed":
                        o.speed = parseDouble(need(args, ++i, "--speed", err), "--speed", err);
                        if (o.speed <= 0) {
                            throw new IllegalArgumentException("--speed must be > 0");
                        }
                        break;
                    default:
                        if (a.startsWith("-")) {
                            throw new IllegalArgumentException("unknown option '" + a + "'");
                        }
                        o.positional.add(a);
                }
            }
            return o;
        }

        private static String need(String[] args, int i, String flag, PrintStream err) {
            if (i >= args.length) {
                throw new IllegalArgumentException(flag + " needs a value");
            }
            return args[i];
        }

        private static long parseLong(String raw, String flag, PrintStream err) {
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(flag + " expects a whole number, got '" + raw + "'");
            }
        }

        private static double parseDouble(String raw, String flag, PrintStream err) {
            try {
                return Double.parseDouble(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(flag + " expects a number, got '" + raw + "'");
            }
        }
    }

    private static final class DateTimeFormatters {
        static final java.time.format.DateTimeFormatter STAMP =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    }
}
