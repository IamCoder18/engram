# Open Questions and Known Limitations

## Known limitations

These are real, understood, and documented rather than hidden.

### 1. `bulkRead` sensor publishes need an explicit wrapper on Synapse 0.4.0

`RecordingOrchestrator` cannot see publishes made by `bulkRead` callbacks,
because Synapse binds `HardwareView` to the concrete orchestrator inside
`SafeOpMode.init()`, before Engram runs, and `HardwareActions` is `final`.

**Impact:** sensor data is missing from decorator-strategy recordings unless
wrapped.

**Workaround:** `hardware.bulkRead(50, engram.recording(reader))`. One line per
call site; a no-op once Synapse exposes the publish-listener hook.

**Fix:** the ~25-line Synapse change in
[SYNAPSE-INTEGRATION.md](SYNAPSE-INTEGRATION.md). Engram is already written to
pick it up automatically.

### 2. Up to 100 ms of tail events are lost on abnormal termination

Events buffered since the last flush are gone if the process dies without
running `close()`. Normal `stop()` always flushes.

**Is that acceptable?** It depends on the failure mode being investigated:

- *Behaved wrong but completed* — no loss, the run finished normally.
- *Robot crashed, watchdog reset, app killed* — up to 100 ms is lost, which is
  usually where the interesting events are.

A write-through design would be crash-proof but would put serialization and I/O
on the publishing thread, which the project's non-negotiables rule out. A
middle ground is flushing the whole buffer (rather than only the tail) at each
lifecycle event. Cheap to add; deferred until someone confirms they need it.

### 3. Non-primitive values need a registered codec

Anything outside the built-in mappings is recorded with empty bytes and counted
in `RecorderStats#unencodableValues()`. The topic still appears with its name,
type, and timing.

This is a deliberate default: guessing an encoding for an arbitrary object risks
silently recording something wrong, whereas a visible counter points straight at
the gap. `ValueCodec` is the extension point.

### 4. Topic ids are per-file

Ids are assigned in first-publish order within one recording. There is no
cross-file identity, so correlating an autonomous recording with a teleop one
means matching on topic *name*. Fine for the current use; a shared id space
would be needed for a cross-run index.

### 5. The whole recording is held in memory when read

A few megabytes for a full match, which is nothing on a desktop. A 30-minute
autonomous test session would be a few hundred megabytes. Streaming queries
would need a different reader; not currently a problem.

---

## Open questions

### Which strategy is active on a given robot?

Answered at runtime:

```java
engram.strategyName();                // "publish-listener" or "decorator"
CaptureStrategies.isPublishListenerAvailable();
engram.isSensorCaptureAutomatic();
```

The CLI could surface this too — `engram inspect` could report whether sensor
publishes are complete. **Worth adding**, since a silently incomplete recording
is the worst failure mode here.

### Should `@Recorded` exist at all?

`EngramSession.start(...)` already enables recording explicitly, which makes the
annotation largely redundant. It currently carries `label`,
`flushIntervalMs`, `maxEventsPerFlush`, and `strategy`.

**Options:** keep it as declarative configuration (current), or drop it and keep
the API explicit. Leaning towards keeping it — it makes the intent visible in the
OpMode's own source, and mirrors how Synapse declares `@RunPeriodically`.

### Should a crashed recording be recoverable mid-run?

A `LIFECYCLE_STOP`-less file is readable, but a crashed one is only obvious after
the fact. A `LIFECYCLE_HEARTBEAT` event every few seconds would let the replay
tool distinguish "still running" from "died silently" from a live tail. Cheap, and
arguably useful — **unimplemented**.

### Retaining old recordings

A season of practice sessions will fill the RC's storage. `OutputLocation` does
not prune. A cap on the newest N files, or a size budget, would be a sensible
addition. **Unimplemented.**

### Web visualizer data path

Deferred until the visualizer is built. JSON export already produces the right
shape. The alternative is serving protobuf directly via `protobuf.js`, which
avoids a conversion step but adds browser-side tooling. JSON is the safe default
in the meantime.

### Sampling and volume control

Everything is recorded. At 60 Hz per gamepad a match is ~1 MB, which is fine. For
long sessions with high-rate topics, per-topic rate limits or "record only
changed values" would bound growth. **Unimplemented; not currently needed.**

---

## Deliberately not doing

- **Replaying OpMode or Node code.** Replay is data analysis, not simulation.
  Re-executing robot logic against recorded sensor data would need hardware
  mocking and determinism guarantees that would cost far more than the value.
- **A real-time dashboard on the robot.** The RC's CPU and storage are better
  spent on the match. Recording is cheap; analysis happens afterwards on a
  laptop.
- **Sampling at record time by default.** Silent data loss is worse than a large
  file.
