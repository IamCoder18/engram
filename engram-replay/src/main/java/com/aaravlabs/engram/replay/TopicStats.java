package com.aaravlabs.engram.replay;

import java.util.Optional;

/**
 * Aggregate statistics for one topic over a time range.
 *
 * <p>The numeric accessors summarise only the values that decoded to a
 * {@link Number}; a topic mixing numbers and strings will report the numbers
 * and {@link #numericCount()} will be lower than {@link #publishCount()}.
 */
public final class TopicStats {

    private final int publishCount;
    private final int numericCount;
    private final int unrecordedCount;
    private final long firstTimeUs;
    private final long lastTimeUs;
    private final double min;
    private final double max;
    private final double sum;

    TopicStats(int publishCount,
               int numericCount,
               int unrecordedCount,
               long firstTimeUs,
               long lastTimeUs,
               double min,
               double max,
               double sum) {
        this.publishCount = publishCount;
        this.numericCount = numericCount;
        this.unrecordedCount = unrecordedCount;
        this.firstTimeUs = firstTimeUs;
        this.lastTimeUs = lastTimeUs;
        this.min = min;
        this.max = max;
        this.sum = sum;
    }

    /** Publishes in range, including unrecordable ones. */
    public int publishCount() {
        return publishCount;
    }

    /** Publishes in range whose value decoded to a number. */
    public int numericCount() {
        return numericCount;
    }

    /** Publishes in range whose value was not representable. */
    public int unrecordedCount() {
        return unrecordedCount;
    }

    /** Timestamp of the first publish in range, or empty if there were none. */
    public Optional<Long> firstTimeUs() {
        return firstTimeUs < 0 ? Optional.empty() : Optional.of(firstTimeUs);
    }

    /** Timestamp of the last publish in range, or empty if there were none. */
    public Optional<Long> lastTimeUs() {
        return lastTimeUs < 0 ? Optional.empty() : Optional.of(lastTimeUs);
    }

    /** Span between the first and last publish in range; 0 if fewer than two. */
    public long activeSpanUs() {
        return firstTimeUs < 0 || lastTimeUs < firstTimeUs ? 0 : lastTimeUs - firstTimeUs;
    }

    /**
     * Average publish rate over {@link #activeSpanUs()}, in hertz.
     *
     * @return 0 when fewer than two publishes, since a rate over a zero-length
     *         interval is not meaningful
     */
    public double averageRateHz() {
        long span = activeSpanUs();
        if (span <= 0) {
            return 0.0;
        }
        return publishCount * 1_000_000.0 / span;
    }

    /** Smallest numeric value in range, or empty if there were no numeric values. */
    public Optional<Double> min() {
        return numericCount == 0 ? Optional.empty() : Optional.of(min);
    }

    /** Largest numeric value in range, or empty if there were no numeric values. */
    public Optional<Double> max() {
        return numericCount == 0 ? Optional.empty() : Optional.of(max);
    }

    /** Mean of the numeric values in range, or empty if there were none. */
    public Optional<Double> mean() {
        return numericCount == 0 ? Optional.empty() : Optional.of(sum / numericCount);
    }

    @Override
    public String toString() {
        return "TopicStats{publishes=" + publishCount
                + ", numeric=" + numericCount
                + ", unrecorded=" + unrecordedCount
                + ", spanUs=" + activeSpanUs()
                + ", rateHz=" + String.format("%.2f", averageRateHz())
                + (numericCount == 0 ? "" : ", min=" + min + ", max=" + max)
                + '}';
    }
}
