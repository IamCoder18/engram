package com.aaravlabs.engram.recorder;

import com.aaravlabs.synapse.Node;
import com.aaravlabs.synapse.Orchestrator;
import com.aaravlabs.synapse.PublishListener;
import com.aaravlabs.synapse.Subscription;
import com.aaravlabs.synapse.Topic;
import com.aaravlabs.synapse.ftc.HardwareActions;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

/**
 * An orchestrator that also exposes Synapse's proposed publish-listener hook,
 * standing in for a future Synapse build.
 *
 * <p>This is a real class rather than a dynamic proxy on purpose.
 * {@code PublishListenerCapture} resolves the hook with
 * {@code target.getClass().getMethod("addPublishListener", PublishListener.class)},
 * and a {@link java.lang.reflect.Proxy} only exposes methods declared by the
 * interfaces it implements -- {@code Orchestrator} does not declare the hook --
 * so a proxied stand-in would silently fail the lookup and quietly exercise the
 * decorator path instead. Declaring the methods here reproduces production
 * resolution exactly.
 *
 * <p>Every {@link Orchestrator} method delegates to a real instance, so the
 * capture strategy is attached to a genuinely functional bus.
 */
final class HookOrchestrator implements Orchestrator {

    private final Orchestrator delegate = Orchestrator.create("hook-orchestrator");
    final List<PublishListener> listeners = new CopyOnWriteArrayList<>();

    /** Publishes delivered while at least one listener was attached. */
    int publishCount;

    // ---- the hook under test --------------------------------------------

    /** Not part of {@link Orchestrator} yet; present to prove the lookup works. */
    public void addPublishListener(PublishListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Not part of {@link Orchestrator} yet. */
    public void removePublishListener(PublishListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /** Fires the hook the way a hook-aware Synapse would. */
    void fire(String topic, Object value) {
        fireAt(topic, value, System.nanoTime());
    }

    void fireAt(String topic, Object value, long timestampNanos) {
        if (!listeners.isEmpty()) {
            publishCount++;
        }
        for (PublishListener listener : listeners) {
            listener.onPublish(topic, value, timestampNanos);
        }
    }

    // ---- Orchestrator: real behaviour, delegated ------------------------

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public <T> Topic<T> getOrCreateTopic(String name, Class<T> type) {
        return delegate.getOrCreateTopic(name, type);
    }

    @Override
    public Optional<Topic<?>> findTopic(String name) {
        return delegate.findTopic(name);
    }

    @Override
    public <T> Optional<Topic<T>> findTopic(String name, Class<T> type) {
        return delegate.findTopic(name, type);
    }

    @Override
    public <T> void publish(String name, T value) {
        fireAt(name, value, System.nanoTime());
        delegate.publish(name, value);
    }

    @Override
    public <T> Subscription subscribe(String name, Class<T> type, Consumer<? super T> handler) {
        return delegate.subscribe(name, type, handler);
    }

    @Override
    public <T> Optional<T> getLatestValue(String name, Class<T> type) {
        return delegate.getLatestValue(name, type);
    }

    @Override
    public Node registerNode(String name, Node node) {
        return delegate.registerNode(name, node);
    }

    @Override
    public void unregisterNode(String name) {
        delegate.unregisterNode(name);
    }

    @Override
    public Optional<Node> findNode(String name) {
        return delegate.findNode(name);
    }

    @Override
    public CompletableFuture<Void> runAction(String actionName) {
        return delegate.runAction(actionName);
    }

    @Override
    public void cancelAllActions() {
        delegate.cancelAllActions();
    }

    @Override
    public ScheduledFuture<?> runPeriodically(Runnable task, int hz) {
        return delegate.runPeriodically(task, hz);
    }

    @Override
    public void runOnHardwareThread(Runnable task) {
        delegate.runOnHardwareThread(task);
    }

    @Override
    public HardwareActions hardware() {
        return delegate.hardware();
    }

    @Override
    public void log(String message) {
        delegate.log(message);
    }

    @Override
    public void log(String tag, String message) {
        delegate.log(tag, message);
    }

    @Override
    public void warn(String message) {
        delegate.warn(message);
    }

    @Override
    public void error(String message) {
        delegate.error(message);
    }

    @Override
    public void error(String message, Throwable t) {
        delegate.error(message, t);
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
