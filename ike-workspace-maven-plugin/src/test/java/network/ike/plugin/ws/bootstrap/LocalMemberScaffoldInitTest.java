package network.ike.plugin.ws.bootstrap;

import network.ike.plugin.ws.TestLog;
import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkspaceGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A member created in place, with a {@code file:} repo URL and no remote yet
 * (IKE-Network/ike-issues#1370, decision 4), is reported as a local repository by
 * {@code ws:scaffold-init}'s clone-missing pass, never as a failed fetch.
 */
class LocalMemberScaffoldInitTest {

    @TempDir
    Path tempDir;

    @Test
    void member_without_a_remote_is_local_not_a_failed_fetch() throws Exception {
        Path member = Files.createDirectories(tempDir.resolve("local-kb"));
        Files.writeString(member.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <groupId>network.ike.data</groupId>
                    <artifactId>local-kb</artifactId>
                    <version>1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """, StandardCharsets.UTF_8);
        git(member, "init", "-q", "-b", "main");
        git(member, "add", "pom.xml");
        git(member, "-c", "user.name=Test", "-c", "user.email=test@example.org",
                "commit", "-q", "-m", "Genesis of local-kb");
        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.1"
                generated: 2026-11-11

                workspace-root:
                  groupId: network.ike.data
                  artifactId: test-ws
                  version: 1-SNAPSHOT

                defaults:
                  branch: main
                  maven-version: "4.0.0-rc-7"

                subprojects:
                  local-kb:
                    description: >
                      A member with no remote yet.
                    repo: %s
                    branch: main
                    version: "1-SNAPSHOT"
                    groupId: network.ike.data
                    depends-on: []
                """.formatted(member.toUri().toString()), StandardCharsets.UTF_8);

        Manifest manifest = ManifestReader.read(tempDir.resolve("workspace.yaml"));
        SubprojectInitializer.Result result = new SubprojectInitializer(new WorkspaceGraph(manifest),
                tempDir.toFile(), "test-ws", new TestLog(), false).run();

        assertThat(result.skipped()).isZero();
        assertThat(result.updated()).isZero();
        assertThat(result.cloned()).isZero();
        List<String> statuses = result.rows().stream()
                .filter(row -> "local-kb".equals(row[0]))
                .map(row -> row[1])
                .toList();
        assertThat(statuses).containsExactly("local");
    }

    private static void git(Path dir, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException(String.join(" ", command) + " failed: " + output);
        }
        assertThat(new File(dir.toFile(), ".git")).exists();
    }
}
