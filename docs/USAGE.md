# Usage

How to record a run and read it back.

## Contents

- [Recording a run](#recording-a-run)
- [Sensor publishes on Synapse 0.4.0](#sensor-publishes-on-synapse-040)
- [Where recordings go](#where-recordings-go)
- [Custom value types](#custom-value-types)
- [Reading a recording](#reading-a-recording)
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
2. the app's external files directory, found reflectively via an Android
   `Context` (needs no runtime permission on any API level)
3. the JVM's `java.io.tmpdir`, which on Android is the app's own cache
   directory — always writable, but not USB-visible

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
        Paths.get("/sdcard/match.engram"),
        orchestrator,
        RecorderConfig.defaults(),
        null);                         // strategy: null = auto
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

## Command line

```bash
JAR=engram-replay/build/libs/engram-replay-*-all.jar

engram inspect match.engram                    # summary, topics, rates, ranges
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

Exit codes: `0` success, `1` unreadable file, `2` bad usage.

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
