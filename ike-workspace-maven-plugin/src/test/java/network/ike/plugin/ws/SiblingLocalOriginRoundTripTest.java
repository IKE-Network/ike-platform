package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The local-origin sibling round trip (IKE-Network/ike-issues#992):
 * create a sibling that chains to its local parent, do work in it,
 * finish — and the parent absorbs the landing by fast-forward while the
 * shared upstream stays untouched, because a finish no longer pushes.
 *
 * <p>The fixture's bare upstreams are the control: after a full round
 * trip their {@code main} must still be at the original commit. Under
 * the pre-#992 model the finish pushed straight there.
 */
class SiblingLocalOriginRoundTripTest {

    private static final List<String> COMPONENTS =
            List.of("lib-a", "lib-b", "app-c");

    @TempDir
    Path tempDir;

    private Path primary;

    @BeforeEach
    void setUp() throws Exception {
        primary = new TestWorkspaceHelper(tempDir).buildSiblingScenario();
        // A real workspace root ignores its member directories, the ws:
        // goal receipts, and local .ike state; this fixture's upstream
        // carries no .gitignore, so without one every member reads as an
        // uncommitted change and any finish refuses before it starts.
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

    @Test
    void siblingFinish_landsInParent_andNeverPushesUpstream() throws Exception {
        String upstreamBefore = upstreamMainSha("lib-a");
        String parentBefore = sha(primary.resolve("lib-a"), "main");

        // 1. Create the sibling — clones from the primary's local members.
        FeatureStartSiblingPublishMojo start =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        start.manifest = primary.resolve("workspace.yaml").toFile();
        start.feature = "round-trip";
        start.execute();

        Path sibling = tempDir.resolve("primary꞉round-trip");
        assertThat(SiblingFinish.localParent(sibling.toFile()))
                .as("the sibling resolves its local parent")
                .contains(primary.toFile());

        // 2. Do work in the sibling: a commit on the feature branch in
        //    one member (the version-qualification commits are already
        //    there from creation).
        Path libA = sibling.resolve("lib-a");
        Files.writeString(libA.resolve("feature.txt"), "sibling work\n",
                StandardCharsets.UTF_8);
        exec(libA, "git", "add", "feature.txt");
        exec(libA, "git", "commit", "-m", "feat: work in the sibling");
        String siblingWork = sha(libA, "feature/round-trip");

        // 3. Finish in the sibling.
        FeatureFinishSquashPublishMojo finish =
                TestLog.createMojo(FeatureFinishSquashPublishMojo.class);
        finish.targetBranch = "main";
        finish.push = true;
        finish.syncParent = true;
        finish.manifest = sibling.resolve("workspace.yaml").toFile();
        finish.feature = "round-trip";
        finish.message = "feat: round trip";
        finish.execute();

        // 4. The parent absorbed the landing: its main moved, and carries
        //    the sibling's work.
        String parentAfter = sha(primary.resolve("lib-a"), "main");
        assertThat(parentAfter)
                .as("parent main fast-forwarded by the finish (#992)")
                .isNotEqualTo(parentBefore);
        assertThat(capture(primary.resolve("lib-a"),
                "git", "show", "--stat", "--oneline", "main"))
                .as("the parent's main carries the sibling's file")
                .contains("feature.txt");
        assertThat(capture(primary.resolve("lib-a"), "git", "status", "--porcelain"))
                .as("the parent's working tree is left clean")
                .isEmpty();

        // 5. Nothing was pushed: the shared upstream is untouched — the
        //    drift #992 was filed to end. Externalization is the parent's
        //    own explicit ws:push.
        assertThat(upstreamMainSha("lib-a"))
                .as("finish must not push to the shared upstream (#992)")
                .isEqualTo(upstreamBefore);

        // 6. The sibling's work is genuinely in the landing, not lost.
        assertThat(siblingWork).isNotEqualTo(parentAfter);   // squashed
        assertThat(capture(primary.resolve("lib-a"), "git", "log", "--oneline", "main"))
                .contains("round trip");
    }

    /**
     * A parent that moved on while the sibling worked is absorbed, not
     * fought: because {@code origin} IS the parent, the finish's existing
     * refresh step baselines the sibling on the parent's current
     * {@code main} before landing, so the parent's own commit is already
     * in the landing and the fast-forward is legitimate. This is the
     * stale-{@code origin/main} drift class disappearing — under the
     * remote-origin model the parent's local commit was invisible to the
     * sibling until someone pushed it (IKE-Network/ike-issues#992).
     */
    @Test
    void siblingFinish_absorbsParentsOwnCommits_originMainIsFreshByDefinition()
            throws Exception {
        FeatureStartSiblingPublishMojo start =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        start.manifest = primary.resolve("workspace.yaml").toFile();
        start.feature = "diverge";
        start.execute();
        Path sibling = tempDir.resolve("primary꞉diverge");

        // Work in the sibling.
        Path libA = sibling.resolve("lib-a");
        Files.writeString(libA.resolve("s.txt"), "s\n", StandardCharsets.UTF_8);
        exec(libA, "git", "add", "s.txt");
        exec(libA, "git", "commit", "-m", "feat: sibling side");

        // The parent's main moves independently — divergence, not a FF.
        Path parentLibA = primary.resolve("lib-a");
        Files.writeString(parentLibA.resolve("p.txt"), "p\n", StandardCharsets.UTF_8);
        exec(parentLibA, "git", "add", "p.txt");
        exec(parentLibA, "git", "commit", "-m", "chore: parent side");
        String parentSha = sha(parentLibA, "main");

        FeatureFinishSquashPublishMojo finish =
                TestLog.createMojo(FeatureFinishSquashPublishMojo.class);
        finish.targetBranch = "main";
        finish.push = true;
        finish.syncParent = true;
        finish.manifest = sibling.resolve("workspace.yaml").toFile();
        finish.feature = "diverge";
        finish.message = "feat: diverge";
        finish.execute();

        // The parent advanced past its own commit, and its own work
        // survived — the landing was built on top of it, not over it.
        String parentAfter = sha(parentLibA, "main");
        assertThat(parentAfter).isNotEqualTo(parentSha);
        assertThat(capture(parentLibA, "git", "log", "--oneline", "main"))
                .as("the parent's independent commit is still in its history")
                .contains("parent side");
        assertThat(Files.exists(parentLibA.resolve("p.txt")))
                .as("the parent's own file is untouched by the landing")
                .isTrue();
        assertThat(capture(parentLibA, "git", "status", "--porcelain"))
                .as("the parent's working tree is left clean")
                .isEmpty();
    }

    /**
     * The safety property in isolation: when the parent genuinely holds a
     * commit the landing does not contain (the race the finish's refresh
     * step cannot close — the parent moved after that refresh), the
     * absorb refuses that member instead of forcing it, and says so. The
     * local-origin analogue of the {@code #858} no-stranded-landing
     * contract, with the parent playing the remote's role.
     */
    @Test
    void absorbIntoParent_refusesNonFastForward_leavingParentUntouched()
            throws Exception {
        FeatureStartSiblingPublishMojo start =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        start.manifest = primary.resolve("workspace.yaml").toFile();
        start.feature = "race";
        start.execute();
        Path sibling = tempDir.resolve("primary꞉race");

        // The sibling lands something on its own main...
        Path libA = sibling.resolve("lib-a");
        exec(libA, "git", "checkout", "main");
        Files.writeString(libA.resolve("s.txt"), "s\n", StandardCharsets.UTF_8);
        exec(libA, "git", "add", "s.txt");
        exec(libA, "git", "commit", "-m", "feat: landed in the sibling");

        // ...while the parent moves independently afterwards.
        Path parentLibA = primary.resolve("lib-a");
        Files.writeString(parentLibA.resolve("p.txt"), "p\n", StandardCharsets.UTF_8);
        exec(parentLibA, "git", "add", "p.txt");
        exec(parentLibA, "git", "commit", "-m", "chore: parent raced ahead");
        String parentSha = sha(parentLibA, "main");

        List<SiblingFinish.Absorbed> results = SiblingFinish.absorbIntoParent(
                sibling.toFile(), primary.toFile(), List.of("lib-a"),
                "main", new TestLog());

        SiblingFinish.Absorbed libAResult = results.stream()
                .filter(r -> r.member().equals("lib-a")).findFirst().orElseThrow();
        assertThat(libAResult.ok())
                .as("a non-fast-forward member is refused, never forced")
                .isFalse();
        assertThat(libAResult.detail()).contains("diverged");
        assertThat(sha(parentLibA, "main"))
                .as("the raced-ahead parent is left exactly as it was")
                .isEqualTo(parentSha);

        // And the failure message tells the operator where to reconcile.
        assertThat(SiblingFinish.absorbFailureMessage(
                List.of(libAResult), "main", primary.toFile()))
                .contains("lib-a")
                .contains("No feature branch was deleted")
                .contains(primary.toAbsolutePath().toString());
    }

    // ── helpers ──────────────────────────────────────────────────

    private String upstreamMainSha(String component) throws Exception {
        Path bare = tempDir.resolve(".upstreams/upstream-" + component + ".git");
        return capture(bare, "git", "rev-parse", "main");
    }

    private static String sha(Path repo, String rev) throws Exception {
        return capture(repo, "git", "rev-parse", rev);
    }

    private static String capture(Path dir, String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        p.waitFor();
        return out;
    }

    private static void exec(Path dir, String... command) throws Exception {
        List<String> full = new java.util.ArrayList<>(List.of(command));
        if (full.size() > 1 && full.get(1).equals("commit")) {
            full.addAll(1, List.of("-c", "user.email=t@example.com",
                    "-c", "user.name=Test", "-c", "commit.gpgsign=false"));
        }
        Process p = new ProcessBuilder(full)
                .directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException(
                    String.join(" ", full) + " failed in " + dir + ": " + out);
        }
    }
}
