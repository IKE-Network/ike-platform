package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IKE-Network/ike-issues#934 (settled 2026-07-27): after a fully
 * confirmed push phase, a sibling feature-finish fast-forwards the
 * parent workspace it was cut from — parent derived from the
 * {@code <parent>꞉<feature>} naming convention, FF-only across the
 * parent working set, divergence skipped, best-effort.
 */
class FeatureFinishParentSyncTest {

    private static final String FEATURE = "sync-test";
    private static final String BRANCH = "feature/" + FEATURE;
    private static final String[] SUBS = {"lib-a", "lib-b", "app-c"};

    @TempDir
    Path tempDir;

    /** The parent (primary) workspace — stays on stale main. */
    private Path parent;

    /** The sibling clone ({@code primary꞉sync-test}) the finish runs in. */
    private Path sibling;

    @BeforeEach
    void setUp() throws Exception {
        TestWorkspaceHelper helper = new TestWorkspaceHelper(tempDir);
        parent = helper.buildSiblingScenario();

        // Parent root ignores its subproject clones; push so the sibling
        // clone inherits the same .gitignore.
        Files.writeString(parent.resolve(".gitignore"),
                ".ike/vcs-state\nlib-a/\nlib-b/\napp-c/\n",
                StandardCharsets.UTF_8);
        exec(parent, "git", "add", ".gitignore");
        exec(parent, "git", "commit", "-m", "workspace: ignore subprojects");
        exec(parent, "git", "push", "origin", "main");

        // Build the sibling clone next to the parent, per the ꞉ naming
        // convention, cloning root + subprojects from the same upstreams.
        String rootUrl = execCapture(parent, "git", "remote", "get-url", "origin");
        sibling = tempDir.resolve("primary" + FeatureFinishSupport.SIBLING_SEPARATOR
                + FEATURE);
        exec(tempDir, "git", "clone", rootUrl, sibling.getFileName().toString());
        for (String name : SUBS) {
            String subUrl = execCapture(parent.resolve(name),
                    "git", "remote", "get-url", "origin");
            exec(sibling, "git", "clone", subUrl, name);
        }

        // Feature work in the SIBLING: qualified versions + content,
        // feature branches pushed; sibling root branched with yaml edits.
        for (String name : SUBS) {
            Path sub = sibling.resolve(name);
            exec(sub, "git", "checkout", "-b", BRANCH);
            Path pom = sub.resolve("pom.xml");
            String pomContent = Files.readString(pom, StandardCharsets.UTF_8);
            Files.writeString(pom, pomContent.replaceFirst(
                    "<version>(\\d+\\.\\d+\\.\\d+)-SNAPSHOT</version>",
                    "<version>$1-" + FEATURE + "-SNAPSHOT</version>"),
                    StandardCharsets.UTF_8);
            exec(sub, "git", "add", "pom.xml");
            exec(sub, "git", "commit", "-m", "feature: qualify version");
            Files.writeString(sub.resolve("feature-work.txt"),
                    "work for " + name, StandardCharsets.UTF_8);
            exec(sub, "git", "add", "feature-work.txt");
            exec(sub, "git", "commit", "-m", "feature: add work");
            exec(sub, "git", "push", "-u", "origin", BRANCH);
        }
        exec(sibling, "git", "checkout", "-b", BRANCH);
        Path wsYaml = sibling.resolve("workspace.yaml");
        String yaml = Files.readString(wsYaml, StandardCharsets.UTF_8);
        yaml = yaml.replace("branch: main", "branch: " + BRANCH);
        yaml = yaml.replace("version: \"1.0.0-SNAPSHOT\"",
                "version: \"1.0.0-" + FEATURE + "-SNAPSHOT\"");
        yaml = yaml.replace("version: \"2.0.0-SNAPSHOT\"",
                "version: \"2.0.0-" + FEATURE + "-SNAPSHOT\"");
        yaml = yaml.replace("version: \"3.0.0-SNAPSHOT\"",
                "version: \"3.0.0-" + FEATURE + "-SNAPSHOT\"");
        Files.writeString(wsYaml, yaml, StandardCharsets.UTF_8);
        exec(sibling, "git", "add", "workspace.yaml");
        exec(sibling, "git", "commit", "-m", "workspace: switch to " + BRANCH);
        exec(sibling, "git", "push", "-u", "origin", BRANCH);
    }

    private FeatureFinishSquashDraftMojo finishMojo() throws Exception {
        FeatureFinishSquashDraftMojo mojo =
                TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.manifest = sibling.resolve("workspace.yaml").toFile();
        mojo.feature = FEATURE;
        mojo.targetBranch = "main";
        mojo.message = "Squash " + FEATURE;
        mojo.publish = true;
        mojo.push = true;
        return mojo;
    }

    @Test
    void siblingFinish_fastForwardsParentWorkingSet() throws Exception {
        // Parent members are at pre-feature main; record them.
        String parentRootBefore = execCapture(parent, "git", "rev-parse", "main");

        FeatureFinishSquashDraftMojo mojo = finishMojo();
        mojo.syncParent = true;

        mojo.execute();

        // Every parent member's local main now equals origin/main.
        for (String name : SUBS) {
            Path dir = parent.resolve(name);
            String local = execCapture(dir, "git", "rev-parse", "main");
            String remote = execCapture(dir,
                    "git", "ls-remote", "origin", "main").split("\\s+")[0];
            assertThat(local)
                    .as(name + ": parent local main fast-forwarded to origin (#934)")
                    .isEqualTo(remote);
        }
        String parentRootAfter = execCapture(parent, "git", "rev-parse", "main");
        String parentRootRemote = execCapture(parent,
                "git", "ls-remote", "origin", "main").split("\\s+")[0];
        assertThat(parentRootAfter).isNotEqualTo(parentRootBefore);
        assertThat(parentRootAfter).isEqualTo(parentRootRemote);

        // The parent stayed on main with a clean tree (checked-out FF).
        assertThat(execCapture(parent, "git", "branch", "--show-current"))
                .isEqualTo("main");
        assertThat(execCapture(parent, "git", "status", "--porcelain", "-uno"))
                .isEmpty();
    }

    @Test
    void divergedParentMember_isSkipped_othersStillSync() throws Exception {
        // Give the parent's lib-a a local main commit (unpushed) so it
        // DIVERGES from origin once the sibling's squash lands.
        Path libA = parent.resolve("lib-a");
        Files.writeString(libA.resolve("parent-wip.txt"), "parent-local work\n",
                StandardCharsets.UTF_8);
        exec(libA, "git", "add", "parent-wip.txt");
        exec(libA, "git", "commit", "-m", "parent: local-only commit");
        String libABefore = execCapture(libA, "git", "rev-parse", "main");

        FeatureFinishSquashDraftMojo mojo = finishMojo();
        mojo.syncParent = true;

        mojo.execute();   // finish itself succeeds — propagation is best-effort

        // lib-a untouched (diverged → skipped)…
        assertThat(execCapture(libA, "git", "rev-parse", "main"))
                .as("a diverged parent member must be left alone (#934)")
                .isEqualTo(libABefore);
        assertThat(libA.resolve("parent-wip.txt")).exists();
        // …while the others fast-forwarded.
        for (String name : new String[]{"lib-b", "app-c"}) {
            Path dir = parent.resolve(name);
            assertThat(execCapture(dir, "git", "rev-parse", "main"))
                    .isEqualTo(execCapture(dir,
                            "git", "ls-remote", "origin", "main").split("\\s+")[0]);
        }
    }

    @Test
    void syncParentFalse_leavesParentUntouched() throws Exception {
        String[] before = new String[SUBS.length];
        for (int i = 0; i < SUBS.length; i++) {
            before[i] = execCapture(parent.resolve(SUBS[i]),
                    "git", "rev-parse", "main");
        }
        String rootBefore = execCapture(parent, "git", "rev-parse", "main");

        FeatureFinishSquashDraftMojo mojo = finishMojo();
        mojo.syncParent = false;

        mojo.execute();

        for (int i = 0; i < SUBS.length; i++) {
            assertThat(execCapture(parent.resolve(SUBS[i]),
                    "git", "rev-parse", "main"))
                    .as(SUBS[i] + ": -DsyncParent=false must not touch the parent")
                    .isEqualTo(before[i]);
        }
        assertThat(execCapture(parent, "git", "rev-parse", "main"))
                .isEqualTo(rootBefore);
    }

    // ── helpers ──────────────────────────────────────────────────

    private void exec(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command) + "\n" + output);
        }
    }

    private static String execCapture(Path workDir, String... command)
            throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(false)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command));
        }
        return output.trim();
    }
}
