# Security Policy

## Supported versions

| Version | Supported |
|---------|-----------|
| 0.1.x | Yes |
| < 0.1 | No |

Engram is pre-1.0. The wire format is versioned independently and files recorded
by 0.1.x stay readable.

## Reporting a vulnerability

Email the maintainer rather than opening a public issue. Please include the
version, the Synapse version, and reproduction steps if you have them.

You can expect an acknowledgement within a few days. Fixes for confirmed issues
ship in the next patch release and are noted in `CHANGELOG.md`.

## Threat model

Engram is a local developer tool that runs on a robot the team controls, so the
interesting questions are about the data it handles rather than remote attack.

**Recording a run.** The recorder writes only to a directory it resolves itself
(`/sdcard/FIRST/engram` when writable, otherwise an app-private location). It
does not read user input, make network calls, or request permissions. Topic
names and values come from team code, so a recording is as trustworthy as the
OpMode that produced it.

**Reading a recording.** The replay tool reads a local file and parses it as
protobuf. Two things follow:

- **Untrusted recordings.** Protobuf parsing is bounds-checked and will not
  corrupt memory, but a maliciously crafted file can still cause a large
  allocation, because the format carries a length prefix per message. Only open
  recordings you produced or trust. There is no sandboxing, and a recording
  should be treated like any other untrusted input file.
- **Recorded values are not sanitised.** Topic names and string values are
  echoed verbatim by `engram query`, `inspect`, and the JSON/CSV exporters. CSV
  and JSON quoting is applied, so the output stays well-formed, but a recording
  containing hostile text will surface that text in your terminal. Do not pipe
  `engram query` output into something that renders it as markup.

**Custom value codecs.** A `ValueCodec` registered via `RecorderConfig` runs on
the robot, on the publishing thread, and its exceptions are caught. A codec that
blocks will therefore block a publish. Engram cannot make an untrusted codec safe
— only register codecs you wrote.

**The Java-serialization fallback** is off by default for a reason. Java
deserialization of attacker-controlled data is a well-known code-execution risk.
Engram only ever *writes* serialized bytes, never reads them, so enabling it is
about file size and convenience rather than safety — but if you enable it, be
aware that a recording then contains data that should not be deserialized
anywhere else.
