package com.aaravlabs.engram.recorder;

import com.aaravlabs.synapse.Orchestrator;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Captures publishes through Synapse's {@code PublishListener} hook.
 *
 * <p>Synapse 0.4.0 does not expose that hook, so it is reached reflectively:
 * the interface and the two registration methods are looked up on the live
 * orchestrator's class loader and a {@link Proxy} is created. Nothing here
 * references {@code PublishListener} at compile time, which is what lets a
 * single Engram build work against every Synapse version -- older ones fall
 * back to {@link DecoratorCapture}, newer ones get complete coverage
 * including {@code bulkRead} sensor publishes.
 *
 * <p>Reflection failures are expected, not exceptional: every one of them
 * returns null from {@link #tryAttach} so the caller can fall back.
 */
final class PublishListenerCapture implements CaptureStrategy {

    private static final String LISTENER_CLASS_NAME = "com.aaravlabs.synapse.PublishListener";
    private static final String ADD_METHOD_NAME = "addPublishListener";
    private static final String REMOVE_METHOD_NAME = "removePublishListener";

    private final Orchestrator target;
    private final Object proxy;
    private final Method removeMethod;

    private PublishListenerCapture(Orchestrator target, Object proxy, Method removeMethod) {
        this.target = target;
        this.proxy = proxy;
        this.removeMethod = removeMethod;
    }

    /** Whether this Synapse build exposes the publish-listener hook. */
    static boolean isAvailable() {
        return findListenerClass() != null;
    }

    private static Class<?> findListenerClass() {
        try {
            return Class.forName(LISTENER_CLASS_NAME, false, PublishListenerCapture.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /**
     * Attaches {@code recorder} to {@code target}, or returns null if this
     * Synapse build cannot support it.
     */
    static PublishListenerCapture tryAttach(Orchestrator target, Recorder recorder) {
        Class<?> listenerClass = findListenerClass();
        if (listenerClass == null) {
            return null;
        }
        try {
            Method addMethod = target.getClass().getMethod(ADD_METHOD_NAME, listenerClass);
            Method removeMethod;
            try {
                removeMethod = target.getClass().getMethod(REMOVE_METHOD_NAME, listenerClass);
            } catch (NoSuchMethodException e) {
                // Optional: without it we simply cannot detach, and the
                // session's close path tolerates that.
                removeMethod = null;
            }

            Object proxy = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    new PublishInvocationHandler(recorder));

            addMethod.invoke(target, proxy);
            return new PublishListenerCapture(target, proxy, removeMethod);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException
                 | RuntimeException | LinkageError e) {
            // Includes the case where the method exists but is a default that
            // throws UnsupportedOperationException.
            return null;
        }
    }

    @Override
    public Orchestrator orchestrator() {
        return target;
    }

    @Override
    public void detach() {
        if (removeMethod == null) {
            return;
        }
        try {
            removeMethod.invoke(target, proxy);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            // Detaching is best effort; a failure here cannot affect the run.
        }
    }

    @Override
    public String name() {
        return "publish-listener";
    }

    /** Bridges the reflective {@code onPublish} call back into the recorder. */
    private static final class PublishInvocationHandler implements InvocationHandler {

        private final Recorder recorder;

        PublishInvocationHandler(Recorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "onPublish":
                    if (args != null && args.length == 3 && args[0] instanceof String && args[2] instanceof Number) {
                        recorder.onPublish((String) args[0], args[1], ((Number) args[2]).longValue());
                    }
                    return null;
                case "toString":
                    return "EngramPublishListener[" + recorder.file() + ']';
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == (args == null ? null : args[0]);
                default:
                    return defaultReturn(method.getReturnType());
            }
        }

        /**
         * A zero of the right type, so an unexpected future method on the
         * listener interface cannot throw back into Synapse's publish path.
         */
        private static Object defaultReturn(Class<?> type) {
            if (type == void.class || !type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) return Boolean.FALSE;
            if (type == char.class) return (char) 0;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            if (type == double.class) return 0d;
            return null;
        }
    }
}
