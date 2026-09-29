# Architecture

## Overview

Engram records Synapse topic traffic on the robot and replays it on a desktop.

Engram is a **separate project** that depends on Synapse; Synapse knows nothing
about it. That constraint shapes most of the design, so it comes first:
[SYNAPSE-INTEGRATION.md](SYNAPSE-INTEGRATION.md) documents what it costs and
the one change that removes the resulting gap.

### Goals

- **Record** every publish during a run, with microsecond timing.
- **Replay** on a desktop with no Synapse, no FTC SDK, and no Android.
- **Analyze** topic values over time.
- **Future:** feed recordings into a web visualizer.

### Non-goals

- Replaying against robot hardware.
- Executing OpMode or Node code during replay.
- Hardware simulation.

---

## System

```
┌────────────────────────────────────────────────────────────┐
│                    FTC Robot (Android)                      │
│                                                            │
│  ┌──────────────┐  onSafeInit  ┌──────────────────────┐    │
│  │ SafeOpMode   │─────────────▶│    EngramSession      │    │
│  │ init() final │              │   recorder + strategy │    │
│  └──────┬───────┘              └──────────┬───────────┘    │
│         │ creates                          │                │
│         ▼                                  │                │
│  ┌──────────────────┐  attach              │                │
│  │   Orchestrator   │◀─────────────────────┘                │
│  │  (OrchestratorImpl)                                      │
│  └────────┬─────────┘                                       │
│           │  publish()  ← the single choke point           │
│           ▼                                                 │
│  ┌──────────────────┐   queue   ┌──────────────────────┐   │
│  │ Recorder         │──────────▶│  EventWriter (daemon) │   │
│  │  encode + buffer │           │  drain every 100ms   │   │
│  └──────────────────┘           └──────────┬───────────┘   │
│                                           ▼               │
│                                    match.engram             │
└────────────────────────────────────────────────────────────┘

              │ copy off the robot
              ▼

┌────────────────────────────────────────────────────────────┐
│                       Desktop                               │
│                                                            │
│   .engram ─▶ EngramRecordingReader ─▶ EngramRecording      │
│             (gzip + truncation)      (indexed, queryable)  │
│                                          │                 │
│                          ┌───────────────┼───────────────┐ │
│                          ▼               ▼               ▼ │
│                     JsonExporter   NdjsonExporter   CsvExporter
└────────────────────────────────────────────────────────────┘
```

---

## Recording

### Capture strategies

A recorder is attached to a live orchestrator by a `CaptureStrategy`, chosen
from what the running Synapse build supports.

**`PublishListenerCapture`** — the good path. Synapse's
`Orchestrator.addPublishListener` hook is looked up reflectively and a
`java.lang.reflect.Proxy` implementing it is installed. Because the hook fires
inside `OrchestratorImpl.publish()`, it sees *every* publish, including the
`bulkRead` sensor path that no wrapper can intercept.

The reflection is the point, not an accident. Nothing in Engram references
`PublishListener` at compile time, so a single build works against Synapse
versions on both sides of that hook existing, and switches automatically when
Synapse gains it.

**`DecoratorCapture`** — the fallback for Synapse 0.4.0. `RecordingOrchestrator`
implements `Orchestrator`, records in `publish`, and delegates everything else.
It has one real gap, documented in
[SYNAPSE-INTEGRATION.md](SYNAPSE-INTEGRATION.md) and worked around by
`EngramSession#recording(BulkReader)`.

### The hot path

`onPublish` runs on whatever thread is publishing — the OpMode loop thread, the
Synapse hardware thread, or a callback-pool thread — and does:

1. resolve or create the topic id (`TopicRegistry`)
2. encode the value (`ValueEncoder`)
3. length-delimit and serialize the event (`ProtoFraming`)
4. append the bytes to a `ConcurrentLinkedQueue`

No locks on the hot path after warm-up, no I/O, no blocking. The cost is
protobuf encoding plus a queue append, on the order of microseconds.

### Buffering

`EventWriter` owns the queue and a single daemon thread.

| Trigger | Behaviour |
|---------|-----------|
| 100 ms elapsed | drain |
| 500 events queued | drain immediately |
| `close()` | drain everything, write `LIFECYCLE_STOP`, close the stream |

Only the transition that reaches exactly 500 events notifies the writer, so a
busy loop costs one uncontended lock per batch rather than per event. The writer
drains the whole queue when it wakes, so growth past the threshold needs no
further signalling and there is no missed-wakeup window.

A failed stream latches the first exception, discards the queue, and exits. Later
publishes are dropped cheaply rather than retried into a broken stream; the
failure surfaces through `RecorderStats#failureMessage()`.

### Topic registry

Ids are dense and assigned in first-publish order, so they are compact varints
on the wire. `ConcurrentHashMap.computeIfAbsent` does the assignment, and the
`TopicDeclaration` is emitted from inside it — which guarantees that for any
topic, the declaration is queued before the publish that triggered it, even when
several threads publish to the same new topic at once.

### Failure policy

Nothing in the recorder throws into robot control flow.

| Situation | Behaviour |
|-----------|-----------|
| Value has no encoding | recorded as empty bytes, counted in `unencodableValues` |
| Codec throws | skipped, warned once, next codec tried |
| Encoding throws unexpectedly | publish counted as dropped, warned, run continues |
| Stream write fails | latched; further publishes dropped; reported in `stats()` |
| `close()` fails | latched, never thrown |

Recording is diagnostics. A bad recording must not cost a match.

---

## Replay

`EngramRecordingReader` parses a file into an immutable `EngramRecording`, which
decodes every event up front so range and point-in-time queries are a binary
search rather than a scan — a full match is roughly 30k samples.

**Gzip** is detected from the leading magic bytes and decompressed transparently.

**Truncation** is expected, not exceptional. A robot process that dies before its
final flush leaves a file ending mid-message. Parsing stops at the last complete
event, sets `isTruncated()`, and everything before it stays queryable —
diagnosing a crashed run is exactly when the partial data is most valuable.

**Ordering.** Events are stored in file order. Because several threads publish
concurrently, timestamps can be very slightly out of order across topics; that
is faithful to the run and is not corrected. Per-topic series are sorted so
range queries behave predictably, with a stable sort so equal timestamps keep
file order.

---

## Module layout

```
engram/
├── proto/engram_recording.proto     # the wire format, compiled once
│
├── engram-proto/                    # generated message classes
│
├── engram-recorder/                 # robot side
│   ├── EngramSession                # user-facing handle
│   ├── Recorder                     # the writer
│   ├── EventWriter                  # queue + background flusher
│   ├── ProtoFraming                 # length-delimited framing
│   ├── TopicRegistry                # id assignment + declarations
│   ├── ValueEncoder / ValueCodec    # value encoding
│   ├── CaptureStrategy              # attach abstraction
│   ├── PublishListenerCapture       # reflective listener hook
│   ├── DecoratorCapture             # decorator fallback
│   ├── RecordingOrchestrator        # the decorator
│   ├── OutputLocation               # writable path on the RC
│   ├── RecorderConfig / Stats / Log
│   └── annotation/Recorded
│
├── engram-replay/                   # desktop side
│   ├── EngramRecordingReader        # parse (+ gzip, truncation)
│   ├── EngramRecording              # query API
│   ├── TopicInfo / Sample / TopicStats
│   ├── Values                       # decoding
│   ├── export/                      # JSON, NDJSON, CSV
│   └── cli/Main                     # the engram command
│
└── docs/
```

Dependency rules, enforced by the build:

- `engram-recorder` → `engram-proto` + Synapse *(compile-only)*
- `engram-replay` → `engram-proto` only
- neither references the FTC SDK or any `android.*` type

`engram-recorder` has **no FTC SDK dependency**, which is why the integration is
a session handle wired into the team's own OpMode rather than an
`EngramOpMode extends SafeOpMode` base class. That base class would force
`org.firstinspires.ftc:RobotCore`, published only as an `.aar` and pulling in
AndroidX. Avoiding it is what lets the recorder build and its tests run on a
plain desktop JVM.

---

## Testing

226 tests. The structure is deliberate:

- **`ProtoFramingTest`** pins the hand-rolled length-delimited framing against
  protobuf's own encoder, across every varint width boundary. The recorder
  frames messages itself so the hot path stays allocation-light, and this test
  is what makes that safe.
- **`TopicRegistryTest` and `EventWriterTest`** hammer both from many threads,
  asserting no event is lost, duplicated, or reordered within a producer.
- **`RecordingOrchestratorTest`** covers the decorator's entire pass-through
  surface against a real Synapse orchestrator. A missing override would only
  surface when Synapse next adds a method.
- **`RoundTripTest`** records with the production writer and reads back with the
  production reader, driving real `Node` lifecycle and a real publish loop. This
  is the test that proves the two halves of the format agree.
- **`PublishListenerCaptureTest`** covers the reflective attach path by putting
  a stub `com.aaravlabs.synapse.PublishListener` on the test classpath and
  attaching to a real class that declares `addPublishListener`. Without the
  stub that path never executes, because the Synapse build under test does not
  have the hook -- which would leave the code that will run on the robot once
  Synapse ships it entirely untested. A dynamic proxy is deliberately *not* used
  as the stand-in: a proxy only exposes methods its interfaces declare, so it
  would silently fail the lookup and quietly exercise the decorator instead.
- **`NoAndroidApiLeakTest`** scans the compiled recorder's constant pools for
  `java/time/` and `java/nio/file/`, which are Android API 26 while the FTC SDK
  declares `minSdkVersion=24`. Using either would throw
  `NoClassDefFoundError` on an API 24/25 Robot Controller — a failure no desktop
  test would catch, and a bad one to meet at a competition. The recorder is
  therefore built on `java.io.File` and `java.text.SimpleDateFormat`, and this
  test fails the build if that regresses.
- **`engram-replay`'s tests** construct protobuf messages directly and never use
  the recorder, because `engram-replay` does not depend on it. That way a
  matching mistake in the writer and the reader cannot cancel out.

---

## Performance

| Path | Cost |
|------|------|
| Publish, topic already declared | encode + queue append, single-digit µs |
| Publish, first sighting of a topic | one extra encode for the declaration |
| Publish, no recorder attached | one `isEmpty()` check |
| Background writer | file write every 100 ms |
| Replay, full match | parse ~1 MB, index ~30k samples, well under a second |

Measured on the demo fixture: 781 publishes across 4 topics in 2 s produced a
14 KB file, 7 KB gzipped, with a 36 KB recorder jar.

The strategy selector is tested in both directions, which matters because
selection has to key off the orchestrator's actual capability rather than the
mere presence of the interface: a classpath where `PublishListener` exists but
the orchestrator lacks `addPublishListener` must still fall back to the
decorator rather than silently recording nothing.
