package com.aaravlabs.engram.recorder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Bounds how much storage a directory of recordings may occupy, by deleting the
 * oldest ones.
 *
 * <h2>What it prunes on</h2>
 * Two limits, both optional:
 *
 * <ul>
 *   <li><b>Maximum total size</b> — the sum of the recording files' sizes.
 *       Storage is the actual constraint on a Robot Controller: a 32 GB SD card
 *       shared with the RC app and match logs.</li>
 *   <li><b>Maximum recording count</b> — how many files survive. A cheap upper
 *       bound that also stops a season of very small recordings from quietly
 *       accumulating into a directory listing that takes a while to scan.</li>
 * </ul>
 *
 * <p>Deliberately <b>no maximum age</b>. {@link File#lastModified()} is the only
 * timestamp available, its resolution depends on the FAT32 SD card the Robot
 * Controller uses, and a wall-clock policy is the one most likely to delete a
 * recording from this morning's practice on the morning of a competition.
 * Count and size are deterministic, need no clock, and are what the storage
 * limit actually cares about.
 *
 * <h2>What it will never delete</h2>
 *
 * <ul>
 *   <li><b>The recording currently being written.</b> {@link #prune} takes the
 *       in-progress file and skips it, so a pass cannot truncate a live
 *       recording. {@link EngramSession} passes the file it is about to write,
 *       which is the only one it knows to be live.</li>
 *   <li><b>Anything below {@link #minRetained()}.</b> Pruning stops rather than
 *       deleting the last recordings, so an over-tight size limit degrades into
 *       "keeps more than you asked for" rather than "the robot has no
 *       recordings".</li>
 *   <li><b>Files that are not recordings.</b> Only {@code .engram} and
 *       {@code .engram.gz} are considered; anything else in the directory is
 *       left alone.</li>
 * </ul>
 *
 * <h2>Failure policy</h2>
 * Nothing here throws. A file that cannot be deleted is recorded in
 * {@link PruneResult#failures()} and the pass stops there rather than deleting
 * a newer recording to work around an older one that will not go — which is
 * also the likely outcome anyway, since a refused delete usually means the
 * whole directory is read-only. A directory the robot cannot write to is a
 * reason to keep recording, not a reason to abort a match.
 *
 * <h2>Threading</h2>
 * {@link #prune} is synchronous and does filesystem I/O, so it must not be
 * called from a publishing thread. It is called from {@code EngramSession}
 * before the recording starts and on a background thread after it ends.
 * Instances are immutable and thread-safe.
 */
public final class RetentionPolicy {

    /** Suffix of a recording file. */
    public static final String EXTENSION = ".engram";

    /** Suffix of a gzipped recording, which is also prunable. */
    public static final String GZIP_EXTENSION = ".engram.gz";

    /** Default number of recordings kept regardless of the limits. */
    public static final int DEFAULT_MIN_RETAINED = 1;

    private static final RetentionPolicy DISABLED = new RetentionPolicy(0L, 0, DEFAULT_MIN_RETAINED);

    private final long maxTotalBytes;
    private final int maxRecordings;
    private final int minRetained;

    private RetentionPolicy(long maxTotalBytes, int maxRecordings, int minRetained) {
        this.maxTotalBytes = maxTotalBytes;
        this.maxRecordings = maxRecordings;
        this.minRetained = minRetained;
    }

    /**
     * A policy that never deletes anything: what {@link RecorderConfig#defaults()}
     * uses, so an unconfigured recorder behaves exactly as it did before
     * retention existed.
     */
    public static RetentionPolicy disabled() {
        return DISABLED;
    }

    /**
     * A policy built from the common case: at most {@code maxRecordings} files
     * and at most {@code maxTotalBytes} of them, always keeping
     * {@link #DEFAULT_MIN_RETAINED}.
     *
     * @param maxTotalBytes size ceiling in bytes; 0 for no size limit
     * @param maxRecordings file-count ceiling; 0 for no count limit
     */
    public static RetentionPolicy of(long maxTotalBytes, int maxRecordings) {
        return of(maxTotalBytes, maxRecordings, DEFAULT_MIN_RETAINED);
    }

    /** As {@link #of(long, int)}, with an explicit floor. */
    public static RetentionPolicy of(long maxTotalBytes, int maxRecordings, int minRetained) {
        if (maxTotalBytes < 0) throw new IllegalArgumentException("maxTotalBytes must be >= 0, got " + maxTotalBytes);
        if (maxRecordings < 0) throw new IllegalArgumentException("maxRecordings must be >= 0, got " + maxRecordings);
        if (minRetained < 1) throw new IllegalArgumentException("minRetained must be >= 1, got " + minRetained);
        return new RetentionPolicy(maxTotalBytes, maxRecordings, minRetained);
    }

    /** Total bytes of recordings permitted; 0 means unlimited. */
    public long maxTotalBytes() {
        return maxTotalBytes;
    }

    /** Recordings permitted; 0 means unlimited. */
    public int maxRecordings() {
        return maxRecordings;
    }

    /** Recordings always kept, whatever the limits say. */
    public int minRetained() {
        return minRetained;
    }

    /**
     * Whether this policy would delete anything at all.
     *
     * <p>False for {@link #disabled()}, which is the default: retention is
     * opt-in, because a recorder that silently removes files from the robot is
     * a worse surprise than a full SD card.
     */
    public boolean isEnabled() {
        return maxTotalBytes > 0 || maxRecordings > 0;
    }

    /**
     * Deletes a file, reporting whether it went.
     *
     * <p>{@link File#delete()} is the only implementation the recorder uses.
     * The seam exists so the failure path -- a file the robot cannot remove,
     * which is what a full or read-only SD card looks like -- can be tested
     * without needing a filesystem that refuses to cooperate.
     */
    interface FileRemover {

        /** Deletes {@code file}, returning true only if it is gone afterwards. */
        boolean remove(File file);

        /** The real thing. */
        FileRemover DELETE = new FileRemover() {
            @Override
            public boolean remove(File file) {
                return file.delete();
            }
        };
    }

    /**
     * Deletes the oldest recordings in {@code directory} until both limits are
     * satisfied. Synchronous: performs filesystem I/O, so call it off the
     * publish path.
     *
     * @param directory where the recordings live; a missing or unreadable
     *                  directory yields an empty result rather than an exception
     * @param inProgress the recording currently being written, or null if there
     *                   is none. Never deleted.
     * @return what happened, including any file that could not be deleted
     */
    public PruneResult prune(File directory, File inProgress) {
        return prune(directory, inProgress, FileRemover.DELETE);
    }

    /** As {@link #prune(File, File)}, with the deletion strategy supplied. */
    PruneResult prune(File directory, File inProgress, FileRemover remover) {
        if (!isEnabled() || directory == null) {
            return PruneResult.skipped();
        }
        File[] listing;
        try {
            listing = directory.listFiles();
        } catch (SecurityException e) {
            // A directory we cannot even list is a directory we cannot prune.
            return PruneResult.skipped();
        }
        if (listing == null) {
            return PruneResult.skipped();
        }

        List<File> candidates = new ArrayList<>(listing.length);
        int protectedCount = 0;
        long bytesBefore = 0L;
        for (File file : listing) {
            if (!isRecording(file)) {
                continue;
            }
            bytesBefore += file.length();
            if (inProgress != null && sameFile(file, inProgress)) {
                protectedCount++;
                continue;
            }
            candidates.add(file);
        }
        if (candidates.isEmpty()) {
            return new PruneResult(true, 0, 0, protectedCount, protectedCount,
                    0L, bytesBefore, bytesBefore, Collections.<String>emptyList());
        }

        // Oldest first. Names are the tie-break because two runs can share a
        // timestamp to the second, and a stable order keeps a pass repeatable.
        Collections.sort(candidates, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                int byTime = Long.compare(a.lastModified(), b.lastModified());
                return byTime != 0 ? byTime : a.getName().compareTo(b.getName());
            }
        });

        List<String> failures = new ArrayList<>();
        long total = bytesBefore;
        int remaining = candidates.size() + protectedCount;
        int deleted = 0;
        long freed = 0L;

        for (File candidate : candidates) {
            if (withinLimits(total, remaining)) {
                break;
            }
            if (remaining - 1 < minRetained) {
                // The floor: an over-tight limit keeps more than was asked for.
                break;
            }
            long size = candidate.length();
            if (!remover.remove(candidate)) {
                // Stop rather than carry on. The candidates are ordered oldest
                // first, so deleting a newer recording to work around an older
                // one that will not go would keep the wrong file and lose the
                // right one. A refusal here almost always means the whole
                // directory is read-only, where the next delete would fail too.
                failures.add(candidate.getName() + ": " + describe(candidate));
                break;
            }
            deleted++;
            freed += size;
            total -= size;
            remaining--;
        }

        return new PruneResult(true, candidates.size(), deleted, remaining, protectedCount,
                freed, bytesBefore, total, failures);
    }

    private boolean withinLimits(long totalBytes, int count) {
        boolean overCount = maxRecordings > 0 && count > maxRecordings;
        boolean overSize = maxTotalBytes > 0 && totalBytes > maxTotalBytes;
        return !overCount && !overSize;
    }

    /** Whether {@code file} is something this policy is allowed to delete. */
    static boolean isRecording(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String name = file.getName();
        return name.endsWith(GZIP_EXTENSION) || name.endsWith(EXTENSION);
    }

    /**
     * Whether two {@link File}s name the same recording.
     *
     * <p>Canonical paths, so {@code ./run.engram} and {@code run.engram} are
     * recognised as one file. A plain string comparison would let a caller
     * asking for the wrong-looking path have the live recording deleted out
     * from under it, which is the one mistake this class exists to prevent.
     */
    private static boolean sameFile(File a, File b) {
        if (a.equals(b)) {
            return true;
        }
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (IOException e) {
            // A path that cannot be resolved still has a usable absolute form.
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    /** Best-effort explanation for a delete that returned false. */
    private static String describe(File file) {
        if (!file.exists()) {
            return "already gone";
        }
        if (!file.canWrite()) {
            return "not writable";
        }
        return "delete refused by the filesystem";
    }

    @Override
    public String toString() {
        if (!isEnabled()) {
            return "RetentionPolicy{disabled}";
        }
        return "RetentionPolicy{maxTotalBytes=" + maxTotalBytes
                + ", maxRecordings=" + maxRecordings
                + ", minRetained=" + minRetained + '}';
    }
}
