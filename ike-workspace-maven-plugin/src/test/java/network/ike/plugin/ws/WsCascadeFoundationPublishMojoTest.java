package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WsCascadeFoundationPublishMojo}'s pure helpers
 * — git-state detection and small string utilities. The
 * subprocess-driving execution path isn't unit-tested here; it
 * exercises the same shared {@link network.ike.plugin.ReleaseSupport}
 * machinery as {@code WsReleaseDraftMojo} and is covered by the
 * existing integration tests.
 *
 * <p>ike-issues#375.
 */
class WsCascadeFoundationPublishMojoTest {

    // ── findMvn ──────────────────────────────────────────────────

    @Test
    void findMvn_picksLocalMvnwWhenExecutable(@TempDir Path tmp) throws IOException {
        Path mvnw = tmp.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\nexec mvn \"$@\"\n");
        mvnw.toFile().setExecutable(true);

        assertThat(WsCascadeFoundationPublishMojo.findMvn(tmp.toFile()))
                .isEqualTo(mvnw.toAbsolutePath().toString());
    }

    @Test
    void findMvn_fallsBackToMvnOnPath(@TempDir Path tmp) {
        // No mvnw file present.
        assertThat(WsCascadeFoundationPublishMojo.findMvn(tmp.toFile()))
                .isEqualTo("mvn");
    }

    @Test
    void findMvn_existsButNotExecutable_fallsBack(@TempDir Path tmp)
            throws IOException {
        Path mvnw = tmp.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\nexec mvn \"$@\"\n");
        // Deliberately not executable.

        assertThat(WsCascadeFoundationPublishMojo.findMvn(tmp.toFile()))
                .isEqualTo("mvn");
    }

    // ── padRight ─────────────────────────────────────────────────

    @Test
    void padRight_paddedAndPassThrough() {
        assertThat(WsCascadeFoundationPublishMojo.padRight("x", 5))
                .isEqualTo("x    ");
        assertThat(WsCascadeFoundationPublishMojo.padRight("xxxxxxxx", 3))
                .isEqualTo("xxxxxxxx");
    }

    // ── RELEASE_CADENCE_PATTERN ──────────────────────────────────

    @Test
    void cadencePattern_matchesAllKnownReleaseCommits() {
        assertThat(WsCascadeFoundationPublishMojo
                .RELEASE_CADENCE_PATTERN.matcher(
                        "release: set version to 42").matches()).isTrue();
        assertThat(WsCascadeFoundationPublishMojo
                .RELEASE_CADENCE_PATTERN.matcher(
                        "release: restore ${project.version} references")
                .matches()).isTrue();
        assertThat(WsCascadeFoundationPublishMojo
                .RELEASE_CADENCE_PATTERN.matcher(
                        "merge: release 42").matches()).isTrue();
        assertThat(WsCascadeFoundationPublishMojo
                .RELEASE_CADENCE_PATTERN.matcher(
                        "post-release: bump to 43-SNAPSHOT").matches()).isTrue();
        assertThat(WsCascadeFoundationPublishMojo
                .RELEASE_CADENCE_PATTERN.matcher(
                        "site: publish ike-tooling 42 (auto-register, #367)")
                .matches()).isTrue();
        assertThat(WsCascadeFoundationPublishMojo
                .RELEASE_CADENCE_PATTERN.matcher(
                        "post-release: sync workspace.yaml versions (#371)")
                .matches()).isTrue();
    }

    @Test
    void cadencePattern_doesNotMatchRealCommits() {
        assertThat(WsCascadeFoundationPublishMojo
                .RELEASE_CADENCE_PATTERN.matcher(
                        "#371 ws:release-publish: sync workspace.yaml")
                .matches()).isFalse();
        assertThat(WsCascadeFoundationPublishMojo
                .RELEASE_CADENCE_PATTERN.matcher(
                        "fix: handle empty target/staging").matches())
                .isFalse();
        assertThat(WsCascadeFoundationPublishMojo
                .RELEASE_CADENCE_PATTERN.matcher(
                        "chore: bump ike-parent 42 -> 43").matches()).isFalse();
    }

    // ── Outcome record ───────────────────────────────────────────

    @Test
    void outcome_factoryMethodsCarryCorrectKind() {
        var released = WsCascadeFoundationPublishMojo.Outcome.released("x");
        assertThat(released.kind())
                .isEqualTo(WsCascadeFoundationPublishMojo
                        .OutcomeKind.RELEASED);
        assertThat(released.detail()).isNull();

        var upToDate = WsCascadeFoundationPublishMojo.Outcome.upToDate(
                "x", "v42");
        assertThat(upToDate.kind())
                .isEqualTo(WsCascadeFoundationPublishMojo
                        .OutcomeKind.UP_TO_DATE);
        assertThat(upToDate.detail()).isEqualTo("at v42");

        var skipped = WsCascadeFoundationPublishMojo.Outcome.skipped(
                "x", "no .git");
        assertThat(skipped.kind())
                .isEqualTo(WsCascadeFoundationPublishMojo
                        .OutcomeKind.SKIPPED);
        assertThat(skipped.detail()).isEqualTo("no .git");

        var failed = WsCascadeFoundationPublishMojo.Outcome.failed(
                "x", "boom");
        assertThat(failed.kind())
                .isEqualTo(WsCascadeFoundationPublishMojo
                        .OutcomeKind.FAILED);
        assertThat(failed.detail()).isEqualTo("boom");
    }

    // ── walkOne: missing directory branches ──────────────────────

    @Test
    void walkOne_returnsSkippedWhenDirectoryAbsent() {
        WsCascadeFoundationPublishMojo mojo = TestLog.createMojo(
                WsCascadeFoundationPublishMojo.class);

        var outcome = mojo.walkOne(
                new File("/does/not/exist/ike-tooling"), "ike-tooling");

        assertThat(outcome.kind())
                .isEqualTo(WsCascadeFoundationPublishMojo
                        .OutcomeKind.SKIPPED);
        assertThat(outcome.detail()).contains("not checked out");
    }

    @Test
    void walkOne_returnsSkippedWhenNoGit(@TempDir Path tmp) {
        WsCascadeFoundationPublishMojo mojo = TestLog.createMojo(
                WsCascadeFoundationPublishMojo.class);

        var outcome = mojo.walkOne(tmp.toFile(), "ike-tooling");

        assertThat(outcome.kind())
                .isEqualTo(WsCascadeFoundationPublishMojo
                        .OutcomeKind.SKIPPED);
        assertThat(outcome.detail()).contains("no .git");
    }

    @Test
    void walkOne_returnsSkippedWhenNoPomXml(@TempDir Path tmp) throws IOException {
        Files.createDirectory(tmp.resolve(".git"));

        WsCascadeFoundationPublishMojo mojo = TestLog.createMojo(
                WsCascadeFoundationPublishMojo.class);

        var outcome = mojo.walkOne(tmp.toFile(), "ike-tooling");

        assertThat(outcome.kind())
                .isEqualTo(WsCascadeFoundationPublishMojo
                        .OutcomeKind.SKIPPED);
        assertThat(outcome.detail()).contains("no pom.xml");
    }
}
