package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WsScaffoldDraftMojo#isPomPackaging}, the predicate that
 * selects which subprojects {@code ws:scaffold-publish} pre-installs so the
 * downstream standalone scaffold delegations can resolve a branch-qualified
 * BOM (IKE-Network/ike-issues#700). Only {@code <packaging>pom</packaging>}
 * artifacts — BOMs, parent POMs, aggregators — qualify.
 */
class WsScaffoldBomPackagingTest {

    private static File writePom(Path dir, String body) throws IOException {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, body, StandardCharsets.UTF_8);
        return pom.toFile();
    }

    @Test
    void trueForPomPackaging(@TempDir Path tmp) throws IOException {
        File pom = writePom(tmp, """
                <project>
                    <artifactId>komet-bom</artifactId>
                    <packaging>pom</packaging>
                </project>
                """);
        assertThat(WsScaffoldDraftMojo.isPomPackaging(pom)).isTrue();
    }

    @Test
    void trueWithSurroundingWhitespace(@TempDir Path tmp) throws IOException {
        File pom = writePom(tmp, """
                <project>
                    <packaging>
                        pom
                    </packaging>
                </project>
                """);
        assertThat(WsScaffoldDraftMojo.isPomPackaging(pom)).isTrue();
    }

    @Test
    void falseForDefaultJarPackaging(@TempDir Path tmp) throws IOException {
        // No <packaging> element → defaults to jar.
        File pom = writePom(tmp, """
                <project>
                    <artifactId>komet-core</artifactId>
                </project>
                """);
        assertThat(WsScaffoldDraftMojo.isPomPackaging(pom)).isFalse();
    }

    @Test
    void falseForExplicitJarPackaging(@TempDir Path tmp) throws IOException {
        File pom = writePom(tmp, """
                <project>
                    <packaging>jar</packaging>
                </project>
                """);
        assertThat(WsScaffoldDraftMojo.isPomPackaging(pom)).isFalse();
    }

    @Test
    void falseWhenPomAbsent(@TempDir Path tmp) {
        assertThat(WsScaffoldDraftMojo.isPomPackaging(
                new File(tmp.toFile(), "pom.xml"))).isFalse();
    }
}
