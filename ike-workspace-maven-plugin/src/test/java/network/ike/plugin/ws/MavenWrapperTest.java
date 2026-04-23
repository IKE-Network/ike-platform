package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MavenWrapper} — the shared generator used by
 * {@code ws:init} scaffolding and the {@code mvnw-standard} upgrade step.
 *
 * <p>Covers the three idempotency cases the upgrade step depends on:
 * empty workspace (all three files written), partial state (only
 * missing files written), fully-present (no-op). Also verifies that
 * an existing properties file with a pinned {@code maven.version} is
 * preserved — the upgrade step reads the pin to avoid overwriting a
 * user's deliberate version choice.
 */
class MavenWrapperTest {

    @Test
    void writeMissingFiles_createsAllThreeWhenAbsent(@TempDir Path tmp) throws IOException {
        int written = MavenWrapper.writeMissingFiles(tmp, "4.0.0-rc-5");

        assertThat(written).isEqualTo(3);
        assertThat(tmp.resolve("mvnw")).exists();
        assertThat(tmp.resolve("mvnw.cmd")).exists();
        assertThat(tmp.resolve(".mvn/wrapper/maven-wrapper.properties")).exists();

        // Properties file carries the requested version
        String props = Files.readString(
                tmp.resolve(".mvn/wrapper/maven-wrapper.properties"),
                StandardCharsets.UTF_8);
        assertThat(props)
                .contains("maven.version=4.0.0-rc-5")
                .contains("distributionUrl=https://repo.maven.apache.org/")
                .contains("apache-maven-4.0.0-rc-5-bin.zip");

        // mvnw script references the properties file at runtime
        String mvnw = Files.readString(tmp.resolve("mvnw"), StandardCharsets.UTF_8);
        assertThat(mvnw)
                .startsWith("#!/bin/sh")
                .contains(".mvn/wrapper/maven-wrapper.properties");

        // mvnw.cmd is a Windows batch file
        String mvnwCmd = Files.readString(tmp.resolve("mvnw.cmd"), StandardCharsets.UTF_8);
        assertThat(mvnwCmd)
                .contains("@echo off")
                .contains(".mvn\\wrapper\\maven-wrapper.properties");
    }

    @Test
    void writeMissingFiles_noOpWhenAllThreePresent(@TempDir Path tmp) throws IOException {
        // Prime the workspace with pre-existing files whose content
        // differs from what the generator would emit.
        Files.createDirectories(tmp.resolve(".mvn/wrapper"));
        Files.writeString(tmp.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "maven.version=3.9.9\ndistributionUrl=custom\n",
                StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("mvnw"), "#!/bin/sh\n# user's custom launcher\n",
                StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("mvnw.cmd"), "@REM user's custom launcher\n",
                StandardCharsets.UTF_8);

        int written = MavenWrapper.writeMissingFiles(tmp, "4.0.0-rc-5");

        assertThat(written).isZero();

        // Nothing was overwritten — the user's 3.9.9 pin and custom
        // launcher content are preserved.
        assertThat(Files.readString(tmp.resolve(".mvn/wrapper/maven-wrapper.properties"),
                StandardCharsets.UTF_8))
                .contains("maven.version=3.9.9")
                .doesNotContain("4.0.0-rc-5");
        assertThat(Files.readString(tmp.resolve("mvnw"), StandardCharsets.UTF_8))
                .contains("user's custom launcher");
        assertThat(Files.readString(tmp.resolve("mvnw.cmd"), StandardCharsets.UTF_8))
                .contains("user's custom launcher");
    }

    @Test
    void writeMissingFiles_fillsOnlyMissingOnes(@TempDir Path tmp) throws IOException {
        // Partial state: properties file exists with a pin, but the
        // launcher scripts are missing. This is the case the upgrade
        // step cares about most — a user who set up a partial workspace
        // and lost the launchers (or never committed them).
        Files.createDirectories(tmp.resolve(".mvn/wrapper"));
        Files.writeString(tmp.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "maven.version=3.9.9\n",
                StandardCharsets.UTF_8);

        int written = MavenWrapper.writeMissingFiles(tmp, "4.0.0-rc-5");

        assertThat(written).isEqualTo(2);
        assertThat(tmp.resolve("mvnw")).exists();
        assertThat(tmp.resolve("mvnw.cmd")).exists();

        // The existing properties file must NOT be overwritten — the
        // user's 3.9.9 pin is preserved.
        assertThat(Files.readString(tmp.resolve(".mvn/wrapper/maven-wrapper.properties"),
                StandardCharsets.UTF_8))
                .contains("maven.version=3.9.9")
                .doesNotContain("4.0.0-rc-5");
    }

    @Test
    void readPinnedVersion_returnsNullWhenPropertiesAbsent(@TempDir Path tmp) throws IOException {
        assertThat(MavenWrapper.readPinnedVersion(tmp)).isNull();
    }

    @Test
    void readPinnedVersion_returnsMavenVersionWhenPresent(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve(".mvn/wrapper"));
        Files.writeString(tmp.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "maven.version=3.9.11\ndistributionUrl=x\n",
                StandardCharsets.UTF_8);

        assertThat(MavenWrapper.readPinnedVersion(tmp)).isEqualTo("3.9.11");
    }

    @Test
    void readPinnedVersion_returnsNullWhenPropertyMissing(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve(".mvn/wrapper"));
        Files.writeString(tmp.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=x\n",
                StandardCharsets.UTF_8);

        assertThat(MavenWrapper.readPinnedVersion(tmp)).isNull();
    }
}
