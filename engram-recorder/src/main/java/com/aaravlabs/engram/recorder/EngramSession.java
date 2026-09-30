package com.aaravlabs.engram.recorder;

import com.aaravlabs.engram.recorder.annotation.Recorded;
import com.aaravlabs.synapse.Orchestrator;
import com.aaravlabs.synapse.ftc.BulkReader;
import com.aaravlabs.synapse.ftc.HardwareView;

import java.io.IOException;
import java.io.File;

/**
 * A recording session: a recorder attached to one live orchestrator, for the
 * duration of one OpMode run.
 *
 * <h2>Wiring it into an OpMode</h2>
 *
 * <pre>{@code
 * public class MyTeleOp extends SafeOpMode {
 *     private EngramSession engram;
 *
 *     @Override protected void onSafeInit() {
 *         engram = EngramSession.start(this, orchestrator);
 *         orchestrator = engram.orchestrator();
 *         ...
 *     }
 *
 *     @Override protected void onSafeStart() { engram.markStart(); }
 *
 *     @Override protected void onSafeStop()  { engram.close(); }
 * }
 * }</pre>
 *
 * <p>Assigning {@code orchestrator = engram.orchestrator()} matters only when
 * the decorator strategy is selected; it is a no-op otherwise, and keeping it
 * unconditional means a Synapse upgrade needs no code change.
 *
 * <h2>Sensor publishes</h2>
 *
 * <p>On a Synapse build with the {@code PublishListener} hook, everything is
 * captured automatically. On an older build the decorator cannot see
 * {@code bulkRead} callbacks, and those must be wrapped:
 *
 * <pre>{@code
 * hardware.bulkRead(50, engram.recording(view -> {
 *     view.publish("odom", pose);
 * }));
 * }</pre>
 *
 * <p>{@link #isSensorCaptureAutomatic()} says which case you are in.
 */
public final class EngramSession implements AutoCloseable {

    private final Recorder recorder;
    private final CaptureStrategy strategy;
    private final Orchestrator effectiveOrchestrator;
    private final OutputLocation location;
    private final RecorderConfig config;
    private final java.util.concurrent.atomic.AtomicReference<PruneResult> lastPrune =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicLong prunePasses =
            new java.util.concurrent.atomic.AtomicLong();

    private volatile boolean closed;

    private EngramSession(Recorder recorder,
                          CaptureStrategy strategy,
                          Orchestrator effectiveOrchestrator,
                          OutputLocation location,
                          RecorderConfig config,
                          PruneResult initialPrune) {
        this.recorder = recorder;
        this.strategy = strategy;
        this.effectiveOrchestrator = effectiveOrchestrator;
        this.location = location;
        this.config = config;
        this.lastPrune.set(initialPrune);
        // The pass that ran before recording started counts as one: it is a pass
        // this session saw the results of, and a caller comparing counts across
        // close() must not be surprised by it.
        if (initialPrune != null) {
            this.prunePasses.set(1L);
        }
    }

    /**
     * Starts a session writing to a resolved output directory.
     *
     * <p>If the OpMode class carries {@link Recorded}, its settings are used;
     * otherwise defaults apply.
     *
     * @param opModeContext the OpMode instance; used for its class name and to
     *                      find an Android {@code Context}. May be null.
     * @param orchestrator the live orchestrator, usually {@code this.orchestrator}
     * @throws IOException if the recording file cannot be created
     */
    public static EngramSession start(Object opModeContext, Orchestrator orchestrator) throws IOException {
        Recorded annotation = annotationFor(opModeContext);
        RecorderConfig config = annotation == null
                ? RecorderConfig.defaults()
                : RecorderConfig.builder()
                        .withFlushIntervalMs(annotation.flushIntervalMs())
                        .withMaxEventsPerFlush(annotation.maxEventsPerFlush())
                        .withRetention(retentionFrom(annotation))
                        .withLog(RecorderLog.stderr())
                        .build();
        String label = annotation != null && !annotation.label().isEmpty()
                ? annotation.label()
                : classNameOf(opModeContext);
        String forced = annotation == null ? null : annotation.strategy();

        OutputLocation location = OutputLocation.resolve(opModeContext);
        return start(label, location.newFile(label), orchestrator, config, forced);
    }

    /**
     * Builds the retention policy an annotation asks for.
     *
     * <p>Both limits default to zero, which is {@link RetentionPolicy#disabled()},
     * so an annotation that says nothing about retention prunes nothing.
     */
    private static RetentionPolicy retentionFrom(Recorded annotation) {
        if (annotation == null) {
            return RetentionPolicy.disabled();
        }
        return RetentionPolicy.of(annotation.retentionMaxBytes(), annotation.retentionMaxRecordings(),
                annotation.retentionMinRetained());
    }

    /**
     * Starts a session with an explicit configuration, ignoring any
     * {@link Recorded} annotation.
     *
     * @throws IOException if the recording file cannot be created
     */
    public static EngramSession start(Object opModeContext,
                                      Orchestrator orchestrator,
                                      RecorderConfig config) throws IOException {
        String label = classNameOf(opModeContext);
        OutputLocation location = OutputLocation.resolve(opModeContext);
        return start(label, location.newFile(label), orchestrator, config, null);
    }

    /**
     * Starts a session writing to an exact file. Intended for tests and for
     * callers that manage their own storage.
     *
     * @param forcedStrategy {@code "auto"}, {@code "decorator"}, or
     *                       {@code "publish-listener"}
     * @throws IOException if the recording file cannot be created
     */
    public static EngramSession start(String label,
                                      File file,
                                      Orchestrator orchestrator,
                                      RecorderConfig config,
                                      String forcedStrategy) throws IOException {
        if (orchestrator == null) throw new IllegalArgumentException("orchestrator must not be null");

        RecorderConfig effectiveConfig = config == null ? RecorderConfig.defaults() : config;
        File parent = file.getAbsoluteFile().getParentFile();

        // Retention runs before the file is opened, so the recording that is
        // about to start has room. This is on the OpMode init path, not the
        // publish path, and the whole pass is a directory listing plus a few
        // unlinks -- but a failure here must not stop the run, so it is
        // swallowed.
        PruneResult initialPrune = pruneBeforeRecording(effectiveConfig, parent);

        Recorder recorder = Recorder.open(file, label, effectiveConfig);
        CaptureStrategy strategy;
        try {
            strategy = CaptureStrategies.attach(orchestrator, recorder, forcedStrategy);
        } catch (RuntimeException e) {
            recorder.close();
            throw e;
        }
        return new EngramSession(recorder, strategy, strategy.orchestrator(),
                parent == null ? OutputLocation.of(new File(".")) : OutputLocation.of(parent),
                effectiveConfig, initialPrune);
    }

    /**
     * Frees space before a recording starts, on the init path.
     *
     * <p>Nothing is in progress yet, so there is no file to protect. Every
     * failure is contained: pruning is housekeeping and the recording matters
     * more.
     *
     * @return what the pass did, or null if none ran
     */
    private static PruneResult pruneBeforeRecording(RecorderConfig config, File directory) {
        RetentionPolicy policy = config.retention();
        if (!policy.isEnabled() || directory == null) {
            return null;
        }
        try {
            PruneResult result = policy.prune(directory, null);
            if (result.deleted() > 0 || !result.isClean()) {
                config.log().warn(result.toString());
            }
            return result;
        } catch (Throwable t) {
            config.log().warn("retention pass before recording failed: " + t);
            return null;
        }
    }

    /**
     * The orchestrator to use from here on: the original when the listener
     * strategy is active, a recording wrapper otherwise.
     */
    public Orchestrator orchestrator() {
        return effectiveOrchestrator;
    }

    /** The underlying recorder, for advanced use. */
    public Recorder recorder() {
        return recorder;
    }

    /** The file being written. */
    public File file() {
        return recorder.file();
    }

    /**
     * The settings this session is running with.
     *
     * <p>Worth having for diagnostics: a recording does not record its own flush
     * settings, so after the fact this is the only way to tell how much data a
     * crash could have cost.
     */
    public RecorderConfig config() {
        return config;
    }

    /** The directory the recording was placed in. */
    public OutputLocation location() {
        return location;
    }

    /**
     * The most recent {@link RetentionPolicy} pass, or null if none has run
     * yet.
     *
     * <p>The pass after {@link #close()} is asynchronous, so a telemetry line
     * that reads this immediately after closing may still see the previous
     * result. Null simply means no pass has completed.
     */
    public PruneResult lastPruneResult() {
        return lastPrune.get();
    }

    /**
     * How many {@link RetentionPolicy} passes this session has seen complete,
     * counting the one that ran before recording started.
     *
     * <p>{@link #lastPruneResult()} alone cannot distinguish "no pass has
     * finished yet" from "the pass I was waiting for already finished and
     * {@code this} is its result", because the pass after {@link #close()} is
     * dispatched before {@code close()} returns and frequently finishes first.
     * A counter moves strictly forward, so a caller can read it before closing,
     * then wait for it to pass that reading and know that whatever
     * {@link #lastPruneResult()} returns is from a later pass. Equal-valued
     * passes count as two, which is the point: a pass that legitimately deleted
     * nothing is still a pass that ran.
     *
     * @return the number of completed passes; 0 if none has
     */
    public long completedPrunePasses() {
        return prunePasses.get();
    }

    /** Which capture strategy is in effect: {@code "publish-listener"} or {@code "decorator"}. */
    public String strategyName() {
        return strategy.name();
    }

    /**
     * Whether sensor publishes from {@code bulkRead} are captured without
     * wrapping the reader.
     *
     * <p>False on Synapse builds without the {@code PublishListener} hook, in
     * which case {@link #recording(BulkReader)} must be used.
     */
    public boolean isSensorCaptureAutomatic() {
        return PublishListenerCapture.isAvailable();
    }

    /**
     * Wraps a {@code bulkRead} reader so its publishes are recorded.
     *
     * <p>Only needed when {@link #isSensorCaptureAutomatic()} is false. Safe to
     * use regardless -- it simply returns the reader unchanged when the
     * listener strategy already covers those publishes.
     */
    public BulkReader recording(BulkReader reader) {
        if (reader == null) {
            throw new IllegalArgumentException("reader must not be null");
        }
        if (isSensorCaptureAutomatic()) {
            return reader;
        }
        // HardwareView is bound to whichever orchestrator it is constructed
        // with, so handing the callback a view over the recording orchestrator
        // routes its publishes through the recorder.
        HardwareView recordingView = new HardwareView(effectiveOrchestrator);
        return view -> reader.read(recordingView);
    }

    /** Records the moment the Start button was pressed. Idempotent. */
    public void markStart() {
        recorder.recordStart();
    }

    /** Current counters, for a status display or a post-match log. */
    public RecorderStats stats() {
        return recorder.stats();
    }

    /**
     * Detaches from the orchestrator and finalizes the file.
     *
     * <p>Idempotent, and safe to call from {@code onSafeStop()}: the
     * {@code LIFECYCLE_STOP} event is written before the stream is closed, and
     * an I/O failure is reported by {@link #stats()} rather than thrown.
     *
     * <p>If retention is configured, a prune pass is dispatched to a background
     * thread once the file is closed, so the directory does not grow without
     * bound across a season. It does not delay {@code close()} and cannot
     * affect the recording. The pass is queued rather than dropped: a pass
     * requested while another is running runs after it, because a suppressed
     * pass is a directory that stays over its limit with nothing scheduled to
     * come back for it.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            strategy.detach();
        } finally {
            recorder.close();
            RetentionSweeper.shared().submit(config.retention(), location.directory(),
                    recorder.file(), config.log(), new java.util.function.Consumer<PruneResult>() {
                        @Override
                        public void accept(PruneResult result) {
                            // Result first, count second: a reader that sees
                            // the new count must also see the result it belongs
                            // to.
                            lastPrune.set(result);
                            prunePasses.incrementAndGet();
                        }
                    });
        }
    }

    private static Recorded annotationFor(Object opModeContext) {
        if (opModeContext == null) {
            return null;
        }
        try {
            return opModeContext.getClass().getAnnotation(Recorded.class);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static String classNameOf(Object opModeContext) {
        if (opModeContext == null) {
            return "opmode";
        }
        String simple = opModeContext.getClass().getSimpleName();
        return simple == null || simple.isEmpty() ? "opmode" : simple;
    }

    /** Convenience for tests: an explicit file. */
    public static EngramSession forTesting(File file, Orchestrator orchestrator) throws IOException {
        return start("TestOpMode", file, orchestrator, RecorderConfig.defaults(), null);
    }
}
