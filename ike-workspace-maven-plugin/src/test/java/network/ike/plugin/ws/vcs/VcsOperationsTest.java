package network.ike.plugin.ws.vcs;

import network.ike.plugin.ws.TestLog;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.Log;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link VcsOperations} using real temp git repos.
 */
class VcsOperationsTest {

    @TempDir
    Path tempDir;

    /** A separate temp dir with no git repo above it — for failure tests. */
    @TempDir
    Path nonRepoDir;

    /** A separate temp dir used as a bare "origin" remote for sync tests. */
    @TempDir
    Path originDir;

    private File repoDir;
    private final Log log = new TestLog();

    @BeforeEach
    void setUp() throws Exception {
        repoDir = tempDir.toFile();
        exec("git", "init", "-b", "main");
        // Hermetic sandbox: ignore any host git config (a global
        // core.hooksPath with failing hooks, commit.gpgsign with no usable
        // key, ...) so the test never depends on the developer's or build
        // agent's machine (IKE-Network/ike-issues#560).
        Path noHooks = Files.createDirectories(tempDir.resolve(".nohooks"));
        exec("git", "config", "core.hooksPath", noHooks.toAbsolutePath().toString());
        exec("git", "config", "commit.gpgsign", "false");
        exec("git", "config", "tag.gpgsign", "false");
        exec("git", "config", "user.email", "test@example.com");
        exec("git", "config", "user.name", "Test");
        Files.writeString(tempDir.resolve("file.txt"), "hello", StandardCharsets.UTF_8);
        exec("git", "add", ".");
        exec("git", "commit", "-m", "Initial commit");
    }

    @Test
    void headSha_returns8chars() throws MojoException {
        String sha = VcsOperations.headSha(repoDir);
        assertThat(sha).hasSize(8);
        assertThat(sha).matches("[0-9a-f]{8}");
    }

    @Test
    void currentBranch_returnsMain() throws MojoException {
        assertThat(VcsOperations.currentBranch(repoDir)).isEqualTo("main");
    }

    @Test
    void readQuery_failure_carriesCommandDirectoryAndStderr() {
        // nonRepoDir has no .git anywhere above it, so `git rev-parse`
        // exits 128 with "not a git repository" on stderr. Before
        // ike-issues#602 that surfaced as a bare "Command failed
        // (exit 128)"; now the exception names the command, the
        // directory, and git's stderr.
        File notARepo = nonRepoDir.toFile();

        assertThatThrownBy(() -> VcsOperations.headSha(notARepo))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("git rev-parse")
                .hasMessageContaining(notARepo.toString())
                .hasMessageContaining("not a git repository");
    }

    @Test
    void mutation_failure_carriesCommandDirectoryAndGitOutput() {
        // The mutating counterpart of the read-query test above: no
        // 'origin' is configured, so `git push` exits non-zero with
        // "fatal: 'origin' does not appear to be a git repository".
        // Before ike-issues#959 that output was logged at debug only and
        // the exception was a bare "Command failed (exit 128)".
        assertThatThrownBy(() -> VcsOperations.push(repoDir, log, "origin", "main"))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("git push origin main")
                .hasMessageContaining(repoDir.toString())
                .hasMessageContaining("fatal")
                .hasMessageNotContaining("Command failed");
    }

    @Test
    void classifyPushProbe_cleanDryRun_isWritable() {
        VcsOperations.PushAccess a = VcsOperations.classifyPushProbe(
                0, "To github.com:o/r.git\n * [new branch]      main -> probe");
        assertThat(a.status())
                .isEqualTo(VcsOperations.PushAccessStatus.WRITABLE);
    }

    @Test
    void classifyPushProbe_permissionDenied_isDenied() {
        // Verbatim output captured from a live `git push --dry-run` over
        // SSH against a repository the authenticated account cannot
        // write (ike-issues#960).
        String real = """
                ERROR: Permission to octocat/Hello-World.git denied to knowledge-graphlet.
                fatal: Could not read from remote repository.

                Please make sure you have the correct access rights
                and the repository exists.""";

        VcsOperations.PushAccess a = VcsOperations.classifyPushProbe(128, real);

        assertThat(a.status())
                .isEqualTo(VcsOperations.PushAccessStatus.DENIED);
        // The operator must see git's own words, not a paraphrase.
        assertThat(a.detail()).contains("Permission to octocat/Hello-World.git denied");
    }

    @Test
    void classifyPushProbe_httpsForbidden_isDenied() {
        VcsOperations.PushAccess a = VcsOperations.classifyPushProbe(128,
                "remote: Permission to o/r.git denied to user.\n"
                        + "fatal: unable to access 'https://github.com/o/r.git/': "
                        + "The requested URL returned error: 403");
        assertThat(a.status())
                .isEqualTo(VcsOperations.PushAccessStatus.DENIED);
    }

    @Test
    void classifyPushProbe_nonFastForward_isWritableNotDenied() {
        // Reaching a ref-state rejection proves the remote accepted us as
        // a writer — the divergence is the calling goal's problem (its
        // refresh resolves it), not an access failure. Misreading this as
        // DENIED would block every finish whose local main is behind.
        VcsOperations.PushAccess a = VcsOperations.classifyPushProbe(1,
                " ! [rejected]        main -> main (non-fast-forward)\n"
                        + "error: failed to push some refs to 'github.com:o/r.git'\n"
                        + "hint: Updates were rejected because the tip of your "
                        + "current branch is behind");

        assertThat(a.status())
                .isEqualTo(VcsOperations.PushAccessStatus.WRITABLE);
    }

    @Test
    void classifyPushProbe_unknownFailure_isUndetermined() {
        // Never silently pass a check that could not be run.
        VcsOperations.PushAccess a = VcsOperations.classifyPushProbe(128,
                "ssh: Could not resolve hostname github.com: nodename nor "
                        + "servname provided, or not known");
        assertThat(a.status())
                .isEqualTo(VcsOperations.PushAccessStatus.UNDETERMINED);
        assertThat(a.detail()).contains("Could not resolve hostname");
    }

    @Test
    void probePushAccess_localWritableRemote_isWritableAndCreatesNothing()
            throws Exception {
        execIn(originDir.toFile(), "git", "init", "--bare", "-b", "main");
        exec("git", "remote", "add", "origin", originDir.toString());

        VcsOperations.PushAccess a = VcsOperations.probePushAccess(
                repoDir, "origin", "main");

        assertThat(a.status())
                .isEqualTo(VcsOperations.PushAccessStatus.WRITABLE);
        // The safety property the whole check rests on: a probe must not
        // publish anything. main must NOT exist on the remote afterwards.
        assertThat(VcsOperations.remoteSha(repoDir, "origin", "main"))
                .isEmpty();
    }

    @Test
    void probePushAccess_missingRemotePath_isUndetermined() throws Exception {
        exec("git", "remote", "add", "origin",
                nonRepoDir.resolve("definitely-not-a-repo").toString());

        VcsOperations.PushAccess a = VcsOperations.probePushAccess(
                repoDir, "origin", "main");

        assertThat(a.status())
                .isNotEqualTo(VcsOperations.PushAccessStatus.WRITABLE);
    }

    @Test
    void push_rejectedNonFastForward_carriesGitRejectionInMessage() throws Exception {
        // The shape a feature-finish push phase actually hits
        // (ike-issues#858/#959): local main diverges from origin/main, so
        // the push is rejected. The operator needs git's reason — not an
        // exit code — to tell a non-fast-forward apart from a denied
        // permission or an unreachable remote.
        execIn(originDir.toFile(), "git", "init", "--bare", "-b", "main");
        exec("git", "remote", "add", "origin", originDir.toString());
        exec("git", "push", "-u", "origin", "main");
        // Rewrite the pushed commit so local main is no longer a
        // descendant of origin/main.
        exec("git", "commit", "--amend", "-m", "Rewritten locally");

        assertThatThrownBy(() -> VcsOperations.push(repoDir, log, "origin", "main"))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("git push origin main")
                .hasMessageContaining("rejected")
                .hasMessageNotContaining("Command failed");
    }

    @Test
    void isClean_trueOnCleanRepo() {
        assertThat(VcsOperations.isClean(repoDir)).isTrue();
    }

    @Test
    void isClean_falseWithUncommittedChanges() throws Exception {
        Files.writeString(tempDir.resolve("dirty.txt"), "change", StandardCharsets.UTF_8);
        assertThat(VcsOperations.isClean(repoDir)).isFalse();
    }

    @Test
    void isPathClean_trueForUntouchedTrackedPath() {
        assertThat(VcsOperations.isPathClean(repoDir, "file.txt")).isTrue();
    }

    @Test
    void isPathClean_falseForModifiedPath_trueForOthers() throws Exception {
        Files.writeString(tempDir.resolve("file.txt"), "hello v2",
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("other.txt"), "x",
                StandardCharsets.UTF_8);
        exec("git", "add", "other.txt");
        exec("git", "commit", "-m", "add other");

        // Only file.txt is modified — the path scope must distinguish them.
        assertThat(VcsOperations.isPathClean(repoDir, "file.txt")).isFalse();
        assertThat(VcsOperations.isPathClean(repoDir, "other.txt")).isTrue();
    }

    @Test
    void commitPaths_commitsOnlyNamedPath_leavingOthersUncommitted()
            throws Exception {
        // Track a second file, then modify both.
        Files.writeString(tempDir.resolve("other.txt"), "v1",
                StandardCharsets.UTF_8);
        exec("git", "add", "other.txt");
        exec("git", "commit", "-m", "add other");
        Files.writeString(tempDir.resolve("file.txt"), "changed",
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("other.txt"), "v2",
                StandardCharsets.UTF_8);

        VcsOperations.commitPaths(repoDir, log, "commit only other.txt",
                "other.txt");

        // other.txt committed in isolation; file.txt's change is untouched.
        assertThat(VcsOperations.isPathClean(repoDir, "other.txt")).isTrue();
        assertThat(VcsOperations.isPathClean(repoDir, "file.txt")).isFalse();
        assertThat(execCapture("git", "log", "-1", "--format=%s"))
                .isEqualTo("commit only other.txt");
        // The committed snapshot of other.txt is its new content.
        assertThat(execCapture("git", "show", "HEAD:other.txt")).isEqualTo("v2");
    }

    @Test
    void checkout_switchesBranch() throws Exception {
        exec("git", "checkout", "-b", "feature/test");
        exec("git", "checkout", "main");

        VcsOperations.checkout(repoDir, log, "feature/test");
        assertThat(VcsOperations.currentBranch(repoDir)).isEqualTo("feature/test");
    }

    @Test
    void checkoutNew_createsAndSwitches() throws MojoException {
        VcsOperations.checkoutNew(repoDir, log, "feature/new");
        assertThat(VcsOperations.currentBranch(repoDir)).isEqualTo("feature/new");
    }

    @Test
    void writeVcsState_createsFile() throws Exception {
        Files.createDirectories(tempDir.resolve(".ike"));
        VcsOperations.writeVcsState(repoDir, VcsState.Action.COMMIT);

        Optional<VcsState> state = VcsState.readFrom(tempDir);
        assertThat(state).isPresent();
        assertThat(state.get().action()).isEqualTo(VcsState.Action.COMMIT);
        assertThat(state.get().branch()).isEqualTo("main");
        assertThat(state.get().sha()).hasSize(8);
    }

    @Test
    void needsSync_falseWhenInSync() throws Exception {
        Files.createDirectories(tempDir.resolve(".ike"));
        VcsOperations.writeVcsState(repoDir, VcsState.Action.COMMIT);

        assertThat(VcsOperations.needsSync(repoDir)).isFalse();
    }

    @Test
    void needsSync_trueWhenShaMismatch() throws Exception {
        Files.createDirectories(tempDir.resolve(".ike"));
        VcsState stale = new VcsState("2026-01-01T00:00:00Z", "other",
                "main", "deadbeef", VcsState.Action.COMMIT);
        VcsState.writeTo(tempDir, stale);

        assertThat(VcsOperations.needsSync(repoDir)).isTrue();
    }

    @Test
    void needsSync_trueWhenBranchMismatch() throws Exception {
        Files.createDirectories(tempDir.resolve(".ike"));
        String sha = VcsOperations.headSha(repoDir);
        VcsState state = new VcsState("2026-01-01T00:00:00Z", "other",
                "feature/x", sha, VcsState.Action.FEATURE_START);
        VcsState.writeTo(tempDir, state);

        assertThat(VcsOperations.needsSync(repoDir)).isTrue();
    }

    @Test
    void needsSync_falseWhenNoStateFile() throws MojoException {
        assertThat(VcsOperations.needsSync(repoDir)).isFalse();
    }

    @Test
    void needsSync_falseWhenStateShaIsAncestorOfHead() throws Exception {
        // Mirrors the ike-issues#232 trace: ws:commit writes the state
        // file, then a routine local commit (or git commit --amend)
        // advances HEAD past it. The state file is now stale but HEAD
        // is just ahead — no sync needed.
        Files.createDirectories(tempDir.resolve(".ike"));
        VcsOperations.writeVcsState(repoDir, VcsState.Action.COMMIT);

        Files.writeString(tempDir.resolve("file.txt"), "hello v2",
                StandardCharsets.UTF_8);
        exec("git", "add", "file.txt");
        exec("git", "commit", "-m", "Local follow-up commit");

        assertThat(VcsOperations.needsSync(repoDir)).isFalse();
    }

    @Test
    void catchUp_noOpWhenNotIkeManaged() throws MojoException {
        // No .ike/ directory — should do nothing
        VcsOperations.catchUp(repoDir, log);
        assertThat(VcsOperations.currentBranch(repoDir)).isEqualTo("main");
    }

    @Test
    void sync_originParityConfirmed_staleCheckpointSelfHealsAtInfoLevel()
            throws Exception {
        // ike-issues#819: local history was rewritten (here, `commit --amend`)
        // after the checkpoint was written, orphaning its recorded sha — but
        // origin already matches the rewritten HEAD, so nothing is missing.
        execIn(originDir.toFile(), "git", "init", "--bare", "-b", "main");
        exec("git", "remote", "add", "origin", originDir.toString());
        exec("git", "push", "-u", "origin", "main");

        Files.createDirectories(tempDir.resolve(".ike"));
        VcsOperations.writeVcsState(repoDir, VcsState.Action.COMMIT);
        String staleSha = VcsState.readFrom(tempDir).orElseThrow().sha();

        exec("git", "commit", "--amend", "-m", "Initial commit (amended)");
        String amendedSha = VcsOperations.headSha(repoDir);
        assertThat(amendedSha).isNotEqualTo(staleSha);
        exec("git", "push", "--force", "origin", "main");

        RecordingLog recordingLog = new RecordingLog();
        VcsOperations.SyncOutcome outcome = VcsOperations.sync(repoDir, recordingLog);

        assertThat(outcome.selfHealed()).isTrue();
        assertThat(outcome.note()).isNotNull()
                .doesNotContain("may not have completed")
                .contains("stale")
                .contains("origin");
        assertThat(recordingLog.warns).isEmpty();
        assertThat(recordingLog.infos).anyMatch(line -> line.contains(outcome.note()));

        VcsState healed = VcsState.readFrom(tempDir).orElseThrow();
        assertThat(healed.sha()).isEqualTo(amendedSha);
        assertThat(healed.action()).isEqualTo(VcsState.Action.SYNC);
    }

    @Test
    void sync_localAheadOfOrigin_staleUnrelatedStateSha_keepsWarnWording()
            throws Exception {
        // Regression guard: when local is genuinely ahead of origin, a
        // state-file mismatch is still a real signal (e.g. a missing push
        // from another machine) — must keep warning, must not self-heal.
        execIn(originDir.toFile(), "git", "init", "--bare", "-b", "main");
        exec("git", "remote", "add", "origin", originDir.toString());
        exec("git", "push", "-u", "origin", "main");

        Files.writeString(tempDir.resolve("file.txt"), "hello v2",
                StandardCharsets.UTF_8);
        exec("git", "add", "file.txt");
        exec("git", "commit", "-m", "Local, unpushed follow-up");

        Files.createDirectories(tempDir.resolve(".ike"));
        VcsState stale = new VcsState("2026-01-01T00:00:00Z", "other",
                "main", "deadbeef", VcsState.Action.COMMIT);
        VcsState.writeTo(tempDir, stale);

        RecordingLog recordingLog = new RecordingLog();
        VcsOperations.SyncOutcome outcome = VcsOperations.sync(repoDir, recordingLog);

        assertThat(outcome.selfHealed()).isFalse();
        assertThat(outcome.note()).isNull();
        assertThat(recordingLog.warns)
                .anyMatch(line -> line.contains("does not match state file"))
                .anyMatch(line -> line.contains("may not have completed"))
                .anyMatch(line -> line.contains("first, then retry sync"));

        VcsState unchanged = VcsState.readFrom(tempDir).orElseThrow();
        assertThat(unchanged.sha()).isEqualTo("deadbeef");
    }

    @Test
    void remoteSha_emptyForLocalRepo() throws MojoException {
        Optional<String> sha = VcsOperations.remoteSha(repoDir, "origin", "main");
        assertThat(sha).isEmpty();
    }

    @Test
    void mergeSquash_squashesIntoCurrentBranch() throws Exception {
        // Create a feature branch with a commit
        exec("git", "checkout", "-b", "feature/test");
        Files.writeString(tempDir.resolve("feature.txt"), "feature work", StandardCharsets.UTF_8);
        exec("git", "add", ".");
        exec("git", "commit", "-m", "Feature commit");

        // Switch back to main and squash
        exec("git", "checkout", "main");
        VcsOperations.mergeSquash(repoDir, log, "feature/test");

        // Working tree has the feature file
        assertThat(tempDir.resolve("feature.txt")).exists();

        // But it's not committed yet (squash stages but doesn't commit)
        String status = execCapture("git", "status", "--porcelain");
        assertThat(status).contains("feature.txt");
    }

    @Test
    void mergeNoFf_createsMergeCommit() throws Exception {
        exec("git", "checkout", "-b", "feature/test");
        Files.writeString(tempDir.resolve("feature.txt"), "work", StandardCharsets.UTF_8);
        exec("git", "add", ".");
        exec("git", "commit", "-m", "Feature");

        exec("git", "checkout", "main");
        VcsOperations.mergeNoFf(repoDir, log, "feature/test", "Merge feature");

        String logOutput = execCapture("git", "log", "--oneline", "-3");
        assertThat(logOutput).contains("Merge feature");
    }

    @Test
    void deleteBranch_removesBranch() throws Exception {
        exec("git", "checkout", "-b", "feature/temp");
        exec("git", "checkout", "main");

        VcsOperations.deleteBranch(repoDir, log, "feature/temp");

        String branches = execCapture("git", "branch", "--list", "feature/temp");
        assertThat(branches.trim()).isEmpty();
    }

    @Test
    void pushSafe_doesNotThrowOnFailure() {
        // No remote — should warn but not throw
        VcsOperations.pushSafe(repoDir, log, "origin", "main");
    }

    @Test
    void addAll_stagesEverything() throws Exception {
        Files.writeString(tempDir.resolve("new.txt"), "new", StandardCharsets.UTF_8);

        VcsOperations.addAll(repoDir, log);

        String staged = execCapture("git", "diff", "--cached", "--name-only");
        assertThat(staged).contains("new.txt");
    }

    @Test
    void addAll_retriesAcrossTransientIndexLock() throws Exception {
        // Simulates the ike-issues#636 collision: a concurrent process
        // holds .git/index.lock when `git add -A` starts, but releases
        // it before the single retry fires. Attempt 1 exits 128, the
        // backoff elapses, attempt 2 stages normally.
        //
        // Coordination is DETERMINISTIC via the #691 command-interceptor
        // seam — the lock is removed exactly when the retry attempt is
        // about to run. The previous sleep-based releaser thread lost
        // the race on loaded CI agents (release 2716) and failed the
        // verify preflight spuriously.
        Files.writeString(tempDir.resolve("new.txt"), "new", StandardCharsets.UTF_8);
        Path lock = tempDir.resolve(".git").resolve("index.lock");
        Files.writeString(lock, "", StandardCharsets.UTF_8);

        java.util.concurrent.atomic.AtomicInteger addAttempts =
                new java.util.concurrent.atomic.AtomicInteger();
        VcsOperations.setCommandInterceptor((dir, cmd) -> {
            if (String.join(" ", cmd).equals("git add -A")
                    && addAttempts.incrementAndGet() == 2) {
                try {
                    Files.deleteIfExists(lock);
                } catch (java.io.IOException e) {
                    throw new MojoException(
                            "test: could not release index.lock: "
                                    + e.getMessage(), e);
                }
            }
        });
        try {
            VcsOperations.addAll(repoDir, log);
        } finally {
            VcsOperations.setCommandInterceptor(null);
        }

        assertThat(addAttempts.get())
                .as("attempt 1 must fail on the lock; attempt 2 succeeds")
                .isEqualTo(2);
        String staged = execCapture("git", "diff", "--cached", "--name-only");
        assertThat(staged).contains("new.txt");
    }

    @Test
    void addAll_persistentLockFailureIsSelfDiagnosing() throws Exception {
        // A lock that never clears: both attempts fail and the thrown
        // exception carries the command, the directory, and git's
        // stderr naming index.lock (#602-style diagnostics, applied to
        // the mutation path by ike-issues#636).
        Files.writeString(tempDir.resolve("new.txt"), "new", StandardCharsets.UTF_8);
        Path lock = tempDir.resolve(".git").resolve("index.lock");
        Files.writeString(lock, "", StandardCharsets.UTF_8);

        try {
            assertThatThrownBy(() -> VcsOperations.addAll(repoDir, log))
                    .isInstanceOf(MojoException.class)
                    .hasMessageContaining("git add -A")
                    .hasMessageContaining(repoDir.toString())
                    .hasMessageContaining("index.lock");
        } finally {
            Files.deleteIfExists(lock);
        }
    }

    // ── recordSwitch / refreshStaleBranchState (#903/#904) ────────

    @Test
    void recordSwitch_noopWithoutIkeDirectory() throws Exception {
        VcsOperations.recordSwitch(repoDir, log);

        assertThat(tempDir.resolve(".ike")).doesNotExist();
    }

    @Test
    void recordSwitch_writesSwitchStateAtCurrentBranchAndSha() throws Exception {
        Files.createDirectories(tempDir.resolve(".ike"));
        exec("git", "checkout", "-b", "feature/x");

        VcsOperations.recordSwitch(repoDir, log);

        Optional<VcsState> state = VcsState.readFrom(tempDir);
        assertThat(state).isPresent();
        assertThat(state.get().branch()).isEqualTo("feature/x");
        assertThat(state.get().sha()).isEqualTo(VcsOperations.headSha(repoDir));
        assertThat(state.get().action()).isEqualTo(VcsState.Action.SWITCH);
    }

    @Test
    void refreshStaleBranchState_falseWithoutStateFile() throws Exception {
        assertThat(VcsOperations.refreshStaleBranchState(repoDir, log)).isFalse();
        assertThat(tempDir.resolve(".ike")).doesNotExist();
    }

    @Test
    void refreshStaleBranchState_falseWhenStateAgrees() throws Exception {
        VcsOperations.writeVcsState(repoDir, VcsState.Action.COMMIT);
        String before = Files.readString(tempDir.resolve(".ike/vcs-state"),
                StandardCharsets.UTF_8);

        assertThat(VcsOperations.refreshStaleBranchState(repoDir, log)).isFalse();
        assertThat(Files.readString(tempDir.resolve(".ike/vcs-state"),
                StandardCharsets.UTF_8)).isEqualTo(before);
    }

    @Test
    void refreshStaleBranchState_refreshesWhenStateNamesAnotherBranch()
            throws Exception {
        VcsOperations.writeVcsState(repoDir, VcsState.Action.COMMIT);
        exec("git", "checkout", "-b", "feature/y");

        assertThat(VcsOperations.refreshStaleBranchState(repoDir, log)).isTrue();

        Optional<VcsState> state = VcsState.readFrom(tempDir);
        assertThat(state).isPresent();
        assertThat(state.get().branch()).isEqualTo("feature/y");
        assertThat(state.get().action()).isEqualTo(VcsState.Action.SWITCH);
        // A refreshed state file agrees with the checkout — no sync needed.
        assertThat(VcsOperations.needsSync(repoDir)).isFalse();
    }

    // ── Helpers ──────────────────────────────────────────────────

    /** Test double capturing log lines by level, for asserting on {@code sync()}'s output. */
    private static final class RecordingLog extends TestLog {
        final List<String> infos = new ArrayList<>();
        final List<String> warns = new ArrayList<>();

        @Override
        public void info(CharSequence c) {
            infos.add(c.toString());
        }

        @Override
        public void warn(CharSequence c) {
            warns.add(c.toString());
        }
    }

    /** Like {@link #exec}, but runs in an arbitrary directory (e.g. a bare origin). */
    private void execIn(File dir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(dir)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command)
                            + (output.isBlank() ? "" : "\n" + output));
        }
    }

    private void exec(String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(repoDir)
                .redirectErrorStream(true)
                .start();
        // Capture combined output so a failure is self-diagnosing
        // (git's stderr would otherwise be lost).
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command)
                            + (output.isBlank() ? "" : "\n" + output));
        }
    }

    private String execCapture(String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(repoDir)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        process.waitFor();
        return output;
    }
}
