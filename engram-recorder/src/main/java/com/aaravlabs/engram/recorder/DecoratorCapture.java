package com.aaravlabs.engram.recorder;

import com.aaravlabs.synapse.Orchestrator;

/**
 * Captures publishes by wrapping the orchestrator.
 *
 * <p>The fallback for Synapse builds without the {@code PublishListener} hook.
 * Because the team must adopt the wrapper for its own publishes, this strategy
 * is only as good as that adoption -- see {@link RecordingOrchestrator} for the
 * {@code bulkRead} gap.
 */
final class DecoratorCapture implements CaptureStrategy {

    private final RecordingOrchestrator wrapper;
    private final Orchestrator target;

    private DecoratorCapture(RecordingOrchestrator wrapper, Orchestrator target) {
        this.wrapper = wrapper;
        this.target = target;
    }

    static DecoratorCapture attach(Orchestrator target, Recorder recorder) {
        return new DecoratorCapture(new RecordingOrchestrator(target, recorder), target);
    }

    @Override
    public Orchestrator orchestrator() {
        return wrapper;
    }

    @Override
    public void detach() {
        // Nothing to undo: the wrapper is inert once the session is closed,
        // and the team holds a direct reference to the real orchestrator.
    }

    @Override
    public String name() {
        return "decorator";
    }

    Orchestrator target() {
        return target;
    }
}
