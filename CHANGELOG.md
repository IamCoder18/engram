# Changelog

All notable changes to Engram are recorded here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet.

## [0.1.0] — 2026-09-28

First working release. Records a Synapse OpMode's topic traffic to a compact
protobuf file and reads it back on a desktop for timing analysis.

### Added

**`engram-proto`** — the shared wire format.
- `engram_recording.proto`: `RecordingHeader`, `RecordingEvent`, `TopicDeclaration`,
  `TopicPublish`, `TopicValue`, `LifecycleEvent`.
- Length-delimited message stream, so the recorder can append while recording and
  a truncated file still yields every complete event.
- Topic manifest carried inline as `TopicDeclaration` events rather than in the
  header, which is what keeps the writer streaming.
- `sint32`/`sint64` for integer topics, so small negative values cost one or two
  bytes instead of ten.
- Format version stamped in the header for future evolution.

**`engram-recorder`** — runs on the Android Robot Controller.
- `EngramSession`, the user-facing handle: `start`, `markStart`, `close`.
- `Recorder`, the writer. Publishes are encoded to bytes and queued; a single
  daemon thread drains the queue to disk every 100 ms or every 500 events.
  No serialization or I/O ever happens on the publishing thread.
- `PublishListenerCapture`, which reaches Synapse's `PublishListener` hook
  reflectively and installs a `java.lang.reflect.Proxy`. Nothing references that
  type at compile time, so one Engram build works against Synapse versions both
  before and after the hook exists.
- `DecoratorCapture`, the fallback for Synapse builds without the hook.
- `EngramSession#recording(BulkReader)`, which closes the `bulkRead` gap the
  decorator necessarily has.
- `ValueEncoder` with built-in mappings for the boxed primitives, `String`,
  `Character`, `Enum`, `BigInteger`, `BigDecimal`, and `byte[]`; a `ValueCodec`
  SPI for anything else; an opt-in Java-serialization fallback; per-type warning
  de-duplication.
- `OutputLocation`, which picks a writable directory on the RC without
  compiling against the Android SDK, and reports which one it chose.
- `@Recorded` for declarative configuration.
- `RecorderStats` reporting publishes, drops, unencodable values, bytes written,
  and any stream failure.
- Failure policy: nothing thrown by the recorder propagates into robot control
  flow. Encoding failures record empty bytes and count; stream failures latch and
  drop.

**`engram-replay`** — runs on a desktop. Depends only on `protobuf-java`.
- `EngramRecordingReader` with transparent gzip support and truncation tolerance.
- `EngramRecording` query API: per-topic sample series, point-in-time lookup,
  time-range filtering, publish counts, rates, min/max/mean.
- `JsonExporter`, `NdjsonExporter`, `CsvExporter`.
- `engram` CLI with `inspect`, `topics`, `query`, `export`, and real-time `play`.
- A self-contained runnable jar (`fatJar`).

### Test coverage
- 226 tests, 0 skipped.
- The reflective `PublishListener` attach path is covered by a test-source stub
  of `com.aaravlabs.synapse.PublishListener` plus a real orchestrator class
  declaring the hook, since that path cannot execute against a Synapse build
  that lacks it.
- Strategy selection is tested in both directions, including the partial
  classpath where the interface exists but the orchestrator does not implement
  it -- the case that would otherwise record nothing silently.
- Truncation fixtures append a deliberately partial message rather than slicing
  at an offset, which can land on a message boundary and produce a clean end of
  stream instead of genuine truncation.

### Android compatibility
- The recorder targets the FTC SDK's declared `minSdkVersion=24`. It uses
  `java.io.File` and `java.text.SimpleDateFormat` rather than `java.nio.file`
  and `java.time`, which are API 26 and would throw `NoClassDefFoundError` on an
  API 24/25 device. `NoAndroidApiLeakTest` enforces this by scanning the
  compiled classes.
- `OutputLocation` reaches an Android `Context` through
  `AppUtil.getDefContext()`, because RobotCore's `OpMode` and `OpModeInternal`
  expose no `getContext()`. Verified by inspecting RobotCore 8.0-12.0.
- `OutputLocation#isUsbVisible()` reports whether the chosen directory can be
  pulled off the robot, so a recording that landed in the app's private cache
  says so instead of looking equally fine.

### Design notes
- The recorder module has **no FTC SDK dependency**. The obvious
  `EngramOpMode extends SafeOpMode` base class would force a dependency on
  `org.firstinspires.ftc:RobotCore`, which is published only as an `.aar` and
  pulls in AndroidX. Engram instead exposes a session handle the team wires into
  their own OpMode, which keeps the recorder testable on a plain desktop JVM.
- The `bulkRead` gap is real and documented rather than hidden. See
  [docs/SYNAPSE-INTEGRATION.md](docs/SYNAPSE-INTEGRATION.md).

[Unreleased]: https://github.com/IamCoder18/engram/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/IamCoder18/engram/releases/tag/v0.1.0
