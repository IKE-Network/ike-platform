package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Working-set report test for {@code ws:switch} (epic #764, child #767).
 *
 * <p>The switch report renders the shared {@link WorkingSetReportTable}: one
 * row per member, the <b>aggregator (workspace root) included</b>. This is the
 * #763 fix — a subproject-only table hid the workspace root, so a stale
 * aggregator left on the source branch never appeared. The test asserts the
 * aggregator row is present and labelled {@code aggregator}, that the root's
 * POM version is gathered the same way as a subproject's, and that the
 * {@code Effect} column states what the switch would do to each member.
 *
 * <p>Builds a real git-root workspace with a bare upstream so {@code ws:switch}
 * exercises actual branch discovery. Runs in <em>draft</em> mode so no checkout
 * happens — the focus is the report table, not the switch mechanics (those are
 * covered by {@link WsSwitchParkRestoreTest} / {@link WsSwitchIntegrationTest}).
 */
class WsSwitchReportTest {

    /** Distinctive version on the workspace-root POM, asserted in the table. */
    private static final String ROOT_VERSION = "9.9.9-ROOT-SNAPSHOT";

    @TempDir
    Path tmp;

    @Test
    void switchDraft_report_includesAggregatorRowWithRootVersion()
            throws Exception {
        buildWorkspace();

        // Draft switch feature/x → main. The report must table every member.
        WsSwitchDraftMojo mojo = TestLog.createMojo(WsSwitchDraftMojo.class);
        mojo.manifest = tmp.resolve("workspace.yaml").toFile();
        mojo.branch = "main";
        mojo.publish = false;       // draft — preview only
        mojo.noStash = true;        // keep the focus off the stash flow
        mojo.execute();

        String report = readReport();

        // Shared working-set table headers (final column "Effect" — a
        // mutating goal).
        assertThat(report)
                .as("working-set table headers present")
                .contains("Member")
                .contains("Kind")
                .contains("Version")
                .contains("Branch")
                .contains("SHA")
                .contains("Effect");

        // The subproject row.
        assertThat(report)
                .as("subproject row present and labelled")
                .contains("shared")
                .contains("subproject");

        // The aggregator (workspace root) row — the #763 fix. The root dir
        // name is the @TempDir leaf; assert its kind label and that the row's
        // Effect describes the root switch.
        assertThat(report)
                .as("aggregator row present and labelled 'aggregator'")
                .contains("aggregator");
        assertThat(report)
                .as("aggregator effect describes the root branch switch")
                .contains("would switch `feature/x` → `main`");

        // The root's POM version is gathered the same way as a subproject's
        // — a subproject-only table would never have surfaced it (#763).
        assertThat(report)
                .as("workspace-root POM version appears in the table (#763 fix)")
                .contains(ROOT_VERSION);
    }

    // ── Fixture ──────────────────────────────────────────────────────

    /**
     * Build a git-root workspace with one subproject {@code shared} declared on
     * both {@code main} and {@code feature/x}, cloned from a bare upstream and
     * checked out to {@code feature/x}. The workspace root carries a POM with a
     * distinctive {@link #ROOT_VERSION} so the aggregator row's version is
     * assertable. Leaves the root and the clone on {@code feature/x}.
     *
     * @throws Exception on fixture or git failure
     */
    private void buildWorkspace() throws Exception {
        Files.createDirectories(tmp.resolve(".nohooks"));

        String sharedUrl = bareWithBranches("shared");
        clone(sharedUrl, "shared");

        // Git-root: a workspace.yaml declaring {shared} on both branches, plus
        // a root POM carrying the distinctive version. main and feature/x both
        // declare the same membership (no park) — the switch is a plain branch
        // move.
        git(tmp, "init", "-b", "main");
        hermetic(tmp);
        Files.writeString(tmp.resolve(".gitignore"),
                "/shared/\n/.up/\n/.nohooks/\n.ike/vcs-state\nws꞉*.md\n",
                StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.test</groupId><artifactId>ws-root</artifactId>
                  <version>%s</version></project>
                """.formatted(ROOT_VERSION), StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("workspace.yaml"),
                yaml(sharedUrl, "main"), StandardCharsets.UTF_8);
        git(tmp, "add", ".gitignore", "pom.xml", "workspace.yaml");
        git(tmp, "commit", "-m", "main");
        git(tmp, "checkout", "-b", "feature/x");
        Files.writeString(tmp.resolve("workspace.yaml"),
                yaml(sharedUrl, "feature/x"), StandardCharsets.UTF_8);
        git(tmp, "add", "workspace.yaml");
        git(tmp, "commit", "-m", "feature");
        // Root + shared now on feature/x.
    }

    private String bareWithBranches(String name) throws Exception {
        Path bare = tmp.resolve(".up").resolve(name + ".git");
        Files.createDirectories(bare);
        git(bare, "init", "--bare");

        Path work = tmp.resolve(".up").resolve("w-" + name);
        Files.createDirectories(work);
        git(work, "init", "-b", "main");
        hermetic(work);
        Files.writeString(work.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.test</groupId><artifactId>%s</artifactId>
                  <version>1.0.0-SNAPSHOT</version></project>
                """.formatted(name), StandardCharsets.UTF_8);
        git(work, "add", ".");
        git(work, "commit", "-m", "init");
        git(work, "remote", "add", "origin", bare.toAbsolutePath().toString());
        git(work, "push", "-u", "origin", "main");
        git(work, "checkout", "-b", "feature/x");
        git(work, "push", "-u", "origin", "feature/x");
        return bare.toUri().toString();
    }

    private void clone(String url, String name) throws Exception {
        git(tmp, "clone", url, name);
        Path dir = tmp.resolve(name);
        hermetic(dir);
        git(dir, "checkout", "feature/x");
    }

    private static String yaml(String sharedUrl, String branch) {
        return """
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  shared:
                    repo: %s
                    branch: %s
                    version: "1.0.0-SNAPSHOT"
                """.formatted(sharedUrl, branch);
    }

    private void hermetic(Path dir) throws Exception {
        git(dir, "config", "user.email", "test@example.com");
        git(dir, "config", "user.name", "Test");
        git(dir, "config", "commit.gpgsign", "false");
        git(dir, "config", "core.hooksPath",
                tmp.resolve(".nohooks").toAbsolutePath().toString());
    }

    private String readReport() throws Exception {
        try (java.util.stream.Stream<Path> stream = Files.list(tmp)) {
            Path reportFile = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> p.getFileName().toString().contains("switch"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "No switch report .md in " + tmp));
            return Files.readString(reportFile, StandardCharsets.UTF_8);
        }
    }

    private void git(Path dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new RuntimeException("git " + String.join(" ", args)
                    + " failed:\n" + out);
        }
    }
}
