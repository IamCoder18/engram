package com.aaravlabs.engram.recorder;

import org.firstinspires.ftc.robotcore.internal.system.AppUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputLocationTest {

    @Test
    void fileNameHasTheAgreedShape() {
        String name = OutputLocation.fileName("MyTeleOp", epoch(2026, 9, 28, 14, 45, 23));
        assertEquals("MyTeleOp_2026-09-28_144523.engram", name);
    }

    @Test
    void sanitizesCharactersThatAreAwkwardOnSdCards() {
        String name = OutputLocation.fileName("my op/mode:v2*", epoch(2026, 1, 2, 3, 4, 5));
        assertEquals("my_op_mode_v2__2026-01-02_030405.engram", name);
    }

    @Test
    void keepsUnderscoresAndDashes() {
        String name = OutputLocation.fileName("my_op-2", epoch(2026, 1, 2, 3, 4, 5));
        assertTrue(name.startsWith("my_op-2_"), name);
    }

    @Test
    void truncatesVeryLongOpModeNames() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longName.append('x');
        }
        String name = OutputLocation.fileName(longName.toString(), epoch(2026, 1, 2, 3, 4, 5));
        assertEquals(64 + 1 + "2026-01-02_030405".length() + ".engram".length(), name.length());
        assertTrue(name.length() < 255, "filenames must stay well inside the FAT32 limit");
    }

    @Test
    void handlesNullAndEmptyNames() {
        long when = epoch(2026, 1, 2, 3, 4, 5);
        assertTrue(OutputLocation.fileName(null, when).startsWith("opmode_"));
        assertTrue(OutputLocation.fileName("", when).startsWith("opmode_"));
    }

    @Test
    void ofCreatesTheDirectory(@TempDir File dir) {
        File target = new File(new File(dir, "a"), "b/c");
        OutputLocation location = OutputLocation.of(target);
        assertTrue(target.isDirectory());
        assertEquals(target, location.directory());
        assertEquals("explicit", location.describe());
    }

    @Test
    void ofRejectsAnUnusableDirectory(@TempDir File dir) throws java.io.IOException {
        File file = new File(dir, "afile");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(1);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertThrows(IllegalArgumentException.class, () -> OutputLocation.of(file));
    }

    @Test
    void newFileLandsInsideTheChosenDirectory(@TempDir File dir) {
        OutputLocation location = OutputLocation.of(dir);
        File file = location.newFile("Op");
        assertEquals(dir, file.getParentFile());
        assertTrue(file.getName().endsWith(".engram"));
    }

    @Test
    void resolveFallsBackToAWritableLocationOnDesktop(@TempDir File dir) throws java.io.IOException {
        // /sdcard/FIRST does not exist off-device, so this exercises the
        // fallback path that keeps the recorder usable in tests and on a dev
        // machine.
        OutputLocation location = OutputLocation.resolve(null);
        assertTrue(location.directory().canWrite(),
                "the resolved directory must be writable: " + location);
        assertFalse(location.describe().isEmpty());
    }

    @Test
    void resolveUsesAnAndroidContextExposedByTheOpMode(@TempDir File dir) throws java.io.IOException {
        // Future-proofing branch: no current OpMode exposes a Context, but if
        // one ever does, this route should win over the AppUtil fallback.
        // Stands in for the Android Context case: a bean exposing
        // getExternalFilesDir(String) that returns a real directory.
        File external = new File(dir, "external");
        FakeContext context = new FakeContext(external);

        OutputLocation location = OutputLocation.resolve(context);

        assertTrue(location.describe().contains("external files"),
                "should prefer the app-private external dir, got: " + location);
    }

    @Test
    void resolveToleratesAContextThatDoesNotBehave(@TempDir File dir) throws java.io.IOException {
        // Must not throw; it should fall through to the next candidate.
        OutputLocation location = OutputLocation.resolve(new Object() {
            public Object getContext() {
                throw new UnsupportedOperationException("not android");
            }
        });
        assertTrue(location.directory().canWrite());
    }

    @Test
    void toStringIsInformative(@TempDir File dir) {
        String s = OutputLocation.of(dir).toString();
        assertTrue(s.contains(dir.toString()), s);
        assertTrue(s.contains("explicit"), s);
    }

    @Test
    void fallsBackToAppUtilWhenTheOpModeExposesNoContext(@TempDir File dir) throws Exception {
        // The real reason this route exists: RobotCore's OpMode and
        // OpModeInternal expose no getContext() or getApplicationContext(). The
        // SDK reaches a Context through AppUtil.getDefContext(), so that is the
        // path that actually fires on a Robot Controller. A fake AppUtil is put
        // in front of the real classloader so the lookup succeeds on desktop.
        File appExternal = new File(dir, "app-external");
        AppUtil.setExternalFilesDir(appExternal);
        try {
            OutputLocation location = OutputLocation.resolve(new ObjectWithNoContext());

            assertTrue(location.describe().contains("app external files"),
                    "should have used the AppUtil route, got: " + location);
            assertEquals(new File(appExternal, "engram"), location.directory());
            assertTrue(location.isUsbVisible(), "external storage is reachable over USB");
        } finally {
            AppUtil.setExternalFilesDir(null);
        }
    }

    /** An object with no Context accessor, mimicking the real OpMode. */
    static final class ObjectWithNoContext {
    }

    /** Minimal stand-in for android.content.Context. */
    static final class FakeContext {
        private final File externalFilesDir;

        FakeContext(File externalFilesDir) {
            this.externalFilesDir = externalFilesDir;
        }

        public java.io.File getExternalFilesDir(String type) {
            if (!externalFilesDir.isDirectory() && !externalFilesDir.mkdirs()) {
                return null;
            }
            return externalFilesDir;
        }
    }

    /** Local epoch millis for a fixed instant. Filenames render in local time. */
    private static long epoch(int y, int m, int d, int h, int min, int s) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d, h, min, s);
        return c.getTimeInMillis();
    }
}
