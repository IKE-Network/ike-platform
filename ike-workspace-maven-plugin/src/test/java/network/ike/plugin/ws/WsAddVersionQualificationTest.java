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
 * are no-ops, and the parent version is never touched. Qualification
 * cascades through a multi-module tree — children's in-tree
 * {@code <parent><version>} refs and redundant own versions move with the
 * root, while external parents and literal-coincidence dependency versions
 * stay untouched (IKE-Network/ike-issues#1051).
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
                dir, "1-SNAPSHOT", "feature/claude-assistant", new TestLog());

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
        String recorded = WsAddMojo.qualifyForBranch(
                dir, "1-SNAPSHOT", "main", new TestLog());
        assertThat(recorded).isEqualTo("1-SNAPSHOT");
        assertThat(Files.readString(dir.resolve("pom.xml")))
                .contains("<version>1-SNAPSHOT</version>");
    }

    @Test
    void idempotent_when_already_qualified(@TempDir Path tmp)
            throws IOException {
        Path dir = withPom(tmp, "1-claude-assistant-SNAPSHOT");
        String recorded = WsAddMojo.qualifyForBranch(
                dir, "1-claude-assistant-SNAPSHOT", "feature/claude-assistant",
                new TestLog());
        assertThat(recorded).isEqualTo("1-claude-assistant-SNAPSHOT");
    }

    @Test
    void null_version_returned_unchanged(@TempDir Path tmp) throws IOException {
        assertThat(WsAddMojo.qualifyForBranch(
                tmp, null, "feature/x", new TestLog())).isNull();
    }

    // ── #1051: cascade through a multi-module tree ───────────────

    /** ikm-reasoner-shaped fixture: external parent, two aggregator levels. */
    private static void writeMultiModuleTree(Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>dev.ikm.build</groupId>
                        <artifactId>java-parent</artifactId>
                        <version>1.64.0</version>
                    </parent>
                    <groupId>dev.ikm</groupId>
                    <artifactId>reasoner-root</artifactId>
                    <version>0.40.1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>m1</module>
                        <module>nested</module>
                    </modules>
                </project>
                """, StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("m1"));
        // m1: in-tree parent ref, no own version, plus a DECOY dependency
        // whose version literally coincides with the root's — GA matching
        // must leave it alone.
        Files.writeString(root.resolve("m1/pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>dev.ikm</groupId>
                        <artifactId>reasoner-root</artifactId>
                        <version>0.40.1-SNAPSHOT</version>
                    </parent>
                    <artifactId>reasoner-m1</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>external-lib</artifactId>
                            <version>0.40.1-SNAPSHOT</version>
                        </dependency>
                    </dependencies>
                </project>
                """, StandardCharsets.UTF_8);
        // nested: second aggregator level, inherits groupId from root,
        // redundantly declares its own version at the old value.
        Files.createDirectories(root.resolve("nested/deep"));
        Files.writeString(root.resolve("nested/pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>dev.ikm</groupId>
                        <artifactId>reasoner-root</artifactId>
                        <version>0.40.1-SNAPSHOT</version>
                    </parent>
                    <artifactId>reasoner-nested</artifactId>
                    <version>0.40.1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>deep</module>
                    </modules>
                </project>
                """, StandardCharsets.UTF_8);
        // deep: parent is the NESTED aggregator, whose groupId is
        // inherited — exercises the groupId-fallback in GA matching.
        Files.writeString(root.resolve("nested/deep/pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>dev.ikm</groupId>
                        <artifactId>reasoner-nested</artifactId>
                        <version>0.40.1-SNAPSHOT</version>
                    </parent>
                    <artifactId>reasoner-deep</artifactId>
                </project>
                """, StandardCharsets.UTF_8);
    }

    @Test
    void cascades_into_children_of_multi_module_subproject(@TempDir Path tmp)
            throws IOException {
        writeMultiModuleTree(tmp);

        String recorded = WsAddMojo.qualifyForBranch(
                tmp, "0.40.1-SNAPSHOT", "feature/incremental-reasoner",
                new TestLog());

        assertThat(recorded).isEqualTo("0.40.1-incremental-reasoner-SNAPSHOT");

        String root = Files.readString(tmp.resolve("pom.xml"));
        assertThat(root)
                .as("root own version qualified, external parent untouched")
                .contains("<version>0.40.1-incremental-reasoner-SNAPSHOT</version>")
                .contains("<version>1.64.0</version>")
                .doesNotContain("<version>0.40.1-SNAPSHOT</version>");

        String m1 = Files.readString(tmp.resolve("m1/pom.xml"));
        assertThat(m1)
                .as("m1 parent ref qualified (#1051)")
                .contains("<version>0.40.1-incremental-reasoner-SNAPSHOT</version>");
        assertThat(m1.split("<version>0.40.1-SNAPSHOT</version>", -1).length - 1)
                .as("exactly the decoy dependency keeps the literal old "
                        + "version — the parent ref moved, the dependency "
                        + "(GA-mismatched) did not")
                .isEqualTo(1);

        String nested = Files.readString(tmp.resolve("nested/pom.xml"));
        assertThat(nested)
                .as("nested parent ref AND redundant own version qualified")
                .doesNotContain("<version>0.40.1-SNAPSHOT</version>");

        String deep = Files.readString(tmp.resolve("nested/deep/pom.xml"));
        assertThat(deep)
                .as("deep parent ref (inherited-groupId aggregator) qualified")
                .contains("<version>0.40.1-incremental-reasoner-SNAPSHOT</version>")
                .doesNotContain("<version>0.40.1-SNAPSHOT</version>");
    }
}
