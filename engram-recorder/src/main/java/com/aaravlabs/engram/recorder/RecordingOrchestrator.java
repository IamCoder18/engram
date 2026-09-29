package com.aaravlabs.engram.recorder;

import com.aaravlabs.synapse.Node;
import com.aaravlabs.synapse.Orchestrator;
import com.aaravlabs.synapse.Subscription;
import com.aaravlabs.synapse.Topic;
import com.aaravlabs.synapse.ftc.HardwareActions;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

/**
 * An {@link Orchestrator} that records every publish before forwarding it.
 *
 * <p>Used only when the running Synapse build has no
 * {@code PublishListener} hook. The team must use the instance returned by
 * {@link #orchestrator()} for anything it publishes itself, and must register
 * nodes built with it, or those publishes will not be recorded.
 *
 * <p><b>Known gap.</b> {@code HardwareActions.bulkRead} callbacks publish
 * through a {@code HardwareView} that Synapse binds to the real orchestrator
 * inside {@code SafeOpMode.init()}, before any Engram code runs. Those sensor
 * publishes are not visible here. {@link EngramSession#recording} exists to
 * close that gap, and the listener strategy does not have it at all.
 */
final class RecordingOrchestrator implements Orchestrator {

    private final Orchestrator delegate;
    private final Recorder recorder;

    RecordingOrchestrator(Orchestrator delegate, Recorder recorder) {
        this.delegate = delegate;
        this.recorder = recorder;
    }

    @Override
    public <T> void publish(String name, T value) {
        // Record first: the timestamp should precede any subscriber's
        // observation of the value, and it is taken on the publishing thread
        // either way, but this ordering makes that explicit.
        recorder.onPublish(name, value, System.nanoTime());
        delegate.publish(name, value);
    }

    // ---- everything below is a straight pass-through ---------------------

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
