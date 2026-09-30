# Engram

Record and replay FTC OpMode runs.

Engram captures every topic publish during a [Synapse](https://github.com/IamCoder18/synapse)
OpMode, serializes it to a compact binary file, and a standalone desktop tool
reads that file back for timing analysis and debugging — with no FTC SDK and no
Android anywhere in the read path.

The name comes from memory research: an *engram* is the physical trace an
experience leaves behind. This captures the trace of a run and plays it back.

```
   robot (Android)                        desktop
┌────────────────────┐                 ┌──────────────────────┐
│  SafeOpMode        │                 │  engram inspect      │
│    ↓               │   copy off      │  engram query        │
│  EngramSession     │   the robot     │  engram export       │
│    ↓               │  ─────────────► │    ↓                 │
│  Orchestrator      │   match.engram  │  EngramRecording     │
│    ↓ publish()     │                 │  (query API)         │
│  Recorder → file   │                 │                      │
└────────────────────┘                 └──────────────────────┘
```

## Status

**0.1.0, working and tested.** 275 tests, including round trips that record
through a real Synapse orchestrator and read back through the production reader,
and a stub `PublishListener` that makes the reflective attach path testable.
Plus an opt-in 150-second soak that measures the "never blocks the publisher"
and "never allocates unboundedly" claims rather than only arguing them.

## Modules

| Module | Runs on | Depends on |
|--------|---------|-----------|
| `engram-proto` | — | `protobuf-java` |
| `engram-recorder` | Robot Controller (Android API 24+) | `engram-proto`, Synapse *(compile-only)* |
| `engram-replay` | Desktop | `engram-proto` |

Neither the recorder nor the replay tool depends on the FTC SDK or on Android.
That is deliberate: it is what lets the whole system be tested on a plain
desktop JVM.

## Quick start

Add to your TeamCode build:

```groovy
dependencies {
    implementation 'com.aaravlabs:synapse:0.4.0'
    implementation 'com.aaravlabs:engram-recorder:0.1.0'
}
```

Wire a session into your OpMode:

```java
public class MyTeleOp extends SafeOpMode {
    private EngramSession engram;

    @Override protected void onSafeInit() {
        engram = EngramSession.start(this, orchestrator);
        orchestrator = engram.orchestrator();
        // ... your existing setup ...
    }

    @Override protected void onSafeStart() { engram.markStart(); }

    @Override protected void onSafeStop()  { engram.close(); }
}
```

Then, on your laptop:

```bash
java -jar engram-replay-all.jar inspect MyTeleOp_2026-09-28_144523.engram
java -jar engram-replay-all.jar query  MyTeleOp_2026-09-28_144523.engram --topic drive/power
```

Full integration guide, including the `bulkRead` caveat that applies on Synapse
0.4.0: **[docs/USAGE.md](docs/USAGE.md)**.

## What you get

```
$ engram inspect match.engram
OpMode       DemoTeleOp
Format       v1
Started      2026-09-29 22:35:56
Duration     1.940 s
Events       787
Topics       4
init at      0.000 s
start at     0.143 s

Capture      INCOMPLETE
Finalized    no
Declared     4 topics, 4 observed with publishes
             unfinalized: no LIFECYCLE_STOP: the run was killed, crashed, or lost
             power before it closed

TOPIC                            TYPE                     PUBLISHES  RATE (Hz) RANGE
drive/power                      Double                        195     100.52 -1.000 .. 0.9996
cmd/target                       Double                        196     100.51 -1.000 .. 0.9996
g1/left_stick_y                  Double                        195     100.52 -0.9000 .. 0.1000
g1/a                             Boolean                       195     100.52 -
```

`Capture` is the answer to "can I trust this file?". A recording that was killed
mid-match, truncated by a partial write, or that declared a topic it never
published to is reported as `INCOMPLETE` and listed with the reason. Pass
`--strict` to turn that into exit code 3, or `--expect-topic name` to assert
that a topic you care about is in there — the file cannot know what it was
supposed to contain, so that expectation has to come from you.

```
$ engram inspect match.engram --strict --expect-topic sensor/odom-left
...
Capture      INCOMPLETE
Finalized    no
Declared     4 topics, 4 observed with publishes
Expected     1 named, 1 missing
             unfinalized: no LIFECYCLE_STOP: the run was killed, crashed, or lost
             power before it closed
             missing-expected-topic: expected but absent from the recording
             sensor/odom-left
$ echo $?
3
```

```
$ engram query match.engram --topic drive/power
drive/power: 195 sample(s), rate 97.25 Hz, min -0.999923, max 0.999992, mean 0.188892

     TIME (us)  VALUE
         92304  0.0
        112022  0.04997916927067833
        122318  0.09983341664682815
```

## Bounding what a season of practice leaves behind

Engram never deletes a recording unless you ask it to. When you do ask, it keeps
the newest ones within a size and/or count budget, never touches the recording
currently being written, and never drops below a floor you set:

```java
engram = EngramSession.start(this, orchestrator,
        RecorderConfig.builder()
                .withRetention(RetentionPolicy.of(256L * 1024 * 1024, 20))
                .withLog(RecorderLog.stderr())
                .build());
```

The same thing declaratively, for an OpMode that carries `@Recorded`:

```java
@Recorded(retentionMaxBytes = 256L * 1024 * 1024, retentionMaxRecordings = 20)
public class MyTeleOp extends SafeOpMode { ... }
```

Pruning runs before a recording starts (so the new one has room) and on a
background thread once it has ended, so it is never on the publishing path.

## Documentation

| Doc | Contents |
|-----|----------|
| [docs/USAGE.md](docs/USAGE.md) | **Start here.** Integrating with an OpMode, reading recordings, custom value types |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Recording pipeline, capture strategies, buffering, replay internals |
| [docs/SYNAPSE-INTEGRATION.md](docs/SYNAPSE-INTEGRATION.md) | The Synapse constraint that shapes the design, and the one gap the decorator has |
| [docs/PROTOBUF-SCHEMA.md](docs/PROTOBUF-SCHEMA.md) | Wire format and the reasoning behind each encoding choice |
| [docs/OPEN-QUESTIONS.md](docs/OPEN-QUESTIONS.md) | Deferred decisions and known limitations |
| [CHANGELOG.md](CHANGELOG.md) | Release history |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to build, test, and what the project holds non-negotiable |

## Design commitments

- **Recording never affects the robot.** No recorder code path throws into
  `publish`, blocks a publishing thread on I/O, or allocates unboundedly. An
  unencodable value is recorded as empty bytes and counted; a stream failure
  latches and drops. The soak exists to measure this rather than assert it in
  prose: it drives a match-length load at ~1,150 publishes per second and
  reports per-publish latency percentiles, queue depth, and heap growth.
- **Replay needs nothing from the robot.** No Synapse, no FTC SDK, no Android. A
  recording is self-contained, and this is enforced by the module graph.
- **Crashes are data, not errors.** A file that ends mid-message — what a robot
  process that dies before its final flush leaves behind — is read up to the last
  complete event and flagged, because diagnosing a crashed run is exactly when
  the partial data matters most. `inspect` says so in a `Capture` section and,
  under `--strict`, in the exit code.
- **Gaps are documented, not hidden.** The `bulkRead` sensor-capture gap on
  Synapse 0.4.0 is real; it is spelled out in
  [docs/SYNAPSE-INTEGRATION.md](docs/SYNAPSE-INTEGRATION.md) along with the
  one-line workaround and the Synapse change that would remove it. What the file
  format *cannot* detect — a topic that was never captured at all — is stated
  plainly rather than papered over.
- **Engram does not delete your data unless you say so.** Retention is opt-in,
  and even then it will not touch the recording being written.

## File size

A 2.5-minute match with two gamepads at 60 Hz and ten sensor topics at 20 Hz is
roughly 30k events, about **1 MB raw**.

Gzip is detected and handled transparently on read, and how well it helps depends
entirely on how much the values change. The demo fixture — four topics of smooth
sine data, which is close to worst case — compressed 14 KB to 7 KB. Real gamepad
recordings change slowly between frames and compress far better, so expect
somewhere between a tenth and a half of the raw size. Gzip it and measure; the
tool will tell you what you got.

## Building

```bash
./gradlew build                              # compile, test, package
./gradlew :engram-replay:fatJar              # self-contained runnable jar
java -jar engram-replay/build/libs/engram-replay-*-all.jar --help
```

Requires a JDK 17 or newer. Output targets Java 11 bytecode so it drops into an
existing FTC team project unchanged.

## Load testing

The `test` task runs in about a minute and holds nothing back. The match-length
soak is separate, because it takes two and a half minutes of wall clock:

```bash
./gradlew soakTest                          # 150 s, one real match
./gradlew :engram-recorder:soakTest \
    -Pengram.soak.seconds=20                 # a shorter look at the same numbers
```

It publishes at roughly 1,150 topics per second from four threads shaped like an
OpMode loop, a control node, a `bulkRead` callback, and a telemetry thread, then
prints latency percentiles, queue depth, heap growth, and file size. What it
asserts and what it merely reports is spelled out at the top of
`MatchLengthSoakTest`, and the distinction is deliberate: the timing budget is
generous enough not to fail a shared CI runner for a GC pause, while the
correctness invariants are strict.

## License

MIT. See [LICENSE](LICENSE).
