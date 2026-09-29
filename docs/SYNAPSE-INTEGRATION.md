# Synapse Integration

Engram is a **separate project** that depends on Synapse. It cannot change
Synapse internals. This records what that constraint costs, because the answer
shaped the whole design.

## Summary

| Problem | Severity | Resolution |
|---------|----------|------------|
| `bulkRead` sensor publishes bypass any orchestrator wrapper | Real gap | Worked around with `EngramSession#recording(BulkReader)`; a Synapse change would remove the need |
| `SafeOpMode.init()` is `final` | Blocking | Solved without touching Synapse, via a session handle |
| FTC SDK is `.aar`-only and drags in AndroidX | Blocking | Solved by not depending on the SDK at all |
| `hardware` facade is bound before any Engram code runs | Same as #1 | See below |

---

## Problem 1: `bulkRead` escapes any wrapper

### Why it matters

`bulkRead` is the documented way to get sensor data onto the bus:

```java
hw.bulkRead(50, view -> {
    view.publish("motor/intake/amps", intake.getCurrent(CurrentUnit.AMPS));
    view.publish("odom/pose", pose);
});
```

Sensor topics are the highest-value data in a recording. Losing them makes Engram
much less useful.

### Why a decorator cannot capture them

Tracing Synapse 0.4.0:

1. `SafeOpMode.init()` (SafeOpMode.java:75-81), in order:
   - `orchestrator = FtcOrchestrator.create();`
   - `hardware = orchestrator.hardware();` ← **bound here, before user code**
   - `safeMap = new SafeHardwareMap(hardwareMap, hardware);`
   - `onSafeInit();`

2. `OrchestratorImpl.hardware()` (OrchestratorImpl.java:450-451) returns
   `new HardwareActions(this)` — bound to the **concrete** `OrchestratorImpl`,
   not the interface.

3. `HardwareActions` is `public final`, and its field is
   `private final OrchestratorImpl orchestrator` (HardwareActions.java:43). It
   cannot be substituted — not by inheritance (final), not by constructor (takes
   the concrete type).

4. `OrchestratorImpl.scheduleHardwareBulkRead()` (OrchestratorImpl.java:437-438)
   constructs `new HardwareView(this)` — again the real orchestrator.

5. `HardwareView.publish()` (HardwareView.java:30) calls
   `orchestrator.publish(...)` on that real instance.

So reassigning the OpMode's `orchestrator` field to a wrapper does nothing for
sensor traffic. The `hardware` facade was built in `init()` and stored in a
`final` field. **Every `bulkRead` publish escapes recording.**

### What a decorator does capture

- `orchestrator.publish(...)` from `onSafeLoop()` / `onSafeStart()` / etc.
- publishes from any `Node` constructed with the wrapped orchestrator
- `GamepadAdaptor.attach(orchestrator, ...)`

So gamepad and node output are covered; sensor reads are not.

### The workaround

`HardwareView`'s constructor is public and it binds to whichever orchestrator it
is given. So Engram hands the callback a view over the *recording* orchestrator:

```java
public BulkReader recording(BulkReader reader) {
    if (isSensorCaptureAutomatic()) {
        return reader;   // listener hook already covers this
    }
    HardwareView recordingView = new HardwareView(effectiveOrchestrator);
    return view -> reader.read(recordingView);
}
```

Usage:

```java
hardware.bulkRead(50, engram.recording(view -> {
    view.publish("odom/pose", pose);
}));
```

`EngramSessionTest` asserts both halves of this: a view over the original
orchestrator is *not* recorded, and one from `recording(...)` *is*.

### The real fix

One choke point covers everything: `OrchestratorImpl.publish()`
(OrchestratorImpl.java:201). A listener invoked there sees every publish,
including `bulkRead`, because `HardwareView` routes through the same method.

Synapse already has the precedent — `LogSink` is a pluggable extension point on
`Orchestrator`. The proposed addition:

```java
// new: com/aaravlabs/synapse/PublishListener.java

package com.aaravlabs.synapse;

/**
 * Notified synchronously on every publish, before subscriber dispatch.
 * Intended for diagnostics: recording, metrics, tracing. Listeners run on
 * the publishing thread and must not block.
 */
@FunctionalInterface
public interface PublishListener {
    void onPublish(String topicName, Object value, long timestampNanos);
}
```

```java
// Orchestrator.java — default methods keep this source- and
// binary-compatible for every existing implementor.

default void addPublishListener(PublishListener listener) {
    throw new UnsupportedOperationException("publish listeners not supported");
}

default void removePublishListener(PublishListener listener) {
    // no-op
}
```

```java
// OrchestratorImpl.java

private final List<PublishListener> publishListeners = new CopyOnWriteArrayList<>();

@Override public void addPublishListener(PublishListener listener) {
    if (listener != null) publishListeners.add(listener);
}

@Override public void removePublishListener(PublishListener listener) {
    if (listener != null) publishListeners.remove(listener);
}
```

Inserted at the top of `publish()`, right after the `closed` guard and the null
check:

```java
public <T> void publish(String topicName, T value) {
    if (closed) { /* ... */ return; }
    if (value == null) { throw new IllegalArgumentException(...); }

    if (!publishListeners.isEmpty()) {
        long now = System.nanoTime();
        for (PublishListener listener : publishListeners) {
            try {
                listener.onPublish(topicName, value, now);
            } catch (Throwable t) {
                log.error(name, "publish listener threw", t);
            }
        }
    }

    // ... existing type-check / recordLatest / dispatch ...
}
```

Properties of that change:

- Purely additive. `default` methods break no existing implementor.
- One call site, so nothing can route around it.
- `CopyOnWriteArrayList` iteration is lock-free, and the `isEmpty()` guard means
  zero cost when no recorder is attached.
- A throwing listener is caught and logged, so instrumentation can never break
  the robot.
- The timestamp is taken once at the top of `publish()`, so it reflects when the
  publish was requested rather than after dispatch work.

Cost: about 25 lines, no API breakage, no behaviour change when unused.

**Engram is already written against this.** `PublishListenerCapture` resolves
the hook reflectively at attach time, so adding it to Synapse and bumping the
version in `gradle.properties` switches the strategy with no Engram code change
and no team code change.

---

## Problem 2: `SafeOpMode.init()` is `final`

`init()` is `public final void init()` (SafeOpMode.java:75), so a subclass cannot
intercept orchestrator creation. Engram does not need to: the surrounding hooks
are overridable.

`EngramSession` is a plain handle the team wires into their own OpMode:

```java
@Override protected void onSafeInit() {
    engram = EngramSession.start(this, orchestrator);
    orchestrator = engram.orchestrator();
    // ... existing setup ...
}

@Override protected void onSafeStart() { engram.markStart(); }
@Override protected void onSafeStop()  { engram.close(); }
```

This works because `onSafeInit()` is called as the **last** statement of
`init()` (SafeOpMode.java:80) — so recording is attached before any node is
registered or any `bulkRead` is scheduled — and `onSafeStop()` runs **before**
`orchestrator.close()` in `stop()` (SafeOpMode.java:102-103), so the final events
are captured before teardown.

The cost is one extra field and one renamed method in each recorded OpMode. That
was a deliberate trade, and it is what let the recorder drop the FTC SDK
dependency entirely.

---

## Problem 3: the FTC SDK is an `.aar`

The obvious shape — `EngramOpMode extends SafeOpMode` — needs the FTC SDK on
the compile classpath, because `SafeOpMode` extends the SDK's `OpMode`.

`org.firstinspires.ftc:RobotCore` is published to Maven Central as an **`.aar`**,
not a jar, and its POM depends on `androidx.appcompat`, which lives on Google's
Maven repo. A `java-library` module cannot consume an `.aar` without the Android
plugin, and adding that plugin to a build that only needs a compile-time class is
a large cost.

Synapse itself works around this with `libs/ftc-sdk-stub.jar`. Engram avoids the
problem instead: nothing in `engram-recorder` references `OpMode`, `SafeOpMode`,
`HardwareMap`, or any `android.*` type. The recorder's dependency list is
`engram-proto` plus Synapse, and its tests run on a desktop JVM against a real
`Orchestrator`.

If a base class is ever wanted, it can be added in a separate optional module
that carries the SDK dependency, without contaminating the recorder.

---

## Version compatibility

`engram-recorder` compiles against `com.aaravlabs:synapse:0.4.0` and uses only
types present in it. `PublishListenerCapture` is reached entirely through
reflection, so:

| Synapse | Strategy | Coverage |
|---------|----------|----------|
| 0.4.0 (current) | decorator | OpMode hooks, nodes, gamepad. `bulkRead` needs `recording(...)`. |
| any future version with the hook | listener | everything, automatically |

`CaptureStrategies.isPublishListenerAvailable()` reports which case you are in at
runtime, and `EngramSession#strategyName()` says which strategy was chosen.
