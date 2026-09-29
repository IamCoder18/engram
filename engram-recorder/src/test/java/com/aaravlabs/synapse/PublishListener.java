package com.aaravlabs.synapse;

/**
 * Test-only stand-in for the hook Engram expects Synapse to add.
 *
 * <p>This mirrors the signature proposed in
 * {@code docs/SYNAPSE-INTEGRATION.md}. It exists so the reflective attach path
 * in {@code PublishListenerCapture} can be tested against a Synapse that
 * <em>does</em> expose the hook -- otherwise that path never executes, because
 * the Synapse build under test does not have it.
 *
 * <p>Declaring it here is sound: {@code PublishListenerCapture} reaches it only
 * through {@code Class.forName} and a {@link java.lang.reflect.Proxy}, so what
 * is under test is the proxy's argument handling and object-method behaviour,
 * not the interface's declaration. When real Synapse ships this type it shadows
 * this one on the test classpath with an identical signature, so the tests keep
 * testing what they are meant to.
 */
@FunctionalInterface
public interface PublishListener {

    /**
     * Called synchronously inside {@link Orchestrator#publish}, before any
     * subscriber dispatch.
     *
     * @param topicName      the topic published to
     * @param value          the published value
     * @param timestampNanos a {@link System#nanoTime()} reading
     */
    void onPublish(String topicName, Object value, long timestampNanos);
}
