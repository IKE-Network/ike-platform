package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Coverage for the post-create reactor verification added to
 * {@code ws:feature-start-sibling-publish} (the {@code -Dverify} flag) and the
 * dedicated {@code ws:feature-start-sibling-publish-verify} goal
 * (IKE-Network/ike-issues#777).
 *
 * <p>The actual reactor build is stubbed by overriding {@link
 * FeatureStartSiblingPublishMojo#verifySibling(File)} — the same seam
 * {@code ws:checkpoint-publish} exposes for its #689 gate — so the tests assert
 * <em>whether</em> the build is invoked and what the report says, without
 * running a nested Maven build. Bare mode (a single repo cloned from a bare
 * upstream) is used, mirroring {@link FeatureStartSiblingPublishBareModeTest}.
 */
class FeatureStartSiblingPublishVerifyTest {

    @TempDir
    Path tempDir;

    private String originalUserDir;
    private Path repo;

    @BeforeEach
    void setUp() throws Exception {
        originalUserDir = System.getProperty("user.dir");

        Path bare = tempDir.resolve(".upstreams/app.git");
        Files.createDirectories(bare);
        exec(bare, "git", "init", "--bare");

        Path work = tempDir.resolve(".upstreams/work");
        Files.createDirectories(work);
        Files.writeString(work.resolve("pom.xml"), pom("1.0.0-SNAPSHOT"),
                StandardCharsets.UTF_8);
        exec(work, "git", "init", "-b", "main");
        exec(work, "git", "add", ".");
        exec(work, "git", "commit", "-m", "init");
        exec(work, "git", "remote", "add", "origin",
                bare.toAbsolutePath().toString());
        exec(work, "git", "push", "-u", "origin", "main");

        exec(tempDir, "git", "clone", bare.toUri().toString(), "app");
        repo = tempDir.resolve("app");
        System.setProperty("user.dir", repo.toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    // ── The two goals' defaults (pure, no clone) ─────────────────────

    @Test
    void publishGoal_doesNotVerifyByDefault_andReportsAsPublish() {
        FeatureStartSiblingPublishMojo base = new FeatureStartSiblingPublishMojo();
        assertThat(base.verifyByDefault()).isFalse();
        assertThat(base.shouldVerify()).isFalse();
        assertThat(base.reportGoal())
                .isEqualTo(WsGoal.FEATURE_START_SIBLING_PUBLISH);
    }

    @Test
    void verifyGoal_verifiesByDefault_andReportsToItsOwnFile() {
        FeatureStartSiblingPublishVerifyMojo verify =
                new FeatureStartSiblingPublishVerifyMojo();
        assertThat(verify.verifyByDefault()).isTrue();
        assertThat(verify.shouldVerify()).isTrue();
        assertThat(verify.reportGoal())
                .isEqualTo(WsGoal.FEATURE_START_SIBLING_PUBLISH_VERIFY);
    }

    // ── Whether the build runs ───────────────────────────────────────

    @Test
    void withoutVerifyFlag_doesNotBuild_andReportEmitsBootstrapStep()
            throws Exception {
        AtomicBoolean built = new AtomicBoolean(false);
        FeatureStartSiblingPublishMojo mojo = recordingMojo(built);
        mojo.feature = "noverify";

        WorkspaceReportSpec spec = mojo.runGoal();

        assertThat(built).isFalse();
        assertThat(spec.goal()).isEqualTo(WsGoal.FEATURE_START_SIBLING_PUBLISH);
        // Deliverable #1: the bootstrap full-reactor build is the first step.
        assertThat(spec.content())
                .contains("./mvnw clean install -DskipTests")
                .contains("build the whole reactor first")
                .doesNotContain("**Verified**");
    }

    @Test
    void withVerifyFlag_buildsAfterCreation_andReportSaysVerified()
            throws Exception {
        AtomicBoolean built = new AtomicBoolean(false);
        FeatureStartSiblingPublishMojo mojo = recordingMojo(built);
        mojo.feature = "withverify";
        mojo.verify = true;
        mojo.verifyGoals = "clean install -DskipTests -T 1C";

        WorkspaceReportSpec spec = mojo.runGoal();

        assertThat(built).isTrue();
        assertThat(spec.content()).contains("**Verified**");
    }

    @Test
    void verifyGoal_buildsByDefault_withoutTheFlag() throws Exception {
        AtomicBoolean built = new AtomicBoolean(false);
        FeatureStartSiblingPublishVerifyMojo mojo =
                new FeatureStartSiblingPublishVerifyMojo() {
                    @Override
                    protected void verifySibling(File siblingRoot) {
                        // Verification runs only after the clone exists.
                        assertThat(siblingRoot).isDirectory();
                        built.set(true);
                    }
                };
        TestLog.injectInto(mojo);
        mojo.feature = "vdefault";
        mojo.verifyGoals = "clean install -DskipTests -T 1C";

        WorkspaceReportSpec spec = mojo.runGoal();

        assertThat(built).isTrue();
        assertThat(spec.goal())
                .isEqualTo(WsGoal.FEATURE_START_SIBLING_PUBLISH_VERIFY);
    }

    @Test
    void buildFailure_propagates_leavingTheCloneIntact() throws Exception {
        // The real verifySibling rethrows a build failure; a stub that throws
        // stands in for a non-building reactor. The sibling must already exist.
        FeatureStartSiblingPublishMojo mojo =
                new FeatureStartSiblingPublishMojo() {
                    @Override
                    protected void verifySibling(File siblingRoot) {
                        throw new MojoException("reactor build failed");
                    }
                };
        TestLog.injectInto(mojo);
        mojo.feature = "broken";
        mojo.verify = true;

        assertThatThrownBy(mojo::runGoal)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("reactor build failed");
        assertThat(tempDir.resolve("app꞉broken").resolve(".git")).isDirectory();
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private FeatureStartSiblingPublishMojo recordingMojo(AtomicBoolean built) {
        FeatureStartSiblingPublishMojo mojo = new FeatureStartSiblingPublishMojo() {
            @Override
            protected void verifySibling(File siblingRoot) {
                assertThat(siblingRoot).isDirectory();
                built.set(true);
            }
        };
        TestLog.injectInto(mojo);
        return mojo;
    }

    private static String pom(String version) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>app</artifactId>
                    <version>%s</version>
                </project>
                """.formatted(version);
    }

    private void exec(Path workDir, String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command) + "\n" + out);
        }
    }
}
