package network.ike.plugin.ws;

import org.apache.maven.api.plugin.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the post-merge qualifier guard
 * (ike-issues#292).
 *
 * <p>The guard previously used a {@code content.contains("-<qualifier>-")}
 * check that flagged any artifactId, groupId, or property whose name
 * happened to embed the qualifier substring. The fix narrows the check
 * to actual Maven version coordinates of the form
 * {@code <digits>(.<digits>)*-<qualifier>-SNAPSHOT}.
 */
class PostMergeQualifierGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void clean_pom_with_qualifier_substring_in_artifactid_passes() throws Exception {
        // Repro from ike-issues#292: artifactId 'reasoner-hybrid' must
        // NOT be flagged when merging feature/reasoner.
        File dir = scaffoldSubproject();
        Files.writeString(dir.toPath().resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <groupId>dev.ikm.hybrid-reasoner</groupId>
                    <artifactId>reasoner-hybrid</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """, StandardCharsets.UTF_8);

        FeatureFinishSupport.verifyAndFixQualifiers(
                dir, "feature/reasoner", new TestLog());

        assertThat(Files.readString(dir.toPath().resolve("pom.xml"),
                StandardCharsets.UTF_8))
                .contains("<artifactId>reasoner-hybrid</artifactId>")
                .contains("<groupId>dev.ikm.hybrid-reasoner</groupId>");
    }

    @Test
    void clean_pom_with_qualifier_in_property_name_passes() throws Exception {
        // ${reasoner-test-data.groupid} is a legitimate property name.
        File dir = scaffoldSubproject();
        Files.writeString(dir.toPath().resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <properties>
                        <reasoner-test-data.groupid>dev.test</reasoner-test-data.groupid>
                    </properties>
                </project>
                """, StandardCharsets.UTF_8);

        FeatureFinishSupport.verifyAndFixQualifiers(
                dir, "feature/reasoner", new TestLog());

        assertThat(Files.readString(dir.toPath().resolve("pom.xml"),
                StandardCharsets.UTF_8))
                .contains("<reasoner-test-data.groupid>");
    }

    @Test
    void real_qualified_version_is_stripped() throws Exception {
        // Genuine residual qualifier in <version> — guard SHOULD fix it.
        File dir = scaffoldSubproject();
        Files.writeString(dir.toPath().resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0.0-reasoner-SNAPSHOT</version>
                </project>
                """, StandardCharsets.UTF_8);

        FeatureFinishSupport.verifyAndFixQualifiers(
                dir, "feature/reasoner", new TestLog());

        // The qualifier should have been stripped.
        String after = Files.readString(dir.toPath().resolve("pom.xml"),
                StandardCharsets.UTF_8);
        assertThat(after).doesNotContain("1.0.0-reasoner-SNAPSHOT");
        assertThat(after).contains("1.0.0-SNAPSHOT");
    }

    /**
     * Set up a directory shaped like a subproject the guard can scan.
     * Initialize git so the guard's auto-fix commit machinery has a
     * repo to write to (the guard early-exits before commit when no
     * contamination is found, so the clean-pom tests don't need
     * commits, but the strip test does).
     */
    private File scaffoldSubproject() throws Exception {
        File dir = tempDir.toFile();
        runQuiet(dir, "git", "init", "-q", "-b", "main");
        runQuiet(dir, "git", "config", "user.email", "test@example.com");
        runQuiet(dir, "git", "config", "user.name", "Test");
        return dir;
    }

    private static void runQuiet(File dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir)
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor();
    }
}
