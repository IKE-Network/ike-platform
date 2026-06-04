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

    // ── Fixture helpers ──────────────────────────────────────────────

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
