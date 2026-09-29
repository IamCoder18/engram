/**
 * Reads and inspects Engram recordings on a desktop.
 *
 * <p>This package depends only on the protobuf runtime and the shared wire
 * format. It does not depend on Synapse, the FTC SDK, or Android: a recording
 * is a self-contained file, and reading one needs none of the machinery that
 * produced it.
 *
 * <p>Start with {@link com.aaravlabs.engram.replay.EngramRecordingReader} to
 * load a file, then query the resulting
 * {@link com.aaravlabs.engram.replay.EngramRecording}. The
 * {@code export} subpackage converts one to JSON, NDJSON, or CSV, and
 * {@code cli} provides the {@code engram} command-line tool.
 */
package com.aaravlabs.engram.replay;
