package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.WorkspaceStateAssert;
import network.ike.plugin.ws.vcs.WorkspaceStateAssert.SubState;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the pre-checkpoint reactor verify gate in {@code
 * ws:checkpoint-publish} (IKE-Network/ike-issues#689).
 *
 * <p>Before #689 the goal aligned, committed, and then tagged every
 * subproject without ever building the reactor — so a checkpoint whose
 * pinned subprojects no longer compile together was cut anyway and the
 * skew only surfaced across CI cycles. The fix adds {@link
 * WsCheckpointPublishMojo#verifyReactor()} between the alignment commit
 * and the superclass tagging step: a failed build aborts the goal and no
 * tag is created.
 *
 * <p>These tests build a publishable workspace — a git root with an
 * {@code origin} bare upstream (so the superclass's workspace tag push
 * succeeds) and two on-disk subprojects declared in {@code workspace.yaml}
 * (so the superclass actually cuts {@code checkpoint/*} tags on each) — and
 * drive the gate via a {@link GateControlledMojo} that overrides {@code
 * autoAlign()} to a no-op (no nested align build) and {@code
 * verifyReactor()} to the outcome under test (no nested Maven build). They
 * assert on the {@code checkpoint/<name>} tags landing — or not — across
 * the subprojects and the workspace repo via {@link WorkspaceStateAssert}.
 */
class WsCheckpointVerifyGateTest {

    @TempDir
    Path tempDir;

    private static final String CHECKPOINT = "gate-test";
    private static final String WS_TAG = "checkpoint/" + CHECKPOINT;
    private static final List<String> SUBPROJECTS = List.of("lib-a", "lib-b");

    /**
     * Gate refuses and nothing is tagged: when {@code verifyReactor()}
     * throws (a simulated reactor compile failure), the goal aborts
     * carrying that cause and no {@code checkpoint/*} tag exists in any
     * subproject or in the workspace repo.
     */
    @Test
    void verifyFailure_abortsGoal_andLeavesNoCheckpointTags() throws Exception {
        Path root = scaffoldPublishableWorkspace();

        GateControlledMojo mojo = newMojo(root);
        mojo.verifyOutcome = () -> {
            throw new MojoException("simulated reactor compile failure");
        };

        assertThatThrownBy(mojo::execute)
                .as("a failed reactor build must abort the checkpoint")
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("simulated reactor compile failure");

        assertNoCheckpointTags(root);
    }

    /**
     * Happy path: when {@code verifyReactor()} succeeds (no-op), the gate
     * lets the goal through and the superclass cuts the {@code
     * checkpoint/<name>} tag in every subproject and in the workspace repo.
     */
    @Test
    void verifySuccess_letsCheckpointThrough_andTagsLand() throws Exception {
        Path root = scaffoldPublishableWorkspace();

        GateControlledMojo mojo = newMojo(root);
        mojo.verifyOutcome = () -> { /* reactor builds — gate passes */ };

        assertThatCode(mojo::execute)
                .as("a passing reactor build must let the checkpoint proceed")
                .doesNotThrowAnyException();

        assertCheckpointTagsPresent(root);
    }

    /**
     * {@code skipVerify} bypass: with the <em>real</em> {@code
     * verifyReactor()} (only {@code autoAlign()} is no-oped) and {@code
     * verifyGoals} pointed at a non-existent goal that <em>would</em> fail
     * the reactor build if it ran, {@code -Dws.checkpoint.skipVerify=true}
     * short-circuits before spawning Maven. The proof is that the goal
     * completes and tags land: had the real verify reached the subprocess
     * it would have run the bogus goal and the wrapped build failure would
     * have aborted the checkpoint.
     */
    @Test
    void skipVerify_bypassesBuild_andStillTags() throws Exception {
        Path root = scaffoldPublishableWorkspace();

        // GateControlledMojo no-ops autoAlign() but leaves the REAL
        // verifyReactor() in place (verifyOutcome is unused here).
        GateControlledMojo mojo = new GateControlledMojo() {
            @Override
            protected void verifyReactor() throws MojoException {
                // Defer to the production gate so skipVerify is what
                // prevents the (bogus) build — not the test override.
                superVerifyReactor();
            }
        };
        TestLog.injectInto(mojo);
        setField(mojo, "manifest", root.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        setField(mojo, "name", CHECKPOINT);
        setField(mojo, "issueRepo", "");
        setField(mojo, "skipVerify", true);
        // A goal that would fail the reactor build if it were ever spawned.
        setField(mojo, "verifyGoals", "this-goal-does-not-exist:boom");

        assertThatCode(mojo::execute)
                .as("skipVerify must bypass the build and let the checkpoint "
                        + "tag (a spawned bogus goal would have aborted it)")
                .doesNotThrowAnyException();

        assertCheckpointTagsPresent(root);
    }

    // ── Assertions ───────────────────────────────────────────────────

    private void assertNoCheckpointTags(Path root) {
        Map<String, SubState> states =
                WorkspaceStateAssert.snapshot(root, SUBPROJECTS);
        for (SubState state : states.values()) {
            WorkspaceStateAssert.assertNoTagAtHead(state, WS_TAG);
        }
        for (String sub : SUBPROJECTS) {
            assertThat(allTags(root.resolve(sub)))
                    .as("no checkpoint tag may exist in subproject '%s' "
                            + "after a refused gate", sub)
                    .noneMatch(t -> t.startsWith("checkpoint/"));
        }
        assertThat(allTags(root))
                .as("no checkpoint tag may exist in the workspace repo "
                        + "after a refused gate")
                .noneMatch(t -> t.startsWith("checkpoint/"));
    }

    private void assertCheckpointTagsPresent(Path root) {
        Map<String, SubState> states =
                WorkspaceStateAssert.snapshot(root, SUBPROJECTS);
        for (SubState state : states.values()) {
            assertThat(state.tagsAtHead())
                    .as("subproject '%s' must carry the checkpoint tag at HEAD",
                            state.name())
                    .contains(WS_TAG);
        }
        assertThat(allTags(root))
                .as("workspace repo must carry the checkpoint tag")
                .contains(WS_TAG);
    }

    // ── Fixture ──────────────────────────────────────────────────────

    /**
     * Build a publishable workspace: a git root (cloned from a bare
     * upstream so it has an {@code origin} for the superclass's tag push)
     * with two on-disk subproject git repos declared in {@code
     * workspace.yaml}, all trees clean. Returns the workspace root.
     */
    private Path scaffoldPublishableWorkspace() throws Exception {
        Path upstreams = tempDir.resolve(".upstreams");
        Path noHooks = Files.createDirectories(tempDir.resolve(".nohooks"));

        // Bare upstream for the workspace root, so super.runGoal() can push
        // the checkpoint tag + branch to origin.
        Path bareRoot = upstreams.resolve("upstream-ws.git");
        Files.createDirectories(bareRoot);
        exec(bareRoot, "git", "init", "--bare");

        Path seed = upstreams.resolve("seed-ws");
        Files.createDirectories(seed);
        Files.writeString(seed.resolve("workspace.yaml"), workspaceYaml(),
                StandardCharsets.UTF_8);
        Files.writeString(seed.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>local.test</groupId>
                    <artifactId>verify-gate-ws</artifactId>
                    <version>1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """, StandardCharsets.UTF_8);
        // Mirror a real workspace aggregator: the subproject working
        // copies are independent git repos, excluded from the workspace's
        // own git so the root tree stays clean (otherwise the clone would
        // see lib-a/ and lib-b/ as untracked and the WORKING_TREE_CLEAN
        // preflight would trip before the gate ever runs).
        Files.writeString(seed.resolve(".gitignore"), """
                .ike/vcs-state
                /lib-a/
                /lib-b/
                """, StandardCharsets.UTF_8);
        exec(seed, "git", "init", "-b", "main");
        hermetic(seed, noHooks);
        exec(seed, "git", "add", ".");
        exec(seed, "git", "commit", "-m", "Initial workspace root");
        exec(seed, "git", "remote", "add", "origin",
                bareRoot.toAbsolutePath().toString());
        exec(seed, "git", "push", "-u", "origin", "main");

        // Clone the root so it has a working origin remote.
        exec(tempDir, "git", "clone",
                bareRoot.toAbsolutePath().toString(), "workspace");
        Path root = tempDir.resolve("workspace");
        hermetic(root, noHooks);

        // On-disk subprojects (no upstream needed — the superclass only
        // tags them locally; it pushes only the workspace repo).
        createSubproject(root.resolve("lib-a"), "1.0.0-SNAPSHOT", noHooks);
        createSubproject(root.resolve("lib-b"), "2.0.0-SNAPSHOT", noHooks);

        return root;
    }

    private void createSubproject(Path dir, String version, Path noHooks)
            throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>local.test</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                </project>
                """.formatted(dir.getFileName().toString(), version),
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(".gitignore"),
                ".ike/vcs-state\n", StandardCharsets.UTF_8);
        exec(dir, "git", "init", "-b", "main");
        hermetic(dir, noHooks);
        exec(dir, "git", "add", ".");
        exec(dir, "git", "commit", "-m", "Initial commit");
    }

    private static String workspaceYaml() {
        return """
                schema-version: "1.1"
                generated: "2026-06-15"

                defaults:
                  branch: main

                subprojects:
                  lib-a:
                    type: software
                    description: Shared library A
                    repo: https://example.com/lib-a.git
                    branch: main
                    version: "1.0.0-SNAPSHOT"
                  lib-b:
                    type: software
                    description: Library B (depends on A)
                    repo: https://example.com/lib-b.git
                    branch: main
                    version: "2.0.0-SNAPSHOT"
                    depends-on:
                      - subproject: lib-a
                        relationship: build
                """;
    }

    // ── Test mojo + helpers ──────────────────────────────────────────

    private GateControlledMojo newMojo(Path root) throws Exception {
        GateControlledMojo mojo = new GateControlledMojo();
        TestLog.injectInto(mojo);
        setField(mojo, "manifest", root.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        setField(mojo, "name", CHECKPOINT);
        // Disable milestone/testing-context snapshot — it would hit GitHub.
        setField(mojo, "issueRepo", "");
        return mojo;
    }

    /**
     * Test stand-in for {@link WsCheckpointPublishMojo} that suppresses the
     * nested {@code ws:align-publish} build ({@code autoAlign()} no-op) and
     * routes {@code verifyReactor()} to a per-test {@link #verifyOutcome}, so
     * the #689 gate's pass/fail outcome is controlled without spawning Maven.
     * The {@code skipVerify} bypass test instead subclasses this with the
     * real {@code verifyReactor()}.
     */
    private static class GateControlledMojo extends WsCheckpointPublishMojo {
        /** What the verify gate should do; defaults to "build passes". */
        Runnable verifyOutcome = () -> { };

        @Override
        protected void autoAlign() {
            // No nested align build — the tree is already clean and aligned.
        }

        @Override
        protected void verifyReactor() throws MojoException {
            verifyOutcome.run();
        }

        /**
         * Bridge to the production {@link
         * WsCheckpointPublishMojo#verifyReactor()} so a nested anonymous
         * subclass (the skipVerify test) can exercise the real gate logic
         * — including its {@code skipVerify} early-return — rather than the
         * {@link #verifyReactor()} stub above.
         *
         * @throws MojoException if the real gate fails the build
         */
        final void superVerifyReactor() throws MojoException {
            super.verifyReactor();
        }
    }

    private static java.util.List<String> allTags(Path repo) {
        String raw = execCapture(repo, "git", "tag", "--list");
        return raw.lines().map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private static void exec(Path workDir, String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException("Command failed (exit " + code + "): "
                    + String.join(" ", command)
                    + (out.isBlank() ? "" : "\n" + out));
        }
    }

    private static String execCapture(Path workDir, String... command) {
        try {
            Process p = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code != 0) {
                throw new RuntimeException("Command failed (exit " + code
                        + "): " + String.join(" ", command) + "\n" + out);
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void hermetic(Path dir, Path noHooks) throws Exception {
        exec(dir, "git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec(dir, "git", "config", "commit.gpgsign", "false");
        exec(dir, "git", "config", "tag.gpgsign", "false");
        exec(dir, "git", "config", "user.email", "test@example.com");
        exec(dir, "git", "config", "user.name", "Test");
    }

    private static void setField(Object target, String fieldName, Object value,
                                 Class<?> declaringClass) throws Exception {
        Field f = declaringClass.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void setField(Object target, String fieldName, Object value)
            throws Exception {
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
