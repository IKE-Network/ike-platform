package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link WsAddMojo}'s pre-existing-checkout branch alignment
 * (IKE-Network/ike-issues#902): a subproject directory that already exists must honor
 * the same workspace-branch-coherence rule as a fresh clone — before this alignment,
 * the existing-directory path silently kept whatever branch the checkout happened to
 * be on, which is exactly how ike-starter-set's checkout stayed on {@code main} while
 * its workspace.yaml entry declared {@code feature/chronology-builder}.
 *
 * <p>The state-file scenarios cover IKE-Network/ike-issues#903: an alignment that
 * does not record itself in {@code .ike/vcs-state} is undone by the VCS-bridge sync
 * in the very next {@code ws:*} goal, which restores the state-file branch.
 */
class WsAddExistingCheckoutAlignmentTest {

    @TempDir Path tempDir;

    @Test
    void noop_when_checkout_already_on_the_workspace_branch() throws Exception {
        Path sub = repoOnBranch("feature/chronology-builder");

        align(mojo("feature/chronology-builder"), sub);

        assertThat(currentBranch(sub)).isEqualTo("feature/chronology-builder");
    }

    @Test
    void creates_the_branch_from_head_when_nobody_has_it() throws Exception {
        // The ike-starter-set case: pre-existing checkout on main, no feature branch
        // anywhere — the clone path would have created it locally, and so must this one.
        Path sub = repoOnBranch("main");

        align(mojo("feature/chronology-builder"), sub);

        assertThat(currentBranch(sub)).isEqualTo("feature/chronology-builder");
    }

    @Test
    void switches_to_an_existing_local_branch() throws Exception {
        Path sub = repoOnBranch("main");
        run(sub, "git", "branch", "feature/chronology-builder");

        align(mojo("feature/chronology-builder"), sub);

        assertThat(currentBranch(sub)).isEqualTo("feature/chronology-builder");
    }

    @Test
    void tracks_the_remote_branch_when_only_the_remote_has_it() throws Exception {
        Path origin = repoOnBranch("main");
        run(origin, "git", "checkout", "-q", "-b", "feature/chronology-builder");
        Files.writeString(origin.resolve("remote-only.txt"), "remote\n", StandardCharsets.UTF_8);
        run(origin, "git", "add", "remote-only.txt");
        run(origin, "git", "commit", "-q", "-m", "remote-only");
        run(origin, "git", "checkout", "-q", "main");

        Path sub = tempDir.resolve("work");
        run(tempDir, "git", "clone", "-q", "--branch", "main", "--single-branch",
                origin.toString(), "work");

        align(mojo("feature/chronology-builder"), sub);

        assertThat(currentBranch(sub)).isEqualTo("feature/chronology-builder");
        assertThat(sub.resolve("remote-only.txt")).exists();
    }

    @Test
    void refuses_loudly_on_a_modified_worktree() throws Exception {
        Path sub = repoOnBranch("main");
        Files.writeString(sub.resolve("wip.txt"), "uncommitted\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> align(mojo("feature/chronology-builder"), sub))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("uncommitted")
                .hasMessageContaining("main")
                .hasMessageContaining("feature/chronology-builder");
        assertThat(currentBranch(sub)).isEqualTo("main");
    }

    @Test
    void alignment_records_a_switch_action_in_the_state_file() throws Exception {
        // The ike-starter-set case with the bridge active: checkout on main,
        // state file (written by hooks) also on main, workspace branch elsewhere.
        Path sub = repoOnBranch("main");
        writeState(sub, "main");

        align(mojo("feature/chronology-builder"), sub);

        assertThat(currentBranch(sub)).isEqualTo("feature/chronology-builder");
        Optional<VcsState> state = VcsState.readFrom(sub);
        assertThat(state).isPresent();
        assertThat(state.get().branch()).isEqualTo("feature/chronology-builder");
        assertThat(state.get().action()).isEqualTo(VcsState.Action.SWITCH);
        assertThat(state.get().sha())
                .isEqualTo(VcsOperations.headSha(sub.toFile()));
    }

    @Test
    void alignment_survives_a_simulated_bridge_sync() throws Exception {
        // The #903 regression proper: before the fix, catchUp() saw a state
        // file naming main, declared the repo out of sync, and switched the
        // freshly aligned checkout straight back — the exact "Switching
        // branch: feature/chronology-builder → main" observed in the wild.
        Path sub = repoOnBranch("main");
        writeState(sub, "main");

        align(mojo("feature/chronology-builder"), sub);
        VcsOperations.catchUp(sub.toFile(), new TestLog());

        assertThat(currentBranch(sub)).isEqualTo("feature/chronology-builder");
    }

    @Test
    void refreshes_a_stale_state_file_when_already_on_the_workspace_branch() throws Exception {
        // Checkout already correct but the state file remembers another
        // branch — without a refresh, the next goal's bridge sync would
        // switch the checkout AWAY from the workspace branch.
        Path sub = repoOnBranch("feature/chronology-builder");
        writeState(sub, "main");

        align(mojo("feature/chronology-builder"), sub);
        VcsOperations.catchUp(sub.toFile(), new TestLog());

        assertThat(currentBranch(sub)).isEqualTo("feature/chronology-builder");
        Optional<VcsState> state = VcsState.readFrom(sub);
        assertThat(state).isPresent();
        assertThat(state.get().branch()).isEqualTo("feature/chronology-builder");
    }

    @Test
    void does_not_invent_state_for_a_checkout_without_an_ike_directory() throws Exception {
        // Non-bridge repos have no .ike/ — alignment must not create one.
        Path sub = repoOnBranch("main");

        align(mojo("feature/chronology-builder"), sub);

        assertThat(currentBranch(sub)).isEqualTo("feature/chronology-builder");
        assertThat(sub.resolve(".ike")).doesNotExist();
    }

    @Test
    void noop_leaves_an_agreeing_state_file_untouched() throws Exception {
        // Already on the branch, state file agrees — a pure no-op must not
        // rewrite the file (timestamp/machine churn would dirty the worktree).
        Path sub = repoOnBranch("feature/chronology-builder");
        writeState(sub, "feature/chronology-builder");
        String before = Files.readString(
                sub.resolve(".ike/vcs-state"), StandardCharsets.UTF_8);

        align(mojo("feature/chronology-builder"), sub);

        String after = Files.readString(
                sub.resolve(".ike/vcs-state"), StandardCharsets.UTF_8);
        assertThat(after).isEqualTo(before);
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Writes a hook-style {@code .ike/vcs-state} recording the given branch at
     * the repo's current HEAD — the state a bridge-managed repo carries before
     * any alignment. Also excludes {@code .ike/} from git the way production
     * machines do via the global gitignore (which the hermetic surefire git
     * config deliberately strips) — without it, the state file itself would
     * trip the alignment's dirty-worktree refusal.
     */
    private static void writeState(Path repo, String branch) throws Exception {
        Files.writeString(repo.resolve(".git/info/exclude"), ".ike/\n",
                StandardCharsets.UTF_8);
        VcsState.writeTo(repo, VcsState.create(
                branch, VcsOperations.headSha(repo.toFile()), VcsState.Action.COMMIT));
    }

    /** A real repo with one commit, HEAD on the named branch. */
    private Path repoOnBranch(String branch) throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("repo-" + branch.replace('/', '-')));
        run(dir, "git", "init", "-q", "-b", branch);
        run(dir, "git", "config", "user.email", "test@ike.network");
        run(dir, "git", "config", "user.name", "Test");
        Files.writeString(dir.resolve("seed.txt"), "seed\n", StandardCharsets.UTF_8);
        run(dir, "git", "add", "seed.txt");
        run(dir, "git", "commit", "-q", "-m", "seed");
        return dir;
    }

    private static String currentBranch(Path dir) throws Exception {
        Process p = new ProcessBuilder("git", "branch", "--show-current")
                .directory(dir.toFile()).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor();
        return out;
    }

    private static void run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectErrorStream(true).start();
        int exit = p.waitFor();
        if (exit != 0) {
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("Command failed (" + exit + "): "
                    + String.join(" ", cmd) + "\n" + out);
        }
    }

    private static WsAddMojo mojo(String branch) throws Exception {
        WsAddMojo mojo = TestLog.createMojo(WsAddMojo.class);
        Field branchField = WsAddMojo.class.getDeclaredField("branch");
        branchField.setAccessible(true);
        branchField.set(mojo, branch);
        Field subprojectField = WsAddMojo.class.getDeclaredField("subproject");
        subprojectField.setAccessible(true);
        subprojectField.set(mojo, "test-subproject");
        return mojo;
    }

    private static void align(WsAddMojo mojo, Path subprojectDir) throws Exception {
        Method m = WsAddMojo.class.getDeclaredMethod("alignExistingCheckout", Path.class);
        m.setAccessible(true);
        try {
            m.invoke(mojo, subprojectDir);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }
}
