package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;

import network.ike.workspace.Subproject;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.VersionSupport;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shared logic for feature-finish goals (squash, merge, rebase).
 *
 * <p>Each strategy goal delegates to this class for validation,
 * version stripping, workspace.yaml updates, branch deletion,
 * and state file writing. The actual merge operation is performed
 * by the strategy goal itself.
 */
class FeatureFinishSupport {

    private FeatureFinishSupport() {}

    /**
     * Detect the feature branch name from subproject branches.
     * If all subprojects on a feature branch agree on the name,
     * returns it. Also checks the workspace root branch.
     *
     * @param root       workspace root directory
     * @param components subproject names to scan
     * @param mojo       the calling mojo (for gitBranch access)
     * @param log        Maven logger
     * @return the detected feature name (without "feature/" prefix)
     * @throws MojoException if no feature branch is detected
     */
    static String detectFeature(File root, List<String> components,
                                 AbstractWorkspaceMojo mojo, Log log)
            throws MojoException {
        Set<String> features = new TreeSet<>();

        // Check workspace root branch
        if (new File(root, ".git").exists()) {
            String wsBranch = mojo.gitBranch(root);
            if (wsBranch.startsWith("feature/")) {
                features.add(wsBranch.substring("feature/".length()));
            }
        }

        // Check subproject branches
        for (String name : components) {
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) continue;
            String branch = mojo.gitBranch(dir);
            if (branch.startsWith("feature/")) {
                features.add(branch.substring("feature/".length()));
            }
        }

        if (features.isEmpty()) {
            throw new MojoException(
                    "No components are on a feature branch. "
                    + "Specify -Dfeature=<name> or switch to a feature branch.");
        }

        if (features.size() == 1) {
            String detected = features.iterator().next();
            log.info("  Detected feature: " + detected);
            return detected;
        }

        // Multiple features — list them for the user
        throw new MojoException(
                "Multiple feature branches detected: " + features
                + ". Specify -Dfeature=<name> to disambiguate.");
    }

    /**
     * Validate that a subproject is eligible for feature-finish.
     *
     * <p>Checks three consistency requirements:
     * <ol>
     *   <li>The git working tree must be on the expected feature branch</li>
     *   <li>The workspace.yaml branch field must agree with git</li>
     *   <li>The working tree must have no uncommitted changes</li>
     * </ol>
     *
     * <p>A mismatch between git and workspace.yaml indicates that
     * branches were switched outside the {@code ws:} workflow, which
     * is not supported. The build fails with a diagnostic rather than
     * silently proceeding with inconsistent state.
     *
     * @param root       workspace root directory
     * @param name       subproject name
     * @param branchName expected git branch (e.g., "feature/my-work")
     * @param subproject  the workspace.yaml subproject record
     * @param mojo       the calling mojo (for git operations)
     * @return null if eligible, "MODIFIED" for uncommitted changes,
     *         or a descriptive skip/error reason string
     */
    static String validateComponent(File root, String name, String branchName,
                                     Subproject subproject,
                                     AbstractWorkspaceMojo mojo) {
        File dir = new File(root, name);
        File gitDir = new File(dir, ".git");

        if (!gitDir.exists()) {
            return "not cloned";
        }

        String currentBranch = mojo.gitBranch(dir);
        if (!currentBranch.equals(branchName)) {
            return "on " + currentBranch + ", not " + branchName;
        }

        // Verify workspace.yaml agrees with git — a mismatch means
        // branches were switched outside the ws: workflow.
        String yamlBranch = subproject.branch();
        if (yamlBranch != null && !yamlBranch.equals(currentBranch)) {
            return "INCONSISTENT: git is on " + currentBranch
                    + " but workspace.yaml says " + yamlBranch
                    + " — resolve with ws:feature-start or update workspace.yaml";
        }

        String status = mojo.gitStatus(dir);
        if (!status.isEmpty()) {
            return "MODIFIED";  // Caller should throw
        }

        return null;
    }

    /**
     * Generate a structured commit message by aggregating per-subproject
     * commit history from the feature branch.
     */
    static String generateFeatureMessage(File root, List<String> components,
                                          String branchName, String targetBranch,
                                          String userMessage, Log log) {
        var sb = new StringBuilder();
        if (userMessage != null && !userMessage.isBlank()) {
            sb.append(userMessage).append("\n\n");
        }
        sb.append(branchName).append("\n");

        for (String name : components) {
            File dir = new File(root, name);
            try {
                List<String> commits = VcsOperations.commitLog(
                        dir, targetBranch, branchName);
                if (commits.isEmpty()) continue;
                sb.append("\n## ").append(name)
                  .append(" (").append(commits.size()).append(" commit")
                  .append(commits.size() == 1 ? "" : "s").append(")\n");
                for (String line : commits) {
                    String msg = line.contains(" ")
                            ? line.substring(line.indexOf(' ') + 1) : line;
                    sb.append("- ").append(msg).append("\n");
                }
            } catch (MojoException e) {
                log.debug("Could not get log for " + name + ": " + e.getMessage());
            }
        }

        // Workspace repo changes
        try {
            List<String> wsCommits = VcsOperations.commitLog(
                    root, targetBranch, branchName);
            if (!wsCommits.isEmpty()) {
                sb.append("\n## workspace (").append(wsCommits.size())
                  .append(" commit").append(wsCommits.size() == 1 ? "" : "s")
                  .append(")\n");
                for (String line : wsCommits) {
                    String msg = line.contains(" ")
                            ? line.substring(line.indexOf(' ') + 1) : line;
                    sb.append("- ").append(msg).append("\n");
                }
            }
        } catch (MojoException e) {
            log.debug("Could not get workspace log: " + e.getMessage());
        }

        return sb.toString().stripTrailing();
    }

    /**
     * Strip branch-qualified version back to base SNAPSHOT.
     * Returns the base version, or null if no stripping was needed.
     */
    static String stripBranchVersion(File dir, Subproject subproject,
                                      String branchName, Log log)
            throws MojoException {
        // Read actual version from POM on disk — workspace.yaml may be stale
        // if the branch update commit failed (#83).
        String currentVersion = readCurrentVersion(dir, log);
        String qualifier = qualifierFromBranch(branchName);
        if (currentVersion == null
                || !containsBranchQualifier(currentVersion, qualifier)) {
            return null;
        }

        String baseVersion = stripQualifier(currentVersion, qualifier);

        log.info("    version: " + currentVersion + " → " + baseVersion);
        setAllVersions(dir, currentVersion, baseVersion, log);

        // Also strip any other branch-qualified versions in the POM tree
        // (BOM imports, version properties, etc. set by cascadeBomProperties
        // and cascadeBomImports during feature-start).
        stripAllBranchQualifiedVersions(dir, qualifier, log);

        ReleaseSupport.exec(dir, log, "git", "add", "-A");
        ReleaseSupport.exec(dir, log, "git", "commit", "-m",
                "merge-prep: strip branch qualifier → " + baseVersion);

        return baseVersion;
    }

    /**
     * Strip branch-qualified version in bare mode.
     */
    static String stripBranchVersionBare(File dir, String branchName, Log log)
            throws MojoException {
        File pom = new File(dir, "pom.xml");
        if (!pom.exists()) return null;

        String currentVersion;
        try {
            currentVersion = ReleaseSupport.readPomVersion(pom);
        } catch (MojoException e) {
            return null;
        }

        String qualifier = qualifierFromBranch(branchName);
        if (currentVersion == null
                || !containsBranchQualifier(currentVersion, qualifier)) {
            return null;
        }

        String baseVersion = stripQualifier(currentVersion, qualifier);

        log.info("  Version: " + currentVersion + " → " + baseVersion);
        setAllVersions(dir, currentVersion, baseVersion, log);
        stripAllBranchQualifiedVersions(dir, qualifier, log);
        ReleaseSupport.exec(dir, log, "git", "add", "-A");
        ReleaseSupport.exec(dir, log, "git", "commit", "-m",
                "merge-prep: strip branch qualifier → " + baseVersion);

        return baseVersion;
    }

    /**
     * Delete feature branch locally and remotely.
     */
    static void deleteBranch(File dir, Log log, String branchName)
            throws MojoException {
        VcsOperations.deleteBranch(dir, log, branchName);
        log.info("    deleted local branch: " + branchName);

        Optional<String> remoteSha = VcsOperations.remoteSha(dir, "origin", branchName);
        if (remoteSha.isPresent()) {
            VcsOperations.deleteRemoteBranch(dir, log, "origin", branchName);
            log.info("    deleted remote branch: origin/" + branchName);
        } else {
            log.info("    remote branch origin/" + branchName
                    + " does not exist (never pushed) — skipping");
        }
    }

    /**
     * Clean up feature branch snapshot sites.
     */
    static void cleanFeatureSites(File root, List<String> components,
                                    String branchName, Log log) {
        String featurePath = ReleaseSupport.branchToSitePath(branchName);
        for (String name : components) {
            String siteDisk = ReleaseSupport.siteDiskPath(
                    name, "snapshot", featurePath);
            try {
                ReleaseSupport.cleanRemoteSiteDir(
                        new File(root, name), log, siteDisk);
            } catch (MojoException e) {
                log.debug("No snapshot site to clean for " + name
                        + ": " + e.getMessage());
            }
        }
    }

    /**
     * Update workspace.yaml branch fields back to targetBranch and commit.
     */
    static void updateWorkspaceYaml(Path manifestPath, List<String> components,
                                      String targetBranch, String feature,
                                      Log log) {
        try {
            Map<String, String> updates = new LinkedHashMap<>();
            for (String name : components) {
                updates.put(name, targetBranch);
            }
            ManifestWriter.updateBranches(manifestPath, updates);
            log.info("  Updated workspace.yaml branches → " + targetBranch);

            File wsRoot = manifestPath.getParent().toFile();
            File wsGit = new File(wsRoot, ".git");
            if (wsGit.exists()) {
                ReleaseSupport.exec(wsRoot, log, "git", "add", "workspace.yaml");
                if (VcsOperations.hasStagedChanges(wsRoot)) {
                    ReleaseSupport.exec(wsRoot, log, "git", "commit", "-m",
                            "workspace: restore branches to " + targetBranch
                                    + " after feature/" + feature);
                }
            }
        } catch (IOException | MojoException e) {
            log.warn("  Could not update workspace.yaml: " + e.getMessage());
        }
    }

    /**
     * Merge the workspace aggregator repo from the feature branch to the
     * target branch. Mirrors the per-subproject merge: checkout target,
     * no-ff merge, push.
     */
    static void mergeWorkspaceRepo(Path manifestPath, String branchName,
                                     String targetBranch, boolean keepBranch,
                                     boolean push, Log log)
            throws MojoException {
        File wsRoot = manifestPath.getParent().toFile();
        if (!new File(wsRoot, ".git").exists()) return;

        String wsBranch = null;
        try {
            wsBranch = VcsOperations.currentBranch(wsRoot);
        } catch (MojoException e) {
            return;
        }

        if (wsBranch != null && wsBranch.equals(branchName)) {
            log.info("  Merging workspace repo: " + branchName + " → " + targetBranch);
            VcsOperations.checkout(wsRoot, log, targetBranch);
            VcsOperations.mergeNoFf(wsRoot, log, branchName,
                    "Merge " + branchName + " into " + targetBranch);
            if (push) {
                VcsOperations.pushIfRemoteExists(wsRoot, log, "origin", targetBranch);
            }
        }

        if (!keepBranch) {
            try {
                deleteBranch(wsRoot, log, branchName);
            } catch (MojoException e) {
                log.warn("  Could not delete ws branch: " + e.getMessage());
            }
        }

        // Write state file for ws
        if (VcsState.isIkeManaged(wsRoot.toPath())) {
            VcsOperations.writeVcsState(wsRoot, VcsState.Action.FEATURE_FINISH);
        }
    }

    /**
     * Scan for stale feature branches across all subprojects and offer
     * interactive cleanup after a successful feature-finish.
     *
     * <p>Stale branches are feature branches that are fully merged into the
     * target branch and are not the branch just finished. A 30-second
     * interactive timeout defaults to "no" (safe for unattended runs).
     *
     * @param root         workspace root directory
     * @param components   subproject names to scan
     * @param finishedBranch the branch that was just finished (excluded from stale list)
     * @param targetBranch the merge target (e.g., "main")
     * @param log          Maven logger
     */
    static void promptStaleBranchCleanup(File root, List<String> components,
                                           String finishedBranch, String targetBranch,
                                           Log log) {
        // Collect stale branches across all subprojects
        Map<String, List<String>> staleBranches = new LinkedHashMap<>();
        for (String name : components) {
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) continue;

            List<String> merged = VcsOperations.mergedBranches(
                    dir, targetBranch, "feature/");
            List<String> stale = merged.stream()
                    .filter(b -> !b.equals(finishedBranch))
                    .toList();
            if (!stale.isEmpty()) {
                staleBranches.put(name, stale);
            }
        }

        if (staleBranches.isEmpty()) return;

        // Collect unique branch names with last-commit dates
        Set<String> uniqueBranches = new TreeSet<>();
        staleBranches.values().forEach(uniqueBranches::addAll);

        log.info("");
        log.info("  Stale feature branches (merged into " + targetBranch + "):");
        for (String branch : uniqueBranches) {
            // Get date from first subproject that has it
            String date = "unknown";
            for (var entry : staleBranches.entrySet()) {
                if (entry.getValue().contains(branch)) {
                    date = VcsOperations.branchLastCommitDate(
                            new File(root, entry.getKey()), branch);
                    break;
                }
            }
            int subprojectCount = (int) staleBranches.values().stream()
                    .filter(list -> list.contains(branch))
                    .count();
            log.info("    " + branch + " (" + subprojectCount
                    + " subproject" + (subprojectCount == 1 ? "" : "s")
                    + ", last commit: " + date + ")");
        }

        // Prompt for deletion with 30-second timeout
        log.info("");
        String prompt = "  Delete " + uniqueBranches.size() + " stale branch"
                + (uniqueBranches.size() == 1 ? "" : "es")
                + "? [y/N] (30s timeout → No): ";

        boolean delete = promptWithTimeout(prompt, false, 30, log);

        if (delete) {
            for (var entry : staleBranches.entrySet()) {
                File dir = new File(root, entry.getKey());
                for (String branch : entry.getValue()) {
                    try {
                        VcsOperations.deleteBranch(dir, log, branch);
                        log.info("    deleted: " + entry.getKey() + "/" + branch);
                    } catch (MojoException e) {
                        log.warn("    could not delete " + entry.getKey()
                                + "/" + branch + ": " + e.getMessage());
                    }
                }
            }
            log.info("  Stale branches cleaned up.");
        } else {
            log.info("  Skipping stale branch cleanup.");
        }
    }

    /**
     * Prompt with a timeout that returns the default if no input arrives.
     */
    private static boolean promptWithTimeout(String prompt, boolean defaultValue,
                                               int timeoutSeconds, Log log) {
        java.io.Console console = System.console();
        if (console == null) {
            // Non-interactive — use default
            return defaultValue;
        }

        var future = new java.util.concurrent.FutureTask<>(() -> {
            String input = console.readLine(prompt);
            return input != null && (input.trim().equalsIgnoreCase("y")
                    || input.trim().equalsIgnoreCase("yes"));
        });

        Thread inputThread = Thread.ofVirtual().start(future);
        try {
            return future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.info("  (timeout — using default: "
                    + (defaultValue ? "Yes" : "No") + ")");
            future.cancel(true);
            return defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    // ── Post-merge qualifier guard ────────────────────────────────

    /**
     * Verify that no branch-qualified versions remain in the subproject's
     * POM tree after merging to the target branch. If any are found, they
     * are auto-stripped and committed as a fixup.
     *
     * <p>This guards against contamination when the merge-prep strip was
     * incomplete (e.g., some POMs were missed) or when commits were
     * cherry-picked outside the {@code ws:} workflow.
     *
     * @param dir        the subproject directory (now on the target branch)
     * @param branchName the feature branch that was just merged
     * @param log        Maven logger
     * @throws MojoException if POM files cannot be scanned or committed
     */
    static void verifyAndFixQualifiers(File dir, String branchName, Log log)
            throws MojoException {
        String qualifier = qualifierFromBranch(branchName);
        if (qualifier == null) return;

        List<File> allPoms = ReleaseSupport.findPomFiles(dir);
        List<String> contaminated = new ArrayList<>();

        for (File pom : allPoms) {
            try {
                String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
                if (content.contains("-" + qualifier + "-")) {
                    contaminated.add(dir.toPath().relativize(pom.toPath()).toString());
                }
            } catch (IOException e) {
                log.warn("    Could not read " + pom + ": " + e.getMessage());
            }
        }

        if (contaminated.isEmpty()) return;

        log.warn("    Post-merge guard: " + contaminated.size()
                + " POM(s) still contain branch qualifier '" + qualifier + "'");
        for (String path : contaminated) {
            log.warn("      " + path);
        }

        // Auto-strip and commit
        stripAllBranchQualifiedVersions(dir, qualifier, log);

        // Also strip the artifact version if still qualified
        String currentVersion = readCurrentVersion(dir, log);
        if (currentVersion != null
                && containsBranchQualifier(currentVersion, qualifier)) {
            String baseVersion = stripQualifier(currentVersion, qualifier);
            setAllVersions(dir, currentVersion, baseVersion, log);
            log.info("    Auto-fixed: " + currentVersion + " → " + baseVersion);
        }

        ReleaseSupport.exec(dir, log, "git", "add", "-A");
        String status = ReleaseSupport.execCapture(dir,
                "git", "status", "--porcelain");
        if (!status.isEmpty()) {
            ReleaseSupport.exec(dir, log, "git", "commit", "-m",
                    "fixup: strip residual branch qualifier '"
                            + qualifier + "' after merge");
            log.info("    Auto-fixed and committed qualifier cleanup");
        }
    }

    /**
     * Scan a subproject directory for any version strings containing a
     * branch qualifier. Returns the list of POM-relative paths that
     * are contaminated, or an empty list if clean.
     *
     * <p>This is a read-only check suitable for use in verification
     * goals or draft modes.
     *
     * @param dir        the subproject directory
     * @param qualifier  the branch qualifier to search for
     * @return list of relative POM paths containing the qualifier
     */
    static List<String> findQualifierContamination(File dir, String qualifier) {
        List<String> contaminated = new ArrayList<>();
        if (qualifier == null) return contaminated;

        List<File> allPoms;
        try {
            allPoms = ReleaseSupport.findPomFiles(dir);
        } catch (MojoException e) {
            return contaminated;
        }

        for (File pom : allPoms) {
            try {
                String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
                if (content.contains("-" + qualifier + "-")) {
                    contaminated.add(dir.toPath().relativize(pom.toPath()).toString());
                }
            } catch (IOException ignored) {
            }
        }

        return contaminated;
    }

    // ── Internal helpers ─────────────────────────────────────────

    private static String readCurrentVersion(File dir, Log log) {
        try {
            return ReleaseSupport.readPomVersion(new File(dir, "pom.xml"));
        } catch (MojoException e) {
            log.warn("    Could not read version from " + dir.getName()
                    + "/pom.xml: " + e.getMessage());
            return null;
        }
    }

    /**
     * Derive the version qualifier from a feature branch name.
     * For example, {@code "feature/search-provider-diagnostics"}
     * yields {@code "search-provider-diagnostics"} via
     * {@link VersionSupport#safeBranchName(String)}.
     *
     * <p>This is intentionally derived from the branch name rather
     * than parsed from the version string, because version strings
     * may use non-semver schemes (date-based, single-segment, etc.)
     * where structural parsing of the numeric/qualifier boundary
     * is ambiguous.
     *
     * @param branchName the full branch name (e.g., "feature/my-work")
     * @return the qualifier as it appears in version strings
     */
    private static String qualifierFromBranch(String branchName) {
        return VersionSupport.safeBranchName(branchName);
    }

    /**
     * Check whether a version string contains the given branch qualifier.
     * Matches versions of any numeric depth (single-segment, semver,
     * or otherwise) — never assumes a specific version scheme.
     *
     * @param version   version string to test
     * @param qualifier the branch qualifier to look for
     * @return true if the version is a SNAPSHOT containing the qualifier
     */
    private static boolean containsBranchQualifier(String version, String qualifier) {
        return version != null
                && version.endsWith("-SNAPSHOT")
                && version.contains("-" + qualifier + "-");
    }

    /**
     * Strip the branch qualifier from a version, returning the base SNAPSHOT.
     *
     * @param version   branch-qualified version
     * @param qualifier the qualifier to strip
     * @return base SNAPSHOT version
     */
    private static String stripQualifier(String version, String qualifier) {
        return version.replace("-" + qualifier + "-SNAPSHOT", "-SNAPSHOT");
    }

    /**
     * Scan all POM files in a subproject for version strings containing
     * the given branch qualifier and strip them back to base SNAPSHOT.
     * This reverses the cascade done by feature-start (BOM properties,
     * BOM imports, version properties).
     *
     * <p>Uses {@link PomModel} (Maven 4 model API) to identify
     * qualified versions in properties, dependencies, and parent
     * blocks, then applies corrections via {@link PomRewriter}
     * (OpenRewrite LST) for lossless edits. Only versions containing
     * the specific branch qualifier are modified — other non-numeric
     * suffixes (e.g., {@code rc1}, {@code beta}) are left untouched.
     *
     * <p>No assumption is made about the numeric version scheme:
     * single-segment ({@code 92}), two-segment ({@code 1.0}), semver
     * ({@code 3.0.7}), and deeper schemes all work identically.
     *
     * @param dir       the subproject directory containing POM files
     * @param qualifier the branch qualifier to strip (e.g., "search-provider-diagnostics")
     * @param log       Maven logger
     * @throws MojoException if POM files cannot be located
     */
    private static void stripAllBranchQualifiedVersions(File dir,
                                                         String qualifier,
                                                         Log log)
            throws MojoException {
        if (qualifier == null) return;

        List<File> allPoms = ReleaseSupport.findPomFiles(dir);

        for (File pom : allPoms) {
            try {
                PomModel model = PomModel.parse(pom.toPath());
                String content = model.content();
                String updated = content;

                // Strip qualified properties
                for (var entry : model.properties().entrySet()) {
                    String value = entry.getValue();
                    if (containsBranchQualifier(value, qualifier)) {
                        String base = stripQualifier(value, qualifier);
                        updated = PomModel.updateProperty(
                                updated, entry.getKey(), base);
                        log.debug("    property " + entry.getKey()
                                + ": " + value + " → " + base
                                + " in " + pom.getName());
                    }
                }

                // Strip qualified dependencies (including BOM imports)
                for (var dep : model.allDependencies()) {
                    String version = dep.getVersion();
                    if (containsBranchQualifier(version, qualifier)) {
                        String base = stripQualifier(version, qualifier);
                        updated = PomModel.updateDependencyVersion(
                                updated, dep.getGroupId(),
                                dep.getArtifactId(), base);
                        log.debug("    dependency " + dep.getGroupId()
                                + ":" + dep.getArtifactId()
                                + ": " + version + " → " + base
                                + " in " + pom.getName());
                    }
                }

                // Strip qualified parent version
                var parent = model.parent();
                if (parent != null
                        && containsBranchQualifier(parent.getVersion(), qualifier)) {
                    String base = stripQualifier(parent.getVersion(), qualifier);
                    updated = PomModel.updateParentVersion(
                            updated, parent.getArtifactId(), base);
                    log.debug("    parent " + parent.getArtifactId()
                            + ": " + parent.getVersion() + " → " + base
                            + " in " + pom.getName());
                }

                if (!updated.equals(content)) {
                    Files.writeString(pom.toPath(), updated, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                log.warn("    Could not strip versions in " + pom + ": "
                        + e.getMessage());
            }
        }
    }

    static void setAllVersions(File dir, String oldVersion, String newVersion,
                                 Log log) throws MojoException {
        File pom = new File(dir, "pom.xml");
        ReleaseSupport.setPomVersion(pom, oldVersion, newVersion);

        List<File> allPoms = ReleaseSupport.findPomFiles(dir);
        for (File subPom : allPoms) {
            if (subPom.equals(pom)) continue;
            try {
                String content = Files.readString(subPom.toPath(), StandardCharsets.UTF_8);
                if (content.contains("<version>" + oldVersion + "</version>")) {
                    String updated = content.replace(
                            "<version>" + oldVersion + "</version>",
                            "<version>" + newVersion + "</version>");
                    Files.writeString(subPom.toPath(), updated, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                log.warn("    Could not update " + subPom + ": " + e.getMessage());
            }
        }
    }
}
