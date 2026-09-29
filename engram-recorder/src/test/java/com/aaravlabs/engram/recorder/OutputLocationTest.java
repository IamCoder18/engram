package com.aaravlabs.engram.recorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputLocationTest {

    @Test
    void fileNameHasTheAgreedShape() {
        String name = OutputLocation.fileName("MyTeleOp", LocalDateTime.of(2026, 9, 28, 14, 45, 23));
        assertEquals("MyTeleOp_2026-09-28_144523.engram", name);
    }

    @Test
    void sanitizesCharactersThatAreAwkwardOnSdCards() {
        String name = OutputLocation.fileName("my op/mode:v2*", LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        assertEquals("my_op_mode_v2__2026-01-02_030405.engram", name);
    }

    @Test
    void keepsUnderscoresAndDashes() {
        String name = OutputLocation.fileName("my_op-2", LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        assertTrue(name.startsWith("my_op-2_"), name);
    }

    @Test
    void truncatesVeryLongOpModeNames() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longName.append('x');
        }
        String name = OutputLocation.fileName(longName.toString(), LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        assertEquals(64 + 1 + "2026-01-02_030405".length() + ".engram".length(), name.length());
        assertTrue(name.length() < 255, "filenames must stay well inside the FAT32 limit");
    }

    @Test
    void handlesNullAndEmptyNames() {
        LocalDateTime when = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        assertTrue(OutputLocation.fileName(null, when).startsWith("opmode_"));
        assertTrue(OutputLocation.fileName("", when).startsWith("opmode_"));
    }

    @Test
    void ofCreatesTheDirectory(@TempDir Path dir) {
        Path target = dir.resolve("a/b/c");
        OutputLocation location = OutputLocation.of(target);
        assertTrue(Files.isDirectory(target));
        assertEquals(target, location.directory());
        assertEquals("explicit", location.describe());
    }

    @Test
    void ofRejectsAnUnusableDirectory(@TempDir Path dir) throws java.io.IOException {
        Path file = dir.resolve("afile");
        try {
            Files.write(file, new byte[]{1});
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThrows(IllegalArgumentException.class, () -> OutputLocation.of(file));
    }

    @Test
    void newFileLandsInsideTheChosenDirectory(@TempDir Path dir) {
        OutputLocation location = OutputLocation.of(dir);
        Path file = location.newFile("Op");
        assertEquals(dir, file.getParent());
        assertTrue(file.getFileName().toString().endsWith(".engram"));
    }

    @Test
    void resolveFallsBackToAWritableLocationOnDesktop(@TempDir Path dir) throws java.io.IOException {
        // /sdcard/FIRST does not exist off-device, so this exercises the
        // fallback path that keeps the recorder usable in tests and on a dev
        // machine.
        OutputLocation location = OutputLocation.resolve(null);
        assertTrue(Files.isWritable(location.directory()),
                "the resolved directory must be writable: " + location);
        assertFalse(location.describe().isEmpty());
    }

    @Test
    void resolveUsesAnAndroidContextWhenOneIsAvailable(@TempDir Path dir) throws java.io.IOException {
        // Stands in for the Android Context case: a bean exposing
        // getExternalFilesDir(String) that returns a real directory.
        Path external = dir.resolve("external");
        FakeContext context = new FakeContext(external);

        OutputLocation location = OutputLocation.resolve(context);

        assertTrue(location.describe().contains("external files"),
                "should prefer the app-private external dir, got: " + location);
    }

    @Test
    void resolveToleratesAContextThatDoesNotBehave(@TempDir Path dir) throws java.io.IOException {
        // Must not throw; it should fall through to the next candidate.
        OutputLocation location = OutputLocation.resolve(new Object() {
            public Object getContext() {
                throw new UnsupportedOperationException("not android");
            }
        });
        assertTrue(Files.isWritable(location.directory()));
    }

    @Test
    void toStringIsInformative(@TempDir Path dir) {
        String s = OutputLocation.of(dir).toString();
        assertTrue(s.contains(dir.toString()), s);
        assertTrue(s.contains("explicit"), s);
    }

    /** Minimal stand-in for android.content.Context. */
    static final class FakeContext {
        private final Path externalFilesDir;

        FakeContext(Path externalFilesDir) {
            this.externalFilesDir = externalFilesDir;
        }

        public java.io.File getExternalFilesDir(String type) {
            try {
                Files.createDirectories(externalFilesDir);
            } catch (Exception e) {
                return null;
            }
            return externalFilesDir.toFile();
        }
    }
}
