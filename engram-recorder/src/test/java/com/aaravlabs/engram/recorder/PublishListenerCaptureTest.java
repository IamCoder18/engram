package com.aaravlabs.engram.recorder;

import com.aaravlabs.engram.replay.EngramRecording;
import com.aaravlabs.engram.replay.EngramRecordingReader;
import com.aaravlabs.synapse.Orchestrator;
import com.aaravlabs.synapse.PublishListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the reflective attach path used against a Synapse build that
 * exposes {@code PublishListener}.
 *
 * <p>This is the strategy that will activate on the robot once Synapse ships
 * the hook, and it is reached entirely through {@code Class.forName} and
 * {@link Proxy} -- so without a stub interface it would never execute under
 * test. See {@code com.aaravlabs.synapse.PublishListener} in this source set.
 */
class PublishListenerCaptureTest {

    private File dir;
    private File file;
    private Recorder recorder;

    @BeforeEach
    void setUp() throws IOException {
        dir = java.nio.file.Files.createTempDirectory("engram-listener").toFile();
        file = new File(dir, "listener.engram");
        recorder = Recorder.open(file, "ListenerOp");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (recorder != null) {
            recorder.close();
        }
        if (dir != null) {
            deleteRecursively(dir);
        }
    }

    // ---- availability ----------------------------------------------------

    @Test
    void theStubInterfaceMakesTheHookDiscoverable() {
        assertTrue(PublishListenerCapture.isAvailable(),
                "the test stub interface should be found reflectively");
        assertTrue(CaptureStrategies.isPublishListenerAvailable());
    }

    // ---- attaching -------------------------------------------------------

    @Test
    void attachesToAnOrchestratorThatExposesTheHook() {
        HookOrchestrator hook = new HookOrchestrator();
        CaptureStrategy strategy = CaptureStrategies.attach(hook, recorder);

        assertEquals("publish-listener", strategy.name());
        assertSame(hook, strategy.orchestrator(),
                "the listener strategy must not wrap: the original is returned");
        assertEquals(1, hook.listeners.size());
    }

    @Test
    void recordsPublishesDeliveredThroughTheHook(@TempDir File out) throws IOException {
        HookOrchestrator hook = new HookOrchestrator();
        EngramSession session = EngramSession.start(
                "Hooked", new File(out, "hooked.engram"), hook,
                RecorderConfig.defaults(), "publish-listener");
        try {
            assertEquals("publish-listener", session.strategyName());
            assertTrue(session.isSensorCaptureAutomatic(),
                    "with the hook present, sensor capture needs no wrapper");

            hook.fire("drive/power", 0.5);
            hook.fire("g1/a", true);
            hook.fire("arm/tick", 42L);
        } finally {
            session.close();
        }

        EngramRecording recording = EngramRecordingReader.read(new File(out, "hooked.engram"));

        assertEquals(0.5, (Double) recording.valueAt("drive/power", Long.MAX_VALUE).orElseThrow());
        assertEquals(Boolean.TRUE, recording.valueAt("g1/a", Long.MAX_VALUE).orElseThrow());
        assertEquals(42L, recording.valueAt("arm/tick", Long.MAX_VALUE).orElseThrow());
        assertEquals(3, recording.topics().size());
    }

    @Test
    void theTimestampIsCarriedThroughTheProxy(@TempDir File out) throws IOException {
        HookOrchestrator hook = new HookOrchestrator();
        EngramSession session = EngramSession.start(
                "Timed", new File(out, "timed.engram"), hook,
                RecorderConfig.defaults(), "publish-listener");
        try {
            // Space the publishes so the relative timestamps are distinguishable.
            long base = System.nanoTime();
            hook.fireAt("ramp", 1.0, base);
            hook.fireAt("ramp", 2.0, base + 40_000_000L);
        } finally {
            session.close();
        }

        EngramRecording recording = EngramRecordingReader.read(new File(out, "timed.engram"));
        List<Long> times = new ArrayList<>();
        recording.samples("ramp").forEach(s -> times.add(s.timeUs()));

        assertEquals(2, times.size());
        assertTrue(Math.abs((times.get(1) - times.get(0)) - 40_000) < 5_000,
                "the proxy must pass the listener's timestamp through, delta was "
                        + (times.get(1) - times.get(0)));
    }

    @Test
    void detachStopsFurtherRecording(@TempDir File out) throws IOException {
        HookOrchestrator hook = new HookOrchestrator();
        EngramSession session = EngramSession.start(
                "Detach", new File(out, "detach.engram"), hook,
                RecorderConfig.defaults(), "publish-listener");
        try {
            hook.fire("before", 1.0);
        } finally {
            session.close();
        }

        assertTrue(hook.listeners.isEmpty(), "close() must remove the listener");
        assertEquals(1, hook.publishCount, "the listener fires only while attached");

        EngramRecording recording = EngramRecordingReader.read(new File(out, "detach.engram"));
        assertTrue(recording.topic("before").isPresent());
        assertFalse(recording.topic("after").isPresent());
    }

    @Test
    void autoSelectionPrefersTheHookWhenAvailable() {
        HookOrchestrator hook = new HookOrchestrator();
        assertEquals("publish-listener",
                CaptureStrategies.attach(hook, recorder, null).name());
    }

    @Test
    void forcedListenerStrategyFailsClearlyWhenTheHookIsAbsent() {
        // A real Synapse 0.4.0 orchestrator: the interface is on the classpath
        // (the test stub) but the class has no addPublishListener method.
        Orchestrator plain = Orchestrator.create("no-hook");
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> CaptureStrategies.attach(plain, recorder, "publish-listener"));
            assertTrue(e.getMessage().contains("PublishListener"), e.getMessage());
        } finally {
            plain.close();
        }
    }

    @Test
    void autoSelectionFallsBackWhenTheInterfaceExistsButTheOrchestratorLacksTheHook() {
        // The important robustness case: presence of the interface alone must
        // not be mistaken for a usable hook. Selection depends on the
        // orchestrator actually having the method.
        Orchestrator plain = Orchestrator.create("no-hook");
        try {
            CaptureStrategy strategy = CaptureStrategies.attach(plain, recorder, null);
            assertEquals("decorator", strategy.name());
            assertNotSame(plain, strategy.orchestrator());
        } finally {
            plain.close();
        }
    }

    // ---- proxy behaviour -------------------------------------------------

    @Test
    void theListenerProxyImplementsTheObjectMethodsCorrectly() {
        HookOrchestrator hook = new HookOrchestrator();
        CaptureStrategies.attach(hook, recorder);
        PublishListener listener = hook.listeners.get(0);

        // A proxy that mishandles these can break logging or any identity-based
        // bookkeeping Synapse might do with the listener.
        assertNotNull(listener.toString());
        assertTrue(listener.toString().contains("engram"), listener.toString());
        assertEquals(listener, listener);
        assertFalse(listener.equals(new Object()));
        assertEquals(listener.hashCode(), listener.hashCode());
    }

    @Test
    void theProxySurvivesPublishingFromManyThreads() throws Exception {
        HookOrchestrator hook = new HookOrchestrator();
        EngramSession session = EngramSession.start(
                "Concurrent", new File(java.nio.file.Files.createTempDirectory("engram-conc").toFile(), "c.engram"),
                hook, RecorderConfig.defaults(), "publish-listener");
        try {
            int threads = 6;
            int perThread = 500;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                final int id = t;
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            hook.fire("shared", (double) i);
                            hook.fire("lane/" + id, (double) i);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS));
        } finally {
            session.close();
        }

        EngramRecording recording = EngramRecordingReader.read(session.file());
        assertEquals(6 * 500, recording.topic("shared").orElseThrow().publishCount(),
                "no publish may be lost through the proxy");
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        // Best effort: a leftover temp file must not fail a test.
        file.delete();
    }
}
