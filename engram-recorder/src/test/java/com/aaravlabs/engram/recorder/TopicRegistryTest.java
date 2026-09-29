package com.aaravlabs.engram.recorder;

import com.aaravlabs.engram.proto.EngramProto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicRegistryTest {

    private final ValueEncoder encoder = new ValueEncoder(RecorderConfig.defaults());

    @Test
    void assignsDenseIdsInFirstSeenOrder() {
        ConcurrentLinkedQueue<EngramProto.TopicDeclaration> seen = new ConcurrentLinkedQueue<>();
        TopicRegistry registry = new TopicRegistry((d, ts) -> seen.add(d));

        assertEquals(0, registry.idFor("a", 1.0, encoder, 0));
        assertEquals(1, registry.idFor("b", 2.0, encoder, 0));
        assertEquals(2, registry.idFor("c", 3.0, encoder, 0));

        assertEquals(3, registry.size());
        java.util.List<String> names = new java.util.ArrayList<>();
        seen.forEach(d -> names.add(d.getName()));
        assertEquals(java.util.Arrays.asList("a", "b", "c"), names);
    }

    @Test
    void repeatPublishesReuseTheSameIdAndDeclareOnlyOnce() {
        ConcurrentLinkedQueue<EngramProto.TopicDeclaration> seen = new ConcurrentLinkedQueue<>();
        TopicRegistry registry = new TopicRegistry((d, ts) -> seen.add(d));

        for (int i = 0; i < 100; i++) {
            assertEquals(0, registry.idFor("drive/power", 1.0 * i, encoder, 0));
        }

        assertEquals(1, seen.size(), "a topic must be declared exactly once");
        assertEquals(1, registry.size());
    }

    @Test
    void declarationCarriesNameAndObservedType() {
        ConcurrentLinkedQueue<EngramProto.TopicDeclaration> seen = new ConcurrentLinkedQueue<>();
        TopicRegistry registry = new TopicRegistry((d, ts) -> seen.add(d));

        registry.idFor("g1/a", true, encoder, 0);

        EngramProto.TopicDeclaration d = seen.peek();
        assertEquals("g1/a", d.getName());
        assertEquals("java.lang.Boolean", d.getJavaType());
        assertEquals(EngramProto.ValueType.VALUE_TYPE_BOOL, d.getValueType());
    }

    @Test
    void distinctTopicsGetDistinctIdsEvenUnderContention() throws Exception {
        int topicCount = 200;
        int threads = 8;
        int perThread = 50;

        ConcurrentLinkedQueue<EngramProto.TopicDeclaration> seen = new ConcurrentLinkedQueue<>();
        TopicRegistry registry = new TopicRegistry((d, ts) -> seen.add(d));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int threadIndex = t;
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // Every thread hammers the same topic set, so
                        // computeIfAbsent is doing real work.
                        registry.idFor("topic-" + ((threadIndex * perThread + i) % topicCount),
                                1.0, encoder, 0);
                    }
                } catch (Throwable e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers should finish");
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(0, failures.get(), "no worker should fail");

        assertEquals(topicCount, registry.size(), "every topic must be registered exactly once");
        assertEquals(topicCount, seen.size(), "each topic declared exactly once, despite contention");

        // Ids must be a dense 0..n-1 range with no duplicates.
        long distinctIds = seen.stream().map(EngramProto.TopicDeclaration::getTopicId).distinct().count();
        assertEquals(topicCount, distinctIds);
        for (int id = 0; id < topicCount; id++) {
            final int wanted = id;
            assertTrue(seen.stream().anyMatch(d -> d.getTopicId() == wanted), "missing id " + id);
        }
    }

    @Test
    void declarationIsEmittedBeforeThePublishThatTriggeredIt() {
        // Ordering is guaranteed because the same thread that declares the
        // topic is the one that enqueues the first publish.
        ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
        TopicRegistry registry = new TopicRegistry((d, ts) -> order.add("declare:" + d.getName()));

        registry.idFor("x", 1.0, encoder, 0);
        order.add("publish:x");

        assertEquals(List.of("declare:x", "publish:x"), List.copyOf(order));
    }

    @Test
    void differentTopicsDoNotShareAnId() {
        TopicRegistry registry = new TopicRegistry((d, ts) -> { });
        assertNotEquals(registry.idFor("a", 1.0, encoder, 0), registry.idFor("b", 1.0, encoder, 0));
    }
}
