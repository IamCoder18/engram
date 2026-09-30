package com.aaravlabs.engram.recorder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one {@link RetentionPolicy#prune} pass actually did.
 *
 * <p>Immutable, and safe to read from a status display or a post-match log.
 * Every failure is recorded rather than thrown: a recording that cannot be
 * deleted is a problem worth reporting, but it is never a reason to interrupt
 * a run.
 *
 * <p>Instances are thread-safe.
 */
public final class PruneResult {

    private final boolean enabled;
    private final int candidates;
    private final int deleted;
    private final int retained;
    private final int protectedCount;
    private final long bytesFreed;
    private final long bytesBefore;
    private final long bytesAfter;
    private final List<String> failures;

    PruneResult(boolean enabled,
                int candidates,
                int deleted,
                int retained,
                int protectedCount,
                long bytesFreed,
                long bytesBefore,
                long bytesAfter,
                List<String> failures) {
        this.enabled = enabled;
        this.candidates = candidates;
        this.deleted = deleted;
        this.retained = retained;
        this.protectedCount = protectedCount;
        this.bytesFreed = bytesFreed;
        this.bytesBefore = bytesBefore;
        this.bytesAfter = bytesAfter;
        this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
    }

    /** The result of a pass that was not needed or not permitted to run. */
    static PruneResult skipped() {
        return new PruneResult(false, 0, 0, 0, 0, 0L, 0L, 0L, Collections.emptyList());
    }

    /** Whether a policy was configured at all. False means nothing was touched. */
    public boolean isEnabled() {
        return enabled;
    }

    /** How many recording files the pass considered. */
    public int candidates() {
        return candidates;
    }

    /** How many files were deleted. */
    public int deleted() {
        return deleted;
    }

    /** How many recordings remain in the directory, including protected ones. */
    public int retained() {
        return retained;
    }

    /**
     * How many candidates were skipped because they were the recording currently
     * being written.
     *
     * <p>Always at most one. Present so a pass that deletes nothing can
     * explain itself.
     */
    public int protectedCount() {
        return protectedCount;
    }

    /** Bytes reclaimed by the deletions. */
    public long bytesFreed() {
        return bytesFreed;
    }

    /** Total bytes the recordings occupied before the pass. */
    public long bytesBefore() {
        return bytesBefore;
    }

    /** Total bytes the recordings occupy after the pass. */
    public long bytesAfter() {
        return bytesAfter;
    }

    /**
     * One entry per file that could not be deleted, of the form
     * {@code name: reason}. Empty when the pass went cleanly.
     */
    public List<String> failures() {
        return failures;
    }

    /** Whether every file selected for deletion was actually removed. */
    public boolean isClean() {
        return failures.isEmpty();
    }

    @Override
    public String toString() {
        if (!enabled) {
            return "PruneResult{disabled}";
        }
        StringBuilder sb = new StringBuilder("PruneResult{kept=").append(retained)
                .append(", deleted=").append(deleted)
                .append(", protected=").append(protectedCount)
                .append(", freed=").append(bytesFreed).append("B, remaining=")
                .append(bytesAfter).append('B');
        if (!failures.isEmpty()) {
            sb.append(", failures=").append(failures);
        }
        return sb.append('}').toString();
    }
}
