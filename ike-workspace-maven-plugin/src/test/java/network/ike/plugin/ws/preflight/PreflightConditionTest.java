package network.ike.plugin.ws.preflight;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the pure-string helpers in {@link PreflightCondition}
 * introduced by #346 to expand the {@code ws:release-draft}
 * pre-flight scope.
 */
class PreflightConditionTest {

    // ── hasDistributionManagementOrParent ─────────────────────────

    @Test
    void hasDistMgmtOrParent_withDistMgmt_true() {
        String pom = """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>x</artifactId>
                  <version>1</version>
                  <distributionManagement>
                    <repository>...</repository>
                  </distributionManagement>
                </project>
                """;
        assertThat(PreflightCondition.hasDistributionManagementOrParent(pom))
                .isTrue();
    }

    @Test
    void hasDistMgmtOrParent_withParent_true() {
        String pom = """
                <project>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>35</version>
                  </parent>
                  <artifactId>child</artifactId>
                </project>
                """;
        assertThat(PreflightCondition.hasDistributionManagementOrParent(pom))
                .isTrue();
    }

    @Test
    void hasDistMgmtOrParent_neither_false() {
        // The its/ pom that broke the v150 cascade — no parent, no
        // distributionManagement.
        String pom = """
                <project>
                  <groupId>network.ike.examples.its</groupId>
                  <artifactId>ike-example-ws-its</artifactId>
                  <version>1-SNAPSHOT</version>
                  <packaging>pom</packaging>
                </project>
                """;
        assertThat(PreflightCondition.hasDistributionManagementOrParent(pom))
                .isFalse();
    }

    @Test
    void hasDistMgmtOrParent_nullOrBlank_false() {
        assertThat(PreflightCondition.hasDistributionManagementOrParent(null))
                .isFalse();
        assertThat(PreflightCondition.hasDistributionManagementOrParent(""))
                .isFalse();
    }

    // ── shadowsProperty ───────────────────────────────────────────

    @Test
    void shadowsProperty_topLevelDeclaration_true() {
        // The exact case that bit the v150 cascade — its/ pom
        // shadowed the inherited ike-tooling.version with a local
        // value, pinning the plugin to v126.
        String pom = """
                <project>
                  <properties>
                    <ike-tooling.version>126</ike-tooling.version>
                  </properties>
                </project>
                """;
        assertThat(PreflightCondition.shadowsProperty(pom,
                "ike-tooling.version")).isTrue();
    }

    @Test
    void shadowsProperty_commentNamingTheProperty_false() {
        // komet-bom's real shape after renaming its shadow: the pin is
        // now named for the artifact, and the comment explains why by
        // naming the property it no longer declares. Prose is not a
        // declaration (ike-issues#1004).
        String pom = """
                <project>
                  <properties>
                    <!-- Named for the artifact it pins: declaring
                         <ike-docs.version> here would pin plugin
                         resolution. -->
                    <koncept-core.version>97</koncept-core.version>
                  </properties>
                </project>
                """;
        assertThat(PreflightCondition.shadowsProperty(pom,
                "ike-docs.version")).isFalse();
        assertThat(PreflightCondition.shadowsProperty(pom,
                "koncept-core.version")).isTrue();
    }

    @Test
    void shadowsProperty_namespacedAlternative_false() {
        // The post-#347 layout: same value but under `it.*` namespace
        // — does NOT shadow the inherited property.
        String pom = """
                <project>
                  <properties>
                    <it.ike-tooling.version>126</it.ike-tooling.version>
                  </properties>
                </project>
                """;
        assertThat(PreflightCondition.shadowsProperty(pom,
                "ike-tooling.version")).isFalse();
    }

    @Test
    void shadowsProperty_notDeclared_false() {
        String pom = """
                <project>
                  <properties>
                    <java.version>25</java.version>
                  </properties>
                </project>
                """;
        assertThat(PreflightCondition.shadowsProperty(pom,
                "ike-tooling.version")).isFalse();
    }

    @Test
    void shadowsProperty_noPropertiesBlock_false() {
        String pom = """
                <project>
                  <groupId>x</groupId>
                  <artifactId>y</artifactId>
                  <version>1</version>
                </project>
                """;
        assertThat(PreflightCondition.shadowsProperty(pom,
                "ike-tooling.version")).isFalse();
    }

    // ── extractParentGa + extractParentVersion ───────────────────

    @Test
    void extractParentGa_normalCase_returnsConcat() {
        String pom = """
                <project>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>35</version>
                  </parent>
                  <artifactId>child</artifactId>
                </project>
                """;
        assertThat(PreflightCondition.extractParentGa(pom))
                .isEqualTo("network.ike.platform:ike-parent");
    }

    @Test
    void extractParentGa_noParent_returnsNull() {
        String pom = "<project><artifactId>orphan</artifactId></project>";
        assertThat(PreflightCondition.extractParentGa(pom)).isNull();
    }

    @Test
    void extractParentVersion_normalCase_returnsVersion() {
        String pom = """
                <project>
                  <parent>
                    <groupId>x</groupId>
                    <artifactId>y</artifactId>
                    <version>1.2.3</version>
                  </parent>
                </project>
                """;
        assertThat(PreflightCondition.extractParentVersion(pom))
                .isEqualTo("1.2.3");
    }

    @Test
    void extractParentVersion_noParent_returnsNull() {
        assertThat(PreflightCondition.extractParentVersion(
                "<project/>")).isNull();
    }

    @Test
    void extractParentVersion_parentWithoutVersion_returnsNull() {
        // Maven 4 allows omitting <version> from <parent> when the
        // version is inherited from the workspace. We don't handle
        // that case specifically — the helper just returns null and
        // the coherence check skips it.
        String pom = """
                <project>
                  <parent>
                    <groupId>x</groupId>
                    <artifactId>y</artifactId>
                  </parent>
                </project>
                """;
        assertThat(PreflightCondition.extractParentVersion(pom)).isNull();
    }

    // ── containsGhPagesLeakPattern (ike-issues#358) ──────────────────

    @Test
    void containsGhPagesLeakPattern_doubledArtifactWithBomJson_true() {
        String porcelain =
                " M ike-example-its/ike-example-its/bom.json\n"
                + " M ike-example-its/ike-example-its/dependencies.html";
        assertThat(PreflightCondition.containsGhPagesLeakPattern(porcelain))
                .isTrue();
    }

    @Test
    void containsGhPagesLeakPattern_examplesDoubledPath_true() {
        String porcelain = "?? examples/doc-example/built-with.html";
        assertThat(PreflightCondition.containsGhPagesLeakPattern(porcelain))
                .isTrue();
    }

    @Test
    void containsGhPagesLeakPattern_distributionManagementHtml_true() {
        String porcelain = " M ike-example-ws/ike-example-ws/distribution-management.html";
        assertThat(PreflightCondition.containsGhPagesLeakPattern(porcelain))
                .isTrue();
    }

    @Test
    void containsGhPagesLeakPattern_onlyPomEdit_false() {
        String porcelain = " M pom.xml\n M src/site/site.xml";
        assertThat(PreflightCondition.containsGhPagesLeakPattern(porcelain))
                .isFalse();
    }

    @Test
    void containsGhPagesLeakPattern_emptyOrNull_false() {
        assertThat(PreflightCondition.containsGhPagesLeakPattern(""))
                .isFalse();
        assertThat(PreflightCondition.containsGhPagesLeakPattern(null))
                .isFalse();
    }

    // ── extractTopLevelArtifactId (skip <parent>) ─────────────────

    @Test
    void extractTopLevelArtifactId_skipsParentBlock() {
        String pom = """
                <project>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>43</version>
                  </parent>
                  <artifactId>ike-example-ws</artifactId>
                </project>
                """;
        assertThat(PreflightCondition.extractTopLevelArtifactId(pom))
                .isEqualTo("ike-example-ws");
    }

    @Test
    void extractTopLevelArtifactId_noParent() {
        String pom = "<project><artifactId>solo</artifactId></project>";
        assertThat(PreflightCondition.extractTopLevelArtifactId(pom))
                .isEqualTo("solo");
    }

    @Test
    void extractTopLevelArtifactId_missing_null() {
        assertThat(PreflightCondition.extractTopLevelArtifactId(
                "<project/>")).isNull();
        assertThat(PreflightCondition.extractTopLevelArtifactId(null))
                .isNull();
    }

    // ── detectGhPagesLeakDirs (filesystem probe) ──────────────────

    @Test
    void detectGhPagesLeakDirs_doubledNameWithIndexHtml_detected(
            @TempDir Path tmp) throws IOException {
        Path leak = tmp.resolve("foo").resolve("foo");
        Files.createDirectories(leak);
        Files.writeString(leak.resolve("index.html"), "<html/>");

        List<Path> hits = new ArrayList<>();
        PreflightCondition.detectGhPagesLeakDirs(
                tmp.toFile(), List.of("foo"), hits);

        assertThat(hits).containsExactly(leak);
    }

    @Test
    void detectGhPagesLeakDirs_doubledNameButNoIndex_notDetected(
            @TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("foo").resolve("foo"));
        // No index.html — could be a legitimate nested dir; don't flag.

        List<Path> hits = new ArrayList<>();
        PreflightCondition.detectGhPagesLeakDirs(
                tmp.toFile(), List.of("foo"), hits);

        assertThat(hits).isEmpty();
    }

    @Test
    void detectGhPagesLeakDirs_singleNameNoNesting_notDetected(
            @TempDir Path tmp) throws IOException {
        Path subproject = tmp.resolve("foo");
        Files.createDirectories(subproject);
        Files.writeString(subproject.resolve("index.html"), "<html/>");
        // Single layer — a real subproject directory, not a leak.

        List<Path> hits = new ArrayList<>();
        PreflightCondition.detectGhPagesLeakDirs(
                tmp.toFile(), List.of("foo"), hits);

        assertThat(hits).isEmpty();
    }

    @Test
    void detectGhPagesLeakDirs_multipleArtifactIds_findsAllHits(
            @TempDir Path tmp) throws IOException {
        for (String id : List.of("ike-example-ws", "ike-example-its")) {
            Path leak = tmp.resolve(id).resolve(id);
            Files.createDirectories(leak);
            Files.writeString(leak.resolve("index.html"), "<html/>");
        }

        List<Path> hits = new ArrayList<>();
        PreflightCondition.detectGhPagesLeakDirs(
                tmp.toFile(),
                List.of("ike-example-ws", "ike-example-its"),
                hits);

        assertThat(hits).hasSize(2);
    }

    @Test
    void detectGhPagesLeakDirs_nonexistentDir_noNPE() {
        List<Path> hits = new ArrayList<>();
        PreflightCondition.detectGhPagesLeakDirs(
                new File("/does/not/exist"), List.of("foo"), hits);

        assertThat(hits).isEmpty();
    }

    // ── containsScpexeSiteUrl (#372) ──────────────────────────────

    @Test
    void containsScpexeSiteUrl_scpexeInsideSiteBlock_true() {
        String pom = """
                <project>
                  <distributionManagement>
                    <site>
                      <id>x</id>
                      <url>scpexe://proxy/srv/ike-site/x/</url>
                    </site>
                  </distributionManagement>
                </project>
                """;
        assertThat(PreflightCondition.containsScpexeSiteUrl(pom))
                .isTrue();
    }

    @Test
    void containsScpexeSiteUrl_httpsSiteUrl_false() {
        String pom = """
                <project>
                  <distributionManagement>
                    <site>
                      <id>x</id>
                      <url>https://ike.network/x/</url>
                    </site>
                  </distributionManagement>
                </project>
                """;
        assertThat(PreflightCondition.containsScpexeSiteUrl(pom))
                .isFalse();
    }

    @Test
    void containsScpexeSiteUrl_scpexeOutsideSiteBlock_false() {
        // scpexe:// elsewhere in the pom but not inside <site> — we
        // only flag the site URL since that's the post-#304 retired
        // path. Other references would already fail at their own
        // resolution step.
        String pom = """
                <project>
                  <distributionManagement>
                    <repository>
                      <url>scpexe://legacy/wherever/</url>
                    </repository>
                    <site>
                      <url>https://ike.network/x/</url>
                    </site>
                  </distributionManagement>
                </project>
                """;
        assertThat(PreflightCondition.containsScpexeSiteUrl(pom))
                .isFalse();
    }

    @Test
    void containsScpexeSiteUrl_noDistMgmt_false() {
        assertThat(PreflightCondition.containsScpexeSiteUrl(
                "<project/>")).isFalse();
        assertThat(PreflightCondition.containsScpexeSiteUrl(null))
                .isFalse();
    }

    // ── PUSH_ACCESS (ike-issues#960) ──────────────────────────────

    @TempDir
    Path pushTempDir;

    @Test
    void pushAccess_allMembersWritable_passes() throws Exception {
        Path root = Files.createDirectories(pushTempDir.resolve("ws"));
        initRepoWithOrigin(root, pushTempDir.resolve("origin-root"));
        Path sub = Files.createDirectories(root.resolve("sub-a"));
        initRepoWithOrigin(sub, pushTempDir.resolve("origin-sub"));

        assertThat(PreflightCondition.PUSH_ACCESS.check(
                PreflightContext.of(root.toFile(), null, List.of("sub-a"))))
                .isEmpty();
    }

    @Test
    void pushAccess_probeDoesNotPublishAnything() throws Exception {
        // The property the whole gate depends on: running the check must
        // leave the remote exactly as it was.
        Path root = Files.createDirectories(pushTempDir.resolve("ws2"));
        Path origin = pushTempDir.resolve("origin-root2");
        initRepoWithOrigin(root, origin);

        PreflightCondition.PUSH_ACCESS.check(
                PreflightContext.of(root.toFile(), null, List.of()));

        // The bare origin must still have no refs at all.
        String refs = run(origin.toFile(), "git", "for-each-ref");
        assertThat(refs).isEmpty();
    }

    @Test
    void pushAccess_memberWithoutOrigin_isSkippedNotFailed() throws Exception {
        Path root = Files.createDirectories(pushTempDir.resolve("ws3"));
        initRepoWithOrigin(root, pushTempDir.resolve("origin-root3"));
        // A subproject that is a git repo but has no remote at all: there
        // is nothing to strand work against, so it must not fail the gate.
        Path sub = Files.createDirectories(root.resolve("local-only"));
        initRepo(sub);

        assertThat(PreflightCondition.PUSH_ACCESS.check(
                PreflightContext.of(root.toFile(), null,
                        List.of("local-only"))))
                .isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────

    private void initRepo(Path dir) throws Exception {
        run(dir.toFile(), "git", "init", "-q", "-b", "main");
        // Hermetic: never inherit the host's hooks or signing config.
        run(dir.toFile(), "git", "config", "core.hooksPath",
                dir.resolve(".nohooks").toString());
        run(dir.toFile(), "git", "config", "commit.gpgsign", "false");
        run(dir.toFile(), "git", "config", "user.email", "test@example.com");
        run(dir.toFile(), "git", "config", "user.name", "Test");
        Files.writeString(dir.resolve("f.txt"), "x");
        run(dir.toFile(), "git", "add", "f.txt");
        run(dir.toFile(), "git", "commit", "-q", "-m", "init");
    }

    private void initRepoWithOrigin(Path dir, Path origin) throws Exception {
        Files.createDirectories(origin);
        run(origin.toFile(), "git", "init", "-q", "--bare", "-b", "main");
        initRepo(dir);
        run(dir.toFile(), "git", "remote", "add", "origin", origin.toString());
    }

    private String run(File dir, String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(dir)
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).trim();
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IllegalStateException(String.join(" ", command)
                    + " failed (" + exit + "): " + out);
        }
        return out;
    }
}
