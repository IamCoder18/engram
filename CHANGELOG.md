# Changelog

All notable changes to Engram are recorded here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

**Match-length soak test.** `MatchLengthSoakTest` drives a 150-second run at
roughly 1,150 publishes per second from four threads shaped like a real OpMode
(60 Hz gamepads, a 100 Hz control node, 20 Hz `bulkRead` bursts of ten sensor
values, 5 Hz telemetry), and measures the two claims that were previously only
argued in prose: per-publish latency and whether the recorder accumulates without
bound. It is tagged `soak`, excluded from `test`, and run by
`./gradlew soakTest`; `-Pengram.soak.seconds=N` shortens it. It prints its
measurements and asserts only on invariants that must hold on any machine, plus
one deliberately loose p99 budget — the reasoning is in the class Javadoc.

**Recording retention.** `RetentionPolicy`, configurable through the existing
mechanisms (`RecorderConfig.builder().withRetention(...)` and the `@Recorded`
annotation), bounds how much storage the output directory may hold by deleting the
oldest recordings. Two limits, maximum total bytes and maximum recording count,
with a floor of recordings always kept. It runs before a recording starts and on
a background thread after it ends, never deletes the recording being written,
never touches anything that is not a recording, and degrades to a reported
failure rather than an exception. Off by default: Engram deletes nothing unless
asked. `EngramSession#lastPruneResult()` reports what a pass did.

**Capture completeness in `engram inspect`.** A new `Capture` section reports
whether the recording is actually complete: `unfinalized` (no
`LIFECYCLE_STOP`, i.e. killed mid-run), `truncated`, `declared-never-published`,
`undeclared-topic`, `missing-init`, `no-topics`, and `missing-expected-topic`.
`--expect-topic <name>` (repeatable) asserts topics the caller cares about, and
`--strict` turns an incomplete capture into exit code 3. `engram export
--format json` carries the same verdict in a `capture` object.

### Changed

- `TopicInfo` gained `isDeclared()`, distinguishing a topic whose declaration the
  file carried from one the reader synthesised out of a publish.
- `inspect` prints a `Capture` block before the topic table. The pre-existing
  truncation `WARNING` line is unchanged, so anything reading that output still
  works, and `inspect` still exits 0 for an incomplete file unless `--strict` is
  passed.
- `Recorder` gained `bufferedEventCount()`, the queue depth the soak uses to
  observe whether the writer is keeping up.
- Retention passes are queued on a single background worker instead of being
  suppressed when one is already running. A suppressed pass is a directory that
  stays over its configured limit with nothing scheduled to come back for it —
  the next session, which on a robot may be the next practice session. Passes
  are still serialised rather than run concurrently, because two passes can
  select the same oldest file and the loser's refused delete would abort it
  early.

### Fixed

- `EngramSession#completedPrunePasses()`. `lastPruneResult()` alone cannot
  distinguish "no pass has finished yet" from "the pass I was waiting for has
  already finished", because the pass after `close()` is dispatched before
  `close()` returns and the background thread often wins that race. A caller
  polling for a newer result could wait forever for a result that had already
  arrived — the retained-recording test failed roughly one run in six for exactly
  that reason, and reported the very result it had been waiting for as the
  symptom.

### Test coverage

- 276 tests, 0 skipped, plus the opt-in soak.

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
