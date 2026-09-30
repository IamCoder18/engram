# Usage

How to record a run and read it back.

## Contents

- [Recording a run](#recording-a-run)
- [Sensor publishes on Synapse 0.4.0](#sensor-publishes-on-synapse-040)
- [Where recordings go](#where-recordings-go)
- [Keeping the directory under control](#keeping-the-directory-under-control)
- [Custom value types](#custom-value-types)
- [Reading a recording](#reading-a-recording)
- [Checking a recording is complete](#checking-a-recording-is-complete)
- [Command line](#command-line)
- [Exporting for other tools](#exporting-for-other-tools)
- [Compressing a recording](#compressing-a-recording)
- [Recording without SafeOpMode](#recording-without-safeopmode)
- [API reference](#api-reference)

---

## Recording a run

Add Engram to your TeamCode build:

```groovy
dependencies {
    implementation 'com.aaravlabs:synapse:0.4.0'
    implementation 'com.aaravlabs:engram-recorder:0.1.0'
}
```

Then wire a session into your OpMode:

```java
import com.aaravlabs.engram.recorder.EngramSession;

public class MyTeleOp extends SafeOpMode {

    private EngramSession engram;

    @Override protected void onSafeInit() {
        // Start recording. Pass `this` so the OpMode class name and, on a
        // device, an Android Context can be found.
        engram = EngramSession.start(this, orchestrator);

        // From here on, use the orchestrator Engram hands back. This is the
        // same instance on Synapse builds that expose the publish-listener
        // hook, and a recording wrapper otherwise — assigning it
        // unconditionally means a Synapse upgrade needs no code change.
        orchestrator = engram.orchestrator();

        SafeDevice<DcMotorEx> intake = safeMap.device(DcMotorEx.class, "intake");
        orchestrator.registerNode("intake", new IntakeNode(orchestrator, intake));
    }

    @Override protected void onSafeStart() {
        engram.markStart();
    }

    @Override protected void onSafeStop() {
        engram.close();   // writes LIFECYCLE_STOP and flushes to disk
    }
}
```

`onSafeStop()` runs before `SafeOpMode.stop()` closes the orchestrator, so the
final events are captured before teardown. `close()` is idempotent and never
throws — a storage failure is reported through `engram.stats()`, not an
exception in your shutdown path.

Engram does **not** close the orchestrator. It does not own it, and closing it
early would break your shutdown ordering.

### Configurable with an annotation

Instead of passing a configuration in code, annotate the OpMode:

```java
@Recorded(label = "TeleOp Drive", flushIntervalMs = 50)
@TeleOp(name = "TeleOp Drive")
public class MyTeleOp extends SafeOpMode { ... }
```

`EngramSession.start(this, orchestrator)` picks the annotation up
automatically. Passing an explicit `RecorderConfig` overrides it.

### Checking what was recorded

```java
RecorderStats stats = engram.stats();
if (!stats.isHealthy()) {
    telemetry.addLine("engram: " + stats.failureMessage());
}
if (stats.unencodableValues() > 0) {
    telemetry.addLine("engram: " + stats.unencodableValues() + " value(s) had no codec");
}
```

A non-zero `unencodableValues()` means a topic was carrying a type Engram had no
encoding for. See [Custom value types](#custom-value-types).

---

## Sensor publishes on Synapse 0.4.0

**Read this before you conclude your recording is missing sensor data.**

Synapse 0.4.0 does not expose a publish-listener hook, so Engram falls back to
wrapping the orchestrator. That wrapper sees every publish made *through the
orchestrator* — OpMode hooks, your nodes, `GamepadAdaptor` — but it cannot see
publishes made by `bulkRead` callbacks, because Synapse binds `HardwareView` to
the real orchestrator inside `SafeOpMode.init()`, before Engram runs.

Two ways to handle it:

**Wrap the reader** (works today, one line per `bulkRead`):

```java
hardware.bulkRead(50, engram.recording(view -> {
    view.publish("odom/pose", pose);
    view.publish("motor/intake/amps", intake.getCurrent(CurrentUnit.AMPS));
}));
```

`engram.recording(...)` hands your callback a `HardwareView` bound to the
recording orchestrator, so its publishes are captured. On a Synapse build that
has the listener hook it returns your reader unchanged, so it is safe to leave
in place permanently.

**Or check at runtime** and branch:

```java
if (engram.isSensorCaptureAutomatic()) {
    hardware.bulkRead(50, reader);   // captured automatically
} else {
    telemetry.addLine("engram: wrap bulkRead readers with engram.recording(...)");
}
```

Once Synapse ships `Orchestrator.addPublishListener`, Engram detects it
automatically, the decorator is not used, and this whole section stops applying.
Nothing in your code needs to change. The reasoning and the proposed Synapse
change are in [SYNAPSE-INTEGRATION.md](SYNAPSE-INTEGRATION.md).

---

## Where recordings go

`EngramSession` resolves the first writable directory from:

1. `/sdcard/FIRST/engram` — the standard FTC directory, reachable over USB
2. the app's external files directory, reached reflectively through
   `AppUtil.getDefContext()` (needs no runtime permission on any API level; the
   SDK's own `OpMode` has no `Context` accessor, so this is the only route that
   works on a real device)
3. the JVM's `java.io.tmpdir`, which on Android is the app's own cache
   directory — always writable, but **not** USB-visible

Check where it landed, especially on the first run:

```java
telemetry.addLine("engram -> " + engram.file());
if (!engram.location().isUsbVisible()) {
    telemetry.addLine("WARNING: not USB-visible, pull it with adb");
}
```

Filenames look like `MyTeleOp_2026-09-28_144523.engram`.

Which directory was chosen is available for logging:

```java
telemetry.addLine("engram -> " + engram.location().directory()
        + " (" + engram.location().describe() + ")");
```

To write somewhere specific — a test, or a directory you manage — pass the file
directly:

```java
EngramSession session = EngramSession.start(
        "MyTeleOp",                    // label recorded in the header
        new File("/sdcard/match.engram"),
        orchestrator,
        RecorderConfig.defaults(),
        null);                         // strategy: null = auto
```

The recorder's API is built on `java.io.File`, not `java.nio.file`: the FTC SDK
declares `minSdkVersion=24` and `java.nio.file` is API 26, so the latter would
throw `NoClassDefFoundError` on an older Robot Controller. The replay tool is
desktop-only and accepts either a `File` or a `Path`.

---

## Keeping the directory under control

A season of practice fills the Robot Controller's storage. Engram will not
delete anything unless you configure a `RetentionPolicy` — a recorder that
quietly removes files from a robot someone paid to build is a worse surprise
than a full SD card.

```java
import com.aaravlabs.engram.recorder.RetentionPolicy;

EngramSession session = EngramSession.start(this, orchestrator,
        RecorderConfig.builder()
                // 0 for either limit means "no limit of that kind"
                .withRetention(RetentionPolicy.of(256L * 1024 * 1024, 20))
                .withLog(RecorderLog.stderr())
                .build());
```

Or, declaratively:

```java
@Recorded(retentionMaxBytes = 256L * 1024 * 1024, retentionMaxRecordings = 20)
public class MyTeleOp extends SafeOpMode { ... }
```

`RetentionPolicy.of(maxTotalBytes, maxRecordings[, minRetained])` prunes the
oldest `.engram` / `.engram.gz` files until both limits hold:

| Limit | Why |
|-------|-----|
| **max total bytes** | Storage is the real constraint: a 32 GB card shared with the RC app and match logs. |
| **max recording count** | Stops a season of very small recordings from accumulating into a directory listing that takes a while to scan. |

There is deliberately **no maximum age**. `File.lastModified()` is the only
timestamp available, its resolution depends on the FAT32 card, and a wall-clock
policy is the one most likely to delete this morning's practice on the morning
of a competition.

**What it will never delete:**

- the recording currently being written. Pruning skips it explicitly, so a pass
  cannot truncate a live recording;
- anything below `minRetained` (default `1`). An over-tight size limit degrades
  into "kept more than you asked for", not "the robot has no recordings";
- anything that is not a recording — a `.engram.tmp`, a notes file, a
  directory.

**When it runs.** Once before the recording starts, so the new one has room,
and once on a background thread after `close()`, so the directory does not grow
without bound across a season. Neither pass is on the publishing path.

**When it cannot finish.** A file that will not delete is reported through
`engram.lastPruneResult()` and in the log, and the pass stops rather than
deleting a *newer* recording to work around an *older* one that will not go —
which is also the likely outcome, since a refused delete usually means the whole
directory is read-only. Nothing throws, and a prune failure never reaches a
publish or a match.

```java
PruneResult result = engram.lastPruneResult();
if (result != null && !result.isClean()) {
    telemetry.addLine("engram: could not prune " + result.failures());
}
```

---

## Custom value types

Engram encodes the boxed primitives, `String`, `Character`, `Enum`,
`BigInteger`, `BigDecimal`, and `byte[]` with no configuration. Enums are stored
by name, which makes states and modes readable in the replay tool.

Anything else — a `Pose2d`, an `AprilTagDetection`, a custom struct — is recorded
with **empty bytes** and counted in `RecorderStats#unencodableValues()`. The
topic still appears in the recording with its name, type, and timing, so you can
see that it existed and how often it fired. Register a codec to capture the
values:

```java
public final class PoseCodec implements ValueCodec {

    @Override public boolean supports(Class<?> type) {
        return Pose2d.class.isAssignableFrom(type);
    }

    @Override public void encode(Object value, ByteArrayOutputStream out) throws IOException {
        Pose2d p = (Pose2d) value;
        out.write(String.format("%.4f,%.4f,%.4f", p.x, p.y, p.heading)
                .getBytes(StandardCharsets.UTF_8));
    }
}
```

```java
EngramSession session = EngramSession.start(this, orchestrator,
        RecorderConfig.builder().withCodec(new PoseCodec()).build());
```

The replay tool treats custom bytes as opaque and renders them as
`base64:...`. Register a matching decoder there when you build a consumer.

Codecs are consulted only when a topic is first seen, so `supports` is called
once per topic. A codec that throws is reported once and skipped; it can never
take down a run.

There is an opt-in Java-serialization fallback:

```java
RecorderConfig.builder().withJavaSerializationFallback(true).build();
```

It is **off by default**. It bloats files and is brittle across versions. It
only ever writes, never reads, so it is a convenience rather than a safety
issue — see [SECURITY.md](../SECURITY.md).

---

## Reading a recording

```java
EngramRecording r = EngramRecordingReader.read(Paths.get("match.engram"));

r.opModeName();                 // "MyTeleOp"
r.durationUs();                 // microseconds, or -1 if unknown
r.isTruncated();                // did the robot die mid-write?
r.topics();                     // List<TopicInfo>, in declaration order

// What was a topic holding at a moment?
double power = (Double) r.valueAt("drive/power", 12_500_000).orElse(0.0);

// Everything a topic did in a window, in time order.
for (Sample s : r.samples("drive/power", 10_000_000, 13_000_000)) {
    System.out.println(s.timeUs() + "us " + s.value());
}

// How did it behave overall?
TopicStats stats = r.stats("drive/power");
stats.publishCount();
stats.averageRateHz();
stats.min();     // Optional<Double>, empty for non-numeric topics
stats.max();
stats.mean();
```

`valueAt` returns the newest publish **at or before** the given time — the same
"latest value" semantics Synapse itself uses.

An unknown topic name throws `IllegalArgumentException` rather than returning
empty, so a typo is not mistaken for "nothing was recorded".

---

## Checking a recording is complete

A recording that silently lost data looks exactly like one that captured
everything. That is exactly how the `bulkRead` sensor gap hid. `inspect` now
reports completeness:

```
Capture      COMPLETE
Finalized    yes
Declared     4 topics, 4 observed with publishes
             every declared topic has publishes and the run closed cleanly
```

or, for a robot that was killed mid-match:

```
Capture      INCOMPLETE
Finalized    no
Declared     4 topics, 4 observed with publishes
             unfinalized: no LIFECYCLE_STOP: the run was killed, crashed, or lost
             power before it closed
```
### What counts as incomplete

| Code | Means |
|------|-------|
| `unfinalized` | No `LIFECYCLE_STOP`. The recorder writes it immediately before closing the stream, so its absence means the process was killed, crashed, or lost power mid-run. **The strongest signal the format has.** |
| `truncated` | The file ends mid-message, so the tail of the run is gone. |
| `declared-never-published` | A topic's declaration was written but its first publish was not. The recorder queues a declaration immediately before the publish that triggers it, so the only way to see one is a stream that stopped between the two. |
| `undeclared-topic` | Publishes referencing a topic id whose declaration never arrived. The recorder never produces this; a file that does is damaged. |
| `missing-init` | No `LIFECYCLE_INIT`, so there is no time origin. |
| `no-topics` | Nothing was ever declared. |
| `missing-expected-topic` | A topic you named with `--expect-topic` is not in the file. |

### What it cannot detect, and why

**A topic the capture path never saw at all is invisible.** Nothing in the file
records which topics *should* exist, so a `bulkRead` topic that was never
observed is indistinguishable from a robot with no sensors. This is a property
of the wire format, not of the report.

The defence is to say what you expect:

```bash
engram inspect match.engram \
    --expect-topic drive/power \
    --expect-topic sensor/odom-left \
    --expect-topic sensor/odom-right \
    --strict
```

`--strict` exits `3` when the recording is incomplete, so a CI job or a shell
loop can act on it without parsing prose. Without `--strict`, `inspect` still
exits `0` — a crashed recording is data to be read, not an error to be refused.

From Java:

```java
CaptureReport report = recording.captureReport(Arrays.asList("sensor/odom-left"));
if (!report.isComplete()) {
    for (CaptureReport.Finding f : report.findings()) {
        System.out.println(f.code() + " " + f.topics());
    }
}
```

`engram export --format json` carries the same verdict in a `capture` object, so
a web visualizer does not have to reimplement any of it.

---

## Command line

```bash
JAR=engram-replay/build/libs/engram-replay-*-all.jar

engram inspect match.engram                    # summary, completeness, topics, rates
engram inspect match.engram --strict           # exit 3 if anything is missing
engram inspect match.engram --expect-topic sensor/odom-left
engram topics  match.engram                    # every topic with its type
engram query   match.engram --topic drive/power
engram query   match.engram --topic drive/power --from 0 --to 5000000
engram export  match.engram --format json --out match.json
engram export  match.engram --topic drive/power --format csv --out power.csv
engram play    match.engram --topic drive/power --speed 2.0
```

Times are microseconds since the recording started. `play` streams a topic to
stdout at real-time speed (or faster with `--speed`), which is useful for
eyeballing a run alongside a video.

Exit codes: `0` success, `1` unreadable file, `2` bad usage, `3` — from
`inspect --strict` only — the file was read but the recording is incomplete.

---

## Exporting for other tools

`--format json` produces one self-contained document — the shape a web
visualizer should consume:

```json
{
  "formatVersion": 1,
  "opMode": "MyTeleOp",
  "startEpochMs": 1759062000000,
  "initTimeUs": 0, "startTimeUs": 150000, "stopTimeUs": 5010000,
  "truncated": false,
  "capture": { "complete": true, "finalized": true, "declaredTopics": 1, "observedTopics": 1, "problems": [] },
  "topics":  [ { "id": 0, "name": "drive/power", "javaType": "java.lang.Double", "valueType": "VALUE_TYPE_DOUBLE", "publishes": 1800, "unrecorded": 0 } ],
  "samples": [ { "t": 0, "topicId": 0, "topic": "drive/power", "value": 0.0 } ]
}
```

`--format ndjson` writes one event per line for `jq` and streaming consumers.
`--format csv` writes `time_us,topic,value` (or `time_us,value` with
`--topic`) for spreadsheets and plotting tools.

All three honour `--topic`, `--from`, and `--to`.

---

## Compressing a recording

Gzip any recording before moving it off the robot. The replay tool detects the
gzip magic bytes and decompresses transparently, so `.engram.gz` works anywhere
`.engram` does:

```bash
gzip match.engram
engram inspect match.engram.gz
```

Expect roughly 8–10x on a match-length file; gamepad axes change slowly between
frames, which both protobuf and gzip exploit.

---

## Recording without `SafeOpMode`

If you construct the orchestrator yourself, attach manually:

```java
Recorder recorder = Recorder.open(
        Paths.get("/sdcard/match.engram"), "MyOp",
        RecorderConfig.defaults());
recorder.onPublish("topic", value, System.nanoTime());
recorder.recordStart();
// ...
recorder.recordStart();   // no: call recordStop via close()
recorder.close();
```

Or use a session, which handles attachment and detach for you:

```java
EngramSession session = EngramSession.start(this, orchestrator);
Orchestrator bus = session.orchestrator();
// ...
session.close();
```

---

## API reference

Full Javadoc is in the published `-javadoc.jar` artifacts. The entry points:

| Type | Purpose |
|------|---------|
| `EngramSession` | Start, mark start, close. The normal integration point. |
| `Recorder` | The writer, if you want to drive it directly. |
| `RecorderConfig` | Flush interval, buffer depth, codecs, logging. |
| `ValueCodec` | Encode a type Engram has no built-in mapping for. |
| `RecorderStats` | Publishes, drops, unencodable values, bytes, failure. |
| `OutputLocation` | Directory resolution and filename generation. |
| `CaptureStrategies` | Strategy selection; `isPublishListenerAvailable()`. |
| `EngramRecordingReader` | Load a file into an `EngramRecording`. |
| `EngramRecording` | Queries: samples, `valueAt`, `stats`, lifecycle. |
| `TopicInfo` / `Sample` / `TopicStats` | Per-topic detail. |
| `JsonExporter` / `NdjsonExporter` / `CsvExporter` | Conversion. |
| `cli.Main` | The `engram` command. |
