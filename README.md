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

**0.1.0, working and tested.** 223 tests, including round trips that record
through a real Synapse orchestrator and read back through the production reader,
and a stub `PublishListener` that makes the reflective attach path testable.

## Modules

| Module | Runs on | Depends on |
|--------|---------|-----------|
| `engram-proto` | — | `protobuf-java` |
| `engram-recorder` | Robot Controller | `engram-proto`, Synapse *(compile-only)* |
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
Duration     2.008 s
Events       788
Topics       4
init at      0.000 s
start at     0.100 s
stop at      2.108 s

TOPIC                            TYPE                     PUBLISHES  RATE (Hz) RANGE
drive/power                      Double                        195      97.25 -0.9999 .. 1.000
cmd/target                       Double                        196      98.12 -0.9999 .. 1.000
g1/left_stick_y                  Double                        195      97.67 -0.4000 .. 0.1111
g1/a                             Boolean                       195      97.68 -
```

```
$ engram query match.engram --topic drive/power
drive/power: 195 sample(s), rate 97.25 Hz, min -0.999923, max 0.999992, mean 0.188892

     TIME (us)  VALUE
         92304  0.0
        112022  0.04997916927067833
        122318  0.09983341664682815
```

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
  latches and drops.
- **Replay needs nothing from the robot.** No Synapse, no FTC SDK, no Android. A
  recording is self-contained, and this is enforced by the module graph.
- **Crashes are data, not errors.** A file that ends mid-message — what a robot
  process that dies before its final flush leaves behind — is read up to the last
  complete event and flagged, because diagnosing a crashed run is exactly when
  the partial data matters most.
- **Gaps are documented, not hidden.** The `bulkRead` sensor-capture gap on
  Synapse 0.4.0 is real; it is spelled out in
  [docs/SYNAPSE-INTEGRATION.md](docs/SYNAPSE-INTEGRATION.md) along with the
  one-line workaround and the Synapse change that would remove it.

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

## License

MIT. See [LICENSE](LICENSE).
