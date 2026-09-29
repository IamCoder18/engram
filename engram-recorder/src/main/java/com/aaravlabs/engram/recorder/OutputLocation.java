package com.aaravlabs.engram.recorder;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Resolves a writable directory for recordings on the Robot Controller.
 *
 * <p>Android has made external storage progressively harder to write to, so
 * rather than assuming any particular location works, this tries candidates in
 * order of preference and uses the first that is actually writable:
 *
 * <ol>
 *   <li>{@code /sdcard/FIRST/engram} — the standard FTC directory. The Robot
 *       Controller app is granted legacy external storage access, so this works
 *       on every SDK version the FTC supports, and files here are reachable over
 *       USB with no path hunting. This is the good one.</li>
 *   <li>The app's external files directory, obtained through
 *       {@code AppUtil.getDefContext()}. Needs no runtime permission on any API
 *       level, and lives on external storage, but sits under
 *       {@code Android/data/<package>/files/} — reachable over USB, but you have
 *       to look for it.</li>
 *   <li>The JVM's {@code java.io.tmpdir}, which on Android is the app's own
 *       cache directory. Always writable, but <b>not</b> visible over USB.</li>
 * </ol>
 *
 * <p>{@link #isUsbVisible()} reports whether the chosen directory can be
 * retrieved from a laptop. A recording you cannot get off the robot is
 * worthless, so a session landing somewhere invisible says so loudly rather than
 * failing quietly.
 *
 * <h2>Why no {@code java.nio.file}</h2>
 * The FTC SDK declares {@code minSdkVersion=24}, but {@code java.nio.file} and
 * {@code java.time} are API 26. Using them here would make the recorder throw
 * {@code NoClassDefFoundError} on an API 24 or 25 device, so this class is
 * deliberately built on {@link File} and {@link SimpleDateFormat}, which are
 * available on every Android version the RC supports. {@code NoAndroidApiLeakTest}
 * fails the build if that regresses.
 */
public final class OutputLocation {

    /** Subdirectory created under the FTC root. */
    public static final String DIRECTORY_NAME = "engram";

    private static final String TIMESTAMP_PATTERN = "yyyy-MM-dd_HHmmss";

    private final File directory;
    private final String description;
    private final boolean usbVisible;

    private OutputLocation(File directory, String description, boolean usbVisible) {
        this.directory = directory;
        this.description = description;
        this.usbVisible = usbVisible;
    }

    /**
     * Picks a writable directory, trying candidates in order.
     *
     * @param opModeContext any object from the OpMode. May be null, and is used
     *                      only as a best-effort route to an Android
     *                      {@code Context}.
     * @throws IOException if no candidate directory could be created
     */
    public static OutputLocation resolve(Object opModeContext) throws IOException {
        for (Candidate candidate : candidates(opModeContext)) {
            File dir = candidate.path;
            if (isUsableDirectory(dir)) {
                return new OutputLocation(dir, candidate.description, candidate.usbVisible);
            }
        }
        throw new IOException("no writable engram output directory found; tried "
                + describeCandidates(opModeContext));
    }

    private static boolean isUsableDirectory(File dir) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            return dir.canWrite();
        } catch (SecurityException e) {
            return false;
        }
    }

    /** An explicit directory, mainly for tests and desktop tooling. */
    public static OutputLocation of(File directory) {
        if (!isUsableDirectory(directory)) {
            throw new IllegalArgumentException("cannot use directory " + directory);
        }
        return new OutputLocation(directory, "explicit", true);
    }

    /** The chosen directory. */
    public File directory() {
        return directory;
    }

    /** Human-readable description of why this directory was chosen. */
    public String describe() {
        return description;
    }

    /**
     * Whether a recording here can be pulled off the robot over USB.
     *
     * <p>False only for the {@code java.io.tmpdir} fallback, which is the app's
     * private cache. Worth surfacing in telemetry: if this is false, the file
     * has to be retrieved with the device's app-data tooling rather than a
     * plain file browser.
     */
    public boolean isUsbVisible() {
        return usbVisible;
    }

    /**
     * Builds a filename of the form {@code MyTeleOp_2026-09-28_144523.engram}.
     *
     * <p>The class name is stripped of characters that are awkward on a FAT32
     * SD card and truncated, so the whole name stays well inside the 255-byte
     * limit even for long OpMode names.
     */
    public File newFile(String opModeName) {
        return new File(directory, fileName(opModeName, System.currentTimeMillis()));
    }

    /** Builds a filename for a specific instant. */
    public static String fileName(String opModeName, long epochMillis) {
        // SimpleDateFormat is not thread-safe; a fresh one per call is correct
        // and costs nothing next to a filesystem call.
        String stamp = new SimpleDateFormat(TIMESTAMP_PATTERN, Locale.ROOT).format(new Date(epochMillis));
        return sanitize(opModeName) + '_' + stamp + ".engram";
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
        final File path;
        final String description;
        final boolean usbVisible;

        Candidate(File path, String description, boolean usbVisible) {
            this.path = path;
            this.description = description;
            this.usbVisible = usbVisible;
        }
    }

    private static List<Candidate> candidates(Object opModeContext) {
        List<Candidate> out = new ArrayList<>(3);
        out.add(new Candidate(new File("/sdcard/FIRST", DIRECTORY_NAME), "/sdcard/FIRST/engram", true));

        File appExternal = appExternalFilesDir(opModeContext);
        if (appExternal != null) {
            out.add(new Candidate(new File(appExternal, DIRECTORY_NAME),
                    "app external files dir", true));
        }

        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp != null && !tmp.isEmpty()) {
            out.add(new Candidate(new File(tmp, DIRECTORY_NAME), "java.io.tmpdir", false));
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
     * Obtains the app's external files directory without compiling against the
     * Android or FTC SDK. Three routes, in order:
     *
     * <ol>
     *   <li>The object itself, if it already answers {@code getExternalFilesDir}
     *       -- that is, if it is a {@code Context} (or something shaped like
     *       one) in its own right.</li>
     *   <li>{@code getContext()} / {@code getApplicationContext()} on the
     *       object, if some OpMode base ever exposes one. Future-proofing: the
     *       SDK's current {@code OpMode} does not.</li>
     *   <li>{@code AppUtil.getDefContext()}, which is how the FTC SDK itself
     *       reaches a {@code Context}. Verified present on RobotCore 8.0
     *       through 12.0. This is the route that actually fires on a Robot
     *       Controller, because {@code OpMode} and {@code OpModeInternal}
     *       expose no Context accessor of their own.</li>
     * </ol>
     *
     * @return the app's external files directory, or null if no route worked
     */
    private static File appExternalFilesDir(Object opModeContext) {
        File dir = externalFilesDirOf(opModeContext);
        if (dir != null) {
            return dir;
        }
        dir = externalFilesDirOf(contextFromOpModeGetters(opModeContext));
        if (dir != null) {
            return dir;
        }
        return externalFilesDirOf(contextFromAppUtil());
    }

    private static File externalFilesDirOf(Object context) {
        if (context == null) {
            return null;
        }
        try {
            Object dir = context.getClass()
                    .getMethod("getExternalFilesDir", String.class)
                    .invoke(context, (String) null);
            return dir instanceof File ? (File) dir : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static Object contextFromOpModeGetters(Object opModeContext) {
        if (opModeContext == null) {
            return null;
        }
        for (String getter : new String[]{"getContext", "getApplicationContext"}) {
            try {
                Object result = opModeContext.getClass().getMethod(getter).invoke(opModeContext);
                if (result != null) {
                    return result;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Not present; the next route is tried.
            }
        }
        return null;
    }

    private static Object contextFromAppUtil() {
        try {
            return Class.forName("org.firstinspires.ftc.robotcore.internal.system.AppUtil")
                    .getMethod("getDefContext")
                    .invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Not running under the Robot Controller, or the SDK moved it.
            return null;
        }
    }

    @Override
    public String toString() {
        return "OutputLocation{" + directory + " (" + description
                + (usbVisible ? ", usb-visible" : ", NOT usb-visible") + ")}";
    }
}
