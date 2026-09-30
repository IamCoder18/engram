package com.aaravlabs.engram.recorder;

import com.aaravlabs.engram.replay.EngramRecordingReader;
import com.aaravlabs.engram.recorder.annotation.Recorded;
import com.aaravlabs.synapse.Orchestrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the retention policy: which files it may delete, which it must never
 * delete, and what it does when the filesystem disagrees.
 *
 * <p>Deleting a recording is irreversible and it happens on a robot with no
 * screen, so the "never delete" rules are asserted as directly as the selection
 * rules rather than inferred from the happy path.
 */
class RetentionPolicyTest {

    /**
     * An arbitrary fixed point in the past for fixture modification times, so
     * "oldest" is decided by the test rather than by the clock.
     */
    private static final long BASE = 1_600_000_000_000L;

    private Orchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = Orchestrator.create("retention-test");
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    // ---- defaults --------------------------------------------------------

    @Test
    void retentionIsOffUnlessConfigured() {
        assertFalse(RecorderConfig.defaults().retention().isEnabled(),
                "an unconfigured recorder must delete nothing, exactly as before retention existed");
        assertFalse(RecorderConfig.builder().build().retention().isEnabled());
        assertFalse(RecorderConfig.builder().withRetention(null).build().retention().isEnabled(),
                "a null policy degrades to disabled rather than to something destructive");
        assertFalse(RetentionPolicy.of(0L, 0).isEnabled());
        assertFalse(RetentionPolicy.of(0L, 0, RetentionPolicy.DEFAULT_MIN_RETAINED).isEnabled());
    }

    @Test
    void thePolicyExposesItsLimits() {
        RetentionPolicy policy = RetentionPolicy.of(1_000L, 3, 2);
        assertEquals(1_000L, policy.maxTotalBytes());
        assertEquals(3, policy.maxRecordings());
        assertEquals(2, policy.minRetained());
        assertTrue(policy.isEnabled());
        assertTrue(policy.toString().contains("maxRecordings=3"), policy.toString());
        assertTrue(RetentionPolicy.disabled().toString().contains("disabled"));
    }

    @Test
    void nonsensicalLimitsAreRejectedAtConfigurationTime() {
        assertThrows(IllegalArgumentException.class, () -> RetentionPolicy.of(-1L, 1));
        assertThrows(IllegalArgumentException.class, () -> RetentionPolicy.of(1L, -1));
        assertThrows(IllegalArgumentException.class, () -> RetentionPolicy.of(1L, 1, 0));
    }

    @Test
    void aDisabledPolicyDeletesNothingAtAll(@TempDir File dir) throws IOException {
        recording(dir, "a.engram", 100, BASE);
        recording(dir, "b.engram", 100, BASE + 1_000L);

        PruneResult result = RetentionPolicy.disabled().prune(dir, null);

        assertFalse(result.isEnabled());
        assertEquals(0, result.deleted());
        assertEquals(2, recordings(dir).size());
        assertEquals("PruneResult{disabled}", result.toString());
    }

    // ---- selection -------------------------------------------------------

    @Test
    void deletesTheOldestRecordingsUntilTheCountLimitIsMet(@TempDir File dir) throws IOException {
        File oldest = recording(dir, "aaa.engram", 100, BASE);
        File middle = recording(dir, "bbb.engram", 100, BASE + 1_000L);
        File newest = recording(dir, "ccc.engram", 100, BASE + 2_000L);

        PruneResult result = RetentionPolicy.of(0L, 2).prune(dir, null);

        assertEquals(1, result.deleted());
        assertFalse(oldest.exists(), "the oldest recording is the one to go");
        assertTrue(middle.exists());
        assertTrue(newest.exists(), "the newest recording is never the first casualty");
        assertEquals(2, result.retained());
        assertEquals(100L, result.bytesFreed());
        assertTrue(result.isClean());
    }

    @Test
    void deletesOldestFirstUntilTheSizeLimitIsMet(@TempDir File dir) throws IOException {
        // 1600 bytes present, 900 permitted: deleting the two oldest leaves
        // 800, which is under the limit, so the pass must stop there.
        File a = recording(dir, "a.engram", 400, BASE);
        File b = recording(dir, "b.engram", 400, BASE + 1_000L);
        recording(dir, "c.engram", 400, BASE + 2_000L);
        File d = recording(dir, "d.engram", 400, BASE + 3_000L);

        PruneResult result = RetentionPolicy.of(900L, 0).prune(dir, null);

        assertEquals(2, result.deleted());
        assertEquals(800L, result.bytesAfter());
        assertTrue(result.bytesAfter() <= 900L, "the size limit must actually be met");
        assertEquals(1600L, result.bytesBefore());
        assertFalse(a.exists());
        assertFalse(b.exists());
        assertTrue(d.exists());
    }

    @Test
    void stopsAsSoonAsBothLimitsAreSatisfied(@TempDir File dir) throws IOException {
        recording(dir, "a.engram", 100, BASE);
        recording(dir, "b.engram", 100, BASE + 1_000L);

        PruneResult result = RetentionPolicy.of(1_000_000L, 50).prune(dir, null);

        assertEquals(0, result.deleted());
        assertEquals(2, recordings(dir).size());
        assertEquals(200L, result.bytesAfter());
    }

    @Test
    void anEmptyDirectoryIsNotAnError(@TempDir File dir) {
        PruneResult result = RetentionPolicy.of(1L, 1).prune(dir, null);

        assertTrue(result.isEnabled());
        assertEquals(0, result.candidates());
        assertEquals(0, result.deleted());
        assertTrue(result.isClean());
    }

    @Test
    void aMissingDirectoryIsNotAnError(@TempDir File dir) {
        PruneResult result = RetentionPolicy.of(1L, 1).prune(new File(dir, "absent"), null);

        assertFalse(result.isEnabled());
        assertEquals(0, result.deleted());
    }

    // ---- what must never be deleted --------------------------------------

    @Test
    void neverDeletesBelowTheFloorTheUserSet(@TempDir File dir) throws IOException {
        recording(dir, "a.engram", 100, BASE);
        recording(dir, "b.engram", 100, BASE + 1_000L);
        recording(dir, "c.engram", 100, BASE + 2_000L);

        // A size limit of one byte would delete everything, but the floor of 2
        // holds two recordings no matter what.
        PruneResult result = RetentionPolicy.of(1L, 1, 2).prune(dir, null);

        assertEquals(1, result.deleted(), "only as many as it takes to respect the floor");
        assertEquals(2, result.retained());
        assertEquals(2, recordings(dir).size());
        assertTrue(result.bytesAfter() > 1L);
    }

    @Test
    void neverDeletesTheRecordingBeingWritten(@TempDir File dir) throws IOException {
        // The live recording is the oldest file in the directory, which is
        // exactly the one a naive "delete the oldest" pass would remove.
        File live = recording(dir, "live.engram", 100, BASE);
        File older = recording(dir, "older.engram", 100, BASE + 1_000L);

        PruneResult result = RetentionPolicy.of(1L, 1).prune(dir, live);

        assertTrue(live.exists(), "the recording currently being written must survive");
        assertFalse(older.exists(), "the next-oldest is fair game");
        assertEquals(1, result.protectedCount());
        assertEquals(1, result.retained());
        assertTrue(result.bytesAfter() > 1L,
                "staying above a size limit is the price of not truncating a live recording");
    }

    @Test
    void protectsTheInProgressFileWhosePathIsWrittenDifferently(@TempDir File dir) throws IOException {
        File live = recording(dir, "live.engram", 100, BASE);
        recording(dir, "other.engram", 100, BASE + 1_000L);

        // A caller holding "./live.engram" must still be recognised as holding
        // the same file; comparing the literal strings would not.
        File awkward = new File(dir, "." + File.separator + "live.engram");
        PruneResult result = RetentionPolicy.of(1L, 1).prune(dir, awkward);

        assertEquals(1, result.protectedCount());
        assertTrue(live.exists());
    }

    @Test
    void leavesEverythingThatIsNotARecordingAlone(@TempDir File dir) throws IOException {
        File notes = new File(dir, "notes.txt");
        writeFile(notes, "keep me".getBytes("UTF-8"), BASE);
        File partial = new File(dir, "half-written.engram.tmp");
        writeFile(partial, "keep me too".getBytes("UTF-8"), BASE + 1_000L);
        File subdir = new File(dir, "archive.engram");
        assertTrue(subdir.mkdirs());
        File older = recording(dir, "older.engram", 100, BASE + 2_000L);
        File newest = recording(dir, "newest.engram", 100, BASE + 3_000L);

        PruneResult result = RetentionPolicy.of(1L, 1).prune(dir, null);

        assertEquals(1, result.deleted());
        assertFalse(older.exists());
        assertTrue(newest.exists());
        assertTrue(notes.exists(), "a file that is not a recording is not ours to delete");
        assertTrue(partial.exists(), "a partially-written temporary is not ours to delete either");
        assertTrue(subdir.isDirectory(), "a directory is not a recording");
    }

    @Test
    void countsGzippedRecordingsToo(@TempDir File dir) throws IOException {
        File plain = recording(dir, "old.engram", 100, BASE);
        File zipped = recording(dir, "newer.engram.gz", 100, BASE + 1_000L);

        PruneResult result = RetentionPolicy.of(1L, 1).prune(dir, null);

        assertEquals(2, result.candidates(), "both extensions are recordings");
        assertEquals(1, result.deleted());
        assertTrue(zipped.exists(), "the newer of the two survives");
        assertFalse(plain.exists());
    }

    @Test
    void aZeroLengthRecordingIsPrunedLikeAnyOther(@TempDir File dir) throws IOException {
        // What a crash between opening the file and the first flush leaves: a
        // file with a valid header and nothing in it.
        File empty = recording(dir, "crashed.engram", 0, BASE);
        recording(dir, "good.engram", 100, BASE + 1_000L);

        PruneResult result = RetentionPolicy.of(1L, 1).prune(dir, null);

        assertEquals(1, result.deleted());
        assertFalse(empty.exists());
        assertTrue(new File(dir, "good.engram").exists());
    }

    // ---- failure handling ------------------------------------------------

    @Test
    void aRecordingThatCannotBeDeletedStopsThePassRatherThanLosingANewerOne(@TempDir File dir) throws IOException {
        File oldest = recording(dir, "a.engram", 100, BASE);
        File stubborn = recording(dir, "b.engram", 100, BASE + 1_000L);
        File newest = recording(dir, "c.engram", 100, BASE + 2_000L);

        // A removal that always fails on one file models a read-only or full SD
        // card: exactly the situation retention exists for, and one no portable
        // in-process filesystem fixture can produce.
        PruneResult result = RetentionPolicy.of(1L, 1).prune(dir, null, refusing(oldest));

        assertEquals(0, result.deleted(), "the pass must not delete a newer file to route around an older one");
        assertFalse(result.isClean());
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().get(0).startsWith("a.engram: "), result.failures().toString());
        assertTrue(oldest.exists());
        assertTrue(stubborn.exists());
        assertTrue(newest.exists());
        assertEquals(300L, result.bytesAfter());
        assertEquals(3, result.retained());
        assertTrue(result.toString().contains("failures="), result.toString());    }

    @Test
    void theCountsStayConsistentWhenEveryDeletionFails(@TempDir File dir) throws IOException {
        recording(dir, "a.engram", 100, BASE);
        recording(dir, "b.engram", 100, BASE + 1_000L);

        PruneResult result = RetentionPolicy.of(1L, 1).prune(dir, null, refusingAll());

        assertEquals(0, result.deleted());
        assertEquals(2, result.retained(), "files that survived are still retained");
        assertEquals(200L, result.bytesAfter());
        assertEquals(0L, result.bytesFreed());
        assertEquals(1, result.failures().size(), "the pass stopped at the first refusal");
    }

    @Test
    void aRemovalThatThrowsIsLeftForTheCallerToContain(@TempDir File dir) throws IOException {
        // The policy does not pretend a mid-pass failure did not happen; the
        // sweeper is what catches it, and the session-start pass catches it too.
        recording(dir, "a.engram", 100, BASE);
        recording(dir, "b.engram", 100, BASE + 1_000L);

        RetentionPolicy.FileRemover exploding = new RetentionPolicy.FileRemover() {
            @Override
            public boolean remove(File file) {
                throw new IllegalStateException("the filesystem is having a day");
            }
        };
        assertThrows(IllegalStateException.class,
                () -> RetentionPolicy.of(1L, 1).prune(dir, null, exploding));
        assertEquals(2, recordings(dir).size(), "nothing was deleted before the failure");
    }

    @Test
    void aSessionRecordsCorrectlyAfterPruningHasFreedSpace(@TempDir File dir) throws Exception {
        // The realistic sequence: the directory is over budget, the pass runs
        // on the init path, and the recording that follows is intact.
        for (int i = 0; i < 6; i++) {
            recording(dir, "old" + i + ".engram", 100, BASE + i * 1_000L);
        }
        EngramSession session = EngramSession.start("Resilient", new File(dir, "run.engram"),
                orchestrator, configWith(RetentionPolicy.of(300L, 3)), null);
        // The count is asserted before close(), not after: close() dispatches a
        // pass that deletes from this very directory, so "how many are here" is
        // only a settled question once that pass has finished.
        assertEquals(4, recordings(dir).size(),
                "three of the six old recordings went; the fourth is the one being made");
        session.orchestrator().publish("t", 1.0);
        session.orchestrator().publish("t", 2.0);
        long passesBefore = session.completedPrunePasses();
        session.close();

        // And once the post-close pass has finished, the directory is within the
        // limit it was given.
        awaitFreshPrune(session, passesBefore);
        assertTrue(recordings(dir).size() <= 3, "the post-close pass brings the directory within its limit");
        assertTrue(new File(dir, "run.engram").exists(), "and never at the expense of the live recording");
        assertEquals(2, EngramRecordingReader.read(new File(dir, "run.engram"))
                .topic("t").orElseThrow().publishCount());
    }

    // ---- wiring into the session -----------------------------------------

    @Test
    void aSessionWithoutRetentionNeverDeletesAnything(@TempDir File dir) throws IOException {
        for (int i = 0; i < 5; i++) {
            recording(dir, "old" + i + ".engram", 100, BASE + i * 1_000L);
        }
        EngramSession session = EngramSession.start(
                "NoRetention", new File(dir, "new.engram"), orchestrator, RecorderConfig.defaults(), null);
        session.close();

        assertEquals(6, recordings(dir).size());
        assertNull(session.lastPruneResult(), "no pass should have run at all");
    }

    @Test
    void aSessionFreesSpaceBeforeItStartsRecording(@TempDir File dir) throws Exception {
        for (int i = 0; i < 5; i++) {
            recording(dir, "old" + i + ".engram", 100, BASE + i * 1_000L);
        }
        EngramSession session = EngramSession.start("Pruning", new File(dir, "new.engram"),
                orchestrator, configWith(RetentionPolicy.of(300L, 0)), null);
        // Asserted before close(), not after: close() dispatches a pass over
        // this same directory, and the @TempDir cleanup would otherwise race
        // with it rather than with the assertion.
        assertEquals(4, recordings(dir).size(),
                "two of the five old recordings went; the fourth is the one being made");
        assertNotNull(session.lastPruneResult(), "the pre-record pass is reported");
        assertEquals(2, session.lastPruneResult().deleted());
        long passesBefore = session.completedPrunePasses();
        session.close();
        awaitFreshPrune(session, passesBefore);
    }

    @Test
    void aSessionPrunesTheOldestAfterItEnds(@TempDir File dir) throws Exception {
        recording(dir, "ancient.engram", 100, BASE);

        EngramSession first = EngramSession.start("First", new File(dir, "first.engram"),
                orchestrator, configWith(RetentionPolicy.of(0L, 2)), null);
        long firstPasses = first.completedPrunePasses();
        first.close();
        assertEquals(0, awaitFreshPrune(first, firstPasses).deleted(), "nothing to trim yet");
        assertEquals(2, recordings(dir).size());

        EngramSession second = EngramSession.start("Second", new File(dir, "second.engram"),
                orchestrator, configWith(RetentionPolicy.of(0L, 2)), null);
        long secondPasses = second.completedPrunePasses();
        second.close();

        // The pass after close() runs on a background thread.
        assertEquals(1, awaitFreshPrune(second, secondPasses).deleted(), "the third file tips the directory over the limit");
        assertEquals(2, recordings(dir).size(), "the limit holds across runs");
        assertTrue(new File(dir, "second.engram").exists(), "the recording just made is never deleted");
        assertTrue(new File(dir, "first.engram").exists());
        assertFalse(new File(dir, "ancient.engram").exists(), "the oldest goes first");
    }

    @Test
    void theRecordingJustWrittenSurvivesItsOwnPostClosePass(@TempDir File dir) throws Exception {
        EngramSession session = EngramSession.start("Solo", new File(dir, "solo.engram"),
                orchestrator, configWith(RetentionPolicy.of(1L, 1)), null);
        for (int i = 0; i < 50; i++) {
            session.orchestrator().publish("t", (double) i);
        }
        assertNotNull(session.lastPruneResult(), "the pass before recording started is reported");
        assertEquals(1L, session.completedPrunePasses(), "and it is counted as one pass");
        long passesBefore = session.completedPrunePasses();
        session.close();
        assertEquals(0, awaitFreshPrune(session, passesBefore).deleted());

        assertTrue(new File(dir, "solo.engram").exists(),
                "a one-recording directory must keep its recording");
        assertEquals(1, recordings(dir).size());
        // And the recording is still readable: retention never corrupts one.
        assertEquals(50, EngramRecordingReader.read(new File(dir, "solo.engram"))
                .topic("t").orElseThrow().publishCount());
    }

    @Test
    void theAnnotationCanConfigureRetention() throws Exception {
        EngramSession session = EngramSession.start(new AnnotatedForRetention(), orchestrator);
        try {
            assertEquals(2, session.config().retention().maxRecordings());
            assertEquals(64L, session.config().retention().maxTotalBytes());
            assertTrue(session.config().retention().isEnabled());
        } finally {
            // The annotation enables retention, so closing dispatches a pass
            // over the shared output directory. It is waited for rather than
            // left running into the next test.
            long passesBefore = session.completedPrunePasses();
            session.close();
            awaitFreshPrune(session, passesBefore);
            session.file().delete();
        }
    }

    @Test
    void anAnnotationWithoutRetentionAttributesPrunesNothing() {
        assertFalse(RetentionPolicy.of(0L, 0, RetentionPolicy.DEFAULT_MIN_RETAINED).isEnabled());
    }

    @Test
    void aPassRequestedWhileAnotherIsRunningIsNotDiscarded(@TempDir File dir) throws Exception {
        // Two sessions in a row, which is what an OpMode meet is: the second
        // close() happens microseconds after the first, while the first pass is
        // still walking the directory. Both passes have to run. A pass that is
        // suppressed instead of queued is a directory that is never brought back
        // within its limit -- nothing else is scheduled to come back for it, and
        // on a robot "the next one" is the next practice session.
        File busy = new File(dir, "busy");
        assertTrue(busy.mkdirs());
        for (int i = 0; i < 3_000; i++) {
            assertTrue(new File(busy, "bulk" + i + ".engram").createNewFile(), "fixture setup");
        }

        File victim = new File(dir, "victim");
        assertTrue(victim.mkdirs());
        recording(victim, "oldest.engram", 100, BASE);
        recording(victim, "newer.engram", 100, BASE + 1_000L);

        AtomicInteger busyPasses = new AtomicInteger();
        AtomicInteger victimPasses = new AtomicInteger();
        RetentionSweeper sweeper = RetentionSweeper.shared();
        sweeper.submit(RetentionPolicy.of(1L, 1), busy, null, null,
                result -> busyPasses.incrementAndGet());
        sweeper.submit(RetentionPolicy.of(0L, 1), victim, null, null,
                result -> victimPasses.incrementAndGet());

        assertEquals(1, awaitCount(victimPasses), "a requested pass is a promise, not a suggestion");
        assertFalse(new File(victim, "oldest.engram").exists(),
                "the queued pass still brought the directory within its limit");
        assertTrue(new File(victim, "newer.engram").exists());
        assertEquals(1, awaitCount(busyPasses));
    }

    // ---- helpers ---------------------------------------------------------

    /** A config with a retention policy and a log that swallows its warnings. */
    private RecorderConfig configWith(RetentionPolicy policy) {
        return RecorderConfig.builder().withRetention(policy).withLog(m -> { }).build();
    }

    private static RetentionPolicy.FileRemover refusing(final File except) {
        return new RetentionPolicy.FileRemover() {
            @Override
            public boolean remove(File file) {
                return !except.equals(file) && file.delete();
            }
        };
    }

    private static RetentionPolicy.FileRemover refusingAll() {
        return new RetentionPolicy.FileRemover() {
            @Override
            public boolean remove(File file) {
                return false;
            }
        };
    }

    private static File recording(File dir, String name, int bytes, long modifiedAt) throws IOException {
        File file = new File(dir, name);
        writeFile(file, new byte[bytes], modifiedAt);
        return file;
    }

    private static void writeFile(File file, byte[] contents, long modifiedAt) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(contents);
        }
        // An explicit modification time, because "oldest first" is the whole
        // point of the policy and a filesystem's timestamp resolution would
        // otherwise decide the outcome of the test.
        assertTrue(file.setLastModified(modifiedAt), "fixture setup: " + file);
    }

    private static List<File> recordings(File dir) {
        File[] listing = dir.listFiles();
        List<File> out = new ArrayList<>();
        if (listing != null) {
            for (File f : listing) {
                if (f.getName().endsWith(".engram") || f.getName().endsWith(".engram.gz")) {
                    out.add(f);
                }
            }
        }
        out.sort(Comparator.comparing(File::getName));
        return out;
    }

    /**
     * Waits for a prune pass that completed after {@code passesBefore} was read.
     *
     * <p>Read the baseline <b>before</b> calling {@code close()}. The pass is
     * dispatched from inside {@code close()} and the daemon thread it runs on
     * often finishes before the next line of the test executes, so a baseline
     * read afterwards is frequently the very result being waited for — and a
     * wait for "something newer than that" then never ends.
     *
     * <p>Passes are counted rather than compared by identity. Two passes can
     * legitimately report the same numbers, and identity would also mean the
     * wait hangs for any code path that reports a result it already had instead
     * of allocating a new one. The count moves strictly forward for every pass
     * that completes, so crossing the baseline proves a pass has finished; the
     * assertions on what it did are unchanged.
     */
    private static PruneResult awaitFreshPrune(EngramSession session, long passesBefore)
            throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (session.completedPrunePasses() > passesBefore) {
                PruneResult result = session.lastPruneResult();
                if (result == null) {
                    throw new AssertionError("a prune pass was counted but no result was reported; last was null");
                }
                return result;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no prune pass completed within 10s of close(); session reported "
                + session.completedPrunePasses() + " pass(es), last was " + session.lastPruneResult());
    }

    /**
     * Waits for {@code counter} to reach {@code target}, for a pass submitted
     * straight to the sweeper rather than through a session.
     */
    private static int awaitCount(AtomicInteger counter) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (counter.get() >= 1) {
                return counter.get();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the requested prune pass never ran within 10s; it was discarded");
    }

    @Recorded(retentionMaxBytes = 64L, retentionMaxRecordings = 2)
    static final class AnnotatedForRetention {
    }
}
