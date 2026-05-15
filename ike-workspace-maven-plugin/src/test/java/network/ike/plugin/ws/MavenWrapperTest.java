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
 * {@code ws:scaffold-init} scaffolding and the {@code mvnw} reconciler step.
 *
 * <p>Covers the standard {@code only-script} wrapper output, the
 * idempotency cases the reconciler depends on (empty, partial,
 * fully-present), legacy custom-wrapper detection, and full replacement
 * via {@link MavenWrapper#writeAll}.
 */
class MavenWrapperTest {

    private static final String VERSION = "4.0.0-rc-5";

    @Test
    void writeMissingFiles_createsStandardWrapperWhenAbsent(@TempDir Path tmp) throws IOException {
        int written = MavenWrapper.writeMissingFiles(tmp, VERSION);

        assertThat(written).isEqualTo(3);
        assertThat(tmp.resolve("mvnw")).exists();
        assertThat(tmp.resolve("mvnw.cmd")).exists();
        assertThat(tmp.resolve(".mvn/wrapper/maven-wrapper.properties")).exists();

        // only-script: no maven-wrapper.jar binary is committed
        assertThat(tmp.resolve(".mvn/wrapper/maven-wrapper.jar")).doesNotExist();

        // Properties file uses the standard keys IDEs key on
        String props = Files.readString(
                tmp.resolve(".mvn/wrapper/maven-wrapper.properties"),
                StandardCharsets.UTF_8);
        assertThat(props)
                .contains("wrapperVersion=" + MavenWrapper.WRAPPER_VERSION)
                .contains("distributionType=only-script")
                .contains("distributionUrl=https://repo.maven.apache.org/")
                .contains("apache-maven-" + VERSION + "-bin.zip");

        // mvnw is the standard Apache launcher, executable
        String mvnw = Files.readString(tmp.resolve("mvnw"), StandardCharsets.UTF_8);
        assertThat(mvnw)
                .startsWith("#!/bin/sh")
                .contains("Apache Maven Wrapper")
                .contains(".mvn/wrapper/maven-wrapper.properties")
                .doesNotContain("minimal bootstrap");
        assertThat(Files.isExecutable(tmp.resolve("mvnw"))).isTrue();

        // mvnw.cmd is the standard Apache Windows launcher
        String mvnwCmd = Files.readString(tmp.resolve("mvnw.cmd"), StandardCharsets.UTF_8);
        assertThat(mvnwCmd)
                .contains("Apache Maven Wrapper")
                .contains(".mvn/wrapper/maven-wrapper.properties");
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

        int written = MavenWrapper.writeMissingFiles(tmp, VERSION);

        assertThat(written).isZero();

        // Nothing was overwritten — the user's pin and custom launcher
        // content are preserved.
        assertThat(Files.readString(tmp.resolve(".mvn/wrapper/maven-wrapper.properties"),
                StandardCharsets.UTF_8))
                .contains("maven.version=3.9.9")
                .doesNotContain(VERSION);
        assertThat(Files.readString(tmp.resolve("mvnw"), StandardCharsets.UTF_8))
                .contains("user's custom launcher");
        assertThat(Files.readString(tmp.resolve("mvnw.cmd"), StandardCharsets.UTF_8))
                .contains("user's custom launcher");
    }

    @Test
    void writeMissingFiles_fillsOnlyMissingOnes(@TempDir Path tmp) throws IOException {
        // Partial state: properties file exists, launchers missing.
        Files.createDirectories(tmp.resolve(".mvn/wrapper"));
        Files.writeString(tmp.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "maven.version=3.9.9\n",
                StandardCharsets.UTF_8);

        int written = MavenWrapper.writeMissingFiles(tmp, VERSION);

        assertThat(written).isEqualTo(2);
        assertThat(tmp.resolve("mvnw")).exists();
        assertThat(tmp.resolve("mvnw.cmd")).exists();

        // The existing properties file must NOT be overwritten.
        assertThat(Files.readString(tmp.resolve(".mvn/wrapper/maven-wrapper.properties"),
                StandardCharsets.UTF_8))
                .contains("maven.version=3.9.9")
                .doesNotContain(VERSION);
    }

    @Test
    void writeAll_overwritesLegacyWrapperWithStandard(@TempDir Path tmp) throws IOException {
        // Prime a legacy custom wrapper.
        Files.createDirectories(tmp.resolve(".mvn/wrapper"));
        Files.writeString(tmp.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "maven.version=3.9.9\ndistributionUrl=x\n", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("mvnw"),
                "#!/bin/sh\n# This is a minimal bootstrap.\n", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("mvnw.cmd"),
                "@REM minimal bootstrap\n", StandardCharsets.UTF_8);

        MavenWrapper.writeAll(tmp, VERSION);

        // Legacy content fully replaced by the standard wrapper.
        assertThat(Files.readString(tmp.resolve("mvnw"), StandardCharsets.UTF_8))
                .contains("Apache Maven Wrapper")
                .doesNotContain("minimal bootstrap");
        assertThat(Files.readString(tmp.resolve("mvnw.cmd"), StandardCharsets.UTF_8))
                .contains("Apache Maven Wrapper")
                .doesNotContain("minimal bootstrap");
        assertThat(Files.readString(tmp.resolve(".mvn/wrapper/maven-wrapper.properties"),
                StandardCharsets.UTF_8))
                .contains("distributionType=only-script")
                .contains("apache-maven-" + VERSION + "-bin.zip")
                .doesNotContain("maven.version=");
    }

    @Test
    void isLegacyWrapper_trueForCustomBootstrap(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("mvnw"),
                "#!/bin/sh\n# This is a minimal bootstrap.\n", StandardCharsets.UTF_8);
        assertThat(MavenWrapper.isLegacyWrapper(tmp)).isTrue();
    }

    @Test
    void isLegacyWrapper_falseForStandardWrapper(@TempDir Path tmp) throws IOException {
        MavenWrapper.writeMissingFiles(tmp, VERSION);
        assertThat(MavenWrapper.isLegacyWrapper(tmp)).isFalse();
    }

    @Test
    void isLegacyWrapper_falseWhenAbsent(@TempDir Path tmp) throws IOException {
        assertThat(MavenWrapper.isLegacyWrapper(tmp)).isFalse();
    }

    @Test
    void readPinnedVersion_returnsNullWhenPropertiesAbsent(@TempDir Path tmp) throws IOException {
        assertThat(MavenWrapper.readPinnedVersion(tmp)).isNull();
    }

    @Test
    void readPinnedVersion_parsesStandardDistributionUrl(@TempDir Path tmp) throws IOException {
        MavenWrapper.writeMissingFiles(tmp, "3.9.11");
        assertThat(MavenWrapper.readPinnedVersion(tmp)).isEqualTo("3.9.11");
    }

    @Test
    void readPinnedVersion_fallsBackToLegacyMavenVersionKey(@TempDir Path tmp) throws IOException {
        // Legacy properties: no parseable distributionUrl, explicit key.
        Files.createDirectories(tmp.resolve(".mvn/wrapper"));
        Files.writeString(tmp.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "maven.version=3.9.11\ndistributionUrl=x\n",
                StandardCharsets.UTF_8);

        assertThat(MavenWrapper.readPinnedVersion(tmp)).isEqualTo("3.9.11");
    }

    @Test
    void readPinnedVersion_returnsNullWhenVersionUndeterminable(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve(".mvn/wrapper"));
        Files.writeString(tmp.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=x\n",
                StandardCharsets.UTF_8);

        assertThat(MavenWrapper.readPinnedVersion(tmp)).isNull();
    }
}
