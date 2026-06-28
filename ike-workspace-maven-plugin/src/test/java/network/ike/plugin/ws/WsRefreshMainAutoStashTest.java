package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Goal-level auto-stash round-trip for {@code ws:refresh-main} (the #780 wave,
 * hardened in IKE-Network/ike-issues#781): a subproject carrying uncommitted
 * work must NOT block the refresh — its WIP is auto-stashed before main is
 * advanced and re-applied after. Exercises the real
 * {@link ParkSupport}/{@link AutoStashGuard} stash machinery against bare
 * upstreams (so the per-user {@code refs/ws-stash} push + restore run for real).
 */
class WsRefreshMainAutoStashTest {

    @TempDir
    Path tmp;

    @Test
    void dirtySubproject_isAutoStashedAndRestored() throws Exception {
        TestWorkspaceHelper helper = new TestWorkspaceHelper(tmp);
        Path primary = helper.buildSiblingScenario();
        // The per-user stash ref is keyed on git user.email — set an identity
        // in each clone so AutoStashGuard can resolve the slug.
        for (String c : new String[] {"lib-a", "lib-b", "app-c"}) {
            exec(primary.resolve(c), "git", "config", "user.email", "t@example.com");
            exec(primary.resolve(c), "git", "config", "user.name", "Test");
        }

        // Uncommitted WIP in lib-b — the normal resting state of a
        // Syncthing-bridged workspace.
        Path wip = primary.resolve("lib-b").resolve("wip.txt");
        Files.writeString(wip, "in flight", StandardCharsets.UTF_8);

        WsRefreshMainMojo mojo = TestLog.createMojo(WsRefreshMainMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.mainBranch = "main";
        mojo.remote = "origin";
        mojo.execute(); // must NOT throw despite lib-b's WIP

        // The WIP is restored after the refresh — the round-trip preserved it
        // byte-for-byte, not merely left a file behind.
        assertThat(wip).exists();
        assertThat(Files.readString(wip, StandardCharsets.UTF_8))
                .isEqualTo("in flight");
        // And the auto-stash was consumed (no local stash entry left dangling).
        assertThat(execCapture(primary.resolve("lib-b"), "git", "stash", "list"))
                .isEmpty();
    }

    private void exec(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) {
            throw new RuntimeException("failed: " + String.join(" ", cmd));
        }
    }

    private String execCapture(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        p.waitFor();
        return out;
    }
}
