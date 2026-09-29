package com.aaravlabs.engram.recorder;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.File;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the recorder against Android API-level regressions.
 *
 * <p>The FTC SDK declares {@code minSdkVersion=24}, but several JDK classes the
 * recorder would naturally reach for are API 26. Using one on an API 24 or 25
 * Robot Controller throws {@code NoClassDefFoundError} on the first publish --
 * a failure mode that no desktop test would ever catch, and a bad one to meet
 * on a competition day.
 *
 * <p>This scans the compiled recorder classes for references to those packages,
 * so the constraint is enforced by the build rather than by remembering.
 */
class NoAndroidApiLeakTest {

    /**
     * JDK packages that require Android API 26, above the SDK's declared
     * minimum of 24.
     */
    private static final String[] API_26_PACKAGES = {
            "java/time/",        // LocalDateTime, DateTimeFormatter
            "java/nio/file/",    // Path, Files, Paths
    };

    /**
     * Packages that are fine but would be a smell: streams and {@code Base64}
     * are API 24/26 and the recorder has no business using them on the hot path.
     */
    private static final String[] AVOID_PACKAGES = {
            "java/util/stream/",
    };

    @Test
    void theRecorderReferencesNoApi26OnlyClasses() throws IOException {
        List<String> leaks = scanForRecorderClasses(API_26_PACKAGES);
        if (!leaks.isEmpty()) {
            fail("the recorder references Android API 26 classes, but the FTC SDK declares"
                    + " minSdkVersion=24. On an API 24/25 Robot Controller this throws"
                    + " NoClassDefFoundError on the first publish. Use java.io.File and"
                    + " java.text.SimpleDateFormat instead. Offending references:\n  "
                    + String.join("\n  ", leaks));
        }
    }

    @Test
    void theRecorderAvoidsStreamsOnThePublishPath() throws IOException {
        List<String> leaks = scanForRecorderClasses(AVOID_PACKAGES);
        if (!leaks.isEmpty()) {
            fail("the recorder should not use java.util.stream; it allocates per element on a"
                    + " hot path. Offending references:\n  " + String.join("\n  ", leaks));
        }
    }

    /**
     * Scans the built recorder jar for references to the given internal name
     * prefixes. String constants appear in the constant pool of every class
     * that names them, which is a good enough proxy for "references this type"
     * and cannot miss a class.
     */
    private static List<String> scanForRecorderClasses(String[] prefixes) throws IOException {
        List<String> hits = new ArrayList<>();
        Path classes = locateCompiledClasses();

        if (Files.isDirectory(classes)) {
            try (java.util.stream.Stream<Path> walk = Files.walk(classes)) {
                for (Path classFile : (Iterable<Path>) walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".class"))::iterator) {
                    collectHits(classFile, classes, prefixes, hits);
                }
            }
            return hits;
        }

        try (ZipFile zip = new ZipFile(classes.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                String constantPool;
                try (InputStream in = zip.getInputStream(entry)) {
                    constantPool = readAll(in);
                }
                record(entry.getName(), constantPool, prefixes, hits);
            }
        }
        return hits;
    }

    private static void collectHits(Path classFile, Path root, String[] prefixes, List<String> hits)
            throws IOException {
        String constantPool;
        try (InputStream in = Files.newInputStream(classFile)) {
            constantPool = readAll(in);
        }
        record(root.relativize(classFile).toString(), constantPool, prefixes, hits);
    }

    private static void record(String name, String constantPool, String[] prefixes, List<String> hits) {
        for (String prefix : prefixes) {
            if (constantPool.contains(prefix)) {
                hits.add(name + " -> " + prefix);
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        // Class files are ISO-8859-1, so bytes map to chars one to one and
        // internal names stay readable.
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /**
     * Finds the compiled recorder classes, preferring the build output and
     * falling back to the jar so the check works however tests are invoked.
     */
    private static Path locateCompiledClasses() {
        Path classes = Paths.get("build", "classes", "java", "main");
        if (Files.isDirectory(classes)) {
            return classes;
        }
        File[] candidates = new File("build", "libs").listFiles(
                (dir, name) -> name.startsWith("engram-recorder") && name.endsWith(".jar"));
        if (candidates != null && candidates.length > 0) {
            return candidates[0].toPath();
        }
        throw new IllegalStateException(
                "cannot find the compiled recorder; run ./gradlew :engram-recorder:test");
    }
}
