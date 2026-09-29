/**
 * Records Synapse topic traffic to a compact, self-describing file.
 *
 * <p>Start with {@link com.aaravlabs.engram.recorder.EngramSession}, which owns
 * the recorder, attaches it to a live orchestrator, and handles the lifecycle.
 *
 * <p>Nothing in this package depends on the FTC SDK or on Android, so it
 * compiles and tests on a plain desktop JVM. For how to wire it into an OpMode,
 * and for the one gap the decorator strategy has on older Synapse builds, see
 * {@link com.aaravlabs.engram.recorder.EngramSession} and the project's
 * {@code docs/SYNAPSE-INTEGRATION.md}.
 */
package com.aaravlabs.engram.recorder;
