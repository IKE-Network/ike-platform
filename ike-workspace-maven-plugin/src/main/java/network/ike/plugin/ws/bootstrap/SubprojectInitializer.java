package network.ike.plugin.ws.bootstrap;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.Ansi;
import network.ike.plugin.ws.MavenWrapper;
import network.ike.plugin.ws.PostMutationSync;
import network.ike.plugin.ws.WsGoal;
import network.ike.plugin.ws.WorkspaceReport;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.workspace.Defaults;
import network.ike.workspace.Subproject;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks the subprojects declared in {@code workspace.yaml} and ensures
 * each one is cloned and initialized with the workspace-standard
 * configuration: post-checkout git hook, Maven wrapper at the declared
 * version, {@code .mvn/jvm.config}, and the per-subproject
 * {@code CLAUDE.md} / {@code CLAUDE-<name>.md} pair.
 *
 * <p>Subsumes the per-subproject half of the retired
 * {@code InitWorkspaceMojo} (folded into {@code ws:scaffold-init} per
 * IKE-Network/ike-issues#393). Three initialization modes per
 * subproject:
 *
 * <ol>
 *   <li><b>Already cloned</b> — directory has {@code .git/}; fetch +
 *       rebase if clean, otherwise skip with a warning.</li>
 *   <li><b>Syncthing working tree</b> — directory exists but no
 *       {@code .git/}. Initializes git in-place: {@code git init},
 *       adds the remote, fetches, and resets to match the remote
 *       branch. This preserves file content synced from another
 *       machine.</li>
 *   <li><b>Fresh clone</b> — no directory; runs {@code git clone}.</li>
 * </ol>
 *
 * <p>Subprojects are initialized in topological (dependency) order.
 *
 * @see WorkspaceBootstrap for the "no workspace.yaml yet" half of
 *      {@code ws:scaffold-init}
 */
public final class SubprojectInitializer {

    private final WorkspaceGraph graph;
    private final File root;
    private final String workspaceName;
    private final Log log;

    /**
     * When {@code true}, reach a pinned {@code sha} with
     * {@code git reset --hard} (deterministic even over a dirty tree)
     * and <em>fail loud</em> if the pin cannot be reached; when
     * {@code false} (the interactive default), reach it with a lenient
     * {@code git checkout} (which refuses to clobber local work) and
     * merely warn on failure.
     *
     * <p>Added for IKE-Network/ike-issues#685: a stale clone that lacks
     * the pinned commit used to fail a bare {@code git checkout <sha>}
     * with {@code unable to read tree <sha>}, get swallowed to a
     * {@code log.warn}, and let CI build silently-stale code. In CI/force
     * mode this flag turns that swallow into a hard failure so a stale
     * pin can never ship. The accompanying {@code git fetch} (run in
     * <em>both</em> modes when the pin is missing locally) fixes the core
     * "unable to read tree" bug.
     *
     * <p><b>Invariant:</b> {@code git reset --hard} is only ever issued
     * when this flag is {@code true} — the default path never destroys
     * uncommitted work.
     */
    private final boolean resetToPin;

    /**
     * Bind an initializer to an already-loaded workspace, in the
     * interactive default mode (lenient {@code git checkout} for pins,
     * warn-only on failure).
     *
     * @param graph         the loaded workspace graph
     * @param root          the workspace root directory
     * @param workspaceName workspace artifactId, used in the report header
     * @param log           the mojo logger
     */
    public SubprojectInitializer(WorkspaceGraph graph, File root,
                                  String workspaceName, Log log) {
        this(graph, root, workspaceName, log, false);
    }

    /**
     * Bind an initializer to an already-loaded workspace, choosing how
     * a pinned {@code sha} is reached.
     *
     * @param graph         the loaded workspace graph
     * @param root          the workspace root directory
     * @param workspaceName workspace artifactId, used in the report header
     * @param log           the mojo logger
     * @param resetToPin    {@code true} (CI/force) to reach a pin with
     *                      {@code git reset --hard} and fail loud when it
     *                      cannot be reached; {@code false} (interactive)
     *                      to use a lenient {@code git checkout} and warn
     *                      only. See {@link #resetToPin} (ike-issues#685).
     */
    public SubprojectInitializer(WorkspaceGraph graph, File root,
                                  String workspaceName, Log log,
                                  boolean resetToPin) {
        this.graph = graph;
        this.root = root;
        this.workspaceName = workspaceName;
        this.log = log;
        this.resetToPin = resetToPin;
    }

    /** Outcome counters for one initialization pass. */
    public record Result(int cloned, int syncthing, int updated,
                          int skipped, int wrappers, int alreadyClean,
                          List<String[]> rows) {
        /** True when every declared subproject was already a healthy clone. */
        public boolean nothingChanged() {
            return cloned == 0 && syncthing == 0 && updated == 0 && wrappers == 0;
        }
    }

    /**
     * Run the full per-subproject initialization pass and emit the
     * workspace-level docs ({@code GOALS.md}, {@code WS-REFERENCE.md},
     * {@code CLAUDE.md}, {@code CLAUDE-&lt;name&gt;.md}).
     *
     * @return the per-subproject outcome counters
     * @throws MojoException if any clone/fetch step fails
     */
    public Result run() throws MojoException {
        Defaults defaults = graph.manifest().defaults();
        List<String> sorted = graph.topologicalSort();

        log.info("");
        log.info(workspaceName + " — Scaffold-Init");
        log.info("══════════════════════════════════════════════════════════════");
        log.info("  Target: all (" + sorted.size() + " components)");
        log.info("  Root:   " + root.getAbsolutePath());
        if (defaults.mavenVersion() != null) {
            log.info("  Maven:  " + defaults.mavenVersion() + " (default)");
        }
        log.info("");

        int cloned = 0;
        int syncthing = 0;
        int updated = 0;
        int skipped = 0;
        int wrappers = 0;
        int alreadyClean = 0;
        List<String[]> rows = new ArrayList<>();

        for (String name : sorted) {
            Subproject subproject = graph.manifest().subprojects().get(name);
            File dir = new File(root, name);
            File gitDir = new File(dir, ".git");

            if (gitDir.exists()) {
                if (VcsOperations.isClean(dir)) {
                    try {
                        String branch = VcsOperations.currentBranch(dir);
                        ReleaseSupport.exec(dir, log,
                                "git", "fetch", "origin", "--quiet");
                        ReleaseSupport.exec(dir, log,
                                "git", "rebase", "origin/" + branch, "--quiet");
                        log.info(Ansi.green("  ✓ ") + name
                                + " — updated (" + branch + ")");
                        updated++;
                        rows.add(new String[]{name, "updated",
                                subproject.repo() != null ? subproject.repo() : "—", "✓"});
                    } catch (MojoException e) {
                        log.warn(Ansi.yellow("  ⚠ ") + name
                                + " — fetch/rebase failed: " + e.getMessage());
                        rows.add(new String[]{name, "update-failed",
                                subproject.repo() != null ? subproject.repo() : "—",
                                e.getMessage()});
                        skipped++;
                    }
                } else {
                    String files = VcsOperations.unstagedFiles(dir);
                    log.warn(Ansi.yellow("  ⚠ ") + name
                            + " — skipped update (uncommitted changes: "
                            + files + ")");
                    skipped++;
                    rows.add(new String[]{name, "skipped",
                            subproject.repo() != null ? subproject.repo() : "—",
                            "uncommitted changes"});
                }
                if (ensureMavenWrapper(dir, subproject, defaults)) {
                    wrappers++;
                }
                ensureJvmConfig(dir);
                ensureClaudeNotes(dir.toPath(), name);
                writeSubprojectClaudeMd(dir.toPath(), subproject);
                checkoutSha(dir, subproject);
                alreadyClean++;
                continue;
            }

            String repo = subproject.repo();
            String branch = subproject.branch();

            if (repo == null || repo.isEmpty()) {
                log.warn(Ansi.yellow("  ⚠ ") + name + " — no repo URL, skipping");
                rows.add(new String[]{name, "skipped", "—", "no repo URL"});
                continue;
            }

            if (dir.exists()) {
                // Syncthing working tree — init git in-place
                log.info(Ansi.cyan("  ↻ ") + name
                        + " — initializing git in existing directory (Syncthing)");
                initSyncthingRepo(dir, repo, branch);
                installHooks(dir);
                if (ensureMavenWrapper(dir, subproject, defaults)) {
                    wrappers++;
                }
                ensureJvmConfig(dir);
                ensureClaudeNotes(dir.toPath(), name);
                writeSubprojectClaudeMd(dir.toPath(), subproject);
                checkoutSha(dir, subproject);
                syncthing++;
                rows.add(new String[]{name, "syncthing-init", repo, "✓"});
            } else {
                log.info(Ansi.cyan("  ↓ ") + name + " — cloning from " + repo);
                cloneRepo(root, name, repo, branch);
                File subprojectDir = new File(root, name);
                installHooks(subprojectDir);
                if (ensureMavenWrapper(subprojectDir, subproject, defaults)) {
                    wrappers++;
                }
                ensureJvmConfig(subprojectDir);
                ensureClaudeNotes(subprojectDir.toPath(), name);
                writeSubprojectClaudeMd(subprojectDir.toPath(), subproject);
                checkoutSha(subprojectDir, subproject);
                cloned++;
                rows.add(new String[]{name, "cloned", repo, "✓"});
            }
        }

        // Ensure Maven wrapper at the workspace root itself (the aggregator POM)
        if (ensureWorkspaceRootWrapper(root, defaults)) {
            wrappers++;
        }

        log.info("");
        StringBuilder summary = new StringBuilder();
        summary.append(cloned).append(" cloned");
        if (syncthing > 0) {
            summary.append(", ").append(syncthing).append(" Syncthing-initialized");
        }
        if (updated > 0) {
            summary.append(", ").append(updated).append(" updated");
        }
        if (skipped > 0) {
            summary.append(", ").append(skipped).append(" skipped");
        }
        if (wrappers > 0) {
            summary.append(", ").append(wrappers).append(" Maven wrappers installed/updated");
        }
        log.info("  Done: " + summary);
        log.info("");

        writeGoalCheatsheet(root.toPath());
        writeWorkspaceReference(root.toPath());
        ensureClaudeNotes(root.toPath(), workspaceName);
        writeWorkspaceClaudeMd(root.toPath(), graph);

        Result result = new Result(cloned, syncthing, updated, skipped,
                wrappers, alreadyClean, rows);
        WorkspaceReport.write(root.toPath(), WsGoal.SCAFFOLD_INIT.qualified(),
                buildInitMarkdownReport(result), log);

        PostMutationSync.refresh(root, log);
        return result;
    }

    // ── Git operations ──────────────────────────────────────────

    private void initSyncthingRepo(File dir, String repo, String branch)
            throws MojoException {
        ReleaseSupport.exec(dir, log, "git", "init");
        ReleaseSupport.exec(dir, log, "git", "remote", "add", "origin", repo);
        ReleaseSupport.exec(dir, log, "git", "fetch", "origin", branch);
        // Mixed reset: updates HEAD and index to match remote, keeps working tree
        ReleaseSupport.exec(dir, log,
                "git", "reset", "origin/" + branch);
    }

    private void cloneRepo(File root, String name, String repo, String branch)
            throws MojoException {
        ReleaseSupport.exec(root, log,
                "git", "clone", "-b", branch, repo, name);
    }

    /**
     * Honor a subproject's pinned {@code sha} from {@code workspace.yaml} —
     * in {@link #resetToPin} (CI / checkpoint-restore) mode only.
     *
     * <p>In the interactive default mode this is a <em>no-op beyond an
     * informational line</em>: ordinary bootstrap follows the entry's
     * {@code branch:} tip, attached (IKE-Network/ike-issues#949). The
     * {@code sha:} pin is checkpoint-managed state that can be
     * arbitrarily stale — historically a fresh clone was checked out
     * detached at the pin, and the next reactor build silently installed
     * feature-era artifacts from a workspace that read as "on main".
     *
     * <p>In {@code resetToPin} mode the pin is reached deterministically
     * (IKE-Network/ike-issues#685):
     * <ol>
     *   <li>No pin → nothing to do.</li>
     *   <li>Already at the pin (by prefix match) → nothing to do.</li>
     *   <li><b>Ensure the commit is present locally</b> — a stale clone
     *       may not have the pinned object yet; fetch when absent.</li>
     *   <li>{@code git reset --hard <sha>} — the branch stays checked
     *       out (attached) pointing at the pin, never a detached HEAD,
     *       and a WARN says so (#949).</li>
     * </ol>
     *
     * <p>On failure, CI/force mode <em>rethrows</em> — a stale pin must
     * never build.
     *
     * @param dir        the subproject working directory
     * @param subproject the subproject definition (its {@link Subproject#sha()}
     *                   is the pin)
     * @throws MojoException only in {@link #resetToPin} mode, when the pin
     *                       cannot be reached
     */
    private void checkoutSha(File dir, Subproject subproject) {
        if (subproject.sha() == null || subproject.sha().isBlank()) {
            return;
        }
        String sha = subproject.sha();
        try {
            String currentSha = ReleaseSupport.execCapture(dir,
                    "git", "rev-parse", "HEAD");
            if (currentSha.startsWith(sha) || sha.startsWith(currentSha)) {
                return; // already at the right commit
            }

            String sha8 = sha.substring(0, Math.min(8, sha.length()));

            // #949: ordinary (interactive) bootstrap follows the branch
            // tip, attached. The sha: pin records checkpoint state — it
            // can be arbitrarily stale (checkpoint-managed), and moving a
            // workspace onto it produces a detached HEAD at a possibly
            // feature-era commit whose next reactor build silently
            // installs stale artifacts. Pins are reached only in
            // resetToPin (CI / checkpoint-restore) mode.
            if (!resetToPin) {
                log.info("    pin " + sha8 + " recorded (checkpoint state)"
                        + " — working tree stays on the branch tip;"
                        + " pass -Dws.scaffold.resetToPin=true to reach pins");
                return;
            }

            // A stale clone may lack the pinned object. Fetch (read-only)
            // before trying to reach it — this is the core #685 fix.
            if (!commitPresent(dir, sha)) {
                log.info("    Fetching pinned commit " + sha8);
                ReleaseSupport.exec(dir, log,
                        "git", "fetch", "origin", "--quiet");
            }

            log.info("    Resetting to pinned SHA: " + sha8);
            ReleaseSupport.exec(dir, log,
                    "git", "reset", "--hard", sha);
            // #949(a): reaching a pin is loud — the branch now points at
            // checkpoint state, which may be behind origin.
            log.warn("    " + subproject.name() + ": branch reset to pinned "
                    + sha8 + " (checkpoint-restore semantics) — may be"
                    + " behind origin/" + subproject.branch());
        } catch (MojoException e) {
            if (resetToPin) {
                // CI/force mode must never build silently-stale code:
                // a pin that cannot be reached is a hard failure.
                throw new MojoException(
                        "Could not reset " + subproject.name()
                                + " to pinned SHA " + sha
                                + " (workspace.yaml). The pinned commit is "
                                + "not reachable even after fetching origin: "
                                + e.getMessage(), e);
            }
            log.warn("    Could not checkout SHA " + sha
                    + ": " + e.getMessage());
        }
    }

    /**
     * Whether the given commit object already exists in {@code dir}'s
     * local object store, tested with {@code git cat-file -e <sha>^{commit}}
     * (exit 0 ⇒ present). Used to decide whether a fetch is needed before
     * reaching a pin (IKE-Network/ike-issues#685).
     *
     * @param dir the subproject working directory
     * @param sha the candidate commit SHA
     * @return {@code true} if the commit is present locally, else {@code false}
     */
    private boolean commitPresent(File dir, String sha) {
        try {
            ReleaseSupport.execCapture(dir,
                    "git", "cat-file", "-e", sha + "^{commit}");
            return true;
        } catch (MojoException e) {
            return false;
        }
    }

    /**
     * Install a defensive post-checkout hook in the subproject's
     * {@code .git/hooks/} directory. Skips if a hook already exists
     * (don't overwrite custom hooks).
     */
    private void installHooks(File subprojectDir) {
        File hooksDir = new File(subprojectDir, ".git/hooks");
        File postCheckout = new File(hooksDir, "post-checkout");

        if (postCheckout.exists()) {
            log.debug("  Hook already exists: " + postCheckout);
            return;
        }

        try {
            if (!hooksDir.exists()) {
                hooksDir.mkdirs();
            }
            String hookScript = "#!/bin/sh\n"
                    + "# Installed by ws:scaffold-init — warns on direct branching.\n"
                    + "# Remove this file to disable the check.\n"
                    + "mvn -q " + WsGoal.CHECK_BRANCH.qualified()
                    + " 2>/dev/null\n";
            Files.writeString(postCheckout.toPath(), hookScript,
                    StandardCharsets.UTF_8);
            postCheckout.setExecutable(true);
            log.info("    Installed post-checkout hook");
        } catch (IOException e) {
            log.warn("    Could not install hook: " + e.getMessage());
        }
    }

    // ── Maven wrapper management ────────────────────────────────

    private static String resolveMavenVersion(Subproject subproject, Defaults defaults) {
        if (subproject.mavenVersion() != null) {
            return subproject.mavenVersion();
        }
        return defaults.mavenVersion();
    }

    private boolean ensureWorkspaceRootWrapper(File root, Defaults defaults) {
        String mavenVersion = defaults.mavenVersion();
        if (mavenVersion == null) {
            return false;
        }
        File pomFile = new File(root, "pom.xml");
        if (!pomFile.exists()) {
            return false;
        }
        return writeWrapperVersion(root.toPath(), mavenVersion, "workspace root");
    }

    private boolean ensureMavenWrapper(File subprojectDir, Subproject subproject,
                                        Defaults defaults) {
        String mavenVersion = resolveMavenVersion(subproject, defaults);
        if (mavenVersion == null) {
            return false;
        }
        File pomFile = new File(subprojectDir, "pom.xml");
        if (!pomFile.exists()) {
            return false;
        }
        return writeWrapperVersion(subprojectDir.toPath(), mavenVersion, null);
    }

    /**
     * Shared wrapper writer. Skips when the wrapper is already standard
     * and pinned to the requested version; otherwise rewrites the
     * properties file, creates {@code mvnw} / {@code mvnw.cmd} when
     * missing, and replaces the legacy custom wrapper in full.
     */
    private boolean writeWrapperVersion(Path dir, String mavenVersion,
                                         String rootLabel) {
        try {
            String currentVersion = MavenWrapper.readPinnedVersion(dir);
            boolean legacy = MavenWrapper.isLegacyWrapper(dir);

            if (currentVersion != null && !legacy
                    && mavenVersion.equals(currentVersion)) {
                log.debug("    " + (rootLabel != null ? rootLabel + " " : "")
                        + "Maven wrapper already at " + mavenVersion);
                return false;
            }

            String prefix = rootLabel != null ? "  ↻ " + rootLabel + " — " : "    ";
            if (legacy) {
                log.info(prefix + "replacing legacy Maven wrapper (Maven "
                        + mavenVersion + ")");
            } else if (currentVersion != null) {
                log.info(prefix + "updating Maven wrapper: " + currentVersion
                        + " → " + mavenVersion);
            } else {
                log.info((rootLabel != null ? "  + " + rootLabel + " — " : "    ")
                        + "installing Maven wrapper for Maven " + mavenVersion);
            }

            Path propsFile = dir.resolve(".mvn").resolve("wrapper")
                    .resolve("maven-wrapper.properties");
            MavenWrapper.writePropertiesFile(propsFile, mavenVersion);

            Path mvnw = dir.resolve("mvnw");
            if (legacy || !Files.exists(mvnw)) {
                MavenWrapper.writeMvnwScript(mvnw);
            }
            Path mvnwCmd = dir.resolve("mvnw.cmd");
            if (legacy || !Files.exists(mvnwCmd)) {
                MavenWrapper.writeMvnwCmdScript(mvnwCmd);
            }

            return true;
        } catch (IOException e) {
            log.warn("    Could not install Maven wrapper: " + e.getMessage());
            return false;
        }
    }

    private void ensureJvmConfig(File subprojectDir) {
        File pomFile = new File(subprojectDir, "pom.xml");
        if (!pomFile.exists()) {
            return;
        }

        try {
            Path mvnDir = subprojectDir.toPath().resolve(".mvn");
            Path jvmConfig = mvnDir.resolve("jvm.config");

            if (Files.exists(jvmConfig)) {
                return;
            }

            Files.createDirectories(mvnDir);
            String config = "-Dpolyglotimpl.AttachLibraryFailureAction=ignore\n";
            Files.writeString(jvmConfig, config, StandardCharsets.UTF_8);
            log.info("    Created .mvn/jvm.config");
        } catch (IOException e) {
            log.warn("    Could not create jvm.config: " + e.getMessage());
        }
    }

    // ── CLAUDE.md generation ────────────────────────────────────

    private void writeWorkspaceClaudeMd(Path wsRoot, WorkspaceGraph graph) {
        Path file = wsRoot.resolve("CLAUDE.md");
        try {
            Files.writeString(file, generateWorkspaceClaudeMd(workspaceName, graph),
                    StandardCharsets.UTF_8);
            log.info("  Updated CLAUDE.md");
        } catch (IOException e) {
            log.debug("Could not write workspace CLAUDE.md: " + e.getMessage());
        }
    }

    private void writeSubprojectClaudeMd(Path subprojectDir, Subproject subproject) {
        Path file = subprojectDir.resolve("CLAUDE.md");
        String generated = generateComponentClaudeMd(subproject);
        try {
            String existing = Files.exists(file)
                    ? Files.readString(file, StandardCharsets.UTF_8) : null;
            // Regenerate without destroying content the template does not own:
            // ike-managed regions are preserved verbatim (#920) and any
            // hand-authored ## sections are rescued to CLAUDE-<name>.md rather
            // than dropped (#917).
            ClaudeMdMerge.Result merged = ClaudeMdMerge.merge(existing, generated);
            if (!merged.rescued().isEmpty()) {
                rescueSectionsToNotes(subprojectDir, subproject.name(),
                        merged.rescued());
            }
            Files.writeString(file, merged.claudeMd(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Could not write CLAUDE.md for " + subproject.name()
                    + ": " + e.getMessage());
        }
    }

    /**
     * Preserve hand-authored sections that {@link #writeSubprojectClaudeMd}
     * would otherwise drop when it regenerates {@code CLAUDE.md}, by appending
     * them to {@code CLAUDE-<name>.md} — the file the scaffold never overwrites
     * (IKE-Network/ike-issues#917). Idempotent: a section already present in the
     * notes file (e.g. copied there by {@link #ensureClaudeNotes} on the first
     * migration) is not appended again, so repeat scaffold runs never duplicate
     * it. Warns loudly, naming each rescued section, so the move is never silent.
     *
     * @param dir      the subproject directory
     * @param name     the subproject name (drives the {@code CLAUDE-<name>.md} file)
     * @param sections the non-template sections to preserve
     */
    private void rescueSectionsToNotes(Path dir, String name,
            List<ClaudeMdMerge.Section> sections) {
        Path notesFile = dir.resolve("CLAUDE-" + name + ".md");
        try {
            String notes = Files.exists(notesFile)
                    ? Files.readString(notesFile, StandardCharsets.UTF_8) : "";
            StringBuilder appended = new StringBuilder(notes);
            List<String> rescuedTitles = new ArrayList<>();
            for (ClaudeMdMerge.Section section : sections) {
                if (notes.contains(section.text().strip())) {
                    continue;  // already preserved (e.g. by first-run migration)
                }
                if (appended.length() > 0
                        && appended.charAt(appended.length() - 1) != '\n') {
                    appended.append('\n');
                }
                appended.append("\n<!-- Preserved from CLAUDE.md by "
                                + "ws:scaffold-init "
                                + "(IKE-Network/ike-issues#917). -->\n")
                        .append(section.text().stripTrailing()).append('\n');
                rescuedTitles.add(section.title());
            }
            if (rescuedTitles.isEmpty()) {
                return;
            }
            Files.writeString(notesFile, appended.toString(),
                    StandardCharsets.UTF_8);
            log.warn(Ansi.yellow("  ⚠ ") + name + " — preserved "
                    + rescuedTitles.size() + " hand-authored CLAUDE.md "
                    + "section(s) into CLAUDE-" + name + ".md: "
                    + String.join(", ", rescuedTitles));
        } catch (IOException e) {
            log.warn("    Could not preserve hand-authored CLAUDE.md sections "
                    + "for " + name + ": " + e.getMessage());
        }
    }

    private void ensureClaudeNotes(Path dir, String name) {
        Path notesFile = dir.resolve("CLAUDE-" + name + ".md");
        if (Files.exists(notesFile)) {
            return;
        }
        try {
            Path existingClaudeMd = dir.resolve("CLAUDE.md");
            if (Files.exists(existingClaudeMd)) {
                String existing = Files.readString(existingClaudeMd, StandardCharsets.UTF_8);
                String migrated = "# " + name + " — Project Notes\n\n"
                        + "<!-- Migrated from CLAUDE.md by ws:scaffold-init.\n"
                        + "     This file is for hand-authored, project-specific information.\n"
                        + "     Commit this file to git. -->\n\n"
                        + existing;
                Files.writeString(notesFile, migrated, StandardCharsets.UTF_8);
                log.info("    Migrated CLAUDE.md → CLAUDE-" + name + ".md");
            } else {
                Files.writeString(notesFile, generateClaudeNotes(name),
                        StandardCharsets.UTF_8);
                log.info("    Created CLAUDE-" + name + ".md (template)");
            }
        } catch (IOException e) {
            log.debug("Could not create CLAUDE-" + name + ".md: " + e.getMessage());
        }
    }

    /**
     * Generate {@code CLAUDE.md} for the workspace root. Static so the
     * generator can be unit-tested without instantiating the mojo.
     *
     * @param wsName the workspace name
     * @param graph  the loaded workspace graph (unused in the body but
     *               kept so future per-subproject summaries can be
     *               threaded through without an API break)
     * @return the markdown content
     */
    public static String generateWorkspaceClaudeMd(String wsName, WorkspaceGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(wsName).append("\n\n");

        sb.append("""
                ## First Steps

                Run `mvn ws:scaffold-init` to clone components, then `mvn validate` to unpack
                full build standards into `.claude/standards/`.

                ## Build

                ```bash
                mvn clean verify -DskipTests -T 1C   # compile + javadoc
                mvn clean verify -T 1C               # full build with tests
                ```

                ## Key Conventions

                - Maven 4 with POM modelVersion 4.1.0
                - `<subprojects>` (not `<modules>`) for aggregation
                - All projects use `--enable-preview` (Java 25)
                - Parent: `network.ike.platform:ike-parent` (from ike-platform)

                ## Prohibited Patterns

                These are the most critical rules. Full standards are in `.claude/standards/MAVEN.md`
                after building.

                - **Never use `maven-antrun-plugin`** — use a proper Maven goal or `exec-maven-plugin`
                  with an external script
                - **Never use `build-helper-maven-plugin` for multi-execution property chaining** —
                  write a proper Maven goal in `ike-maven-plugin` instead
                - **Never embed shell commands inline in POM** — extract to a named script
                - **Never use `git add -A` or `git add .`** — stage specific files
                - **Never use raw git for workspace ops** (commit, push, checkout, merge, branch,
                  stash) — use the `ws:` goals (`ws:commit-publish`, `ws:push`, `ws:switch-publish`, …).
                  `workspace.yaml` `sha:` pins are checkpoint-managed — never hand-edit them. See
                  `.claude/standards/IKE-WORKSPACE.md`

                ## Project-Specific Notes

                """);

        sb.append("See `WS-REFERENCE.md` for complete workspace goal documentation.\n");
        sb.append("See `CLAUDE-").append(wsName)
                .append(".md` for workspace-specific information.\n");
        sb.append("See `.claude/standards/` (after `mvn validate`) for full build standards.\n");

        return sb.toString();
    }

    /**
     * Generate {@code CLAUDE.md} for a subproject directory. Static for
     * testability.
     *
     * @param subproject the subproject definition
     * @return the markdown content
     */
    public static String generateComponentClaudeMd(Subproject subproject) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(subproject.name()).append("\n\n");

        if (subproject.description() != null && !subproject.description().isBlank()) {
            sb.append(subproject.description().strip()).append("\n\n");
        }

        sb.append("""
                ## Build Standards

                Files in `.claude/standards/` are build artifacts unpacked from `ike-build-standards`. DO NOT edit or commit them. See the workspace root CLAUDE.md for details.

                ## Build

                ```bash
                mvn clean verify -DskipTests -T 1C
                ```

                ## Key Facts

                """);

        if (subproject.groupId() != null) {
            sb.append("- GroupId: `").append(subproject.groupId()).append("`\n");
        }
        if (subproject.version() != null) {
            sb.append("- Version: `").append(subproject.version()).append("`\n");
        }
        sb.append("- Uses `--enable-preview` (Java 25)\n");
        sb.append("- BOM: imports `dev.ikm.ike:ike-bom` for dependency version management\n");

        sb.append("""

                ## Prohibited Patterns

                - **Never use `maven-antrun-plugin`** — use a proper Maven goal or `exec-maven-plugin`
                - **Never use `build-helper-maven-plugin` for multi-execution property chaining** —
                  write a proper Maven goal in `ike-maven-plugin`
                - **Never embed shell commands inline in POM** — extract to a named script

                """);

        sb.append("See `.claude/standards/` (after `mvn validate`) for full standards.\n");
        sb.append("See `CLAUDE-").append(subproject.name())
                .append(".md` for project-specific notes.\n");

        return sb.toString();
    }

    /**
     * Generate the starter template for hand-authored project notes.
     * Static for testability.
     *
     * @param name the subproject or workspace name
     * @return the markdown content
     */
    public static String generateClaudeNotes(String name) {
        return "# " + name + " — Project Notes\n\n"
                + "<!-- This file is for hand-authored, project-specific information.\n"
                + "     It is created by ws:scaffold-init but never overwritten.\n"
                + "     Commit this file to git. -->\n\n"
                + "## Architecture\n\n"
                + "## Key Classes\n\n"
                + "## Testing Notes\n";
    }

    // ── GOALS.md and WS-REFERENCE.md generation ─────────────────

    private void writeGoalCheatsheet(Path wsRoot) {
        Path goalsFile = wsRoot.resolve("GOALS.md");
        try {
            Files.writeString(goalsFile, generateGoalCheatsheet(),
                    StandardCharsets.UTF_8);
            log.info("  Updated GOALS.md");
        } catch (IOException e) {
            log.debug("Could not write GOALS.md: " + e.getMessage());
        }
    }

    /**
     * Generate the workspace goal cheatsheet ({@code GOALS.md}). Static
     * so the content can be diffed independently of the mojo.
     *
     * @return the markdown content
     */
    public static String generateGoalCheatsheet() {
        return """
                # Workspace Goals

                All goals are available in IntelliJ's Maven tool window
                under **Plugins > ws** and **Plugins > ike**.

                ## Workspace Management

                | Goal | Description |
                |------|-------------|
                | `ws:scaffold-init` | Bootstrap a new workspace (no manifest yet) or clone declared subprojects (manifest present). Idempotent. |
                | `ws:add` | Add a subproject repo (prompts for URL) |
                | `ws:scaffold-publish` | Reconcile workspace state (versions, groupIds, scaffold conventions) — subsumes the retired scaffold-upgrade goals |
                | `ws:graph` | Print dependency graph (text or DOT format) |
                | `ws:stignore` | Generate Syncthing ignore rules |
                | `ws:remove` | Remove a subproject (prompts for name) |
                | `ws:help` | List all ws: goals with descriptions |

                ## Verification

                | Goal | Description |
                |------|-------------|
                | `ws:scaffold-draft` | Check manifest, parents, BOM cascade, VCS state (folds verify per #393) |
                | `ws:verify-convergence` | Transitive dependency convergence (slow) |
                | `ws:overview` | Workspace overview (manifest, graph, status, cascade) |
                | `ws:check-branch` | Warn when a subproject branch deviates from workspace.yaml |

                ## Version Alignment

                | Goal | Description |
                |------|-------------|
                | `ws:align-draft` | Preview inter-subproject POM version alignment (AlignmentReconciler) |
                | `ws:align-publish` | Apply POM version alignment |
                | `ws:reconcile-branches-draft` / `-publish` | Reconcile branch fields against on-disk state |
                | `ws:scaffold-draft -DupdateParent=true` | Preview parent-POM version cascade (along with other reconciliation) |
                | `ws:scaffold-publish -DparentVersion=<v>` | Pin parent to specific version and cascade |

                ## Branch Coordination

                | Goal | Description |
                |------|-------------|
                | `ws:switch-draft` | Preview switching subprojects to a coordinated branch |
                | `ws:switch-publish` | Switch subprojects to a coordinated branch |
                | `ws:update-feature-draft` | Preview rebasing a feature branch onto main |
                | `ws:update-feature-publish` | Rebase a feature branch onto main |

                ## Feature Branching

                | Goal | Description |
                |------|-------------|
                | `ws:feature-start-draft` | Preview feature branch |
                | `ws:feature-start-publish` | Create feature branch across components |
                | `ws:feature-finish-merge-draft` | Preview no-ff merge |
                | `ws:feature-finish-merge-publish` | No-ff merge (preserves history) |
                | `ws:feature-finish-squash-draft` | Preview squash merge |
                | `ws:feature-finish-squash-publish` | Squash merge (single commit) |
                | `ws:feature-abandon-draft` | Preview abandoning a feature branch |
                | `ws:feature-abandon-publish` | Delete feature branch across components |
                | `ws:feature-start-sibling-draft` | Preview a sibling-clone feature start (no clone) |
                | `ws:feature-start-sibling-publish` | Start a feature in a sibling clone beside the primary (isolated, Syncthing-safe) |

                ## Release & Checkpoint

                | Goal | Description |
                |------|-------------|
                | `ws:release-draft` | Preview what would be released |
                | `ws:release-publish` | Execute workspace release |
                | `ws:release-status` | Diagnose state of any in-flight workspace release |
                | `ws:checkpoint-draft` | Preview checkpoint (tag all subprojects) |
                | `ws:checkpoint-publish` | Execute checkpoint |
                | `ws:post-release` | Bump to next development version |
                | `ws:release-notes` | Generate release notes from GitHub milestone |

                ## VCS Bridge (Syncthing multi-machine)

                These run on a working set of 1..N — every subproject in a
                workspace, or a single repo with no `workspace.yaml`. Run
                `ws:help` for the per-goal single-repo vs. workspace breakdown.

                | Goal | Description |
                |------|-------------|
                | `ws:sync` | Pull then push across the working set (the daily sync op) |
                | `ws:commit-draft` | Preview what would be committed across the working set (read-only) |
                | `ws:commit-publish` | Commit across the working set (stages all by default; `-DstagedOnly` to opt out) |
                | `ws:pull` | Git pull --rebase across the working set |
                | `ws:push` | Push the working set (warns about uncommitted changes) |
                | `ws:report` | List the ws:* goal reports for the working set |

                ## Branch Cleanup

                | Goal | Description |
                |------|-------------|
                | `ws:cleanup-draft` | List merged/stale feature branches |
                | `ws:cleanup-publish` | Delete merged feature branches |

                ## Build Goals (ike:)

                | Goal | Description |
                |------|-------------|
                | `ike:release-draft` | Preview single-repo release |
                | `ike:release-publish` | Execute single-repo release |
                | `ike:generate-bom` | Generate BOM with resolved versions |
                | `ike:site-draft` | Preview site deployment + org-site registration |
                | `ike:site-publish` | Deploy project site and register it on the org site |
                | `ike:help` | List all ike: goals with descriptions |

                ---
                *Generated by `ws:scaffold-init`. See `ws:help` and `ike:help` for full details.*
                """;
    }

    private void writeWorkspaceReference(Path wsRoot) {
        Path refFile = wsRoot.resolve("WS-REFERENCE.md");
        try {
            Files.writeString(refFile, generateWorkspaceReference(),
                    StandardCharsets.UTF_8);
            log.info("  Updated WS-REFERENCE.md");
        } catch (IOException e) {
            log.debug("Could not write WS-REFERENCE.md: " + e.getMessage());
        }
    }

    /**
     * Generate the long-form workspace goal reference
     * ({@code WS-REFERENCE.md}). Static for testability.
     *
     * @return the markdown content
     */
    public static String generateWorkspaceReference() {
        return """
                # Workspace Goals Reference

                Complete reference for `ws:` goals. Quick overview: [GOALS.md](GOALS.md).

                ## Convention: -draft / -publish

                Most mutating goals come in pairs:
                - **-draft** (default) — preview mode, no changes made
                - **-publish** — executes the operation

                Example: `mvn ws:feature-start-draft -Dfeature=X` previews,
                `mvn ws:feature-start-publish -Dfeature=X` executes.

                ---

                ## Feature Branching

                ### Start: `ws:feature-start-draft` / `ws:feature-start-publish`

                Create a feature branch, qualify versions (e.g., `1.0.0-SNAPSHOT` becomes
                `1.0.0-CssUtils-SNAPSHOT`), cascade through BOMs and properties.

                | Parameter | Default | Description |
                |-----------|---------|-------------|
                | `feature` | prompted | Feature name (branch: `feature/<name>`) |
                | `targetBranch` | `main` | Source branch |
                | `skipVersion` | `false` | Skip version qualification |

                Fails if any subproject is on a different feature branch.
                Branches stay local (no auto-push).

                ### Finish: Three Strategies

                **`ws:feature-finish-squash-publish`** (recommended) — single commit on target.
                **`ws:feature-finish-merge-publish`** — no-ff merge, preserves history.

                All strategies:
                - Auto-generate commit message from per-subproject commit history
                - Fail-fast if any subproject has uncommitted changes
                - Strip branch-qualified versions back to base SNAPSHOT
                - Accept optional `-Dmessage="summary"` prepended to auto-generated message

                | Parameter | Default | Description |
                |-----------|---------|-------------|
                | `feature` | prompted | Feature name |
                | `targetBranch` | `main` | Merge target |
                | `keepBranch` | varies | Keep branch after merge |
                | `message` | auto | Optional human summary |

                ### Abandon: `ws:feature-abandon-draft`

                Delete a feature branch without merging.

                ### Sibling clone: `ws:feature-start-sibling-draft` / `-publish`

                Clone the whole workspace into a sibling directory
                (`<workspace>-<feature>`) on `feature/<name>` from inception,
                instead of switching the primary in place. Each component is a
                self-contained clone (`--reference --dissociate` against the
                primary, so large histories are cheap). The primary stays on
                its branch; the sibling is disposable (`rm -rf`) after merge.
                Isolates concurrent work — same-machine or across Syncthing
                machines — so two streams never stage each other's edits.
                Run `-draft` first for a plan + preflight (no clone), then
                `-publish` to create the sibling.

                | Parameter | Default | Description |
                |-----------|---------|-------------|
                | `feature` | prompted | Feature name (branch: `feature/<name>`) |
                | `skipVersion` | `false` | Skip version qualification |
                | `from` | current branch | Base branch to cut the sibling from; required to override when the primary is off the manifest base |

                Branches and clones stay local (no auto-push).

                ---

                ## Workspace Lifecycle

                | Goal | Description |
                |------|-------------|
                | `ws:scaffold-init` | Bootstrap a new workspace, or clone declared subprojects when workspace.yaml already exists. Safe to re-run. |
                | `ws:scaffold-draft` | Check manifest, BOM cascade, VCS state (folds verify per #393) |
                | `ws:verify-convergence` | Transitive dependency convergence (slow) |
                | `ws:overview` | Dashboard: manifest, graph, status, cascade |
                | `ws:scaffold-publish` | Apply workspace-level reconciliation (denormalized YAML field sync, scaffold conventions, etc.) |
                | `ws:pull` | Git pull --rebase across components |

                ---

                ## Release & Checkpoint

                | Goal | Description |
                |------|-------------|
                | `ws:release-draft` / `-publish` | Release release-pending components in topo order |
                | `ws:checkpoint-draft` / `-publish` | Tag all subprojects, record SHAs |
                | `ws:post-release` | Bump to next SNAPSHOT |
                | `ws:align-draft` / `-publish` | Align inter-subproject versions |
                | `ws:release-notes` | Generate notes from GitHub milestone |

                ---

                ## VCS Bridge (Syncthing)

                Run on a working set of 1..N — the whole workspace, or a single
                repo with no `workspace.yaml`. See `ws:help` for the per-goal
                single-repo vs. workspace breakdown.

                | Goal | Description |
                |------|-------------|
                | `ws:commit-publish` | Commit across the working set (`-Dpush=true -Dmessage="..."`) |
                | `ws:push` | Push the working set (warns about uncommitted changes) |
                | `ws:sync` | Pull then push across the working set |
                | `ws:cleanup-draft` / `-publish` | List/delete merged feature branches |

                ---

                ## Preflight Validation

                Workspace goals validate that all subproject working trees are clean
                before starting. If any subproject has uncommitted changes, the goal
                fails immediately with a list of affected repos and files — no partial
                modifications occur.

                **Goals with hard preflight (publish mode):**
                `release`, `align`, `scaffold`, `checkpoint`, `pull`, `switch`,
                `feature-start`, `feature-finish-*`, `feature-abandon`, `update-feature`

                **Draft goals:** warn about uncommitted changes that would block the
                corresponding `-publish` goal, but still run the preview.

                **`ws:commit-publish`:** skips VCS bridge catch-up when there are pending
                changes to commit, preventing branch-switch conflicts.

                **`ws:push`:** warns about uncommitted changes after pushing, and
                automatically sets upstream tracking for new branches.

                ## Troubleshooting

                **"Cannot X — uncommitted changes in:"** — Run `mvn ws:commit-publish -Dmessage="..."` to commit all pending changes, then retry.

                **Maven discovers `.teamcity/pom.xml`** — Add `-pl !.teamcity` to `.mvn/maven.config`.

                **Feature finish: "uncommitted changes"** — Run `mvn ws:commit-publish -Dmessage="..."` first.

                **Feature start: "already on feature branch"** — Finish/abandon the current feature first.

                **Plugin version mismatch** — After upgrading `ike-parent`, run `mvn ws:scaffold-init`.

                **Stale clones on CI** — `ws:scaffold-init` now fetches and rebases existing clones. Delete subproject directories manually only if rebase conflicts occur.

                ---
                *Generated by `ws:scaffold-init`. Regenerated when workspace plugin version changes.*
                """;
    }

    // ── Report ──────────────────────────────────────────────────

    private String buildInitMarkdownReport(Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.cloned()).append(" cloned, ")
                .append(result.syncthing()).append(" Syncthing-initialized, ")
                .append(result.updated()).append(" updated, ")
                .append(result.skipped()).append(" skipped");
        if (result.wrappers() > 0) {
            sb.append(", ").append(result.wrappers()).append(" Maven wrappers updated");
        }
        sb.append(".\n\n");
        sb.append("| Subproject | Action | URL | Status |\n");
        sb.append("|-----------|--------|-----|--------|\n");
        for (String[] row : result.rows()) {
            sb.append("| ").append(row[0])
                    .append(" | ").append(row[1])
                    .append(" | ").append(row[2])
                    .append(" | ").append(row[3])
                    .append(" |\n");
        }
        return sb.toString();
    }
}
