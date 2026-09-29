# Protobuf Schema

The schema is [`../proto/engram_recording.proto`](../proto/engram_recording.proto).
This explains the encoding choices.

## Why protobuf

The requirements were: compact files, a schema that doubles as the topic
manifest, cross-platform readability (Java on the robot, potentially JavaScript
in a future visualizer), and the ability to append while recording.

- **Compact.** Varint and tag encoding is materially smaller than JSON for
  numeric payloads, which dominates a recording.
- **Schema as manifest.** Topic declarations carry name and type, so a reader
  knows what to expect before interpreting any sample.
- **Append-while-writing.** The length-delimited stream format lets the recorder
  write events as they occur without knowing the count in advance.
- **Cross-platform.** `protobuf-java` on the robot; JSON export or `protobuf.js`
  on the web.

## File structure

A `.engram` file is a stream of length-delimited protobuf messages:

```
┌────────────────────────┐
│  RecordingHeader       │  varint byte length + payload
├────────────────────────┤
│  RecordingEvent INIT   │
│  RecordingEvent decl   │  first sighting of "drive/power"
│  RecordingEvent pub    │
│  RecordingEvent decl   │  first sighting of "g1/a"
│  RecordingEvent pub    │
│  ...                   │
│  RecordingEvent STOP   │  always last in a well-formed file
└────────────────────────┘
```

Each message is prefixed by its byte length as a base-128 varint — protobuf's
standard streaming encoding (`writeDelimitedTo` / `parseDelimitedFrom`).

### Why a stream rather than one big message

A single message holding every event would require buffering the whole run in
memory before writing anything, and would make partial recovery impossible. The
stream format gives incremental writes, incremental reads, and forward
compatibility, since a field added later is ignored by old readers.

## Design decisions

### Topic declarations travel inline, not in the header

The obvious layout puts the topic manifest in `RecordingHeader.topics`. That
cannot work while streaming: the header is written before any event exists, so it
cannot describe a topic first seen ten seconds later. Either the writer has to
hold the whole run before emitting a header (defeating streaming and crash
safety), or it has to rewrite the header after the fact (not possible in a
length-delimited stream without an index).

So each topic's `TopicDeclaration` is emitted inline, on the thread that first
publishes to it, immediately before the publish that triggered it. The reader
rebuilds the manifest by scanning declarations. `EngramRecording#topics()`
returns the result, so callers never see the difference.

The declaration is emitted from inside `ConcurrentHashMap.computeIfAbsent`, which
is what guarantees the ordering even when several threads publish to the same new
topic simultaneously.

### `sint32` / `sint64` instead of `int32` / `int64`

Protobuf's `int32`/`int64` encode negative values as 10-byte varints.
`sint32`/`sint64` use ZigZag encoding: one byte at zero, two bytes at ±64,
scaling with magnitude.

FTC topics are full of small signed numbers — encoder counts, positions, deltas,
heading — so this is a large win where it matters. For positive-only values the
encoding is identical to plain `int32`/`int64`, so there is no downside.

### `bytes_val` rather than `Any` or `Struct`

For non-primitive values there were three options:

| Option | Rejected because |
|--------|------------------|
| `google.protobuf.Any` | Needs `protobuf-java-util`; carries type URLs — overhead for data the reader treats as opaque |
| `google.protobuf.Struct` | Needs `protobuf-java-util`; JSON-ish encoding cost on the hot path |
| `bytes_val` | Zero extra dependencies; the reader does not need to interpret custom values |

Engram treats custom values as opaque: the reader stores and returns the bytes,
exporters render them as `base64:...`, and a consumer can apply its own decoder.
`bytes_val` is reserved for exactly this case, so adding typed support later does
not change the wire format.

The serialization strategy for those bytes is a `ValueCodec` chosen by the team.
Java serialization is opt-in and off by default.

### `ValueType` in declarations

`TopicValue` already uses a `oneof`, so a reader could always inspect which field
is set. `TopicDeclaration.value_type` is metadata that lets a reader know what to
expect without touching samples — validating early, typing CSV headers,
resolving a topic before any query.

It is **advisory**. A topic declared as `Number` may legitimately carry an
`Integer` and then a `Double`, so the `oneof` on each sample is always the
authority. `EngramRecordingQueryTest` pins this behaviour.

### `uint64 rel_time_us`

Microsecond offsets from `LIFECYCLE_INIT`, from `System.nanoTime()` on the robot.
Relative timing makes replay immune to wall-clock changes on the phone.
`start_epoch_ms` exists only to correlate a recording with match logs and is
never used for timing.

The field is unsigned because a negative offset has no meaning. The recorder
clamps rather than wrapping.

### `format_version`

Stamped in the header. Additive changes do not need a bump, since protobuf
readers ignore unknown fields; a bump signals a breaking change.

## Crash truncation

A file ending mid-message is a normal outcome, not corruption: it is what a robot
process that dies before its final flush leaves behind.

`EngramRecordingReader` stops at the last complete message and sets
`isTruncated()`. Everything decoded before that point stays queryable, because
diagnosing a crashed run is exactly when partial data is most valuable. A file
that simply ends on a message boundary is *not* truncation — it means the run
ended without a `LIFECYCLE_STOP`, and the reader reports that as a missing stop
rather than as damage.

## Sizes

Measured on the demo fixture — 4 topics, 781 publishes over 2 seconds, values
changing every frame — the file was 14 KB raw and 7 KB gzipped. That is close to
a worst case for compression, because nothing in it repeats.

Projected for a 2.5-minute match with two gamepads at 60 Hz and ten sensors at
20 Hz — roughly 30k events — about **1 MB raw**. Gzip's benefit varies
enormously with the data: a real gamepad recording holds the same axis value
across many consecutive frames, which compresses far better than the demo's
sine wave. Expect anywhere from a tenth to a half of the raw size, and measure
rather than assume.

Per event, after encoding: about **15–25 bytes** for a double-valued topic —
a tag plus varint for the time, one or two bytes for the topic id, and nine for
the `oneof` and the double.

## Example

The binary form is not human-readable, so the same recording as JSON:

```json
{
  "formatVersion": 1,
  "opMode": "DemoTeleOp",
  "startEpochMs": 1759062000000,
  "initTimeUs": 0,
  "startTimeUs": 100000,
  "stopTimeUs": 2108000,
  "truncated": false,
  "topics": [
    { "id": 0, "name": "drive/power",       "javaType": "java.lang.Double",  "valueType": "VALUE_TYPE_DOUBLE", "publishes": 195, "unrecorded": 0 },
    { "id": 1, "name": "cmd/target",        "javaType": "java.lang.Double",  "valueType": "VALUE_TYPE_DOUBLE", "publishes": 196, "unrecorded": 0 },
    { "id": 2, "name": "g1/left_stick_y",   "javaType": "java.lang.Double",  "valueType": "VALUE_TYPE_DOUBLE", "publishes": 195, "unrecorded": 0 },
    { "id": 3, "name": "g1/a",              "javaType": "java.lang.Boolean", "valueType": "VALUE_TYPE_BOOL",   "publishes": 195, "unrecorded": 0 }
  ],
  "samples": [
    { "t": 0,      "topicId": 0, "topic": "drive/power", "value": 0.0 },
    { "t": 92304,  "topicId": 0, "topic": "drive/power", "value": 0.0 },
    { "t": 112022, "topicId": 0, "topic": "drive/power", "value": 0.04997916927067833 }
  ]
}
```

Note the first sample at `t: 0`: `LIFECYCLE_INIT` is the time origin, and the
OpMode's own `onSafeStart` publish happened before the session's clock started,
so relative timestamps are clamped at zero.
