# Contributing to Engram

Thanks for helping out. This is a small project with a narrow purpose: capture
what a Synapse OpMode published, and let someone look at it afterwards.

## The bar for a change

Engram runs on a competition robot, in the last minute before a match, with a
robot on the floor. Two properties are non-negotiable and are enforced by the
design rather than by review alone:

1. **Recording must never affect the robot.** No recorder code path may throw
   into `Orchestrator.publish`, block a publishing thread on I/O, or allocate
   unboundedly. If you find yourself adding a `throw` to the publish path, stop.
2. **The recorder must not require the robot.** `engram-recorder` compiles with
   no FTC SDK and no Android dependency, and its tests run on a desktop JVM.
   Please keep it that way; it is what makes the recorder testable at all.
3. **The recorder must run on Android API 24**, the minimum the FTC SDK
   declares. That rules out `java.nio.file` and `java.time`, which are API 26.
   `NoAndroidApiLeakTest` fails the build if either reappears, so you will find
   out immediately rather than at a competition.

## Getting set up

```bash
git clone https://github.com/IamCoder18/engram.git
cd engram
./gradlew build
```

Requires a JDK 17 or newer to build. The output targets Java 11 bytecode so it
drops into an existing FTC team project unchanged.

## Releasing

Publishing is credential-gated and Central does not want half-released
artifacts, so the sequence matters:

```bash
# 1. Prove the signed artifacts locally before spending a CI run.
./gradlew publishToMavenLocal
gpg --verify ~/.m2/repository/com/aaravlabs/engram-recorder/*/engram-recorder-*.jar.asc \
                ~/.m2/repository/com/aaravlabs/engram-recorder/*/engram-recorder-*.jar

# 2. Commit, then tag. The tag is what triggers publishing.
git tag -a v0.1.0 -m "Initial release" && git push origin main --tags
```

`publish.yml` runs the tests first and only then uploads, so a failing test
costs no Central deployment. If the upload step fails *after* a partial
deployment, check whether Central already accepted it before re-running --
version numbers cannot be reused once published.

`sonatypeUsername` / `sonatypePassword` and `signing.key` / `signing.password`
are read from Gradle properties or the `SONATYPE_USERNAME`,
`SONATYPE_PASSWORD`, `SIGNING_KEY`, `SIGNING_PASSWORD` environment variables,
and live as repository secrets.

## Before opening a pull request

```bash
./gradlew build              # compiles, tests, and packages everything
./gradlew :engram-replay:fatJar
java -jar engram-replay/build/libs/engram-replay-*-all.jar --help
```

The build must be green. There are no skipped or ignored tests, and no
`@Ignore`s; if a test is hard to write, that is usually a sign the design could
be simpler.

## Testing expectations

- **New behaviour needs a test.** The suite covers the wire format, the encoder,
  the buffer under contention, the decorator's full pass-through surface, and
  round trips through a real Synapse orchestrator. Match that bar.
- **Concurrency claims need a concurrency test.** Anything touching the event
  buffer or the topic registry should be exercised from several threads, because
  those run on the OpMode loop, the hardware thread, and the callback pool at
  once.
- **The wire format needs byte-level tests.** `ProtoFramingTest` pins the
  length-delimited framing against protobuf's own encoder. If you change framing,
  that test is the one that must keep passing.
- **Reader tests must not use the recorder to build their input.**
  `engram-replay` does not depend on `engram-recorder` on purpose, so its tests
  construct protobuf messages directly. That keeps a matching mistake in the
  writer and the reader from cancelling out.

## Changing the wire format

`proto/engram_recording.proto` is a published contract. Files recorded on a
robot must stay readable by tools released later.

- Additive field changes are fine: bump `format_version` in the header, and old
  readers will ignore the new field.
- Removing or repurposing a field number is not.
- Any change needs the format-version test in `EngramRecordingReaderTest` to
  still make sense, and a line in `CHANGELOG.md`.

## Style

Match the surrounding code. Two things the codebase does consistently:

- Comments explain *why*, not *what*. A comment restating the line below it is
  noise; a comment explaining a non-obvious constraint is the most valuable
  thing in the file. Several exist because the reasoning took an investigation
  to work out, and it would be lost otherwise.
- Javadoc on anything public states the contract, including thread safety and
  what happens on failure.

## Reporting bugs

A recording that misbehaves is far easier to fix with the file attached. If you
can share one, please do — `engram inspect` and `engram export --format json`
output make a good starting report.
