package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.FaultableExec;

import org.apache.maven.api.plugin.MojoException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #573 park <b>abort-in-place</b> safety criterion (criterion 2): if the
 * branch-push that constitutes the "park" fails, the park must abort
 * <em>before</em> removing the local clone — a clone whose work is not yet on
 * origin is never reduced. {@link ParkSupport#parkSubproject} pushes
 * {@code compBranch} to origin and only calls {@code deleteDirectory} when that
 * push succeeds; on failure it throws "Park aborted … refusing to remove a
 * clone whose work is not on origin".
 *
 * <p>This is the load-bearing data-loss guard. The fixture is the same
 * branch-divergent workspace as {@link WsSwitchParkRestoreTest}
 * ({@code extra} declared on {@code feature/x} but not on {@code main}), driven
 * with {@code -DnoStash} so the park branch-push is the <em>only</em> push the
 * switch issues. The push is forced to fail with
 * {@link FaultableExec#failWhenCommandContains(String) failWhenCommandContains("push")},
 * which intercepts the {@code git push} routed through {@code VcsOperations}.
 *
 * <p>If someone reordered {@code ParkSupport.parkSubproject} to delete the
 * clone before pushing (or to swallow the push failure), assertion #2 below
 * would fail: the {@code extra/.git} clone would be gone even though its work
 * never reached origin.
 */
class WsSwitchParkAbortTest {

    @TempDir
    Path tmp;

    @Test
    void park_aborts_in_place_when_branch_push_fails() throws Exception {
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

        // ── Switch feature/x → main with the park push forced to fail ──────
        // -DnoStash ⇒ the only push the switch issues is the park branch-push
        // for the feature-only member `extra`. FaultableExec intercepts it
        // (via the VcsOperations CommandInterceptor seam) and throws, so the
        // park aborts before deleteDirectory ever runs.
        try (FaultableExec fault = FaultableExec.install()
                .failWhenCommandContains("push")) {

            // 1) The injected push failure propagates: the park aborts and the
            //    switch fails loudly rather than silently losing the clone.
            assertThatThrownBy(() -> switchTo("main"))
                    .isInstanceOf(MojoException.class);

            // 2) THE INVARIANT: extra's clone is still on disk — it was NOT
            //    deleted, because the park push never reached origin. If the
            //    delete were reordered before the push, this would fail.
            assertThat(tmp.resolve("extra").resolve(".git"))
                    .as("feature-only clone NOT deleted when park push fails")
                    .exists();

            // 3) Prove the abort path genuinely ran (no vacuous pass): the
            //    push was actually intercepted at least once.
            assertThat(fault.matchCount())
                    .as("park push was intercepted")
                    .isGreaterThanOrEqualTo(1);
        }

        // The parked branch was never pushed, so origin still lacks any new
        // state for it beyond what the fixture pushed at setup — the clone
        // (assertion #2) remains the system of record for the unpushed work.
        assertThat(tmp.resolve("extra").resolve(".git"))
                .as("clone survives the aborted park").exists();
    }

    // ── Fixture helpers (mirror WsSwitchParkRestoreTest) ──────────────

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
}
