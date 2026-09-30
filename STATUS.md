# Engram — Status

**Date:** 2026-09-30
**Repos:** `IamCoder18/engram` (published) · `IamCoder18/synapse` PR #27 (open, **not ready to merge**)

---

## TL;DR

| Workstream | State |
|---|---|
| Engram implementation | **Done.** 226 tests, 0 failures. |
| Engram verification vs real FTC SDK | **Done.** 2 real defects found and fixed. |
| Engram published to Maven Central + GitHub Packages | **Done.** All 3 modules live and resolving. |
| Engram CI | **Green** (build + publish). |
| Synapse PR #27 (`PublishListener`) | **Open. One real bug fixed locally but NOT yet pushed, and a new regression test is failing un-diagnosed. Do not merge.** |

---

## Done

### 1. Engram implementation

Three modules, 3,850 lines main / 3,672 lines test.

| Module | Purpose | Dependencies |
|---|---|---|
| `engram-proto` | Wire format (protobuf) | `protobuf-java` |
| `engram-recorder` | Robot-side capture, 36 KB jar | `engram-proto`, Synapse *(api)* |
| `engram-replay` | Desktop reader, query API, exporters, CLI | `engram-proto` only |

226 tests, 0 failures, 0 skipped. Clean build, no compiler warnings.

Design decisions worth knowing:
- Topic declarations travel **inline** in the event stream, not in the file header. A header is written before any event exists, so it cannot describe a topic first seen later. Inline declarations keep the writer streaming and crash-safe.
- Capture is reached **reflectively**, so one build works against Synapse versions on both sides of the `PublishListener` hook existing and switches strategy automatically.
- The recorder has **no FTC SDK dependency**, which is what makes it testable on a desktop JVM.
- Recording never throws into `publish`, never blocks a publishing thread on I/O, never allocates unboundedly.

### 2. Verification against the real FTC SDK

Pulled `RobotCore-10.2.0.aar` and ran `javap` rather than assuming. This found **two real defects**, both invisible to the test suite at the time:

| Defect | Consequence had it shipped | Fix |
|---|---|---|
| Recorder used `java.time` / `java.nio.file` (API 26) while `RobotCore` declares `minSdkVersion=24` | `NoClassDefFoundError` on the first publish of an API 24/25 Robot Controller | Moved to `java.io.File` + `java.text.SimpleDateFormat`; added `NoAndroidApiLeakTest` to fail the build if it regresses |
| `OpMode` exposes **no** Android `Context` (verified: `OpMode` → `OpModeInternal` have no `getContext`/`getApplicationContext`) | The app-external-files output path was dead code on every real device, while its test passed against a stand-in that happened to expose the method | Reachable route is `AppUtil.getDefContext()`; `OutputLocation` now tries three routes and reports `isUsbVisible()` |

### 3. Maven publishing — complete and verified

- Mirrors Synapse's setup (`com.gradleup.nmcp` → Central Portal publisher API).
- **Live and resolving:**
  - `https://repo1.maven.org/maven2/com/aaravlabs/engram-proto/0.1.0/` → **200**
  - `.../engram-recorder/0.1.0/` → **200**
  - `.../engram-replay/0.1.0/` → **200**
  - GitHub Packages: `com.aaravlabs.engram-{proto,recorder,replay}` all present
- Signed with the migrated GPG key (RSA 3072, fingerprint `6E69CC5CF456835401AFAD7F868A012195E3876E`, "IamCoder18 (Synapse release signing key)"). Locally verified 15/15 artifacts sign and verify as Good before spending a CI run.
- Publish workflow gates the upload behind a full test run.

### 4. Credential migration (done and cleaned up)

Migrated `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`, `SONATYPE_PASSWORD` from the synapse repo to engram. `SONATYPE_USERNAME` was **empty** in synapse and was supplied manually.

Cleanup verified complete: temporary export workflow deleted, both runs deleted (their logs held the secrets), all local copies of the key/token deleted, and no secret material is in either repo.

---

## In progress — Synapse PR #27

**https://github.com/IamCoder18/synapse/pull/27** · `feature/publish-listener` · base `main` · **OPEN, CI green (Build & Test pass, both images build), 1 commit `fc9274c`**

### What it does
Adds `PublishListener`, notified synchronously inside `OrchestratorImpl.publish`, so diagnostics (recording, metrics, tracing) can observe every publish. This is what closes Engram's `bulkRead` sensor-capture gap.

### CodeRabbit findings — 3 received, 3 valid, **0 applied to the PR**

| # | Severity | Finding | Status |
|---|---|---|---|
| 1 | 🟠 **Major** | **Real bug.** The listener loop iterated with `size()` + `get(i)`. `CopyOnWriteArrayList` reads its current array independently for each call, so a concurrent `removePublishListener` between them could make `get(i)` throw `IndexOutOfBoundsException` — *outside* the `try`, so it would propagate out of `publish` and skip subscriber dispatch. This directly violated the PR's central guarantee that instrumentation cannot break the bus. | Fixed locally, **not pushed** |
| 2 | 🟡 Minor | `addPublishListener` default throws while `removePublishListener` is a silent no-op — inconsistent, and the `@param` doc ("ignored if null") contradicted the throw. | Fixed locally, **not pushed** |
| 3 | 🟡 Minor | Listeners fire before type validation, so a publish rejected with `IllegalArgumentException` is still reported. Neither the PR body nor the javadoc said so. | Documented locally, **not pushed** |

On #2 I chose to keep `addPublishListener` throwing rather than make both no-ops, and documented why: a silent no-op leaves a caller believing a recorder is attached when nothing is being captured. `null` now returns early, so the `@param` doc is truthful. `removePublishListener` stays a no-op because cleanup must always be safe.

Also fixed locally: `markdownlint` MD022 (blank line after `### Added` in CHANGELOG).

### The problem right now

I re-cloned to `/tmp/syn` (`/tmp/synapse-pr` was wiped between sessions) and re-applied all four fixes. Then I added a regression test for finding #1:

```
PublishListenerTest > listenersCanBeAddedAndRemovedWhilePublishing() FAILED
SYNAPSE: 0 tests, 0 failures        <- results XML not readable; run aborted at 2m59s
```

**I have not yet diagnosed why this test fails.** It is one of three possibilities and I do not yet know which:

1. **The test is too aggressive** — most likely. It runs 4 threads × 2,000 publishes while adding/removing listeners, and one of the listener configurations throws on every call, so ~4,000 error log lines get written through Synapse's `LogSink`. That could simply be too slow and hit the 120 s timeout.
2. **A real remaining concurrency defect** that the iterator fix did not cover.
3. **A test bug** of mine (e.g. `delivered` assertion racing the async callback pool).

This is unresolved. Until it is, the local synapse tree is **not** in a state worth pushing.

---

## What I will do next, in order

1. **Diagnose the failing regression test.** Read the actual failure message from the JUnit XML (`build/test-results/test/TEST-*PublishListener*.xml`) — the earlier read returned nothing, so the run needs re-executing and the results path re-checked. Do not guess.
2. **Distinguish test-aggressiveness from a real defect.** If it is logging volume, cut the throwing-listener publishes and re-run with a bounded timeout. If it is a genuine race, fix the implementation and add a test that fails without the fix.
3. **Confirm the suite is green** — synapse was 64 tests, 0 failures before this test was added.
4. **Commit and push the four review fixes + the regression test** to `feature/publish-listener`. Do this promptly — the previous attempt was lost when `/tmp` was cleared, so the fix must land on the remote before anything else.
5. **Reply to CodeRabbit's three comments** summarising what was fixed and why, particularly the reasoning on #2.
6. **Re-run the PR's CI** and confirm it is green.
7. **Stop.** Merging is the maintainer's call and I will not merge.

---

## Once #27 merges

- Engram needs **no code change**: `PublishListenerCapture` resolves the hook reflectively at runtime.
- Bump `synapseVersion` in `gradle.properties` and re-release. The `bulkRead` sensor gap closes and `engram.recording(...)` wrapping becomes unnecessary.
- Update `docs/SYNAPSE-INTEGRATION.md` and `docs/OPEN-QUESTIONS.md`, which currently document the gap as open.

---

## Remaining known gaps in Engram

| Gap | Impact | Status |
|---|---|---|
| `bulkRead` sensor publishes missing on Synapse 0.4.0 | **Silent** — no error, no warning, just absent data | Workaround shipped (`engram.recording(...)`); PR #27 would remove it |
| Never run on a real Robot Controller | The single largest unverified assumption | Needs 15 min on a Control Center: check `strategyName()`, `location().describe()`, `location().isUsbVisible()`, `stats().isHealthy()` |
| No match-length soak test | 150 s at realistic rates unmeasured | Not started |
| No recording retention/pruning | A season of practice fills the RC's storage | Not started |
| `engram inspect` does not report capture completeness | A silently incomplete recording looks fine | Not started |

---

## Verification commands

```bash
# engram
cd /home/aarav/apps/engram
./gradlew build :engram-replay:fatJar

# synapse PR branch
cd /tmp/syn
/home/aarav/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/bin/gradle clean test

# confirm publication
for m in engram-proto engram-recorder engram-replay; do
  curl -s -o /dev/null -w "%{http_code}  $m\n" \
    "https://repo1.maven.org/maven2/com/aaravlabs/$m/0.1.0/$m-0.1.0.pom"
done
```

---

## Note on CI runs

The engram publish history contains two failures that are both **superseded and expected** — do not treat them as current breakage:

- `36577469545` — failed: root project had no `repositories`, so NMCP's check task could not resolve `nmcp-tasks`. Failed *before* any upload. Fixed in `9172964`.
- `36578027259` — failed: HTTP 409 on GitHub Packages, because the *earlier* run had already published `0.1.0` and GitHub Packages does not allow overwriting a version. Packages were deleted, and `36578312902` then succeeded on the same SHA.

`36578312902` (Publish) and `36578024445` (build) are both **success** on `9172964`, which is the tagged commit.
