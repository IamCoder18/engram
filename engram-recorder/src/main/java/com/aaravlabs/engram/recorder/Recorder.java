package com.aaravlabs.engram.recorder;

import com.aaravlabs.engram.proto.EngramProto;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes a Synapse recording to a file.
 *
 * <p>A recorder is opened once per OpMode run. It writes the file header,
 * records a {@code LIFECYCLE_INIT} event, then accepts every publish through
 * {@link #onPublish(String, Object, long)}. Closing writes
 * {@code LIFECYCLE_STOP} and flushes the stream.
 *
 * <h2>Thread safety</h2>
 * {@link #onPublish} is called on whatever thread is publishing -- the OpMode
 * loop thread, the Synapse hardware thread, or a callback-pool thread -- and is
 * safe to call concurrently from all of them. It does no I/O: the event is
 * encoded to bytes and queued, and a background thread drains the queue to
 * disk.
 *
 * <h2>Platform</h2>
 * Uses {@link File} rather than {@code java.nio.file} because the FTC SDK
 * declares {@code minSdkVersion=24} while {@code java.nio.file} is API 26.
 *
 * <h2>Failure policy</h2>
 * Nothing thrown by this class propagates back into the robot's control flow.
 * A publish that cannot be encoded is recorded as empty bytes and counted in
 * {@link RecorderStats#unencodableValues()}; a stream that has failed drops
 * further publishes and is reported by {@link RecorderStats#failureMessage()}.
 * Recording is diagnostics, and a bad recording must never cost a match.
 *
 * <p>Typical use is through {@link EngramSession}, which also handles attaching
 * to an orchestrator and resolving the output path.
 */
public final class Recorder implements Closeable {

    /** Version stamped into every file header. */
    public static final int FORMAT_VERSION = 1;

    private final File file;
    private final String opModeName;
    private final long startNanos;
    private final ValueEncoder encoder;
    private final EventWriter writer;
    private final TopicRegistry topics;
    private final RecorderConfig config;

    private final AtomicLong publishesRecorded = new AtomicLong();
    private final AtomicLong publishesDropped = new AtomicLong();
    private final AtomicLong unencodableValues = new AtomicLong();
    private final AtomicLong lifecycleEvents = new AtomicLong();

    private volatile boolean closed;
    private volatile boolean startRecorded;

    private Recorder(File file, String opModeName, RecorderConfig config, OutputStream out) throws IOException {
        this.file = file;
        this.opModeName = opModeName;
        this.config = config;
        this.encoder = new ValueEncoder(config);
        this.startNanos = System.nanoTime();

        EngramProto.RecordingHeader header = EngramProto.RecordingHeader.newBuilder()
                .setFormatVersion(FORMAT_VERSION)
                .setOpmodeName(opModeName)
                .setStartEpochMs(System.currentTimeMillis())
                .build();

        // The header is the first message in the file. It is written directly
        // before the writer thread exists, which guarantees no event can
        // precede it -- and it is written exactly once, not queued.
        byte[] framedHeader = ProtoFraming.delimit(header.toByteArray());
        out.write(framedHeader);
        out.flush();

        this.writer = new EventWriter(out, config.flushIntervalMs(), config.maxEventsPerFlush());
        this.topics = new TopicRegistry((declaration, ts) ->
                writer.offer(ProtoFraming.delimit(
                        event(Math.max(0L, (ts - startNanos) / 1_000L), declaration).toByteArray())));

        writer.offer(ProtoFraming.delimit(lifecycleEvent(0, EngramProto.LifecycleEvent.Type.LIFECYCLE_INIT)
                .toByteArray()));
        lifecycleEvents.incrementAndGet();
    }

    /**
     * Opens a recorder writing to {@code file}, creating parent directories.
     *
     * @throws IOException if the file cannot be created
     */
    public static Recorder open(File file, String opModeName) throws IOException {
        return open(file, opModeName, RecorderConfig.defaults());
    }

    /**
     * Opens a recorder writing to {@code file}, creating parent directories.
     *
     * @param opModeName recorded in the header; also used in the default filename
     * @throws IOException if the file cannot be created
     */
    public static Recorder open(File file, String opModeName, RecorderConfig config) throws IOException {
        if (file == null) throw new IllegalArgumentException("file must not be null");
        if (opModeName == null) throw new IllegalArgumentException("opModeName must not be null");
        RecorderConfig effective = config == null ? RecorderConfig.defaults() : config;

        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create directory " + parent);
        }
        OutputStream out = new BufferedOutputStream(new java.io.FileOutputStream(file), 64 * 1024);
        try {
            return new Recorder(file, opModeName, effective, out);
        } catch (IOException | RuntimeException e) {
            try {
                out.close();
            } catch (IOException ignored) {
                // Reporting the original failure matters more.
            }
            throw e;
        }
    }

    /**
     * Records a publish. Called once per {@code Orchestrator.publish}, from the
     * publishing thread.
     *
     * <p>Never throws. Values with no registered encoding are recorded with
     * empty bytes rather than dropped, so the replay tool can show that a topic
     * existed and was active even when its values are unrecoverable.
     *
     * @param timestampNanos a {@link System#nanoTime()} reading taken as close
     *                       to the publish as possible; pass a value at or
     *                       before 0 to have the recorder sample the clock itself
     */
    public void onPublish(String topicName, Object value, long timestampNanos) {
        if (closed || writer.failure() != null) {
            publishesDropped.incrementAndGet();
            return;
        }
        try {
            long nanos = timestampNanos > 0 ? timestampNanos : System.nanoTime();
            long relMicros = Math.max(0L, (nanos - startNanos) / 1_000L);

            int topicId = topics.idFor(topicName, value, encoder, nanos);

            ValueEncoder.Encoded encoded = encoder.encode(value);
            if (!encoded.encoded()) {
                unencodableValues.incrementAndGet();
            }

            EngramProto.TopicPublish publish = EngramProto.TopicPublish.newBuilder()
                    .setTopicId(topicId)
                    .setValue(encoded.value())
                    .build();
            writer.offer(ProtoFraming.delimit(
                    event(relMicros, publish).toByteArray()));
            publishesRecorded.incrementAndGet();
        } catch (Throwable t) {
            // A recorder must never break the caller. Record the problem and
            // keep going; the run is more valuable than the trace.
            publishesDropped.incrementAndGet();
            config.log().warn("failed to record publish to '" + topicName + "': " + t);
        }
    }

    /**
     * Records {@code LIFECYCLE_START}, marking the moment the Start button was
     * pressed. Idempotent: later calls are ignored so a stray call cannot
     * corrupt the timeline.
     */
    public void recordStart() {
        if (closed || startRecorded) {
            return;
        }
        startRecorded = true;
        try {
            long relMicros = Math.max(0L, (System.nanoTime() - startNanos) / 1_000L);
            writer.offer(ProtoFraming.delimit(
                    lifecycleEvent(relMicros, EngramProto.LifecycleEvent.Type.LIFECYCLE_START).toByteArray()));
            lifecycleEvents.incrementAndGet();
        } catch (Throwable t) {
            config.log().warn("failed to record lifecycle start: " + t);
        }
    }

    /**
     * Writes {@code LIFECYCLE_STOP} and flushes everything to disk.
     *
     * <p>Idempotent. I/O failures are latched into
     * {@link #stats() failureMessage} rather than thrown, so a shutdown path
     * never has to handle them.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            long relMicros = Math.max(0L, (System.nanoTime() - startNanos) / 1_000L);
            writer.offer(ProtoFraming.delimit(
                    lifecycleEvent(relMicros, EngramProto.LifecycleEvent.Type.LIFECYCLE_STOP).toByteArray()));
            lifecycleEvents.incrementAndGet();
        } catch (Throwable t) {
            config.log().warn("failed to record lifecycle stop: " + t);
        } finally {
            writer.close();
        }
    }

    /** The file being written. */
    public File file() {
        return file;
    }

    /** The OpMode name recorded in the header. */
    public String opModeName() {
        return opModeName;
    }

    /** Number of distinct topics declared so far. */
    public int topicCount() {
        return topics.size();
    }

    /** Current counters. Cheap enough to call from a status display. */
    public RecorderStats stats() {
        IOException failure = writer.failure();
        return new RecorderStats(
                publishesRecorded.get(),
                publishesDropped.get(),
                unencodableValues.get(),
                writer.writtenEventCount() + lifecycleEvents.get(),
                writer.writtenByteCount(),
                failure == null ? null : failure.toString());
    }

    /** Blocks until queued events are on disk. Intended for tests and diagnostics. */
    public void flush() {
        writer.flushNow();
    }

    private static EngramProto.RecordingEvent lifecycleEvent(long relMicros, EngramProto.LifecycleEvent.Type type) {
        return event(relMicros, EngramProto.LifecycleEvent.newBuilder().setType(type).build());
    }

    private static EngramProto.RecordingEvent event(long relMicros, EngramProto.LifecycleEvent lifecycle) {
        return EngramProto.RecordingEvent.newBuilder().setRelTimeUs(relMicros).setLifecycle(lifecycle).build();
    }

    private static EngramProto.RecordingEvent event(long relMicros, EngramProto.TopicDeclaration declaration) {
        return EngramProto.RecordingEvent.newBuilder().setRelTimeUs(relMicros).setTopicDeclaration(declaration).build();
    }

    private static EngramProto.RecordingEvent event(long relMicros, EngramProto.TopicPublish publish) {
        return EngramProto.RecordingEvent.newBuilder().setRelTimeUs(relMicros).setPublish(publish).build();
    }
}
