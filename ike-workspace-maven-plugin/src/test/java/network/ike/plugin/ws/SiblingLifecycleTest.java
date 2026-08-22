package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.maven.api.plugin.MojoException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sibling lifecycle beyond creation and finish: listing what exists
 * (IKE-Network/ike-issues#599) and removing it safely
 * (IKE-Network/ike-issues#600) — against real git repositories, on the
 * local-origin model (#992).
 */
class SiblingLifecycleTest {

    @TempDir
    Path tempDir;

    private Path primary;

    @BeforeEach
    void setUp() throws Exception {
        primary = new TestWorkspaceHelper(tempDir).buildSiblingScenario();
        // A real workspace root ignores its member directories, the ws:
        // goal receipts, and local .ike state; this fixture's upstream
        // carries no .gitignore, so without one every member reads as an
        // uncommitted change (see SiblingLocalOriginRoundTripTest).
        Files.writeString(primary.resolve(".gitignore"), """
                /lib-a/
                /lib-b/
                /app-c/
                ws꞉*.md
                .ike/
                """, StandardCharsets.UTF_8);
        exec(primary, "git", "add", ".gitignore");
        exec(primary, "git", "commit", "-m", "chore: ignore members + receipts");
    }

    // ── ws:sibling-list ──────────────────────────────────────────

    @Test
    void list_classifiesConformantAndLegacySiblings() throws Exception {
        createSibling("lifecycle");
        // A legacy-shaped sibling: right name, but cloned from the shared
        // upstream — origin is a remote remote and no parent is recorded.
        Path legacyRoot = tempDir.resolve("primary꞉legacy");
        exec(tempDir, "git", "clone", "--quiet",
                tempDir.resolve(".upstreams/upstream-primary.git").toString(),
                legacyRoot.getFileName().toString());

        SiblingListMojo list = TestLog.createMojo(SiblingListMojo.class);
        list.manifest = primary.resolve("workspace.yaml").toFile();
        String content = list.runGoal().content();

        assertThat(content)
                .contains("primary꞉lifecycle")
                .contains("| lifecycle |")
                .contains("local ✓")
                .contains("primary꞉legacy")
                .contains("legacy");
    }

    // ── ws:sibling-remove — the preflight ────────────────────────

    @Test
    void removeDraft_namesEverythingThatDiesWithTheTree() throws Exception {
        Path sibling = createSibling("risky");
        // Uncommitted work in one member, a stash in another; the fresh
        // sibling's version-qualification commits are unlanded by nature.
        Files.writeString(sibling.resolve("lib-a/dirty.txt"), "wip\n",
                StandardCharsets.UTF_8);
        Path libB = sibling.resolve("lib-b");
        Files.writeString(libB.resolve("README.md"), "stashed edit\n",
                StandardCharsets.UTF_8);
        exec(libB, "git", "stash");

        SiblingRemoveDraftMojo draft =
                TestLog.createMojo(SiblingRemoveDraftMojo.class);
        draft.manifest = primary.resolve("workspace.yaml").toFile();
        draft.feature = "risky";
        String content = draft.runGoal().content();

        assertThat(content)
                .contains("✗")
                .contains("uncommitted")
                .contains("stash")
                .contains("unlanded")
                .contains("-Dforce=true");
        assertThat(sibling).as("a draft deletes nothing").exists();
    }

    @Test
    void removePublish_refusesUnfinishedWork_andPersistsTheFindings() throws Exception {
        Path sibling = createSibling("unfinished");

        SiblingRemovePublishMojo publish =
                TestLog.createMojo(SiblingRemovePublishMojo.class);
        publish.manifest = primary.resolve("workspace.yaml").toFile();
        publish.feature = "unfinished";

        assertThatThrownBy(publish::execute)
                .isInstanceOf(WorkspaceReportException.class)
                .hasMessageContaining("dies with the tree")
                .hasMessageContaining("-Dforce=true");
        assertThat(sibling).as("a refused removal deletes nothing").exists();
    }

    @Test
    void removePublish_afterFinish_removesCleanly() throws Exception {
        Path sibling = createSibling("done");
        Path libA = sibling.resolve("lib-a");
        Files.writeString(libA.resolve("feature.txt"), "the work\n",
                StandardCharsets.UTF_8);
        exec(libA, "git", "add", "feature.txt");
        exec(libA, "git", "commit", "-m", "feat: work in the sibling");

        FeatureFinishSquashPublishMojo finish =
                TestLog.createMojo(FeatureFinishSquashPublishMojo.class);
        finish.targetBranch = "main";
        finish.push = true;
        finish.syncParent = true;
        finish.manifest = sibling.resolve("workspace.yaml").toFile();
        finish.feature = "done";
        finish.message = "feat: done";
        finish.execute();

        SiblingRemovePublishMojo publish =
                TestLog.createMojo(SiblingRemovePublishMojo.class);
        publish.manifest = primary.resolve("workspace.yaml").toFile();
        publish.feature = "done";
        String content;
        try {
            content = publish.runGoal().content();
        } catch (WorkspaceReportException e) {
            throw new AssertionError("removal refused after a finish; the "
                    + "preflight found:\n" + e.report().content(), e);
        }

        assertThat(sibling)
                .as("a finished sibling removes without force")
                .doesNotExist();
        assertThat(content).contains("Removed");
        assertThat(primary)
                .as("the primary is untouched by the removal")
                .exists();
    }

    @Test
    void removePublish_force_discardsDeliberately() throws Exception {
        Path sibling = createSibling("discard");
        Files.writeString(sibling.resolve("lib-a/dirty.txt"), "wip\n",
                StandardCharsets.UTF_8);

        SiblingRemovePublishMojo publish =
                TestLog.createMojo(SiblingRemovePublishMojo.class);
        publish.manifest = primary.resolve("workspace.yaml").toFile();
        publish.feature = "discard";
        publish.force = true;
        publish.execute();

        assertThat(sibling).doesNotExist();
    }

    @Test
    void remove_unknownTarget_pointsAtTheListing() {
        SiblingRemoveDraftMojo draft =
                TestLog.createMojo(SiblingRemoveDraftMojo.class);
        draft.manifest = primary.resolve("workspace.yaml").toFile();
        draft.feature = "nope";

        assertThatThrownBy(draft::runGoal)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("not a sibling")
                .hasMessageContaining("sibling-list");
    }

    @Test
    void remove_requiresExactlyOneTargetParameter() {
        SiblingRemoveDraftMojo draft =
                TestLog.createMojo(SiblingRemoveDraftMojo.class);
        draft.manifest = primary.resolve("workspace.yaml").toFile();

        assertThatThrownBy(draft::runGoal)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("exactly one");
    }

    // ── helpers ──────────────────────────────────────────────────

    private Path createSibling(String feature) throws Exception {
        FeatureStartSiblingPublishMojo start =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        start.manifest = primary.resolve("workspace.yaml").toFile();
        start.feature = feature;
        start.execute();
        Path sibling = tempDir.resolve("primary꞉" + feature);
        assertThat(sibling).exists();
        return sibling;
    }

    private static void exec(Path dir, String... command) throws Exception {
        List<String> full = new java.util.ArrayList<>(List.of(command));
        if (full.size() > 1 && (full.get(1).equals("commit")
                || full.get(1).equals("stash"))) {
            full.addAll(1, List.of("-c", "user.email=t@example.com",
                    "-c", "user.name=Test", "-c", "commit.gpgsign=false"));
        }
        Process p = new ProcessBuilder(full)
                .directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("command failed in " + dir + ": "
                    + String.join(" ", full) + "\n" + out);
        }
    }
}
