package com.aaravlabs.engram.recorder;

import com.aaravlabs.engram.proto.EngramProto;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Assigns dense topic ids and emits each topic's declaration exactly once, on
 * the thread that first publishes to it.
 *
 * <p>Ids are dense and assigned in first-publish order, so they are compact
 * varints on the wire. The declaration is emitted from inside
 * {@code computeIfAbsent}, which guarantees that for any given topic the
 * declaration is queued before the publish that triggered it, even when
 * several threads publish to the same new topic simultaneously.
 *
 * <p>Declarations travel inline rather than in the file header: a header is
 * written before any event exists and so cannot describe topics discovered
 * later, which is what would make the recorder non-streaming.
 */
final class TopicRegistry {

    /** Receives a topic's one and only declaration. */
    interface DeclarationSink {
        void emit(EngramProto.TopicDeclaration declaration, long timestampNanos);
    }

    private final Map<String, Integer> ids = new ConcurrentHashMap<>();
    private final List<EngramProto.TopicDeclaration> declarations = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger();
    private final DeclarationSink sink;

    TopicRegistry(DeclarationSink sink) {
        this.sink = sink;
    }

    /**
     * Returns the id for {@code name}, declaring it on first sight.
     *
     * @param firstValue the value that first triggered this topic; used only to
     *                   derive the declared type
     * @param timestampNanos timestamp to stamp the declaration with, so it
     *                       shares the publish that follows it
     */
    int idFor(String name, Object firstValue, ValueEncoder encoder, long timestampNanos) {
        Integer known = ids.get(name);
        if (known != null) {
            return known;
        }
        return ids.computeIfAbsent(name, topicName -> {
            ValueEncoder.Encoded encoded = encoder.encode(firstValue);
            int id = nextId.getAndIncrement();
            EngramProto.TopicDeclaration declaration = EngramProto.TopicDeclaration.newBuilder()
                    .setTopicId(id)
                    .setName(topicName)
                    .setJavaType(encoded.javaType())
                    .setValueType(encoded.valueType())
                    .build();
            declarations.add(declaration);
            sink.emit(declaration, timestampNanos);
            return id;
        });
    }

    /** Number of distinct topics seen. */
    int size() {
        return ids.size();
    }

    Collection<EngramProto.TopicDeclaration> declarations() {
        return Collections.unmodifiableList(declarations);
    }
}
