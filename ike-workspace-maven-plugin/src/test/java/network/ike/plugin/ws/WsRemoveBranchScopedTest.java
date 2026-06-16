package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #575 branch-scoped, work-preserving {@code ws:remove} on a
 * <em>branch-divergent</em> workspace: {@code extra} is declared on
 * {@code feature/x} but not on {@code main}; {@code shared} is a member of
 * both branches.
 *
 * <p>Removal must:
 * <ul>
 *   <li>work on any branch (no main-only guard) — the core of #575;</li>
 *   <li>under {@code -DdeleteDir=true} <b>park</b> rather than {@code rm}
 *       the clone: push its branch to origin first, then remove it, and
 *       stash any uncommitted work to {@code refs/ws-stash/<slug>/<branch>}
 *       on origin so nothing is lost;</li>
 *   <li>leave the {@code main} path unchanged (regression guard).</li>
 * </ul>
 *
 * <p>Mirrors {@link WsSwitchParkRestoreTest}: a real git-root workspace with
 * bare {@code file://} upstreams so park (push) and stash (push ref) exercise
 * actual git, with hermetic git identity and disabled hooks.
 */
class WsRemoveBranchScopedTest {

    @TempDir
    Path tmp;

    private String sharedUrl;
    private String extraUrl;

    // ── Test 1 — RED-FIRST: removal works on a feature branch ────────────

    /**
     * On {@code feature/x}, {@code ws:remove -Dsubproject=extra} (no
     * deleteDir) succeeds and drops {@code extra} from the {@code feature/x}
     * {@code workspace.yaml}. Against the unpatched mojo this throws
     * "Cannot remove a subproject from a feature branch"; after #575 it
     * passes. The clone is left in place (default {@code deleteDir=false}).
     *
     * @throws Exception on fixture or git failure
     */
    @Test
    void removes_feature_only_member_on_feature_branch() throws Exception {
        scaffoldOnFeatureX();

        remove("extra", false, false);

        assertThat(manifestBody())
                .as("extra dropped from feature/x workspace.yaml")
                .doesNotContain("\n  extra:");
        assertThat(manifestBody())
                .as("shared retained").contains("\n  shared:");
        // Default deleteDir=false leaves the clone untouched.
        assertThat(tmp.resolve("extra").resolve(".git"))
                .as("clone left in place when deleteDir=false").exists();
    }

    // ── Test 2 — work-preserving park (clean tree) ───────────────────────

    /**
     * On {@code feature/x}, {@code ws:remove -Dsubproject=extra
     * -DdeleteDir=true} parks the clone: its {@code feature/x} branch is
     * present on origin (ls-remote) and the local {@code extra/} clone is
     * gone.
     *
     * @throws Exception on fixture or git failure
     */
    @Test
    void parks_clone_on_deleteDir() throws Exception {
        scaffoldOnFeatureX();

        remove("extra", true, false);

        assertThat(lsRemoteHasBranch(extraUrl, "feature/x"))
                .as("parked branch survives on origin").isTrue();
        assertThat(tmp.resolve("extra"))
                .as("local clone parked (removed)").doesNotExist();
    }

    // ── Test 3 — dirty work stashed before park ──────────────────────────

    /**
     * On {@code feature/x}, with {@code extra}'s clone dirty (an
     * uncommitted file), {@code ws:remove -Dsubproject=extra
     * -DdeleteDir=true} stashes the WIP to
     * {@code refs/ws-stash/<slug>/feature/x} on origin before parking — no
     * work is lost — and removes the clone.
     *
     * @throws Exception on fixture or git failure
     */
    @Test
    void stashes_dirty_work_before_park() throws Exception {
        scaffoldOnFeatureX();

        // Dirty extra's clone with an uncommitted, untracked file.
        Files.writeString(tmp.resolve("extra").resolve("WIP.txt"),
                "uncommitted work\n", StandardCharsets.UTF_8);

        remove("extra", true, false);

        String slug = VcsOperations.userSlug(
                VcsOperations.userEmail(tmp.toFile()));
        String stashRef = ParkSupport.stashRef(slug, "feature/x");
        assertThat(lsRemoteHasRef(extraUrl, stashRef))
                .as("WIP stashed to " + stashRef + " on origin").isTrue();
        assertThat(lsRemoteHasBranch(extraUrl, "feature/x"))
                .as("parked branch survives on origin").isTrue();
        assertThat(tmp.resolve("extra"))
                .as("local clone parked (removed)").doesNotExist();
    }

    // ── Test 4 — main path regression ────────────────────────────────────

    /**
     * On {@code main}, removing a {@code main} member behaves as before: the
     * entry is dropped from the {@code main} {@code workspace.yaml}, and with
     * {@code -DdeleteDir=true} the clone is parked (branch present on origin,
     * clone gone). Proves the main path didn't regress.
     *
     * @throws Exception on fixture or git failure
     */
    @Test
    void removes_main_member_on_main_with_park() throws Exception {
        scaffoldOnFeatureX();

        // Switch the whole workspace to main: drop extra (feature-only) and
        // put shared + root on main, matching main's declared membership.
        git(tmp, "checkout", "main");
        git(tmp.resolve("shared"), "checkout", "main");
        deleteDir(tmp.resolve("extra"));   // not a main member

        assertThat(branchOf(tmp)).isEqualTo("main");
        assertThat(branchOf(tmp.resolve("shared"))).isEqualTo("main");

        remove("shared", true, false);

        assertThat(manifestBody())
                .as("shared dropped from main workspace.yaml")
                .doesNotContain("\n  shared:");
        assertThat(lsRemoteHasBranch(sharedUrl, "main"))
                .as("parked branch survives on origin").isTrue();
        assertThat(tmp.resolve("shared"))
                .as("local clone parked (removed)").doesNotExist();
    }

    // ── Fixture ──────────────────────────────────────────────────────────

    /**
     * Build a real git-root workspace on {@code feature/x}: bare upstreams
     * for {@code shared} and {@code extra}, both cloned in and checked out to
     * {@code feature/x}; a git root whose {@code main} declares
     * {@code {shared}} and whose {@code feature/x} declares
     * {@code {shared, extra}}; an aggregator {@code pom.xml} carrying a
     * {@code with-<subproject>} profile per member so
     * {@code removeProfileFromPom} has something to remove.
     *
     * @throws Exception on fixture or git failure
     */
    private void scaffoldOnFeatureX() throws Exception {
        Files.createDirectories(tmp.resolve(".nohooks"));

        sharedUrl = bareWithBranches("shared");
        extraUrl = bareWithBranches("extra");

        clone(sharedUrl, "shared");
        clone(extraUrl, "extra");

        // Git-root: main declares {shared}; feature/x declares {shared, extra}.
        git(tmp, "init", "-b", "main");
        hermetic(tmp);
        Files.writeString(tmp.resolve(".gitignore"),
                "/shared/\n/extra/\n/.up/\n/.nohooks/\n.ike/vcs-state\nws꞉*.md\n",
                StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("workspace.yaml"),
                yaml(false), StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("pom.xml"), pom(false),
                StandardCharsets.UTF_8);
        git(tmp, "add", ".gitignore", "workspace.yaml", "pom.xml");
        git(tmp, "commit", "-m", "main");
        git(tmp, "checkout", "-b", "feature/x");
        Files.writeString(tmp.resolve("workspace.yaml"),
                yaml(true), StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("pom.xml"), pom(true),
                StandardCharsets.UTF_8);
        git(tmp, "add", "workspace.yaml", "pom.xml");
        git(tmp, "commit", "-m", "feature");
        // Root is now on feature/x; shared + extra present on feature/x.
    }

    /**
     * Run {@code ws:remove} against the temp workspace.
     *
     * @param subproject the subproject name to remove
     * @param deleteDir  whether to park (delete) the clone
     * @param noStash    whether to opt out of auto-stash
     * @throws Exception via reflection or {@code execute()}
     */
    private void remove(String subproject, boolean deleteDir, boolean noStash)
            throws Exception {
        WsRemoveMojo mojo = TestLog.createMojo(WsRemoveMojo.class);
        mojo.manifest = tmp.resolve("workspace.yaml").toFile();
        set(mojo, "subproject", subproject);
        set(mojo, "deleteDir", deleteDir);
        set(mojo, "noStash", noStash);
        mojo.execute();
    }

    private static void set(WsRemoveMojo mojo, String field, Object value)
            throws Exception {
        var f = WsRemoveMojo.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(mojo, value);
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

    private String yaml(boolean feature) {
        String br = feature ? "feature/x" : "main";
        String extra = feature ? """
                  extra:
                    repo: %s
                    branch: feature/x
                    version: "1.0.0-SNAPSHOT"
                """.formatted(extraUrl) : "";
        return """
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  shared:
                    repo: %s
                    branch: %s
                    version: "1.0.0-SNAPSHOT"
                %s""".formatted(sharedUrl, br, extra);
    }

    /**
     * Aggregator pom with a {@code with-<subproject>} profile per declared
     * member, mirroring the file-activated profiles {@code ws:add} writes and
     * {@code ws:remove} (via {@code removeProfileFromPom}) drops.
     *
     * @param feature whether to include the feature-only {@code extra} profile
     * @return the pom.xml body
     */
    private String pom(boolean feature) {
        String extraProfile = feature ? """
                    <profile>
                      <id>with-extra</id>
                      <activation>
                        <file><exists>extra/pom.xml</exists></file>
                      </activation>
                      <modules><module>extra</module></modules>
                    </profile>
                """ : "";
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.test</groupId>
                  <artifactId>ws-root</artifactId>
                  <version>1-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <profiles>
                    <profile>
                      <id>with-shared</id>
                      <activation>
                        <file><exists>shared/pom.xml</exists></file>
                      </activation>
                      <modules><module>shared</module></modules>
                    </profile>
                %s  </profiles>
                </project>
                """.formatted(extraProfile);
    }

    private void hermetic(Path dir) throws Exception {
        git(dir, "config", "user.email", "test@example.com");
        git(dir, "config", "user.name", "Test");
        git(dir, "config", "commit.gpgsign", "false");
        git(dir, "config", "core.hooksPath",
                tmp.resolve(".nohooks").toAbsolutePath().toString());
    }

    private String manifestBody() throws Exception {
        return Files.readString(tmp.resolve("workspace.yaml"),
                StandardCharsets.UTF_8);
    }

    private String branchOf(Path dir) throws Exception {
        return capture(dir, "git", "rev-parse", "--abbrev-ref", "HEAD");
    }

    private boolean lsRemoteHasBranch(String url, String branch)
            throws Exception {
        return capture(tmp, "git", "ls-remote", "--heads", url, branch)
                .contains("refs/heads/" + branch);
    }

    private boolean lsRemoteHasRef(String url, String ref) throws Exception {
        return capture(tmp, "git", "ls-remote", url, ref).contains(ref);
    }

    private void deleteDir(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
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

    private String capture(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        p.waitFor();
        return out;
    }
}
