package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #573 park/restore round-trip on a <em>branch-divergent</em> workspace:
 * {@code extra} is declared on {@code feature/x} but not on {@code main}.
 * Switching to main must <b>park</b> it (push its branch to origin, remove
 * the clone); switching back must <b>restore</b> it (re-clone). {@code shared}
 * (a member of both branches) just switches.
 *
 * <p>Builds a real git-root workspace with bare upstreams so park (push) and
 * restore (clone) exercise actual git. Uses {@code -DnoStash} to keep the
 * focus on park/restore rather than the stash flow.
 */
class WsSwitchParkRestoreTest {

    @TempDir
    Path tmp;

    @Test
    void parks_feature_only_member_on_switch_to_main_then_restores()
            throws Exception {
        String[] urls = buildDivergentWorkspace();
        String extraUrl = urls[1];

        // ── Switch to main → extra parked, shared switched ──────────────
        switchTo("main");

        assertThat(tmp.resolve("extra"))
                .as("feature-only member parked (clone removed)").doesNotExist();
        assertThat(branchOf(tmp.resolve("shared")))
                .as("both-branch member switched").isEqualTo("main");
        assertThat(branchOf(tmp))
                .as("workspace root switched").isEqualTo("main");
        // The parked branch survives on origin (work never lost).
        assertThat(lsRemoteHasBranch(extraUrl, "feature/x")).isTrue();

        // ── Switch back to feature/x → extra restored ───────────────────
        switchTo("feature/x");

        assertThat(tmp.resolve("extra").resolve(".git"))
                .as("feature-only member restored (re-cloned)").exists();
        assertThat(branchOf(tmp.resolve("extra"))).isEqualTo("feature/x");
        assertThat(branchOf(tmp.resolve("shared"))).isEqualTo("feature/x");
        assertThat(branchOf(tmp)).isEqualTo("feature/x");
    }

    /**
     * #573 criterion 4 — content identity across the park/restore round-trip.
     * The existing round-trip test asserts only directory existence and branch
     * name; this one commits a sentinel file with known content onto
     * {@code extra}'s {@code feature/x} branch <em>before</em> the round-trip
     * (so it travels through park → push-to-origin → re-clone under
     * {@code -DnoStash}) and asserts after {@code feature → main → feature}
     * that the sentinel is back with byte-for-byte identical content — not just
     * present.
     *
     * <p>This is what distinguishes a real restore (the parked branch's commits
     * come back intact from origin) from a vacuous one (an empty re-clone, or a
     * stale tree). If park failed to push the committed sentinel, or restore
     * re-cloned the wrong branch, the content assertion would fail.
     */
    @Test
    void round_trip_preserves_subproject_content() throws Exception {
        buildDivergentWorkspace();

        // Commit a sentinel with known content onto extra's feature/x branch,
        // so it is part of the branch that gets parked (pushed to origin) and
        // later restored (re-cloned). Committed — not a WIP file — so it
        // survives under -DnoStash, which skips the stash flow entirely.
        Path extra = tmp.resolve("extra");
        Files.writeString(extra.resolve(SENTINEL_NAME), SENTINEL_BODY,
                StandardCharsets.UTF_8);
        git(extra, "add", SENTINEL_NAME);
        git(extra, "commit", "-m", "add sentinel on feature/x");

        // ── Round-trip: feature/x → main (park extra) → feature/x (restore) ─
        switchTo("main");
        assertThat(tmp.resolve("extra"))
                .as("feature-only member parked on switch to main")
                .doesNotExist();

        switchTo("feature/x");

        // The restored clone exists on feature/x …
        assertThat(extra.resolve(".git"))
                .as("feature-only member restored (re-cloned)").exists();
        assertThat(branchOf(extra)).isEqualTo("feature/x");

        // … and the sentinel is back with IDENTICAL content (not just present).
        Path restoredSentinel = extra.resolve(SENTINEL_NAME);
        assertThat(restoredSentinel)
                .as("sentinel file restored").exists();
        assertThat(Files.readString(restoredSentinel, StandardCharsets.UTF_8))
                .as("sentinel content preserved byte-for-byte across round-trip")
                .isEqualTo(SENTINEL_BODY);
    }

    // ── Fixture helpers ──────────────────────────────────────────────

    /** Sentinel filename committed onto extra's feature/x branch (criterion 4). */
    private static final String SENTINEL_NAME = "SENTINEL.txt";

    /** Known sentinel content asserted byte-for-byte after the round-trip. */
    private static final String SENTINEL_BODY =
            "park/restore content identity — #573 criterion 4\n";

    /**
     * Build the branch-divergent workspace shared by the round-trip tests:
     * bare upstreams for {@code shared} and {@code extra} (each with
     * {@code main} + {@code feature/x}), both cloned in and checked out to
     * {@code feature/x}; a git root whose {@code main} declares
     * {@code {shared}} and whose {@code feature/x} declares
     * {@code {shared, extra}}. Leaves the root and both clones on
     * {@code feature/x}.
     *
     * @return {@code [sharedUrl, extraUrl]} — the bare-upstream URLs
     * @throws Exception on fixture or git failure
     */
    private String[] buildDivergentWorkspace() throws Exception {
        Files.createDirectories(tmp.resolve(".nohooks"));

        // Bare upstreams, each with main + feature/x branches.
        String sharedUrl = bareWithBranches("shared");
        String extraUrl = bareWithBranches("extra");

        // Clone both subprojects into the workspace, on feature/x.
        clone(sharedUrl, "shared");
        clone(extraUrl, "extra");

        // Git-root: main declares {shared}; feature/x declares {shared, extra}.
        git(tmp, "init", "-b", "main");
        hermetic(tmp);
        // Real workspaces gitignore their subproject clones, so the root
        // status stays clean (the switch preflight requires it).
        Files.writeString(tmp.resolve(".gitignore"),
                "/shared/\n/extra/\n/.up/\n/.nohooks/\n.ike/vcs-state\nws꞉*.md\n",
                StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("workspace.yaml"),
                yaml(false, sharedUrl, extraUrl), StandardCharsets.UTF_8);
        git(tmp, "add", ".gitignore", "workspace.yaml");
        git(tmp, "commit", "-m", "main");
        git(tmp, "checkout", "-b", "feature/x");
        Files.writeString(tmp.resolve("workspace.yaml"),
                yaml(true, sharedUrl, extraUrl), StandardCharsets.UTF_8);
        git(tmp, "add", "workspace.yaml");
        git(tmp, "commit", "-m", "feature");
        // Root is now on feature/x; shared + extra present on feature/x.
        return new String[] {sharedUrl, extraUrl};
    }

    private void switchTo(String branch) throws Exception {
        WsSwitchDraftMojo mojo = TestLog.createMojo(WsSwitchDraftMojo.class);
        mojo.manifest = tmp.resolve("workspace.yaml").toFile();
        mojo.branch = branch;
        mojo.publish = true;
        mojo.noStash = true;
        mojo.execute();
    }

    private String bareWithBranches(String name) throws Exception {
        Path bare = tmp.resolve(".up").resolve(name + ".git");
        Files.createDirectories(bare);
        git(bare, "init", "--bare");

        Path work = tmp.resolve(".up").resolve("w-" + name);
        Files.createDirectories(work);
        git(work, "init", "-b", "main");
        hermetic(work);
        Files.writeString(work.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.test</groupId><artifactId>%s</artifactId>
                  <version>1.0.0-SNAPSHOT</version></project>
                """.formatted(name), StandardCharsets.UTF_8);
        git(work, "add", ".");
        git(work, "commit", "-m", "init");
        git(work, "remote", "add", "origin", bare.toAbsolutePath().toString());
        git(work, "push", "-u", "origin", "main");
        git(work, "checkout", "-b", "feature/x");
        git(work, "push", "-u", "origin", "feature/x");
        return bare.toUri().toString();
    }

    private void clone(String url, String name) throws Exception {
        git(tmp, "clone", url, name);
        Path dir = tmp.resolve(name);
        hermetic(dir);
        git(dir, "checkout", "feature/x");
    }

    private static String yaml(boolean feature, String sharedUrl,
                               String extraUrl) {
        String br = feature ? "feature/x" : "main";
        String extra = feature ? """
                  extra:
                    repo: %s
                    branch: feature/x
                    version: "1.0.0-SNAPSHOT"
                """.formatted(extraUrl) : "";
        return """
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  shared:
                    repo: %s
                    branch: %s
                    version: "1.0.0-SNAPSHOT"
                %s""".formatted(sharedUrl, br, extra);
    }

    private void hermetic(Path dir) throws Exception {
        git(dir, "config", "user.email", "test@example.com");
        git(dir, "config", "user.name", "Test");
        git(dir, "config", "commit.gpgsign", "false");
        git(dir, "config", "core.hooksPath",
                tmp.resolve(".nohooks").toAbsolutePath().toString());
    }

    private String branchOf(Path dir) throws Exception {
        return capture(dir, "git", "rev-parse", "--abbrev-ref", "HEAD");
    }

    private boolean lsRemoteHasBranch(String url, String branch)
            throws Exception {
        return capture(tmp, "git", "ls-remote", "--heads", url, branch)
                .contains("refs/heads/" + branch);
    }

    private void git(Path dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new RuntimeException("git " + String.join(" ", args)
                    + " failed:\n" + out);
        }
    }

    private String capture(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        p.waitFor();
        return out;
    }
}
