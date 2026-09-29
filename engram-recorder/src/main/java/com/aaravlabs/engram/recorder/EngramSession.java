package com.aaravlabs.engram.recorder;

import com.aaravlabs.engram.recorder.annotation.Recorded;
import com.aaravlabs.synapse.Orchestrator;
import com.aaravlabs.synapse.ftc.BulkReader;
import com.aaravlabs.synapse.ftc.HardwareView;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

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

    private volatile boolean closed;

    private EngramSession(Recorder recorder,
                          CaptureStrategy strategy,
                          Orchestrator effectiveOrchestrator,
                          OutputLocation location,
                          RecorderConfig config) {
        this.recorder = recorder;
        this.strategy = strategy;
        this.effectiveOrchestrator = effectiveOrchestrator;
        this.location = location;
        this.config = config;
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
                                      Path file,
                                      Orchestrator orchestrator,
                                      RecorderConfig config,
                                      String forcedStrategy) throws IOException {
        if (orchestrator == null) throw new IllegalArgumentException("orchestrator must not be null");

        RecorderConfig effectiveConfig = config == null ? RecorderConfig.defaults() : config;
        Recorder recorder = Recorder.open(file, label, effectiveConfig);
        CaptureStrategy strategy;
        try {
            strategy = CaptureStrategies.attach(orchestrator, recorder, forcedStrategy);
        } catch (RuntimeException e) {
            recorder.close();
            throw e;
        }
        return new EngramSession(recorder, strategy, strategy.orchestrator(),
                OutputLocation.of(file.toAbsolutePath().getParent()), effectiveConfig);
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
    public Path file() {
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

    /** Convenience for tests: an explicit file under a temporary directory. */
    public static EngramSession forTesting(Path file, Orchestrator orchestrator) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) {
            parent = Paths.get(".").toAbsolutePath().normalize();
        }
        return start("TestOpMode", file, orchestrator, RecorderConfig.defaults(), null);
    }
}
