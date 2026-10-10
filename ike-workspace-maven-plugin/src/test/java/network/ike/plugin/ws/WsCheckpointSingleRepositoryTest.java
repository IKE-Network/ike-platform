package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the single-repository mode of {@code ws:checkpoint-draft} and
 * {@code ws:checkpoint-publish} (IKE-Network/ike-issues#1281).
 *
 * <p>A working set of one has no {@code workspace.yaml}: the repository is
 * both the sole member and the root, so it gets the root's treatment. The
 * checkpoint file is committed, the tag lands on that commit, and branch
 * and tag are pushed; the state recorded is the head before that commit.
 *
 * <p>The fixture is a repository cloned from a bare upstream, so the push
 * is real, with the nested Maven build of the verify gate replaced by a
 * controllable outcome as {@link WsCheckpointVerifyGateTest} does.
 */
class WsCheckpointSingleRepositoryTest {

    @TempDir
    Path tempDir;

    private static final String CHECKPOINT = "single-test";
    private static final String TAG = "checkpoint/" + CHECKPOINT;

    private String originalUserDir;

    @BeforeEach
    void rememberUserDir() {
        originalUserDir = System.getProperty("user.dir");
    }

    @AfterEach
    void restoreUserDir() {
        System.setProperty("user.dir", originalUserDir);
    }

    /**
     * A draft touches nothing: no commit, no tag, no file, and the report
     * names the tag it would create and the repository it would tag.
     */
    @Test
    void draft_changesNothing_andReportsThePlan() throws Exception {
        Path repo = scaffoldRepository();
        String headBefore = execCapture(repo, "git", "rev-parse", "HEAD").trim();

        WsCheckpointDraftMojo mojo = new WsCheckpointDraftMojo();
        configure(mojo, repo);

        WorkspaceReportSpec spec = mojo.runGoal();

        assertThat(execCapture(repo, "git", "rev-parse", "HEAD").trim())
                .as("a draft makes no commit")
                .isEqualTo(headBefore);
        assertThat(allTags(repo)).as("a draft creates no tag").isEmpty();
        assertThat(repo.resolve("checkpoints")).doesNotExist();

        assertThat(spec.goal()).isEqualTo(WsGoal.CHECKPOINT_DRAFT);
        String report = spec.content();
        assertThat(report)
                .contains("Repository `" + repo.getFileName() + "` checkpointed at `")
                .contains("(draft)")
                .contains("Tag `" + TAG + "` would be created.")
                .doesNotContain("workspace.yaml");
    }

    /**
     * A publish commits the checkpoint file, tags that commit, pushes both,
     * and records the head before the commit as the checkpointed state.
     */
    @Test
    void publish_commitsTagsAndPushes_recordingTheHeadBefore() throws Exception {
        Path repo = scaffoldRepository();
        Path upstream = tempDir.resolve(".upstreams").resolve("upstream.git");
        String headBefore = execCapture(repo, "git", "rev-parse", "HEAD").trim();

        GateControlledMojo mojo = new GateControlledMojo();
        configure(mojo, repo);

        WorkspaceReportSpec spec = mojo.runGoal();

        Path file = repo.resolve("checkpoints")
                .resolve("checkpoint-" + CHECKPOINT + ".yaml");
        assertThat(file).exists();
        String yaml = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(yaml)
                .contains("name: \"" + CHECKPOINT + "\"")
                .contains("schema-version: \""
                        + WsCheckpointDraftMojo.SINGLE_REPOSITORY_SCHEMA + "\"")
                .contains("    " + repo.getFileName() + ":")
                .contains("sha: \"" + headBefore + "\"")
                .contains("branch: \"main\"")
                .contains("version: \"1.0.0-SNAPSHOT\"");

        String head = execCapture(repo, "git", "rev-parse", "HEAD").trim();
        assertThat(head).as("the checkpoint commit is a new commit")
                .isNotEqualTo(headBefore);
        assertThat(execCapture(repo, "git", "log", "-1", "--format=%s").trim())
                .isEqualTo("checkpoint: " + CHECKPOINT);
        assertThat(execCapture(repo, "git", "rev-parse", "HEAD~1").trim())
                .as("the checkpoint commit sits on the recorded state")
                .isEqualTo(headBefore);
        assertThat(execCapture(repo, "git", "tag", "--points-at", "HEAD"))
                .as("the tag lands on the checkpoint commit")
                .contains(TAG);
        assertThat(execCapture(repo, "git", "status", "--porcelain"))
                .as("the tree is clean afterwards")
                .isBlank();

        assertThat(execCapture(upstream, "git", "rev-parse", "refs/heads/main").trim())
                .as("the branch is pushed")
                .isEqualTo(head);
        assertThat(execCapture(upstream, "git", "tag", "--list"))
                .as("the tag is pushed")
                .contains(TAG);

        assertThat(spec.goal()).isEqualTo(WsGoal.CHECKPOINT_PUBLISH);
        assertThat(spec.content())
                .contains("Tag `" + TAG + "` pushed to `origin`.")
                .doesNotContain("workspace.yaml");
    }

    /** A publish refuses uncommitted modifications and tags nothing. */
    @Test
    void publish_refusesUncommittedModifications() throws Exception {
        Path repo = scaffoldRepository();
        Files.writeString(repo.resolve("README.md"), "edited\n",
                StandardCharsets.UTF_8);

        GateControlledMojo mojo = new GateControlledMojo();
        configure(mojo, repo);

        assertThatThrownBy(mojo::runGoal)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("uncommitted modifications");
        assertThat(allTags(repo)).isEmpty();
        assertThat(repo.resolve("checkpoints")).doesNotExist();
    }

    /** A publish refuses a detached head and tags nothing. */
    @Test
    void publish_refusesDetachedHead() throws Exception {
        Path repo = scaffoldRepository();
        exec(repo, "git", "checkout", "--detach", "HEAD");

        GateControlledMojo mojo = new GateControlledMojo();
        configure(mojo, repo);

        assertThatThrownBy(mojo::runGoal)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("detached HEAD");
        assertThat(allTags(repo)).isEmpty();
    }

    /** A failed verify gate aborts the publish before anything is tagged. */
    @Test
    void publish_failedVerify_tagsNothing() throws Exception {
        Path repo = scaffoldRepository();
        String headBefore = execCapture(repo, "git", "rev-parse", "HEAD").trim();

        GateControlledMojo mojo = new GateControlledMojo();
        configure(mojo, repo);
        mojo.verifyOutcome = () -> {
            throw new MojoException("simulated build failure");
        };

        assertThatThrownBy(mojo::runGoal)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("simulated build failure");
        assertThat(allTags(repo)).isEmpty();
        assertThat(execCapture(repo, "git", "rev-parse", "HEAD").trim())
                .isEqualTo(headBefore);
        assertThat(repo.resolve("checkpoints")).doesNotExist();
    }

    /** A second publish of the same name is a clean no-op. */
    @Test
    void publish_twice_isIdempotent() throws Exception {
        Path repo = scaffoldRepository();

        GateControlledMojo first = new GateControlledMojo();
        configure(first, repo);
        first.runGoal();
        String headAfterFirst = execCapture(repo, "git", "rev-parse", "HEAD").trim();

        GateControlledMojo second = new GateControlledMojo();
        configure(second, repo);
        WorkspaceReportSpec spec = second.runGoal();
        assertThat(spec.content()).contains("already exists");

        assertThat(execCapture(repo, "git", "rev-parse", "HEAD").trim())
                .isEqualTo(headAfterFirst);
        assertThat(allTags(repo).stream().filter(t -> t.equals(TAG)).count())
                .isEqualTo(1);
    }

    /** Without an {@code origin} the checkpoint is cut locally and says so. */
    @Test
    void publish_withoutOrigin_tagsLocally() throws Exception {
        Path repo = scaffoldRepository();
        exec(repo, "git", "remote", "remove", "origin");

        GateControlledMojo mojo = new GateControlledMojo();
        configure(mojo, repo);

        WorkspaceReportSpec spec = mojo.runGoal();
        assertThat(allTags(repo)).contains(TAG);
        assertThat(spec.content())
                .contains("created locally (no `origin` remote — not pushed)");
    }

    // ── Fixture ──────────────────────────────────────────────────────

    /**
     * A single repository on {@code main} with a POM and a README, cloned
     * from a bare upstream so {@code origin} is real, hermetic git config,
     * clean tree. No {@code workspace.yaml} anywhere above it.
     */
    private Path scaffoldRepository() throws Exception {
        Path upstreams = tempDir.resolve(".upstreams");
        Path noHooks = Files.createDirectories(tempDir.resolve(".nohooks"));

        Path bare = upstreams.resolve("upstream.git");
        Files.createDirectories(bare);
        exec(bare, "git", "init", "--bare");

        Path seed = upstreams.resolve("seed");
        Files.createDirectories(seed);
        Files.writeString(seed.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>local.test</groupId>
                    <artifactId>single-repo</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """, StandardCharsets.UTF_8);
        Files.writeString(seed.resolve("README.md"), "single repository\n",
                StandardCharsets.UTF_8);
        Files.writeString(seed.resolve(".gitignore"), """
                .ike/vcs-state
                ws꞉*.md
                """, StandardCharsets.UTF_8);
        exec(seed, "git", "init", "-b", "main");
        hermetic(seed, noHooks);
        exec(seed, "git", "add", ".");
        exec(seed, "git", "commit", "-m", "Initial commit");
        exec(seed, "git", "remote", "add", "origin",
                bare.toAbsolutePath().toString());
        exec(seed, "git", "push", "-u", "origin", "main");

        exec(tempDir, "git", "clone", bare.toAbsolutePath().toString(),
                "single-repo");
        Path repo = tempDir.resolve("single-repo");
        hermetic(repo, noHooks);
        return repo;
    }

    /**
     * Point the mojo at the repository: no manifest, the working set resolves
     * from the user directory, which the goal reads from {@code user.dir}.
     * The milestone lookup is disabled so nothing reaches GitHub.
     */
    private void configure(WsCheckpointDraftMojo mojo, Path repo) throws Exception {
        TestLog.injectInto(mojo);
        System.setProperty("user.dir", repo.toAbsolutePath().toString());
        setField(mojo, "name", CHECKPOINT);
        setField(mojo, "issueRepo", "");
    }

    /**
     * {@link WsCheckpointPublishMojo} with the verify gate's nested Maven
     * build replaced by a per-test outcome, as in the gate test.
     */
    private static class GateControlledMojo extends WsCheckpointPublishMojo {
        Runnable verifyOutcome = () -> { };

        @Override
        protected void verifyReactor() throws MojoException {
            verifyOutcome.run();
        }
    }

    private static List<String> allTags(Path repo) {
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

    private static void hermetic(Path dir, Path noHooks) throws Exception {
        exec(dir, "git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec(dir, "git", "config", "commit.gpgsign", "false");
        exec(dir, "git", "config", "tag.gpgsign", "false");
        exec(dir, "git", "config", "user.email", "test@example.com");
        exec(dir, "git", "config", "user.name", "Test");
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
