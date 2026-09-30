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

### 5. A topic the capture path never saw cannot be detected from the file

Nothing in a recording records which topics *should* have existed. A `bulkRead`
topic that was never observed (see limitation 1) leaves no declaration, no
publish, and no counter — a file that is perfectly well formed and perfectly
incomplete.

**What Engram does:** `engram inspect` reports everything the format genuinely
supports (`unfinalized`, `truncated`, `declared-never-published`,
`undeclared-topic`, `missing-init`, `no-topics`) and takes caller-supplied
expectations through `--expect-topic`, so a missing topic is reported when the
caller says it should be there.

**What it cannot do:** infer the expectation. Closing this properly needs a
format change — a manifest of topics the OpMode *intended* to publish, written
before the run — which is additive and therefore compatible, but is a wire-format
decision rather than a tool change. Unimplemented.

### 6. The whole recording is held in memory when read

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

Partly answered from the file now: `engram inspect` reports whether a recording
is complete, and `--expect-topic` catches the topics the decorator strategy
cannot see on Synapse 0.4.0. What it still cannot report is *why* a topic is
absent, because the file does not record what was meant to be published — see
limitation 5.

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

**Implemented.** `RetentionPolicy` bounds the output directory by maximum total
bytes and maximum recording count, keeps a floor of recordings, never deletes
the one being written, and runs off the publishing path. Off by default; see
[USAGE.md](USAGE.md#keeping-the-directory-under-control).

Open question it did not settle: a **maximum age** was considered and rejected,
because `File.lastModified()` on the RC's FAT32 card is the only clock available
and a wall-clock policy is the one most likely to delete the recording from
today's practice. If teams ask for "delete anything older than a week", the
format's own `startEpochMs` in the header would be a better clock than the
filesystem, and that means reading each candidate's header — cheap for a few
dozen files, and the thing to add first.

### Web visualizer data path

Deferred until the visualizer is built. JSON export already produces the right
shape, and now carries a `capture` block with the completeness verdict so the
front end does not have to reimplement it. The alternative is serving protobuf
directly via `protobuf.js`, which avoids a conversion step but adds browser-side
tooling. JSON is the safe default in the meantime.

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
