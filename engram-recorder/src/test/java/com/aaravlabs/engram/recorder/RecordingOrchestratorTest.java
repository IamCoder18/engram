package com.aaravlabs.engram.recorder;

import com.aaravlabs.synapse.Node;
import com.aaravlabs.synapse.Orchestrator;
import com.aaravlabs.synapse.Subscription;
import com.aaravlabs.synapse.Topic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decorator must be invisible to team code: every method that is not
 * {@code publish} has to reach the real orchestrator and behave identically.
 * These tests cover that pass-through surface explicitly, because a missing
 * override would fail to compile only when Synapse next adds a method.
 */
class RecordingOrchestratorTest {

    private Orchestrator real;
    private Recorder recorder;
    private RecordingOrchestrator wrapper;

    @BeforeEach
    void setUp() throws Exception {
        real = Orchestrator.create("passthrough");
        recorder = Recorder.open(java.io.File.createTempFile("engram", ".engram"), "Op");
        wrapper = new RecordingOrchestrator(real, recorder);
    }

    @AfterEach
    void tearDown() throws Exception {
        wrapper.close();
        recorder.file().delete();
    }

    @Test
    void identityIsForwarded() {
        assertEquals(real.name(), wrapper.name());
    }

    @Test
    void topicOperationsAreForwarded() {
        Topic<String> created = wrapper.getOrCreateTopic("t", String.class);
        assertEquals("t", created.name());
        assertTrue(wrapper.findTopic("t").isPresent());
        assertTrue(wrapper.findTopic("t", String.class).isPresent());
        assertFalse(wrapper.findTopic("missing").isPresent());
    }

    @Test
    void latestValueIsForwarded() {
        wrapper.publish("t", 5.0);
        assertEquals(5.0, wrapper.getLatestValue("t", Double.class).orElseThrow());
        assertFalse(wrapper.getLatestValue("t", String.class).isPresent());
    }

    @Test
    void subscriptionsAreForwarded() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        Subscription subscription = wrapper.subscribe("s", Double.class, v -> hits.incrementAndGet());
        assertTrue(subscription != null);

        wrapper.publish("s", 1.0);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (hits.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertEquals(1, hits.get(), "the subscription must be live on the real bus");

        subscription.unsubscribe();
        wrapper.publish("s", 2.0);
        Thread.sleep(50);
        assertEquals(1, hits.get(), "unsubscribing through the wrapper must work");
    }

    @Test
    void nodeRegistrationIsForwarded() {
        Node node = new Node(real) {
        };
        assertSame(node, wrapper.registerNode("n", node));
        assertTrue(wrapper.findNode("n").isPresent());

        wrapper.unregisterNode("n");
        assertFalse(wrapper.findNode("n").isPresent());
    }

    @Test
    void schedulingIsForwarded() throws Exception {
        AtomicInteger ticks = new AtomicInteger();
        ScheduledFuture<?> future = wrapper.runPeriodically(ticks::incrementAndGet, 200);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (ticks.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertTrue(ticks.get() > 0, "the periodic task must actually be scheduled");
        } finally {
            future.cancel(false);
        }
    }

    @Test
    void hardwareThreadIsForwarded() throws Exception {
        CompletableFuture<String> done = new CompletableFuture<>();
        wrapper.runOnHardwareThread(() -> done.complete("ran"));
        assertEquals("ran", done.get(5, TimeUnit.SECONDS));
    }

    @Test
    void hardwareFacadeIsForwardedAndFunctional() throws Exception {
        // Orchestrator.hardware() returns a fresh facade per call, so identity
        // is not the assertion. What matters is that the wrapper hands back a
        // working facade bound to the real hardware thread -- duplicating it
        // would put hardware work on a second thread.
        CompletableFuture<Boolean> ran = new CompletableFuture<>();
        wrapper.hardware().run(() -> ran.complete(Boolean.TRUE));

        assertEquals(Boolean.TRUE, ran.get(5, TimeUnit.SECONDS),
                "the forwarded facade must schedule onto the real hardware thread");
    }

    @Test
    void loggingIsForwardedWithoutFailing() {
        wrapper.log("message");
        wrapper.log("tag", "message");
        wrapper.warn("warning");
        wrapper.error("error");
        wrapper.error("error", new IllegalStateException("boom"));
    }

    @Test
    void closedStateAndCloseAreForwarded() {
        assertFalse(wrapper.isClosed());
        wrapper.close();
        assertTrue(wrapper.isClosed());
        assertTrue(real.isClosed());
    }

    @Test
    void publishIsRecordedAndForwarded() {
        wrapper.publish("p", 1.25);
        assertEquals(1.25, real.getLatestValue("p", Double.class).orElseThrow(),
                "the publish must still reach the real bus");
        assertEquals(1, recorder.stats().publishesRecorded());
    }

    @Test
    void publishValidationIsStillEnforcedByTheDelegate() {
        // A null value must be rejected by Synapse exactly as it would be
        // without the wrapper, rather than being recorded and swallowed.
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> wrapper.publish("bad", null));
    }

    @Test
    void publishesToAClosedDelegateAreStillRecorded() {
        real.close();
        // Synapse warns and ignores; the recorder sees the attempt either way,
        // which is the more useful behaviour when diagnosing a run.
        wrapper.publish("after", 1.0);
        assertEquals(1, recorder.stats().publishesRecorded());
        assertTrue(real.isClosed());
    }

    @Test
    void genericSignatureIsPreserved() {
        // Compile-time check that the wrapper keeps Orchestrator's exact
        // generic signatures rather than widening them.
        Consumer<Double> handler = d -> { };
        wrapper.subscribe("g", Double.class, handler);
        wrapper.<String>getOrCreateTopic("g2", String.class);
        Optional<Topic<String>> found = wrapper.<String>findTopic("g2", String.class);
        assertTrue(found.isPresent());
    }
}
