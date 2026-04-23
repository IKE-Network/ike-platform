package network.ike.plugin.ws;

import network.ike.plugin.ws.PomSiteScanner.PomSiteSurvey;
import network.ike.plugin.ws.ReactorWalker.ReactorScan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReactorWalkerTest {

    @Test
    void walk_singlePomNoSubprojects(@TempDir Path tmp) throws IOException {
        Path root = writePom(tmp, "a", """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>x</groupId>
                    <artifactId>a</artifactId>
                    <version>1</version>
                </project>
                """);

        ReactorScan scan = ReactorWalker.walk(root);

        assertThat(scan.surveys())
                .extracting(PomSiteSurvey::pomPath)
                .containsExactly(root.toAbsolutePath().normalize());
    }

    @Test
    void walk_modulesDescendedInOrder(@TempDir Path tmp) throws IOException {
        Path rootDir = tmp.resolve("root");
        Files.createDirectories(rootDir);
        Path root = rootDir.resolve("pom.xml");
        Files.writeString(root, """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>x</groupId>
                    <artifactId>root</artifactId>
                    <version>1</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>alpha</module>
                        <module>beta</module>
                    </modules>
                </project>
                """, StandardCharsets.UTF_8);
        Path alpha = writePom(rootDir.resolve("alpha"), "alpha", """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent><groupId>x</groupId><artifactId>root</artifactId><version>1</version></parent>
                    <artifactId>alpha</artifactId>
                </project>
                """);
        Path beta = writePom(rootDir.resolve("beta"), "beta", """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent><groupId>x</groupId><artifactId>root</artifactId><version>1</version></parent>
                    <artifactId>beta</artifactId>
                </project>
                """);

        ReactorScan scan = ReactorWalker.walk(root);

        assertThat(scan.surveys())
                .extracting(PomSiteSurvey::pomPath)
                .containsExactly(
                        root.toAbsolutePath().normalize(),
                        alpha.toAbsolutePath().normalize(),
                        beta.toAbsolutePath().normalize());
    }

    @Test
    void walk_subprojectsElement_usedOverModules(@TempDir Path tmp) throws IOException {
        Path rootDir = tmp.resolve("root");
        Files.createDirectories(rootDir);
        Path root = rootDir.resolve("pom.xml");
        Files.writeString(root, """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <groupId>x</groupId>
                    <artifactId>root</artifactId>
                    <version>1</version>
                    <packaging>pom</packaging>
                    <subprojects>
                        <subproject>child</subproject>
                    </subprojects>
                </project>
                """, StandardCharsets.UTF_8);
        Path child = writePom(rootDir.resolve("child"), "child", """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <parent><groupId>x</groupId><artifactId>root</artifactId><version>1</version></parent>
                    <artifactId>child</artifactId>
                </project>
                """);

        ReactorScan scan = ReactorWalker.walk(root);

        assertThat(scan.surveys())
                .extracting(PomSiteSurvey::pomPath)
                .contains(child.toAbsolutePath().normalize());
    }

    @Test
    void walk_nestedModulesDepthFirst(@TempDir Path tmp) throws IOException {
        Path rootDir = tmp.resolve("root");
        Files.createDirectories(rootDir);
        Path root = rootDir.resolve("pom.xml");
        Files.writeString(root, """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>x</groupId><artifactId>root</artifactId><version>1</version>
                    <packaging>pom</packaging>
                    <modules><module>a</module><module>b</module></modules>
                </project>
                """, StandardCharsets.UTF_8);
        Path aDir = rootDir.resolve("a");
        Files.createDirectories(aDir);
        Path a = aDir.resolve("pom.xml");
        Files.writeString(a, """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent><groupId>x</groupId><artifactId>root</artifactId><version>1</version></parent>
                    <artifactId>a</artifactId><packaging>pom</packaging>
                    <modules><module>a1</module></modules>
                </project>
                """, StandardCharsets.UTF_8);
        Path a1 = writePom(aDir.resolve("a1"), "a1", """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent><groupId>x</groupId><artifactId>a</artifactId><version>1</version></parent>
                    <artifactId>a1</artifactId>
                </project>
                """);
        Path b = writePom(rootDir.resolve("b"), "b", """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent><groupId>x</groupId><artifactId>root</artifactId><version>1</version></parent>
                    <artifactId>b</artifactId>
                </project>
                """);

        ReactorScan scan = ReactorWalker.walk(root);

        assertThat(scan.surveys())
                .extracting(PomSiteSurvey::pomPath)
                .containsExactly(
                        root.toAbsolutePath().normalize(),
                        a.toAbsolutePath().normalize(),
                        a1.toAbsolutePath().normalize(),
                        b.toAbsolutePath().normalize());
    }

    @Test
    void walkAll_concatenatesRoots_dedupsSharedPoms(@TempDir Path tmp) throws IOException {
        Path r1Dir = tmp.resolve("r1");
        Files.createDirectories(r1Dir);
        Path r1 = r1Dir.resolve("pom.xml");
        Files.writeString(r1, """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>x</groupId><artifactId>r1</artifactId><version>1</version>
                </project>
                """, StandardCharsets.UTF_8);
        Path r2Dir = tmp.resolve("r2");
        Files.createDirectories(r2Dir);
        Path r2 = r2Dir.resolve("pom.xml");
        Files.writeString(r2, """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>x</groupId><artifactId>r2</artifactId><version>1</version>
                </project>
                """, StandardCharsets.UTF_8);

        ReactorScan scan = ReactorWalker.walkAll(List.of(r1, r2, r1));

        assertThat(scan.surveys())
                .extracting(PomSiteSurvey::pomPath)
                .containsExactly(
                        r1.toAbsolutePath().normalize(),
                        r2.toAbsolutePath().normalize());
    }

    @Test
    void walk_missingModulePom_throws(@TempDir Path tmp) throws IOException {
        Path rootDir = tmp.resolve("root");
        Files.createDirectories(rootDir);
        Path root = rootDir.resolve("pom.xml");
        Files.writeString(root, """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>x</groupId><artifactId>root</artifactId><version>1</version>
                    <packaging>pom</packaging>
                    <modules><module>missing</module></modules>
                </project>
                """, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ReactorWalker.walk(root))
                .isInstanceOf(IOException.class);
    }

    private static Path writePom(Path dir, String artifactId, String content) throws IOException {
        Files.createDirectories(dir);
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, content, StandardCharsets.UTF_8);
        return pom;
    }
}
