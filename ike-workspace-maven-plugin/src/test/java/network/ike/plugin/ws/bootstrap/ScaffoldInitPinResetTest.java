package network.ike.plugin.ws.bootstrap;

import network.ike.plugin.ws.TestLog;
import network.ike.plugin.ws.TestWorkspaceHelper;
import network.ike.plugin.ws.vcs.WorkspaceStateAssert;
import network.ike.plugin.ws.vcs.WorkspaceStateAssert.SubState;
import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@code resetToPin} half of the IKE-Network/ike-issues#685
 * fix in {@link SubprojectInitializer}.
 *
 * <p>#685 incident: a stale subproject clone that lacks the commit pinned
 * in {@code workspace.yaml} ({@code sha:}) used to fail a bare
 * {@code git checkout <sha>} with {@code unable to read tree <sha>}; the
 * failure was swallowed to a {@code log.warn}, so the build silently
 * proceeded on stale code. The fix gives {@code checkoutSha} a
 * fetch-before-checkout step (so the pin is reachable) and a
 * {@code resetToPin} mode that, in CI/force runs, reaches the pin with
 * {@code git reset --hard} and fails loud if it cannot.
 *
 * <p>Fixture shape (per test): {@link #scaffold(boolean)} clones the
 * subprojects at their upstream HEAD (call this C1); a fresh sibling clone
 * of one subproject's bare upstream then pushes a new commit C2 to
 * {@code main} (so the in-workspace clone stays stale at C1, unaware of
 * C2); finally {@code lib-a}'s {@code sha:} in {@code workspace.yaml} is
 * rewritten to C2. The in-workspace clone is left dirty with an
 * <em>untracked</em> file so {@code run()}'s clean-clone path (which would
 * otherwise fetch+rebase and pull C2 on its own) is skipped — leaving C2
 * genuinely absent when {@code checkoutSha} runs, which is the real #685
 * condition.
 *
 * @see SubprojectInitializer#checkoutSha(File, network.ike.workspace.Subproject)
 */
class ScaffoldInitPinResetTest {

    @TempDir
    Path tempDir;

    /** Subproject whose pin we move; the others stay at C1. */
    private static final String PINNED = "lib-a";

    private static final List<String> ALL = List.of("lib-a", "lib-b", "app-c");

    /** SHA of the upstream HEAD each clone starts at (the C1 baseline). */
    private String c1;

    /** SHA of the new commit pushed to {@code lib-a}'s upstream (the pin). */
    private String c2;

    // ── Tests ─────────────────────────────────────────────────────

    /**
     * #949: the interactive default mode never moves the working tree
     * onto the pin — pins are checkpoint-restore state, honored only in
     * {@code resetToPin} mode. A stale clone stays wherever its branch
     * tip is (here C1); the pin is merely noted.
     */
    @Test
    void defaultMode_staleClone_staysOnBranchTip_pinNotedOnly() throws Exception {
        scaffold(/*resetToPin*/ false);

        // Dirty with an UNTRACKED file: makes run() skip its fetch/rebase
        // (so C2 is not pulled out-of-band) without any tracked-file WIP
        // — a genuinely stale-but-no-local-work clone.
        File libA = new File(tempDir.toFile(), PINNED);
        Files.writeString(libA.toPath().resolve("scratch-untracked.txt"),
                "scratch\n", StandardCharsets.UTF_8);

        runInit(/*resetToPin*/ false);

        SubState state = snapshot().get(PINNED);
        assertThat(state.headSha())
                .as("default-mode scaffold-init must NOT move onto the pin "
                        + "(ike-issues#949); HEAD stays at the branch tip C1")
                .isEqualTo(c1);
        assertThat(libA.toPath().resolve("scratch-untracked.txt"))
                .as("an untracked file is not WIP and must survive")
                .exists();
    }

    /**
     * #949 core incident: a declared-but-missing subproject whose pin is
     * STALE (an old commit) must clone at the branch tip, attached —
     * never detached at the historical pin.
     */
    @Test
    void defaultMode_freshClone_landsAttachedOnBranchTip_notThePin()
            throws Exception {
        scaffold(/*resetToPin*/ false);

        // The manifest pins C2, but rewrite it to pin the OLD C1 —
        // simulating a stale checkpoint-managed pin — and delete the
        // clone so init must re-clone it.
        rewritePinnedSha(c1);
        deleteRecursively(new File(tempDir.toFile(), PINNED).toPath());

        runInit(/*resetToPin*/ false);

        File libA = new File(tempDir.toFile(), PINNED);
        SubState state = snapshot().get(PINNED);
        assertThat(state.headSha())
                .as("fresh clone must follow the branch tip (C2), not the "
                        + "stale C1 pin (ike-issues#949)")
                .isEqualTo(c2);
        assertThat(currentBranchOf(libA))
                .as("fresh clone must be ATTACHED on the declared branch, "
                        + "never a detached HEAD (ike-issues#949)")
                .isEqualTo("main");
    }

    /**
     * #949(b): even in {@code resetToPin} (CI / checkpoint-restore) mode
     * a fresh clone reaches the pin with the branch CHECKED OUT — the
     * branch ref points at the pin; no detached HEAD.
     */
    @Test
    void resetMode_freshClone_reachesPinAttached() throws Exception {
        scaffold(/*resetToPin*/ true);

        rewritePinnedSha(c1);
        deleteRecursively(new File(tempDir.toFile(), PINNED).toPath());

        runInit(/*resetToPin*/ true);

        File libA = new File(tempDir.toFile(), PINNED);
        SubState state = snapshot().get(PINNED);
        assertThat(state.headSha())
                .as("reset mode must reach the pin deterministically (#685)")
                .isEqualTo(c1);
        assertThat(currentBranchOf(libA))
                .as("reaching the pin must leave the branch checked out, "
                        + "not a detached HEAD (ike-issues#949)")
                .isEqualTo("main");
    }

    private static String currentBranchOf(File dir) throws Exception {
        Process p = new ProcessBuilder("git", "branch", "--show-current")
                .directory(dir).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        p.waitFor();
        return out;
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        }
    }

    /**
     * CI/force mode reaches the pin deterministically even over a dirty
     * working tree: {@code git reset --hard} moves HEAD to C2 and discards
     * the uncommitted edit.
     */
    @Test
    void resetMode_dirtyClone_resetsHardToPin() throws Exception {
        scaffold(/*resetToPin*/ true);

        // Dirty a TRACKED file (not committed).
        File libA = new File(tempDir.toFile(), PINNED);
        Path pom = libA.toPath().resolve("pom.xml");
        Files.writeString(pom,
                Files.readString(pom) + "<!-- uncommitted edit -->\n",
                StandardCharsets.UTF_8);

        runInit(/*resetToPin*/ true);

        SubState state = snapshot().get(PINNED);
        assertThat(state.headSha())
                .as("reset-mode scaffold-init must reset --hard to the pin")
                .isEqualTo(c2);
        // reset --hard discards tracked-file WIP. (The tree is not
        // wholesale "clean": run() also writes untracked CLAUDE.md /
        // wrapper / jvm.config files into the clone. We assert the
        // uncommitted *tracked* edit is gone, and that no tracked file is
        // modified.)
        assertThat(Files.readString(pom))
                .as("reset --hard must discard the uncommitted edit to pom.xml")
                .doesNotContain("uncommitted edit");
        assertThat(modifiedTrackedFiles(libA))
                .as("reset --hard must leave no modified tracked files")
                .isEmpty();
    }

    /**
     * Safety: the default (interactive) mode must never destroy local
     * work. With a conflicting uncommitted edit, {@code git checkout}
     * refuses; the failure is warned (not thrown), HEAD stays at C1, and
     * the edit is preserved.
     */
    @Test
    void defaultMode_dirtyClone_preservesWipAndDoesNotThrow() throws Exception {
        scaffold(/*resetToPin*/ false);

        File libA = new File(tempDir.toFile(), PINNED);
        Path pom = libA.toPath().resolve("pom.xml");
        Files.writeString(pom,
                Files.readString(pom) + "<!-- PRECIOUS uncommitted work -->\n",
                StandardCharsets.UTF_8);

        // Must not throw — the interactive path warns and proceeds.
        runInit(/*resetToPin*/ false);

        SubState state = snapshot().get(PINNED);
        assertThat(state.headSha())
                .as("default mode must NOT move HEAD off C1 when that would "
                        + "clobber uncommitted work")
                .isEqualTo(c1);
        assertThat(Files.readString(pom))
                .as("the uncommitted edit must be preserved")
                .contains("PRECIOUS uncommitted work");
    }

    /**
     * Fail-loud: in CI/force mode an unreachable pin must abort the build
     * with a {@link MojoException} (naming the subproject + pin) rather
     * than warn and proceed on stale code.
     */
    @Test
    void resetMode_unreachablePin_failsLoud() throws Exception {
        scaffold(/*resetToPin*/ true);

        // A well-formed but non-existent SHA — not in the repo or origin.
        String bogus = "0123456789abcdef0123456789abcdef01234567";
        rewritePinnedSha(bogus);

        File libA = new File(tempDir.toFile(), PINNED);
        Files.writeString(libA.toPath().resolve("scratch-untracked.txt"),
                "scratch\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> runInit(/*resetToPin*/ true))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining(PINNED)
                .hasMessageContaining(bogus);
    }

    // ── Fixture ───────────────────────────────────────────────────

    /**
     * Build a workspace with bare upstreams, clone the subprojects at C1
     * via a first {@code scaffold-init} pass, push a new commit C2 to
     * {@link #PINNED}'s upstream {@code main} from a throwaway sibling
     * clone, and rewrite {@link #PINNED}'s {@code sha:} to C2.
     *
     * @param resetToPin the mode used for the initial C1-cloning pass
     *                   (the subsequent assertion pass is driven explicitly
     *                   per test via {@link #runInit(boolean)})
     */
    private void scaffold(boolean resetToPin) throws Exception {
        new TestWorkspaceHelper(tempDir).buildWorkspaceWithUpstreams();

        // First pass: clone every subproject at upstream HEAD (C1).
        runInit(resetToPin);
        for (String name : ALL) {
            configureClone(new File(tempDir.toFile(), name));
        }
        c1 = head(new File(tempDir.toFile(), PINNED));

        // Push C2 to PINNED's upstream main from a separate sibling clone,
        // so the in-workspace clone stays stale at C1.
        c2 = pushNewUpstreamCommit(PINNED);

        // Pin PINNED to C2 in workspace.yaml.
        rewritePinnedSha(c2);
    }

    /**
     * Run {@code scaffold-init}'s subproject pass against the current
     * workspace.yaml in the given mode, by invoking
     * {@link SubprojectInitializer} directly (no Maven lifecycle).
     *
     * @param resetToPin whether to reach pins with {@code git reset --hard}
     *                   and fail loud on an unreachable pin
     */
    private void runInit(boolean resetToPin) throws Exception {
        Path manifestPath = tempDir.resolve("workspace.yaml");
        Manifest m = ManifestReader.read(manifestPath);
        WorkspaceGraph graph = new WorkspaceGraph(m);
        new SubprojectInitializer(graph, tempDir.toFile(), "test-ws",
                new TestLog(), resetToPin).run();
    }

    /**
     * Clone {@code subproject}'s bare upstream into a throwaway directory,
     * add a commit on {@code main}, push it, and return its SHA. Leaves the
     * in-workspace clone untouched (and thus stale).
     *
     * @param subproject the subproject whose upstream to advance
     * @return the SHA of the newly-pushed commit
     */
    private String pushNewUpstreamCommit(String subproject) throws Exception {
        Path origin = tempDir.resolve(".upstreams")
                .resolve("upstream-" + subproject + ".git");
        Path adv = Files.createTempDirectory("advance-" + subproject + "-");
        exec(adv.getParent(), "git", "clone",
                origin.toAbsolutePath().toString(), adv.getFileName().toString());
        configureClone(adv.toFile());
        // Touch a *tracked* file (pom.xml) so C2 genuinely differs from C1
        // in a versioned path — required for the "checkout refuses to
        // clobber a conflicting local edit" safety test — and add a new
        // file so the snapshot is unambiguously a different commit.
        Path pom = adv.resolve("pom.xml");
        Files.writeString(pom,
                Files.readString(pom) + "<!-- C2 upstream change -->\n",
                StandardCharsets.UTF_8);
        Files.writeString(adv.resolve("NEW-ON-C2.txt"),
                "second commit on " + subproject + "\n", StandardCharsets.UTF_8);
        exec(adv, "git", "add", "pom.xml", "NEW-ON-C2.txt");
        exec(adv, "git", "commit", "-m", "feat(" + subproject + "): C2");
        exec(adv, "git", "push", "origin", "main");
        return head(adv.toFile());
    }

    /**
     * Rewrite {@link #PINNED}'s {@code sha:} field in workspace.yaml,
     * inserting it after the subproject's {@code version:} line (or
     * replacing an existing pin on a repeat call).
     *
     * @param sha the SHA to pin {@link #PINNED} to
     */
    private void rewritePinnedSha(String sha) throws Exception {
        Path yaml = tempDir.resolve("workspace.yaml");
        String content = Files.readString(yaml, StandardCharsets.UTF_8);
        // lib-a is the first subproject; its version line is immediately
        // followed by the lib-b: key — an unambiguous anchor.
        String anchor = "    version: \"1.0.0-SNAPSHOT\"\n  lib-b:";
        String shaLine = "    sha: \"" + sha + "\"\n";
        String replacement = "    version: \"1.0.0-SNAPSHOT\"\n"
                + shaLine + "  lib-b:";
        String updated;
        if (content.contains("    sha: \"")) {
            // Repeat call (fail-loud test): replace the existing pin value.
            updated = content.replaceFirst(
                    "    sha: \"[0-9a-fA-F]+\"\n",
                    java.util.regex.Matcher.quoteReplacement(shaLine));
        } else {
            assertThat(content)
                    .as("expected the lib-a version/lib-b anchor in workspace.yaml")
                    .contains(anchor);
            updated = content.replace(anchor, replacement);
        }
        Files.writeString(yaml, updated, StandardCharsets.UTF_8);
    }

    // ── git helpers (per-repo hermetic config + exec) ─────────────

    private Map<String, SubState> snapshot() {
        return WorkspaceStateAssert.snapshot(tempDir.toFile(), ALL);
    }

    private static String head(File dir) throws Exception {
        return capture(dir, "git", "rev-parse", "HEAD");
    }

    /**
     * Porcelain status restricted to tracked files ({@code -uno}), so the
     * untracked CLAUDE.md / wrapper / jvm.config files that
     * {@code SubprojectInitializer.run()} writes into the clone don't mask
     * a tracked-file assertion.
     *
     * @param dir the repository working directory
     * @return the trimmed {@code git status --porcelain -uno} output
     */
    private static String modifiedTrackedFiles(File dir) throws Exception {
        return capture(dir, "git", "status", "--porcelain", "-uno");
    }

    /**
     * Pin a per-repo hermetic identity on a clone: no foreign hooks, no
     * signing, a fixed committer. Belt-and-suspenders over the
     * {@code GIT_CONFIG_GLOBAL} hermetic config the surefire run already
     * sets (see this module's pom.xml and {@code TestWorkspaceHelper}).
     *
     * @param dir the clone's working directory
     */
    private void configureClone(File dir) throws Exception {
        Path noHooks = Files.createDirectories(tempDir.resolve(".nohooks"));
        exec(dir.toPath(), "git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec(dir.toPath(), "git", "config", "commit.gpgsign", "false");
        exec(dir.toPath(), "git", "config", "tag.gpgsign", "false");
        exec(dir.toPath(), "git", "config", "user.email", "test@example.com");
        exec(dir.toPath(), "git", "config", "user.name", "Test");
    }

    private static String capture(File dir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(dir)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new RuntimeException("Command failed (exit " + exit + "): "
                    + String.join(" ", command) + "\n" + output);
        }
        return output;
    }

    private static void exec(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new RuntimeException("Command failed (exit " + exit + "): "
                    + String.join(" ", command)
                    + (output.isBlank() ? "" : "\n" + output));
        }
    }
}
