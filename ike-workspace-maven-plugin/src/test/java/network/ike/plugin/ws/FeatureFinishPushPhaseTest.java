package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the two-phase feature-finish contract of
 * IKE-Network/ike-issues#858 (push-before-delete, verified pushes,
 * branches kept on failure), #791 (the aggregator's workspace.yaml
 * reconciliation commit is pushed), #857 (the workspace root repo is
 * covered by the origin/&lt;target&gt; refresh), and the #947 defensive
 * narrowed-refspec guard.
 *
 * <p>Uses a remote-backed workspace: bare upstreams for the root and
 * every subproject (via {@link TestWorkspaceHelper#buildSiblingScenario()}),
 * so pushes, push verification, and remote branch deletion are all real.
 */
class FeatureFinishPushPhaseTest {

    private static final String FEATURE = "push-test";
    private static final String BRANCH = "feature/" + FEATURE;
    private static final String[] SUBS = {"lib-a", "lib-b", "app-c"};

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;
    private Path primary;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        primary = helper.buildSiblingScenario();

        // Real workspace roots gitignore their subproject clone dirs
        // (scaffold-managed); mirror that so the root working tree reads
        // clean with the clones present.
        Files.writeString(primary.resolve(".gitignore"),
                ".ike/vcs-state\nlib-a/\nlib-b/\napp-c/\n",
                StandardCharsets.UTF_8);
        exec(primary, "git", "add", ".gitignore");
        exec(primary, "git", "commit", "-m",
                "workspace: ignore subproject clones");
        exec(primary, "git", "push", "origin", "main");

        // Put every subproject on the feature branch with a qualified
        // version + real feature work, pushed to its origin (so remote
        // feature-branch deletion is observable).
        for (String name : SUBS) {
            Path sub = primary.resolve(name);
            exec(sub, "git", "checkout", "-b", BRANCH);

            Path pom = sub.resolve("pom.xml");
            String pomContent = Files.readString(pom, StandardCharsets.UTF_8);
            String qualified = pomContent.replaceFirst(
                    "<version>(\\d+\\.\\d+\\.\\d+)-SNAPSHOT</version>",
                    "<version>$1-" + FEATURE + "-SNAPSHOT</version>");
            Files.writeString(pom, qualified, StandardCharsets.UTF_8);
            exec(sub, "git", "add", "pom.xml");
            exec(sub, "git", "commit", "-m",
                    "feature: set branch-qualified version");

            Files.writeString(sub.resolve("feature-work.txt"),
                    "feature content for " + name, StandardCharsets.UTF_8);
            exec(sub, "git", "add", "feature-work.txt");
            exec(sub, "git", "commit", "-m", "feature: add feature work");
            exec(sub, "git", "push", "-u", "origin", BRANCH);
        }

        // Workspace root on the feature branch too, with workspace.yaml
        // branch/version fields qualified — what ws:feature-start writes.
        exec(primary, "git", "checkout", "-b", BRANCH);
        Path wsYaml = primary.resolve("workspace.yaml");
        String yaml = Files.readString(wsYaml, StandardCharsets.UTF_8);
        yaml = yaml.replace("branch: main", "branch: " + BRANCH);
        yaml = yaml.replace("version: \"1.0.0-SNAPSHOT\"",
                "version: \"1.0.0-" + FEATURE + "-SNAPSHOT\"");
        yaml = yaml.replace("version: \"2.0.0-SNAPSHOT\"",
                "version: \"2.0.0-" + FEATURE + "-SNAPSHOT\"");
        yaml = yaml.replace("version: \"3.0.0-SNAPSHOT\"",
                "version: \"3.0.0-" + FEATURE + "-SNAPSHOT\"");
        Files.writeString(wsYaml, yaml, StandardCharsets.UTF_8);
        exec(primary, "git", "add", "workspace.yaml");
        exec(primary, "git", "commit", "-m",
                "workspace: switch to " + BRANCH);
        exec(primary, "git", "push", "-u", "origin", BRANCH);
    }

    private FeatureFinishSquashDraftMojo newPublishMojo() throws Exception {
        FeatureFinishSquashDraftMojo mojo =
                TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = FEATURE;
        mojo.targetBranch = "main";
        mojo.message = "Squash " + FEATURE;
        mojo.publish = true;
        return mojo;
    }

    // ── Happy path: push-before-delete, root + reconciliation pushed ──

    @Test
    void pushPhase_pushesEveryMemberAndRoot_thenDeletesBranches() throws Exception {
        FeatureFinishSquashDraftMojo mojo = newPublishMojo();
        mojo.push = true;

        mojo.execute();

        // Every subproject's squash is on origin/main (verified push).
        for (String name : SUBS) {
            Path sub = primary.resolve(name);
            String localMain = execCapture(sub, "git", "rev-parse", "main");
            String remoteMain = execCapture(sub,
                    "git", "ls-remote", "origin", "main").split("\\s+")[0];
            assertThat(remoteMain)
                    .as(name + ": origin/main must equal local main after the finish")
                    .isEqualTo(localMain);
            assertThat(execCapture(sub, "git", "log", "--oneline", "-3"))
                    .contains("Squash " + FEATURE);
        }

        // #791: the root push happens AFTER the workspace.yaml
        // reconciliation commit — origin has it, nothing is left behind.
        String rootLocal = execCapture(primary, "git", "rev-parse", "main");
        String rootRemote = execCapture(primary,
                "git", "ls-remote", "origin", "main").split("\\s+")[0];
        assertThat(rootRemote)
                .as("workspace root: origin/main must include the "
                        + "reconciliation commit (#791)")
                .isEqualTo(rootLocal);
        assertThat(execCapture(primary, "git", "log", "--oneline", "-5"))
                .contains("workspace: restore branches");

        // Branches deleted — locally and on every origin — only after
        // the verified pushes above.
        for (String name : SUBS) {
            Path sub = primary.resolve(name);
            assertThat(execCapture(sub, "git", "branch"))
                    .doesNotContain(BRANCH);
            assertThat(execCapture(sub,
                    "git", "ls-remote", "--heads", "origin", BRANCH))
                    .as(name + ": remote feature branch deleted after push")
                    .isEmpty();
        }
        assertThat(execCapture(primary, "git", "branch"))
                .doesNotContain(BRANCH);
    }

    // ── Push failure: branches kept everywhere, actionable failure ──

    @Test
    void pushFailure_keepsEveryBranch_namesTheMember_andGuidesRecovery()
            throws Exception {
        // Make ONE subproject's origin refuse pushes (reachable for the
        // refresh fetch, rejecting at pre-receive) so its push fails
        // while the others succeed — the transient-push-error shape of
        // the real #858 incident.
        Path appC = primary.resolve("app-c");
        String url = execCapture(appC, "git", "remote", "get-url", "origin");
        Path bareOrigin = Path.of(java.net.URI.create(url));
        Path hook = bareOrigin.resolve("hooks/pre-receive");
        Files.createDirectories(hook.getParent());
        Files.writeString(hook, "#!/bin/sh\n"
                + "echo 'pre-receive: pushes disabled for test' >&2\n"
                + "exit 1\n", StandardCharsets.UTF_8);
        hook.toFile().setExecutable(true);

        FeatureFinishSquashDraftMojo mojo = newPublishMojo();
        mojo.push = true;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("app-c")
                .hasMessageContaining("No feature branch was deleted")
                .hasMessageContaining(WsGoal.PUSH.qualified());

        // The squashes all landed on local main…
        for (String name : SUBS) {
            assertThat(execCapture(primary.resolve(name),
                    "git", "log", "main", "--oneline", "-3"))
                    .contains("Squash " + FEATURE);
        }
        // …but NO feature branch was deleted anywhere — not even in the
        // members whose own pushes succeeded (working-set atomicity).
        for (String name : SUBS) {
            Path sub = primary.resolve(name);
            assertThat(execCapture(sub, "git", "branch"))
                    .as(name + ": local feature branch kept after push failure")
                    .contains(BRANCH);
            if (!name.equals("app-c")) {
                assertThat(execCapture(sub,
                        "git", "ls-remote", "--heads", "origin", BRANCH))
                        .as(name + ": remote feature branch kept")
                        .contains("refs/heads/" + BRANCH);
            }
        }
        assertThat(execCapture(primary, "git", "branch")).contains(BRANCH);

        // The stranded member's origin/main is recoverable with a plain
        // push once the remote is repaired — the commit is intact.
        Files.delete(hook);
        exec(appC, "git", "push", "origin", "main");
        String localMain = execCapture(appC, "git", "rev-parse", "main");
        String remoteMain = execCapture(appC,
                "git", "ls-remote", "origin", "main").split("\\s+")[0];
        assertThat(remoteMain).isEqualTo(localMain);
    }

    // ── push=false on a remote-backed workspace: nothing deleted ──

    @Test
    void pushFalse_remoteBacked_keepsBranches_andPushesNothing() throws Exception {
        // Record every origin/main before the finish.
        String[] originMainBefore = new String[SUBS.length];
        for (int i = 0; i < SUBS.length; i++) {
            originMainBefore[i] = execCapture(primary.resolve(SUBS[i]),
                    "git", "ls-remote", "origin", "main").split("\\s+")[0];
        }

        FeatureFinishSquashDraftMojo mojo = newPublishMojo();
        mojo.push = false;

        mojo.execute();   // succeeds — kept branches are not a failure

        for (int i = 0; i < SUBS.length; i++) {
            Path sub = primary.resolve(SUBS[i]);
            // Squash landed locally…
            assertThat(execCapture(sub, "git", "log", "main", "--oneline", "-3"))
                    .contains("Squash " + FEATURE);
            // …origin/main untouched…
            assertThat(execCapture(sub,
                    "git", "ls-remote", "origin", "main").split("\\s+")[0])
                    .as(SUBS[i] + ": origin/main must not move with -Dpush=false")
                    .isEqualTo(originMainBefore[i]);
            // …and both feature branches kept (deletion requires a
            // confirmed push).
            assertThat(execCapture(sub, "git", "branch")).contains(BRANCH);
            assertThat(execCapture(sub,
                    "git", "ls-remote", "--heads", "origin", BRANCH))
                    .contains("refs/heads/" + BRANCH);
        }
        assertThat(execCapture(primary, "git", "branch")).contains(BRANCH);
    }

    // ── #857/#858: stale root main is refreshed before the root push ──

    @Test
    void staleRootMain_isRefreshedFromOrigin_beforeRootPush() throws Exception {
        // Advance the root upstream's main from a second clone — the
        // "checkpoint commits landed from the other machine" scenario.
        String rootUrl = execCapture(primary,
                "git", "remote", "get-url", "origin");
        Path sideClone = tempDir.resolve("side-root");
        exec(tempDir, "git", "clone", rootUrl, "side-root");
        Files.writeString(sideClone.resolve("external-note.txt"),
                "advanced from the other machine", StandardCharsets.UTF_8);
        exec(sideClone, "git", "add", "external-note.txt");
        exec(sideClone, "git", "commit", "-m",
                "external: root main advanced elsewhere");
        exec(sideClone, "git", "push", "origin", "main");

        FeatureFinishSquashDraftMojo mojo = newPublishMojo();
        mojo.push = true;

        mojo.execute();   // must not fail with a non-fast-forward root push

        // Root origin/main carries BOTH the external commit and the
        // finish's merge + reconciliation.
        String rootLocal = execCapture(primary, "git", "rev-parse", "main");
        String rootRemote = execCapture(primary,
                "git", "ls-remote", "origin", "main").split("\\s+")[0];
        assertThat(rootRemote).isEqualTo(rootLocal);
        String rootLog = execCapture(primary,
                "git", "log", "main", "--oneline", "-8");
        assertThat(rootLog).contains("external: root main advanced elsewhere");
        assertThat(rootLog).contains("workspace: restore branches");
    }

    // ── #857: update-feature draft assesses against origin/<target> ──

    @Test
    void updateFeatureDraft_reportsBehind_whenLocalMainIsStale() throws Exception {
        // Advance lib-a's upstream main from a side clone; the primary's
        // local main stays at the point the feature branched from — the
        // exact #857 repro (draft said "up to date with main" while
        // origin/main was 16 ahead).
        String libAUrl = execCapture(primary.resolve("lib-a"),
                "git", "remote", "get-url", "origin");
        Path side = tempDir.resolve("side-lib-a");
        exec(tempDir, "git", "clone", libAUrl, "side-lib-a");
        Files.writeString(side.resolve("mainline.txt"),
                "external mainline work", StandardCharsets.UTF_8);
        exec(side, "git", "add", "mainline.txt");
        exec(side, "git", "commit", "-m", "external: mainline advanced");
        exec(side, "git", "push", "origin", "main");

        UpdateFeatureDraftMojo mojo =
                TestLog.createMojo(UpdateFeatureDraftMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = FEATURE;
        mojo.targetBranch = "main";
        mojo.publish = false;

        mojo.execute();

        String report = Files.readString(WorkspaceReport.reportPath(
                primary, WsGoal.UPDATE_FEATURE_DRAFT.qualified()),
                StandardCharsets.UTF_8);
        // lib-a is 1 behind the TRUE mainline — the draft must say so,
        // not "up to date with main" measured against stale local main.
        assertThat(report)
                .as("draft must assess lib-a against origin/main (#857)")
                .contains("clean update expected (1 behind");
    }

    // ── #947: narrowed refspec fails loudly instead of mis-assessing ──

    @Test
    void narrowedRefspec_failsLoudly_withRepairInstructions() throws Exception {
        Path libA = primary.resolve("lib-a");
        exec(libA, "git", "config", "remote.origin.fetch",
                "+refs/heads/" + BRANCH + ":refs/remotes/origin/" + BRANCH);
        exec(libA, "git", "update-ref", "-d", "refs/remotes/origin/main");

        FeatureFinishSquashDraftMojo mojo = newPublishMojo();
        mojo.publish = false;   // even the read-only draft must refuse

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("narrowed")
                .hasMessageContaining("remote.origin.fetch");
    }

    // ── helpers ──────────────────────────────────────────────────

    private void exec(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed (exit " + exitCode
                    + "): " + String.join(" ", command)
                    + (output.isBlank() ? "" : "\n" + output));
        }
    }

    private String execCapture(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(false)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String err = new String(process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            throw new RuntimeException("Command failed (exit " + exitCode
                    + "): " + String.join(" ", command)
                    + (err.isBlank() ? "" : "\n" + err));
        }
        return output.trim();
    }
}
