package network.ike.plugin.ws;

import network.ike.plugin.ws.ReactorWalker.ReactorScan;
import network.ike.plugin.ws.ReleasePlan.ArtifactReleasePlan;
import network.ike.plugin.ws.ReleasePlan.GA;
import network.ike.plugin.ws.ReleasePlan.PropertyReleasePlan;
import network.ike.plugin.ws.ReleasePlan.ReferenceKind;
import network.ike.plugin.ws.ReleasePlan.ReferenceSite;
import network.ike.plugin.ws.ReleasePlanCompute.ArtifactReleaseIntent;
import network.ike.plugin.ws.ReleasePlanCompute.SubprojectRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleasePlanComputeTest {

    // ── Artifact plan basics ────────────────────────────────────────

    @Test
    void intent_releaseValueMustNotBeSnapshot() {
        assertThatThrownBy(() -> new ArtifactReleaseIntent(
                new GA("g", "a"),
                "a-subproject",
                Path.of("/tmp/pom.xml"),
                "10-SNAPSHOT",
                "10-SNAPSHOT",
                "11-SNAPSHOT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-SNAPSHOT");
    }

    @Test
    void compute_duplicateIntent_throws() {
        ArtifactReleaseIntent i1 = new ArtifactReleaseIntent(
                new GA("g", "a"), "a-sub", Path.of("/tmp/a/pom.xml"),
                "10-SNAPSHOT", "10", "11-SNAPSHOT");
        ArtifactReleaseIntent i2 = new ArtifactReleaseIntent(
                new GA("g", "a"), "a-sub", Path.of("/tmp/a/pom.xml"),
                "10-SNAPSHOT", "10", "11-SNAPSHOT");

        assertThatThrownBy(() -> ReleasePlanCompute.compute(
                new ReactorScan(List.of()), List.of(), List.of(i1, i2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void compute_artifactReferenceSitesGathered(@TempDir Path tmp) throws IOException {
        Path upstream = writePom(tmp.resolve("up"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>net.up</groupId><artifactId>upstream</artifactId><version>10-SNAPSHOT</version>
                </project>
                """);
        Path consumer = writePom(tmp.resolve("cons"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>net.cons</groupId><artifactId>consumer</artifactId><version>1</version>
                    <dependencies>
                        <dependency><groupId>net.up</groupId><artifactId>upstream</artifactId><version>9</version></dependency>
                    </dependencies>
                </project>
                """);

        ReactorScan scan = ReactorWalker.walkAll(List.of(upstream, consumer));

        ReleasePlan plan = ReleasePlanCompute.compute(scan,
                List.of(
                        new SubprojectRoot("upstream", upstream),
                        new SubprojectRoot("consumer", consumer)),
                List.of(new ArtifactReleaseIntent(
                        new GA("net.up", "upstream"),
                        "upstream", upstream,
                        "10-SNAPSHOT", "10", "11-SNAPSHOT")));

        ArtifactReleasePlan ap = plan.artifacts().get(new GA("net.up", "upstream"));
        assertThat(ap).isNotNull();
        assertThat(ap.releaseValue()).isEqualTo("10");
        assertThat(ap.referenceSites())
                .extracting(ReferenceSite::kind, ReferenceSite::textAtSite)
                .containsExactly(tuple(ReferenceKind.DEPENDENCY, "9"));
    }

    // ── Property plans: basic tracking ──────────────────────────────

    @Test
    void compute_propertyTrackingUpstreamRelease(@TempDir Path tmp) throws IOException {
        Path upstream = writePom(tmp.resolve("up"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>net.up</groupId><artifactId>upstream</artifactId><version>10-SNAPSHOT</version>
                </project>
                """);
        Path consumer = writePom(tmp.resolve("cons"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>net.cons</groupId><artifactId>consumer</artifactId><version>1</version>
                    <properties>
                        <upstream.version>9</upstream.version>
                    </properties>
                    <dependencies>
                        <dependency><groupId>net.up</groupId><artifactId>upstream</artifactId><version>${upstream.version}</version></dependency>
                    </dependencies>
                </project>
                """);

        ReactorScan scan = ReactorWalker.walkAll(List.of(upstream, consumer));

        ReleasePlan plan = ReleasePlanCompute.compute(scan,
                List.of(
                        new SubprojectRoot("upstream", upstream),
                        new SubprojectRoot("consumer", consumer)),
                List.of(new ArtifactReleaseIntent(
                        new GA("net.up", "upstream"),
                        "upstream", upstream,
                        "10-SNAPSHOT", "10", "11-SNAPSHOT")));

        assertThat(plan.properties()).hasSize(1);
        PropertyReleasePlan pp = plan.properties().getFirst();
        assertThat(pp.propertyName()).isEqualTo("upstream.version");
        assertThat(pp.declaringPomPath()).isEqualTo(consumer.toAbsolutePath().normalize());
        assertThat(pp.declaringSubproject()).isEqualTo("consumer");
        assertThat(pp.preReleaseValue()).isEqualTo("9");
        assertThat(pp.releaseValue()).isEqualTo("10");
        assertThat(pp.postReleaseValue()).isEqualTo("10");
        assertThat(pp.referenceSites()).hasSize(1);
        assertThat(pp.referenceSites().getFirst().textAtSite())
                .isEqualTo("${upstream.version}");
    }

    @Test
    void compute_propertyNotTrackingAnyReleasedArtifact_notIncluded(@TempDir Path tmp) throws IOException {
        Path consumer = writePom(tmp.resolve("cons"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>net.cons</groupId><artifactId>consumer</artifactId><version>1</version>
                    <properties>
                        <unrelated.version>99</unrelated.version>
                    </properties>
                    <dependencies>
                        <dependency><groupId>net.other</groupId><artifactId>other</artifactId><version>${unrelated.version}</version></dependency>
                    </dependencies>
                </project>
                """);
        Path upstream = writePom(tmp.resolve("up"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>net.up</groupId><artifactId>upstream</artifactId><version>10-SNAPSHOT</version>
                </project>
                """);

        ReactorScan scan = ReactorWalker.walkAll(List.of(upstream, consumer));
        ReleasePlan plan = ReleasePlanCompute.compute(scan,
                List.of(
                        new SubprojectRoot("upstream", upstream),
                        new SubprojectRoot("consumer", consumer)),
                List.of(new ArtifactReleaseIntent(
                        new GA("net.up", "upstream"), "upstream", upstream,
                        "10-SNAPSHOT", "10", "11-SNAPSHOT")));

        assertThat(plan.properties()).isEmpty();
    }

    // ── Inheritance: parent + child override ────────────────────────

    @Test
    void compute_childOverrideProducesSecondEntry(@TempDir Path tmp) throws IOException {
        // Parent declares ike-tooling.version=123, child overrides to 120
        Path upstream = writePom(tmp.resolve("up"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>net.tool</groupId><artifactId>tool</artifactId><version>123</version>
                </project>
                """);
        Path parentDir = tmp.resolve("parent");
        Files.createDirectories(parentDir);
        Path parent = parentDir.resolve("pom.xml");
        Files.writeString(parent, """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>net.cons</groupId><artifactId>parent</artifactId><version>1</version>
                    <packaging>pom</packaging>
                    <properties>
                        <tool.version>123</tool.version>
                    </properties>
                    <dependencies>
                        <dependency><groupId>net.tool</groupId><artifactId>tool</artifactId><version>${tool.version}</version></dependency>
                    </dependencies>
                    <modules><module>child</module></modules>
                </project>
                """, StandardCharsets.UTF_8);
        Path child = writePom(parentDir.resolve("child"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent><groupId>net.cons</groupId><artifactId>parent</artifactId><version>1</version></parent>
                    <artifactId>child</artifactId>
                    <properties>
                        <tool.version>120</tool.version>
                    </properties>
                    <dependencies>
                        <dependency><groupId>net.tool</groupId><artifactId>tool</artifactId><version>${tool.version}</version></dependency>
                    </dependencies>
                </project>
                """);

        ReactorScan scan = ReactorWalker.walkAll(List.of(upstream, parent));

        ReleasePlan plan = ReleasePlanCompute.compute(scan,
                List.of(
                        new SubprojectRoot("upstream", upstream),
                        new SubprojectRoot("consumer", parent)),
                List.of(new ArtifactReleaseIntent(
                        new GA("net.tool", "tool"), "upstream", upstream,
                        "122", "124", "125-SNAPSHOT")));

        assertThat(plan.properties()).hasSize(2);
        assertThat(plan.properties())
                .extracting(PropertyReleasePlan::declaringPomPath,
                        PropertyReleasePlan::preReleaseValue,
                        PropertyReleasePlan::releaseValue)
                .containsExactlyInAnyOrder(
                        tuple(parent.toAbsolutePath().normalize(), "123", "124"),
                        tuple(child.toAbsolutePath().normalize(), "120", "124"));
    }

    // ── #209 guard ──────────────────────────────────────────────────

    @Test
    void compute_issue209_propertyTracksUpstreamNotSelf(@TempDir Path tmp) throws IOException {
        // Simulates ike-platform releasing 110 while ike-tooling.version tracks 123.
        // The old bug would set ike-tooling.version=110. The plan must set it to the
        // tracked upstream's releaseValue (no upstream here → property should not appear).
        Path pipeline = writePom(tmp.resolve("pipe"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>net.pipe</groupId><artifactId>pipeline</artifactId><version>110-SNAPSHOT</version>
                    <properties>
                        <ike-tooling.version>123</ike-tooling.version>
                    </properties>
                    <dependencies>
                        <dependency><groupId>net.tool</groupId><artifactId>tooling</artifactId><version>${ike-tooling.version}</version></dependency>
                    </dependencies>
                </project>
                """);

        ReactorScan scan = ReactorWalker.walk(pipeline);

        // Only pipeline is in the cascade — upstream tooling is NOT released.
        ReleasePlan plan = ReleasePlanCompute.compute(scan,
                List.of(new SubprojectRoot("pipeline", pipeline)),
                List.of(new ArtifactReleaseIntent(
                        new GA("net.pipe", "pipeline"), "pipeline", pipeline,
                        "110-SNAPSHOT", "110", "111-SNAPSHOT")));

        assertThat(plan.properties())
                .as("ike-tooling.version tracks an out-of-cascade artifact; must not be rewritten")
                .isEmpty();
    }

    @Test
    void compute_propertyReferencingTwoInCascadeArtifactsWithConflict_throws(
            @TempDir Path tmp) throws IOException {
        Path a = writePom(tmp.resolve("a"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>g</groupId><artifactId>a</artifactId><version>1-SNAPSHOT</version>
                </project>
                """);
        Path b = writePom(tmp.resolve("b"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>g</groupId><artifactId>b</artifactId><version>1-SNAPSHOT</version>
                </project>
                """);
        Path consumer = writePom(tmp.resolve("cons"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>g</groupId><artifactId>consumer</artifactId><version>1</version>
                    <properties>
                        <shared.version>9</shared.version>
                    </properties>
                    <dependencies>
                        <dependency><groupId>g</groupId><artifactId>a</artifactId><version>${shared.version}</version></dependency>
                        <dependency><groupId>g</groupId><artifactId>b</artifactId><version>${shared.version}</version></dependency>
                    </dependencies>
                </project>
                """);

        ReactorScan scan = ReactorWalker.walkAll(List.of(a, b, consumer));

        assertThatThrownBy(() -> ReleasePlanCompute.compute(scan,
                List.of(
                        new SubprojectRoot("a", a),
                        new SubprojectRoot("b", b),
                        new SubprojectRoot("consumer", consumer)),
                List.of(
                        new ArtifactReleaseIntent(new GA("g", "a"), "a", a,
                                "1-SNAPSHOT", "10", "11-SNAPSHOT"),
                        new ArtifactReleaseIntent(new GA("g", "b"), "b", b,
                                "1-SNAPSHOT", "20", "21-SNAPSHOT"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared.version")
                .hasMessageContaining("conflicting");
    }

    @Test
    void compute_propertyReferencingTwoInCascadeArtifactsWithAgreement_ok(
            @TempDir Path tmp) throws IOException {
        Path a = writePom(tmp.resolve("a"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>g</groupId><artifactId>a</artifactId><version>1-SNAPSHOT</version>
                </project>
                """);
        Path b = writePom(tmp.resolve("b"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>g</groupId><artifactId>b</artifactId><version>1-SNAPSHOT</version>
                </project>
                """);
        Path consumer = writePom(tmp.resolve("cons"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>g</groupId><artifactId>consumer</artifactId><version>1</version>
                    <properties>
                        <shared.version>9</shared.version>
                    </properties>
                    <dependencies>
                        <dependency><groupId>g</groupId><artifactId>a</artifactId><version>${shared.version}</version></dependency>
                        <dependency><groupId>g</groupId><artifactId>b</artifactId><version>${shared.version}</version></dependency>
                    </dependencies>
                </project>
                """);

        ReactorScan scan = ReactorWalker.walkAll(List.of(a, b, consumer));

        ReleasePlan plan = ReleasePlanCompute.compute(scan,
                List.of(
                        new SubprojectRoot("a", a),
                        new SubprojectRoot("b", b),
                        new SubprojectRoot("consumer", consumer)),
                List.of(
                        new ArtifactReleaseIntent(new GA("g", "a"), "a", a,
                                "1-SNAPSHOT", "10", "11-SNAPSHOT"),
                        new ArtifactReleaseIntent(new GA("g", "b"), "b", b,
                                "1-SNAPSHOT", "10", "11-SNAPSHOT")));

        assertThat(plan.properties()).hasSize(1);
        assertThat(plan.properties().getFirst().releaseValue()).isEqualTo("10");
        assertThat(plan.properties().getFirst().referenceSites()).hasSize(2);
    }

    // ── Co-released sub-artifacts ───────────────────────────────────

    /**
     * Regression guard for the ike-tooling shape: the released
     * subproject has GA {@code net.up:parent} (a pom aggregator),
     * but the property {@code upstream.version} in the consumer
     * references a <em>sub-artifact</em> {@code net.up:plugin} that
     * lives inside the upstream's reactor. The property must track
     * the upstream's releaseValue even though the property's target
     * GA is not the intent GA directly.
     *
     * <p>Before the fix, coveredGa only held intent GAs, so the
     * consumer's property was silently ignored during plan compute
     * and emerged as {@code properties: []} in plan.yaml.
     */
    @Test
    void compute_propertyTargetingSubArtifact_tracksToParentIntent(
            @TempDir Path tmp) throws IOException {
        Path upDir = tmp.resolve("up");
        Files.createDirectories(upDir);
        Path upstream = upDir.resolve("pom.xml");
        Files.writeString(upstream, """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>net.up</groupId>
                    <artifactId>parent</artifactId>
                    <version>10-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <modules><module>plugin</module></modules>
                </project>
                """, StandardCharsets.UTF_8);
        writePom(upDir.resolve("plugin"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent><groupId>net.up</groupId><artifactId>parent</artifactId><version>10-SNAPSHOT</version></parent>
                    <artifactId>plugin</artifactId>
                </project>
                """);
        Path consumer = writePom(tmp.resolve("cons"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>net.cons</groupId><artifactId>consumer</artifactId><version>1</version>
                    <properties>
                        <upstream.version>9</upstream.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>net.up</groupId>
                            <artifactId>plugin</artifactId>
                            <version>${upstream.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        ReactorScan scan = ReactorWalker.walkAll(List.of(upstream, consumer));

        ReleasePlan plan = ReleasePlanCompute.compute(scan,
                List.of(
                        new SubprojectRoot("up", upstream),
                        new SubprojectRoot("consumer", consumer)),
                List.of(new ArtifactReleaseIntent(
                        new GA("net.up", "parent"),
                        "up", upstream,
                        "10-SNAPSHOT", "10", "11-SNAPSHOT")));

        assertThat(plan.properties()).hasSize(1);
        PropertyReleasePlan pp = plan.properties().getFirst();
        assertThat(pp.propertyName()).isEqualTo("upstream.version");
        assertThat(pp.releaseValue()).isEqualTo("10");
        assertThat(pp.postReleaseValue()).isEqualTo("10");
        assertThat(pp.referenceSites()).hasSize(1);
    }

    // ── Maven built-in expressions excluded ─────────────────────────

    /**
     * {@code ${project.version}} is a Maven-built-in expression: it
     * resolves to the declaring POM's own version at build time and
     * has nothing to do with a workspace-level property. Two releasing
     * subprojects using {@code ${project.version}} in self-references
     * must NOT be flagged as a conflicting property target — the old
     * code conflated them and failed-fast every multi-subproject cut.
     */
    @Test
    void compute_projectVersionExpression_ignored(@TempDir Path tmp) throws IOException {
        Path a = writePom(tmp.resolve("a"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>g</groupId><artifactId>a</artifactId><version>1-SNAPSHOT</version>
                    <dependencies>
                        <dependency><groupId>g</groupId><artifactId>a-sub</artifactId><version>${project.version}</version></dependency>
                    </dependencies>
                </project>
                """);
        Path b = writePom(tmp.resolve("b"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>g</groupId><artifactId>b</artifactId><version>1-SNAPSHOT</version>
                    <dependencies>
                        <dependency><groupId>g</groupId><artifactId>b-sub</artifactId><version>${project.version}</version></dependency>
                    </dependencies>
                </project>
                """);

        ReactorScan scan = ReactorWalker.walkAll(List.of(a, b));

        // Different releaseValues for a and b. Without the exclusion
        // the compute would throw "conflicting releaseValues" on
        // ${project.version}.
        ReleasePlan plan = ReleasePlanCompute.compute(scan,
                List.of(new SubprojectRoot("a", a), new SubprojectRoot("b", b)),
                List.of(
                        new ArtifactReleaseIntent(new GA("g", "a"), "a", a,
                                "1-SNAPSHOT", "10", "11-SNAPSHOT"),
                        new ArtifactReleaseIntent(new GA("g", "b"), "b", b,
                                "1-SNAPSHOT", "20", "21-SNAPSHOT")));

        assertThat(plan.properties()).isEmpty();
    }

    // ── Release order preserved ─────────────────────────────────────

    @Test
    void compute_artifactOrderMatchesIntentOrder(@TempDir Path tmp) throws IOException {
        Path a = writePom(tmp.resolve("a"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>g</groupId><artifactId>a</artifactId><version>1-SNAPSHOT</version>
                </project>
                """);
        Path b = writePom(tmp.resolve("b"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>g</groupId><artifactId>b</artifactId><version>1-SNAPSHOT</version>
                </project>
                """);

        ReactorScan scan = ReactorWalker.walkAll(List.of(a, b));

        ReleasePlan plan = ReleasePlanCompute.compute(scan,
                List.of(new SubprojectRoot("a", a), new SubprojectRoot("b", b)),
                List.of(
                        new ArtifactReleaseIntent(new GA("g", "b"), "b", b,
                                "1-SNAPSHOT", "2", "3-SNAPSHOT"),
                        new ArtifactReleaseIntent(new GA("g", "a"), "a", a,
                                "1-SNAPSHOT", "2", "3-SNAPSHOT")));

        assertThat(plan.artifacts().sequencedKeySet())
                .containsExactly(new GA("g", "b"), new GA("g", "a"));
    }

    private static Path writePom(Path dir, String content) throws IOException {
        Files.createDirectories(dir);
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, content, StandardCharsets.UTF_8);
        return pom;
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.api.Assertions.tuple(values);
    }
}
