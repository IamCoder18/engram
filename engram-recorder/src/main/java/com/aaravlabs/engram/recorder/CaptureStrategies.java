package com.aaravlabs.engram.recorder;

import com.aaravlabs.synapse.Orchestrator;

/**
 * Chooses how to attach a recorder to an orchestrator.
 *
 * <p>Prefers the {@link PublishListenerCapture} hook, which sees every publish
 * including sensor reads, and falls back to {@link DecoratorCapture} on Synapse
 * builds that predate it.
 */
public final class CaptureStrategies {

    private CaptureStrategies() {
    }

    /**
     * Attaches {@code recorder} to {@code target} using the best strategy this
     * Synapse build supports.
     *
     * @return the chosen strategy; never null
     */
    public static CaptureStrategy attach(Orchestrator target, Recorder recorder) {
        return attach(target, recorder, null);
    }

    /**
     * Attaches {@code recorder}, optionally forcing a specific strategy.
     *
     * @param force {@code "decorator"} or {@code "publish-listener"} to pin a
     *             strategy, or null to auto-select. Forcing is intended for
     *             tests and for diagnosing which strategy is in effect.
     * @throws IllegalArgumentException if {@code force} names no known strategy
     * @throws IllegalStateException if a forced strategy cannot be used
     */
    public static CaptureStrategy attach(Orchestrator target, Recorder recorder, String force) {
        if (force == null || force.isEmpty() || "auto".equalsIgnoreCase(force)) {
            PublishListenerCapture listener = PublishListenerCapture.tryAttach(target, recorder);
            if (listener != null) {
                return listener;
            }
            return DecoratorCapture.attach(target, recorder);
        }
        if ("decorator".equalsIgnoreCase(force)) {
            return DecoratorCapture.attach(target, recorder);
        }
        if ("publish-listener".equalsIgnoreCase(force)) {
            PublishListenerCapture listener = PublishListenerCapture.tryAttach(target, recorder);
            if (listener == null) {
                throw new IllegalStateException(
                        "the 'publish-listener' strategy was forced but this Synapse build does not"
                                + " expose com.aaravlabs.synapse.PublishListener");
            }
            return listener;
        }
        throw new IllegalArgumentException("unknown capture strategy: '" + force
                + "' (expected 'auto', 'decorator', or 'publish-listener')");
    }

    /**
     * Whether the running Synapse build exposes the publish-listener hook, and
     * therefore whether {@code bulkRead} sensor publishes are captured without
     * extra wiring.
     */
    public static boolean isPublishListenerAvailable() {
        return PublishListenerCapture.isAvailable();
    }
}
