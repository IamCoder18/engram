package com.aaravlabs.engram.recorder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a writable directory for recordings on the Robot Controller.
 *
 * <p>Android has made external storage progressively harder to write to, so
 * this tries several locations in order of preference and uses the first one
 * that is actually writable rather than assuming any particular one works:
 *
 * <ol>
 *   <li>{@code /sdcard/FIRST/engram} -- the standard FTC directory, reachable
 *       over USB with no extra permission on the versions FTC still ships.</li>
 *   <li>A directory under the Android context's external files dir, if a
 *       {@code Context} can be reached reflectively from the OpMode. This needs
 *       no runtime permission on any API level.</li>
 *   <li>The JVM's {@code java.io.tmpdir}, which on Android is the app's own
 *       cache directory. Always writable, but not USB-visible.</li>
 * </ol>
 *
 * <p>Which one was chosen is reported by {@link #describe()} so a recording
 * that cannot be found is diagnosable rather than mysterious.
 */
public final class OutputLocation {

    /** Subdirectory created under the FTC root. */
    public static final String DIRECTORY_NAME = "engram";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    private final Path directory;
    private final String description;

    private OutputLocation(Path directory, String description) {
        this.directory = directory;
        this.description = description;
    }

    /**
     * Picks a writable directory, trying candidates in order.
     *
     * @param opModeContext any object from the OpMode -- used only to look for
     *                      an Android {@code Context}; may be null
     * @throws IOException if no candidate directory could be created
     */
    public static OutputLocation resolve(Object opModeContext) throws IOException {
        for (Candidate candidate : candidates(opModeContext)) {
            try {
                Files.createDirectories(candidate.path);
                if (Files.isWritable(candidate.path)) {
                    return new OutputLocation(candidate.path, candidate.description);
                }
            } catch (IOException | RuntimeException e) {
                // Try the next one.
            }
        }
        throw new IOException("no writable engram output directory found; tried "
                + describeCandidates(opModeContext));
    }

    /** An explicit directory, mainly for tests and desktop tooling. */
    public static OutputLocation of(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot use directory " + directory, e);
        }
        return new OutputLocation(directory, "explicit");
    }

    /** The chosen directory. */
    public Path directory() {
        return directory;
    }

    /** Human-readable description of why this directory was chosen. */
    public String describe() {
        return description;
    }

    /**
     * Builds a filename of the form {@code MyTeleOp_2026-09-28_144523.engram}.
     *
     * <p>The class name is stripped of characters that are awkward on a FAT32
     * SD card, and truncated so the whole name stays well inside the 255-byte
     * limit even for long OpMode names.
     */
    public Path newFile(String opModeName) {
        return directory.resolve(fileName(opModeName, LocalDateTime.now()));
    }

    /** Builds a filename with an explicit timestamp. */
    public static String fileName(String opModeName, LocalDateTime when) {
        String safe = sanitize(opModeName);
        return safe + '_' + when.format(STAMP) + ".engram";
    }

    private static String sanitize(String name) {
        if (name == null || name.isEmpty()) {
            return "opmode";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length() && sb.length() < 64; i++) {
            char c = name.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        }
        return sb.toString();
    }

    // ---- candidate discovery --------------------------------------------

    private static final class Candidate {
        final Path path;
        final String description;

        Candidate(Path path, String description) {
            this.path = path;
            this.description = description;
        }
    }

    private static List<Candidate> candidates(Object opModeContext) {
        List<Candidate> out = new ArrayList<>(3);
        out.add(new Candidate(Paths.get("/sdcard/FIRST", DIRECTORY_NAME), "/sdcard/FIRST/engram"));

        Path external = externalFilesDir(opModeContext);
        if (external != null) {
            out.add(new Candidate(external.resolve(DIRECTORY_NAME), "Android external files dir"));
        }

        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp != null && !tmp.isEmpty()) {
            out.add(new Candidate(Paths.get(tmp, "engram"), "java.io.tmpdir"));
        }
        return out;
    }

    private static String describeCandidates(Object opModeContext) {
        StringBuilder sb = new StringBuilder();
        for (Candidate c : candidates(opModeContext)) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(c.path);
        }
        return sb.toString();
    }

    /**
     * Reaches {@code Context.getExternalFilesDir(null)} without compiling
     * against the Android SDK.
     *
     * @return the app's external files directory, or null if no Context could
     *         be found or the call failed
     */
    private static Path externalFilesDir(Object opModeContext) {
        if (opModeContext == null) {
            return null;
        }
        try {
            Object context = opModeContext;
            for (String getter : new String[]{"getContext", "getApplicationContext"}) {
                try {
                    java.lang.reflect.Method m = opModeContext.getClass().getMethod(getter);
                    Object result = m.invoke(opModeContext);
                    if (result != null) {
                        context = result;
                        break;
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // Try the next candidate getter.
                }
            }
            java.lang.reflect.Method getExternalFilesDir =
                    context.getClass().getMethod("getExternalFilesDir", String.class);
            Object dir = getExternalFilesDir.invoke(context, (String) null);
            if (dir instanceof java.io.File) {
                return ((java.io.File) dir).toPath();
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Not running on Android, or the SDK shape changed. Fall through.
        }
        return null;
    }

    @Override
    public String toString() {
        return "OutputLocation{" + directory + " (" + description + ")}";
    }
}
