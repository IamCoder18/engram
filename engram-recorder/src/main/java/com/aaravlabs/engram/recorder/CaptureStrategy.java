package com.aaravlabs.engram.recorder;

import com.aaravlabs.synapse.Orchestrator;

/**
 * How a recorder gets attached to a live orchestrator.
 *
 * <p>The strategy also decides whether the orchestrator the team keeps using
 * must be replaced: {@link #orchestrator()} returns the instance that should be
 * used from then on, which is the original for the listener strategy and a
 * wrapper for the decorator strategy.
 */
public interface CaptureStrategy {

    /** The orchestrator to use from now on. May be a wrapper. */
    Orchestrator orchestrator();

    /**
     * Detaches from the orchestrator. Called by
     * {@link EngramSession#close()} before the recorder is closed, so no
     * further publishes are recorded after the final lifecycle event.
     */
    void detach();

    /** Short name for logs and {@link EngramSession#strategyName()}. */
    String name();
}
