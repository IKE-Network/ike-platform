package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import org.apache.maven.api.plugin.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link VcsOperations#checkoutTracking}, the primitive behind
 * {@code ws:feature-track-publish}'s adoption of a branch that exists only on
 * the remote.
 *
 * <p>The distinction this pins down is the whole reason the method exists:
 * {@link VcsOperations#checkoutNew} branches from the current {@code HEAD},
 * which would produce a local branch sharing the remote branch's <em>name</em>
 * but none of its <em>commits</em> — a silent no-op that looks like a
 * successful adoption until the feature's changes turn out to be missing.
 */
class CheckoutTrackingTest {

    @TempDir
    Path tempDir;

    private Path origin;
    private Path clone;
    private String featureSha;

    private static final Log LOG = new TestLog();

    @BeforeEach
    void setUp() throws Exception {
        // A bare "remote" with main plus a feature branch carrying a commit
        // that main does not have.
        origin = tempDir.resolve("origin.git");
        Files.createDirectories(origin);
        git(origin, "init", "--bare", "-b", "main");

        Path seed = tempDir.resolve("seed");
        Files.createDirectories(seed);
        git(seed, "init", "-b", "main");
        hermetic(seed);
        Files.writeString(seed.resolve("README.md"), "base\n",
                StandardCharsets.UTF_8);
        git(seed, "add", ".");
        git(seed, "commit", "-m", "base");
        git(seed, "remote", "add", "origin", origin.toAbsolutePath().toString());
        git(seed, "push", "-u", "origin", "main");

        git(seed, "checkout", "-b", "feature/grpc_plugin");
        Files.writeString(seed.resolve("provider.txt"), "grpc\n",
                StandardCharsets.UTF_8);
        git(seed, "add", ".");
        git(seed, "commit", "-m", "add grpc provider");
        git(seed, "push", "-u", "origin", "feature/grpc_plugin");
        featureSha = capture(seed, "rev-parse", "HEAD").trim();

        // A fresh clone that has never checked the feature branch out.
        clone = tempDir.resolve("clone");
        git(tempDir, "clone", origin.toAbsolutePath().toString(),
                clone.getFileName().toString());
        hermetic(clone);
    }

    @Test
    void freshClone_hasNoLocalFeatureBranchButRemoteTrackingExists() {
        File cloneDir = clone.toFile();

        assertThat(VcsOperations.localBranchExists(cloneDir,
                "feature/grpc_plugin")).isFalse();
        assertThat(VcsOperations.remoteTrackingExists(cloneDir, "origin",
                "feature/grpc_plugin")).isTrue();
    }

    @Test
    void checkoutTracking_landsOnRemoteTipNotLocalHead() throws Exception {
        VcsOperations.checkoutTracking(clone.toFile(), LOG, "origin",
                "feature/grpc_plugin");

        assertThat(VcsOperations.currentBranch(clone.toFile()))
                .isEqualTo("feature/grpc_plugin");
        // The decisive assertion: the adopted branch carries the remote's
        // commit, so the feature's content is actually present. Compared via
        // git directly because VcsOperations.headSha abbreviates.
        assertThat(capture(clone, "rev-parse", "HEAD").trim())
                .isEqualTo(featureSha);
        assertThat(clone.resolve("provider.txt")).exists();
    }

    @Test
    void checkoutTracking_setsUpstreamSoPushNeedsNoArguments() throws Exception {
        VcsOperations.checkoutTracking(clone.toFile(), LOG, "origin",
                "feature/grpc_plugin");

        String upstream = capture(clone, "rev-parse", "--abbrev-ref",
                "--symbolic-full-name", "@{u}").trim();

        assertThat(upstream).isEqualTo("origin/feature/grpc_plugin");
    }

    @Test
    void checkoutNew_wouldHaveBranchedFromHead_documentingWhyTrackingIsNeeded()
            throws Exception {
        // Contrast case: the wrong primitive produces a same-named branch
        // that is missing the feature commit entirely.
        String mainSha = capture(clone, "rev-parse", "HEAD").trim();

        VcsOperations.checkoutNew(clone.toFile(), LOG, "feature/grpc_plugin");

        assertThat(capture(clone, "rev-parse", "HEAD").trim())
                .isEqualTo(mainSha)
                .isNotEqualTo(featureSha);
        assertThat(clone.resolve("provider.txt")).doesNotExist();
    }

    // ── fixtures ─────────────────────────────────────────────────────

    private static void hermetic(Path dir) throws Exception {
        git(dir, "config", "user.email", "test@example.com");
        git(dir, "config", "user.name", "Test");
        git(dir, "config", "commit.gpgsign", "false");
    }

    private static void git(Path workDir, String... args) throws Exception {
        capture(workDir, args);
    }

    private static String capture(Path workDir, String... args)
            throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process p = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", args) + " failed (" + exit + "): "
                    + output);
        }
        return output;
    }
}
