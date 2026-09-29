package org.firstinspires.ftc.robotcore.internal.system;

import java.io.File;

/**
 * Test-only stand-in for the FTC SDK's {@code AppUtil}.
 *
 * <p>This exists so {@code OutputLocation}'s Android path can be exercised on a
 * desktop JVM. It is not a mock of the whole SDK class, only of the one method
 * Engram uses: {@code getDefContext()}, which is how the Robot Controller
 * itself reaches an Android {@code Context}.
 *
 * <p>Why this route matters: RobotCore's {@code OpMode} and
 * {@code OpModeInternal} expose <em>no</em> {@code getContext()} or
 * {@code getApplicationContext()}. Verified by inspecting
 * {@code RobotCore-10.2.0.aar} with {@code javap}. So looking for a Context on
 * the OpMode instance finds nothing on a real device, and this is the only
 * route that actually works there.
 */
public final class AppUtil {

    private static File externalFilesDir;

    private AppUtil() {
    }

    /**
     * Stands in for the SDK's static accessor. Returns whatever object exposes
     * {@code getExternalFilesDir(String)}.
     */
    public static Object getDefContext() {
        return new FakeContext();
    }

    /** Points the fake Context at a directory, or null to make it unavailable. */
    public static void setExternalFilesDir(File dir) {
        externalFilesDir = dir;
    }

    /**
     * Public, not private: {@code OutputLocation} invokes
     * {@code getExternalFilesDir} on whatever this returns, and
     * {@code Method.invoke} enforces accessibility of the *declaring class* too.
     * A private nested class here would fail with {@code IllegalAccessException}
     * and the route would be skipped.
     *
     * <p>On a real device the object is {@code android.app.Application}, which
     * is public, so this is a constraint of the test double rather than of the
     * production path.
     */
    public static final class FakeContext {

        public File getExternalFilesDir(String type) {
            if (externalFilesDir == null) {
                return null;
            }
            if (!externalFilesDir.isDirectory() && !externalFilesDir.mkdirs()) {
                return null;
            }
            return externalFilesDir;
        }
    }
}
