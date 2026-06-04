package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WsAddMojo#qualifyForBranch} (IKE-Network/ike-issues#574):
 * a subproject added on a feature branch is recorded at a branch-qualified
 * version with its POM rewritten; {@code main} and already-qualified inputs
 * are no-ops, and the parent version is never touched.
 */
class WsAddVersionQualificationTest {

    private static final String POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>102</version>
                </parent>
                <artifactId>komet-claude-plugin</artifactId>
                <version>1-SNAPSHOT</version>
            </project>
            """;

    private static Path withPom(Path tmp, String version) throws IOException {
        Files.writeString(tmp.resolve("pom.xml"),
                POM.replace("<version>1-SNAPSHOT</version>",
                        "<version>" + version + "</version>"),
                StandardCharsets.UTF_8);
        return tmp;
    }

    @Test
    void qualifies_and_rewrites_on_feature_branch(@TempDir Path tmp)
            throws IOException {
        Path dir = withPom(tmp, "1-SNAPSHOT");

        String recorded = WsAddMojo.qualifyForBranch(
                dir, "1-SNAPSHOT", "feature/claude-assistant");

        assertThat(recorded).isEqualTo("1-claude-assistant-SNAPSHOT");
        String pom = Files.readString(dir.resolve("pom.xml"));
        assertThat(pom)
                .contains("<version>1-claude-assistant-SNAPSHOT</version>")
                .contains("<version>102</version>");   // parent untouched
        assertThat(pom.split("<version>1-SNAPSHOT</version>", -1).length - 1)
                .as("project version rewritten away").isZero();
    }

    @Test
    void noop_on_main(@TempDir Path tmp) throws IOException {
        Path dir = withPom(tmp, "1-SNAPSHOT");
        String recorded = WsAddMojo.qualifyForBranch(dir, "1-SNAPSHOT", "main");
        assertThat(recorded).isEqualTo("1-SNAPSHOT");
        assertThat(Files.readString(dir.resolve("pom.xml")))
                .contains("<version>1-SNAPSHOT</version>");
    }

    @Test
    void idempotent_when_already_qualified(@TempDir Path tmp)
            throws IOException {
        Path dir = withPom(tmp, "1-claude-assistant-SNAPSHOT");
        String recorded = WsAddMojo.qualifyForBranch(
                dir, "1-claude-assistant-SNAPSHOT", "feature/claude-assistant");
        assertThat(recorded).isEqualTo("1-claude-assistant-SNAPSHOT");
    }

    @Test
    void null_version_returned_unchanged(@TempDir Path tmp) throws IOException {
        assertThat(WsAddMojo.qualifyForBranch(tmp, null, "feature/x")).isNull();
    }
}
