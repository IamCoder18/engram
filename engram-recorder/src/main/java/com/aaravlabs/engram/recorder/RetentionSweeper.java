package com.aaravlabs.engram.recorder;

import java.io.File;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs {@link RetentionPolicy} passes off the publish path.
 *
 * <p>Pruning is filesystem work — a directory listing plus one {@code delete}
 * per candidate — and the project's non-negotiable is that nothing on the
 * recording path blocks a publishing thread. So the pass that runs once a
 * recording has finished is dispatched to a background thread here rather than
 * being done inline.
 *
 * <h2>One pass at a time, none dropped</h2>
 * Passes are serialised on a single daemon worker rather than run concurrently
 * and rather than suppressed while another is running.
 *
 * <p>Suppressing one looks harmless — the next session's pass will see a
 * directory that is at least as over budget — and it is not. A suppressed pass
 * is a directory that stays over its configured limit, with nothing scheduled to
 * come back for it: the next pass is a session away, and on a robot that may be
 * the next practice session rather than the next run. The bound is the whole
 * point of a retention policy, so a requested pass is a promise, not a
 * suggestion.
 *
 * <p>Running them concurrently is not the alternative. Two passes over the same
 * directory can both select the same oldest file; the loser's {@code delete}
 * then returns false, and {@link RetentionPolicy} treats a refused delete as
 * fatal and stops — so the pair can leave the directory further over the limit
 * than either pass would have on its own. One worker keeps each pass's view of
 * the directory true for as long as it runs.
 *
 * <p>The queue holds at most one entry per finished session and is drained by
 * that one worker, so it cannot grow during a match; the worker itself times
 * out after {@link #WORKER_IDLE_SECONDS} idle seconds, so a recorder that
 * prunes once per run does not park a thread for the rest of the meet.
 *
 * <p>Daemon, so a wedged filesystem cannot hold up JVM exit.
 */
final class RetentionSweeper {

    /** How long an idle worker thread is kept before it is reclaimed. */
    private static final long WORKER_IDLE_SECONDS = 30L;

    private static final RetentionSweeper INSTANCE = new RetentionSweeper();

    private final ThreadPoolExecutor worker;

    private RetentionSweeper() {
        this.worker = new ThreadPoolExecutor(1, 1, WORKER_IDLE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "engram-prune");
                thread.setDaemon(true);
                return thread;
            }
        });
        // Nothing is pruned until a pass is requested, so the worker is created
        // on first use and given up again once retention goes quiet.
        this.worker.allowCoreThreadTimeOut(true);
    }

    static RetentionSweeper shared() {
        return INSTANCE;
    }

    /**
     * Discards {@code directory}'s oldest recordings on a background thread.
     *
     * <p>Never throws and never blocks: a failure to start the thread, a
     * failing delete, or a rejecting log all degrade to a warning.
     *
     * @param inProgress a recording that must survive the pass; may be null
     * @param onResult   receives the outcome, for telemetry; may be null
     */
    void submit(final RetentionPolicy policy,
                final File directory,
                final File inProgress,
                final RecorderLog log,
                final Consumer<PruneResult> onResult) {
        if (policy == null || !policy.isEnabled() || directory == null) {
            return;
        }
        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    runPass(policy, directory, inProgress, log, onResult);
                }
            });
        } catch (RejectedExecutionException e) {
            warn(log, "could not start the retention pass: " + e);
        }
    }

    private void runPass(RetentionPolicy policy,
                         File directory,
                         File inProgress,
                         RecorderLog log,
                         Consumer<PruneResult> onResult) {
        PruneResult result = null;
        try {
            result = policy.prune(directory, inProgress);
            if (result.deleted() > 0 || !result.isClean()) {
                warn(log, result.toString());
            }
            for (String failure : result.failures()) {
                warn(log, "could not delete " + failure);
            }
        } catch (Throwable t) {
            // Pruning is housekeeping. It must never be the reason a run
            // reports a failure.
            warn(log, "retention pass failed: " + t);
        }
        if (onResult != null && result != null) {
            try {
                onResult.accept(result);
            } catch (RuntimeException e) {
                warn(log, "retention callback threw: " + e);
            }
        }
    }

    private static void warn(RecorderLog log, String message) {
        if (log == null) {
            return;
        }
        try {
            log.warn(message);
        } catch (RuntimeException e) {
            // A log that throws must not take the sweeper with it.
        }
    }
}
