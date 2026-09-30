package com.aaravlabs.engram.replay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Whether a recording is actually complete, judged only from what the file
 * itself records.
 *
 * <h2>Why this exists</h2>
 * A recording that silently lost data looks exactly like a recording that
 * captured everything. The {@code bulkRead} sensor gap hid for exactly this
 * reason: the file was well formed, every topic in it was healthy, and sensor
 * data was simply absent. {@code engram inspect} now reports completeness so
 * that "nothing is wrong here" and "nothing is missing here" stop being the
 * same statement.
 *
 * <h2>What the format can support</h2>
 * The wire format records one thing the recorder saw, not one thing that
 * happened. Everything below is derived from facts it genuinely carries:
 *
 * <ul>
 *   <li>{@link #UNFINALIZED} — a well-formed file with no {@code
 *       LIFECYCLE_STOP}. The recorder writes STOP immediately before closing
 *       the stream, so its absence means the process was killed, crashed, or
 *       lost power mid-run. This is the strongest completeness signal the
 *       format has.</li>
 *   <li>{@link #TRUNCATED} — the file ends mid-message, so the tail of the run
 *       is missing.</li>
 *   <li>{@link #DECLARED_NEVER_PUBLISHED} — a topic whose declaration was
 *       written but whose first publish was not. The recorder queues a
 *       declaration immediately before the publish that triggers it, so the
 *       only way to see one is a file that stops between the two.</li>
 *   <li>{@link #UNDECLARED_TOPIC} — publishes referencing a topic id whose
 *       declaration never arrived. The recorder never produces this; a file
 *       that does is damaged.</li>
 *   <li>{@link #MISSING_INIT} / {@link #NO_TOPICS} — structural holes that no
 *       recorder-written file has.</li>
 *   <li>{@link #MISSING_EXPECTED_TOPIC} — a caller-supplied expectation. The
 *       format has no notion of which topics <i>should</i> exist, so the only
 *       way to catch a whole topic that never appears is to say so.</li>
 * </ul>
 *
 * <h2>What it cannot support</h2>
 * <b>A topic the capture path never saw at all is invisible.</b> Nothing in the
 * file says a sensor was wired up, so a missing {@code bulkRead} topic is
 * indistinguishable from a robot that had no sensors. This is a property of the
 * format, not of this report, and it is why {@link #MISSING_EXPECTED_TOPIC}
 * takes expectations from the caller: knowing what to look for is the only way
 * to notice its absence.
 *
 * <p>Instances are immutable.
 */
public final class CaptureReport {

    /** No {@code LIFECYCLE_STOP}: the run never finalized. */
    public static final String UNFINALIZED = "unfinalized";

    /** The file ends mid-message. */
    public static final String TRUNCATED = "truncated";

    /** No {@code LIFECYCLE_INIT} to anchor timestamps to. */
    public static final String MISSING_INIT = "missing-init";

    /** No topic was ever declared. */
    public static final String NO_TOPICS = "no-topics";

    /** A topic was declared but never published to. */
    public static final String DECLARED_NEVER_PUBLISHED = "declared-never-published";

    /** A topic id was published to without its declaration arriving. */
    public static final String UNDECLARED_TOPIC = "undeclared-topic";

    /** A topic the caller expected is absent. */
    public static final String MISSING_EXPECTED_TOPIC = "missing-expected-topic";

    /**
     * One way a recording falls short, with a stable code for scripts and a
     * sentence for people.
     */
    public static final class Finding {

        private final String code;
        private final String message;
        private final List<String> topics;

        Finding(String code, String message, List<String> topics) {
            this.code = code;
            this.message = message;
            this.topics = Collections.unmodifiableList(new ArrayList<>(topics));
        }

        /** Stable identifier, safe to match on. */
        public String code() {
            return code;
        }

        /** One line explaining what is missing, without listing the topics. */
        public String message() {
            return message;
        }

        /** The topics this finding is about; empty for whole-file findings. */
        public List<String> topics() {
            return topics;
        }

        @Override
        public String toString() {
            return topics.isEmpty() ? code + ": " + message
                    : code + ": " + message + " (" + String.join(", ", topics) + ")";
        }
    }

    private final boolean finalized;
    private final boolean truncated;
    private final String truncationReason;
    private final int declaredTopics;
    private final int observedTopics;
    private final List<Finding> findings;
    private final List<String> expectedTopics;
    private final List<String> missingExpectedTopics;

    private CaptureReport(boolean finalized,
                          boolean truncated,
                          String truncationReason,
                          int declaredTopics,
                          int observedTopics,
                          List<Finding> findings,
                          List<String> expectedTopics,
                          List<String> missingExpectedTopics) {
        this.finalized = finalized;
        this.truncated = truncated;
        this.truncationReason = truncationReason;
        this.declaredTopics = declaredTopics;
        this.observedTopics = observedTopics;
        this.findings = Collections.unmodifiableList(new ArrayList<>(findings));
        this.expectedTopics = Collections.unmodifiableList(new ArrayList<>(expectedTopics));
        this.missingExpectedTopics = Collections.unmodifiableList(new ArrayList<>(missingExpectedTopics));
    }

    /**
     * Reports on a recording with no caller-supplied expectations.
     *
     * <p>Without expectations, {@link #MISSING_EXPECTED_TOPIC} can never fire,
     * so a topic that was never captured at all goes unreported. See the class
     * documentation.
     */
    public static CaptureReport of(EngramRecording recording) {
        return of(recording, Collections.<String>emptyList());
    }

    /**
     * Reports on a recording, additionally checking that every named topic is
     * present.
     *
     * @param expectedTopicNames topics that should appear; may be empty, but not
     *                           null. Names not in the recording are reported.
     */
    public static CaptureReport of(EngramRecording recording, List<String> expectedTopicNames) {
        if (expectedTopicNames == null) throw new IllegalArgumentException("expectedTopicNames must not be null");

        List<Finding> findings = new ArrayList<>();
        boolean finalized = recording.stopTimeUs() >= 0;

        if (!finalized) {
            findings.add(new Finding(UNFINALIZED,
                    "no LIFECYCLE_STOP: the run was killed, crashed, or lost power before it closed",
                    Collections.<String>emptyList()));
        }
        if (recording.isTruncated()) {
            findings.add(new Finding(TRUNCATED,
                    "the file ends mid-message (" + recording.truncationReason() + "); its tail is missing",
                    Collections.<String>emptyList()));
        }
        if (recording.initTimeUs() < 0) {
            findings.add(new Finding(MISSING_INIT,
                    "no LIFECYCLE_INIT: the recording has no time origin",
                    Collections.<String>emptyList()));
        }

        List<String> neverPublished = new ArrayList<>();
        List<String> undeclared = new ArrayList<>();
        int declared = 0;
        int observed = 0;
        for (TopicInfo topic : recording.topics()) {
            if (topic.isDeclared()) {
                declared++;
            } else {
                undeclared.add(topic.name());
            }
            if (topic.publishCount() > 0) {
                observed++;
            } else {
                neverPublished.add(topic.name());
            }
        }

        if (recording.topics().isEmpty()) {
            findings.add(new Finding(NO_TOPICS, "no topic was ever declared", Collections.<String>emptyList()));
        }
        if (!neverPublished.isEmpty()) {
            findings.add(new Finding(DECLARED_NEVER_PUBLISHED,
                    "declared but never published to, so the stream stopped inside the first publish",
                    neverPublished));
        }
        if (!undeclared.isEmpty()) {
            findings.add(new Finding(UNDECLARED_TOPIC,
                    "published to without a declaration; the file is damaged", undeclared));
        }

        List<String> missing = new ArrayList<>();
        for (String expected : expectedTopicNames) {
            if (!recording.topic(expected).isPresent()) {
                missing.add(expected);
            }
        }
        if (!missing.isEmpty()) {
            findings.add(new Finding(MISSING_EXPECTED_TOPIC,
                    "expected but absent from the recording", missing));
        }

        return new CaptureReport(finalized, recording.isTruncated(), recording.truncationReason(),
                declared, observed, findings, expectedTopicNames, missing);
    }

    /** Whether nothing is missing: no findings at all. */
    public boolean isComplete() {
        return findings.isEmpty();
    }

    /** Whether the run reached {@code LIFECYCLE_STOP}. */
    public boolean isFinalized() {
        return finalized;
    }

    /** Whether the file ends mid-message. */
    public boolean isTruncated() {
        return truncated;
    }

    /** Why parsing stopped early, or null. */
    public String truncationReason() {
        return truncationReason;
    }

    /** Topics whose declaration the recording carried. */
    public int declaredTopicCount() {
        return declaredTopics;
    }

    /** Topics that actually received at least one publish. */
    public int observedTopicCount() {
        return observedTopics;
    }

    /** Everything that is missing, in the order it was detected. */
    public List<Finding> findings() {
        return findings;
    }

    /** Stable codes of {@link #findings()}, for matching in a script. */
    public List<String> codes() {
        List<String> out = new ArrayList<>(findings.size());
        for (Finding f : findings) {
            out.add(f.code());
        }
        return out;
    }

    /** The expectations this report was built with, in order. */
    public List<String> expectedTopics() {
        return expectedTopics;
    }

    /** Which of those expectations were not in the recording. */
    public List<String> missingExpectedTopics() {
        return missingExpectedTopics;
    }

    @Override
    public String toString() {
        if (isComplete()) {
            return "CaptureReport{complete}";
        }
        StringBuilder sb = new StringBuilder("CaptureReport{");
        for (Finding f : findings) {
            if (sb.length() > "CaptureReport{".length()) {
                sb.append("; ");
            }
            sb.append(f);
        }
        return sb.append('}').toString();
    }
}
