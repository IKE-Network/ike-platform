package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.ws.reconcile.FeatureVersionReconciler;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.workspace.Subproject;
import network.ike.workspace.Dependency;
import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestException;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.PublishedArtifactSet;
import network.ike.workspace.VersionSupport;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Add a subproject repository to an existing workspace.
 *
 * <p>Given a git URL, this goal:
 * <ol>
 *   <li>Clones the repository into the workspace</li>
 *   <li>Derives the subproject name from the URL (or accepts
 *       {@code -Dsubproject=<name>})</li>
 *   <li>Scans the POM to derive groupId and inter-subproject
 *       dependencies (matching dependency/parent groupIds against
 *       already-registered workspace subprojects)</li>
 *   <li>Appends a subproject entry to workspace.yaml</li>
 *   <li>Adds a file-activated profile to the reactor POM</li>
 *   <li>Re-scans existing subprojects to discover any that depend
 *       on the newly added subproject (backward resolution)</li>
 * </ol>
 *
 * <p>The subproject name is derived from the last path segment of the
 * URL with {@code .git} stripped. For example,
 * {@code https://github.com/ikmdev/tinkar-core.git} becomes
 * {@code tinkar-core}.
 *
 * <pre>{@code
 * mvn ws:add -Drepo=https://github.com/ikmdev/tinkar-core.git
 * mvn ws:add -Drepo=https://github.com/ikmdev/rocks-kb.git
 * mvn ws:add -Drepo=https://github.com/ikmdev/komet.git
 * }</pre>
 *
 * @see WsScaffoldInitMojo for creating a new workspace or cloning all subprojects
 */
@Mojo(name = "add", projectRequired = false, aggregator = true)
public class WsAddMojo extends AbstractWorkspaceMojo {

    /**
     * Git repository URL. Prompted interactively if omitted.
     */
    @Parameter(property = "repo")
    private String repo;

    /**
     * Subproject name override. If omitted, derived from the repo URL
     * (last path segment minus {@code .git}).
     */
    @Parameter(property = "subproject")
    private String subproject;

    /**
     * Short description of the subproject.
     */
    @Parameter(property = "description")
    private String description;

    /**
     * Branch to track. If omitted, the new subproject is placed on the
     * workspace repo's current git branch (the workspace's active branch).
     * If the workspace repo has no git state, falls back to the manifest's
     * {@code defaults.branch}.
     *
     * <p>Passing an explicit {@code -Dbranch=} value that disagrees with
     * the workspace repo's current branch is rejected — heterogeneous
     * branch state across a workspace is not a supported configuration
     * (see ike-issues#286).
     */
    @Parameter(property = "branch")
    private String branch;

    /**
     * Maven groupId for the subproject. If omitted, left as
     * a placeholder in workspace.yaml.
     */
    @Parameter(property = "groupId")
    private String groupId;

    /**
     * Maven version for the subproject. If omitted, derived from
     * the subproject's root POM. Written to workspace.yaml so that
     * {@code ws:feature-start} can branch-qualify it.
     */
    @Parameter(property = "version")
    private String version;

    /**
     * Skip cloning — register the subproject in workspace.yaml without
     * cloning. Dependencies cannot be derived without a POM to scan,
     * so they will be empty. Use {@code ws:scaffold-init} to clone later.
     */
    @Parameter(property = "skipClone", defaultValue = "false")
    private boolean skipClone;

    /** Derived dependency with optional version-property name. */
    record DerivedDep(String subproject, String versionProperty) {}

    /** Creates this goal instance. */
    public WsAddMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        repo = requireParam(repo, "repo", "Git repository URL");

        // Resolve workspace root
        Path wsDir = findWorkspaceRoot();
        Path manifestPath = wsDir.resolve("workspace.yaml");
        Path pomPath = wsDir.resolve("pom.xml");

        if (!Files.exists(manifestPath)) {
            throw new MojoException(
                    "No workspace.yaml found in " + wsDir
                    + ". Run " + WsGoal.SCAFFOLD_INIT.qualified() + " first.");
        }

        // Resolve the target branch up front: workspace repo HEAD is
        // authoritative (ike-issues#286). The new subproject must land
        // on the same branch as the rest of the workspace.
        branch = resolveBranch(wsDir, manifestPath);

        // Derive subproject name from URL if not specified
        if (subproject == null || subproject.isBlank()) {
            subproject = deriveSubprojectName(repo);
        }
        // Validate via SubprojectName (#295). The derived name comes
        // from a git URL's last path segment which usually conforms,
        // but explicit -Dsubproject= and odd repo URLs can fail.
        validateSubprojectName(subproject);

        // Check if already registered — if so, re-derive and update
        // rather than appending a duplicate (idempotent behavior)
        boolean alreadyRegistered = false;
        try {
            Manifest existing = ManifestReader.read(manifestPath);
            alreadyRegistered = existing.subprojects().containsKey(subproject);
        } catch (ManifestException e) {
            // Manifest may be empty/malformed on first add — continue
        }

        if (description == null || description.isBlank()) {
            description = subproject + " subproject.";
        }

        // Clone so we can scan the POM for groupId and dependencies
        Path subprojectDir = wsDir.resolve(subproject);
        boolean cloned = false;
        List<DerivedDep> derivedDeps = null;

        if (!skipClone && !Files.exists(subprojectDir)) {
            cloneSubproject(wsDir);
            cloned = true;
        }

        String detectedParent = null;

        if (Files.exists(subprojectDir.resolve("pom.xml"))) {
            // Derive groupId from POM if not explicitly specified
            if (groupId == null || groupId.isBlank()) {
                groupId = deriveGroupId(subprojectDir);
            }

            // Derive version from POM if not explicitly specified
            if (version == null || version.isBlank()) {
                try {
                    version = ReleaseSupport.readPomVersion(
                            subprojectDir.resolve("pom.xml").toFile());
                } catch (MojoException e) {
                    // Non-fatal — version will be null in manifest
                }
            }

            // Branch-qualify on a feature branch so the added subproject
            // is isolated like the rest (IKE-Network/ike-issues#574).
            // No-op on main; scaffold-publish's FeatureVersionReconciler
            // self-heals if this is skipped, so failures are non-fatal.
            try {
                version = qualifyForBranch(subprojectDir, version, branch);
            } catch (IOException e) {
                getLog().warn("  Could not branch-qualify version: "
                        + e.getMessage());
            }

            // Detect parent POM — record parent: only when the POM's
            // <parent> matches a workspace subproject by full GA (groupId
            // AND artifactId). An external parent that merely shares a
            // groupId with a sibling (e.g. network.ike.platform:ike-parent
            // alongside a network.ike.platform:* sibling) must not be
            // recorded as that sibling — ike-issues#565.
            PomParentSupport.ParentInfo parentInfo = null;
            try {
                parentInfo = PomParentSupport.readParent(
                        subprojectDir.resolve("pom.xml"));
                detectedParent = detectWorkspaceParent(
                        wsDir, manifestPath, parentInfo);
            } catch (Exception e) {
                // Non-fatal — parent detection is best-effort
            }

            // #324 parent coherence: if the new subproject's
            // <parent> GA matches the workspace aggregator's own
            // <parent> GA, enforce two rules:
            //   1. Version coherence — same version as workspace
            //   2. Cycle prevention — empty <relativePath/>
            // Warn (not fail) at add time so an operator who hits
            // either violation gets the heads-up before running
            // mvn install. The matching ws:scaffold-draft check
            // (which folds verify per #393) is authoritative —
            // failure mode there is what blocks a release.
            if (parentInfo != null) {
                checkParentCoherenceAtAdd(wsDir, subprojectDir,
                        subproject, parentInfo);
            }

            // Derive dependencies by matching POM groupIds against
            // already-registered workspace subprojects
            try {
                derivedDeps = deriveDependencies(wsDir, manifestPath,
                        subprojectDir, subproject);
            } catch (IOException e) {
                getLog().warn("  Could not derive dependencies from POM: "
                        + e.getMessage());
            }
        } else if (!skipClone) {
            getLog().warn("  No pom.xml found — dependencies not derived");
        }

        getLog().info("");
        String wsName = readWorkspaceName(wsDir);
        getLog().info(wsName + " — Add Subproject");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Subproject: " + subproject);
        getLog().info("  Repo:       " + repo);
        if (branch != null) {
            getLog().info("  Branch:     " + branch);
        }
        if (version != null && !version.isBlank()) {
            getLog().info("  Version:    " + version);
        }
        if (groupId != null && !groupId.isBlank()) {
            getLog().info("  GroupId:    " + groupId);
        }
        if (detectedParent != null) {
            getLog().info("  Parent:     " + detectedParent + " (detected from POM)");
        }
        if (derivedDeps != null && !derivedDeps.isEmpty()) {
            String depNames = derivedDeps.stream()
                    .map(DerivedDep::subproject)
                    .collect(Collectors.joining(", "));
            getLog().info("  Depends:    " + depNames + " (derived from POM)");
        } else {
            getLog().info("  Depends:    (none)");
        }
        if (alreadyRegistered) {
            getLog().info("  (already registered — re-validating dependencies)");
        }
        if (cloned) {
            getLog().info(Ansi.green("  ✓ ") + "Cloned " + subproject);
        }
        getLog().info("");

        try {
            if (alreadyRegistered) {
                // Update existing entry's depends-on in workspace.yaml
                updateSubprojectDependencies(manifestPath, subproject, derivedDeps);
                getLog().info(Ansi.green("  ✓ ") + "workspace.yaml updated (dependencies re-derived)");
            } else {
                // Append new subproject to workspace.yaml
                appendSubprojectToManifest(manifestPath, derivedDeps, detectedParent);
                getLog().info(Ansi.green("  ✓ ") + "workspace.yaml updated");
            }

            // Profile is idempotent — addProfileToPom already checks for existence
            addProfileToPom(pomPath);
            getLog().info(Ansi.green("  ✓ ") + "pom.xml updated (profile: with-" + subproject + ")");

        } catch (IOException e) {
            throw new MojoException(
                    "Failed to update workspace files: " + e.getMessage(), e);
        }

        // Backward resolution: check if any existing subprojects
        // depend on the newly added subproject's groupId
        if (Files.exists(subprojectDir.resolve("pom.xml"))) {
            try {
                int backfilled = backfillDependencies(
                        wsDir, manifestPath, subproject, subprojectDir);
                if (backfilled > 0) {
                    getLog().info(Ansi.green("  ✓ ") + "Updated " + backfilled
                            + " existing subproject(s) with dependency on "
                            + subproject);
                }
            } catch (IOException e) {
                getLog().warn("  Could not backfill dependencies: "
                        + e.getMessage());
            }
        }

        // Auto-commit workspace.yaml + pom.xml changes
        try {
            ReleaseSupport.exec(wsDir.toFile(), getLog(), "git", "add", "workspace.yaml", "pom.xml");
            ReleaseSupport.exec(wsDir.toFile(), getLog(), "git", "commit", "-m", "workspace: add " + subproject);
            getLog().info(Ansi.green("  ✓ ") + "committed workspace.yaml + pom.xml");
        } catch (Exception e) {
            getLog().warn("  Auto-commit failed (non-fatal): " + e.getMessage());
        }

        // Version alignment: update dependency versions in the newly
        // added subproject (and any backfilled subprojects) to match
        // workspace SNAPSHOT versions. Changes are left uncommitted
        // so the developer can review and fold them into a feature branch.
        if (Files.exists(subprojectDir.resolve("pom.xml"))) {
            try {
                Manifest updatedManifest = ManifestReader.read(manifestPath);
                int aligned = alignVersions(wsDir, subprojectDir, subproject,
                        updatedManifest);
                if (aligned > 0) {
                    getLog().info("");
                    getLog().info(Ansi.yellow("  ⚠ ") + aligned + " file(s) modified for version "
                            + "alignment (uncommitted)");
                    getLog().info("    Review with 'git diff' in " + subproject);
                }
            } catch (IOException e) {
                getLog().warn("  Could not align versions: " + e.getMessage());
            }
        }

        getLog().info("");
        if (cloned) {
            getLog().info("  Subproject added and cloned.");
        } else {
            getLog().info("  Subproject added. Run 'mvn "
                    + WsGoal.SCAFFOLD_INIT.qualified() + "' to clone.");
        }
        getLog().info("");
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("Added subproject **" + subproject + "**");
        report.table(List.of("Field", "Value"), List.of(
                new String[]{"Repo", repo},
                new String[]{"Cloned",
                        cloned ? "yes"
                               : "no — run " + WsGoal.SCAFFOLD_INIT.qualified()}));
        WorkspaceReportSpec spec = new WorkspaceReportSpec(WsGoal.ADD,
                report.build());

        PostMutationSync.refresh(workspaceRoot(), getLog());
        return spec;
    }

    // ── Feature-branch version qualification (#574) ──────────────

    /**
     * On a feature branch, branch-qualify the added subproject's version
     * and rewrite its POM so it is isolated like the rest of the
     * workspace (IKE-Network/ike-issues#574). No-op on {@code main} and
     * when already qualified ({@link VersionSupport#branchQualifiedVersion}
     * is idempotent). {@code scaffold-publish}'s
     * {@code FeatureVersionReconciler} self-heals if this is skipped, so
     * the caller treats a rewrite failure as non-fatal. Package-private
     * for tests.
     *
     * @param subprojectDir the cloned subproject directory
     * @param version       the version about to be recorded (may be null)
     * @param branch        the branch the subproject will track
     * @return the version to record — branch-qualified on a feature branch
     * @throws IOException if the subproject POM cannot be rewritten
     */
    static String qualifyForBranch(Path subprojectDir, String version,
                                   String branch) throws IOException {
        if (version == null || version.isBlank()
                || branch == null || branch.isBlank()
                || "main".equals(branch)) {
            return version;
        }
        String qualified =
                VersionSupport.branchQualifiedVersion(version, branch);
        if (!qualified.equals(version)) {
            Path pom = subprojectDir.resolve("pom.xml");
            if (Files.exists(pom)) {
                FeatureVersionReconciler.rewriteOwnVersion(
                        pom, version, qualified);
            }
        }
        return qualified;
    }

    // ── YAML generation ──────────────────────────────────────────

    void appendSubprojectToManifest(Path manifestPath, List<DerivedDep> derivedDeps,
                                     String detectedParent)
            throws IOException {
        String yaml = Files.readString(manifestPath, StandardCharsets.UTF_8);

        StringBuilder entry = new StringBuilder();
        entry.append("\n  ").append(subproject).append(":\n");
        entry.append("    description: >\n");
        entry.append("      ").append(description).append("\n");
        entry.append("    repo: ").append(repo).append("\n");
        if (branch != null && !branch.isBlank()) {
            entry.append("    branch: ").append(branch).append("\n");
        }
        if (version != null && !version.isBlank()) {
            entry.append("    version: \"").append(version).append("\"\n");
        }
        if (groupId != null && !groupId.isBlank()) {
            entry.append("    groupId: ").append(groupId).append("\n");
        }
        if (detectedParent != null) {
            entry.append("    parent: ").append(detectedParent).append("\n");
        }
        if (derivedDeps != null && !derivedDeps.isEmpty()) {
            entry.append("    depends-on:\n");
            for (DerivedDep dep : derivedDeps) {
                entry.append("      - subproject: ").append(dep.subproject()).append("\n");
                entry.append("        relationship: build\n");
                if (dep.versionProperty() != null) {
                    entry.append("        version-property: ").append(dep.versionProperty()).append("\n");
                }
            }
        } else {
            entry.append("    depends-on: []\n");
        }

        // Insert at the end of the subprojects: block, before any
        // trailing top-level constructs (e.g. the `# ide:` template
        // comment, or a future `ide:` key). #240
        int insertAt = findSubprojectsBlockInsertionPoint(yaml);
        if (insertAt < 0) {
            // No subprojects: key found — append at EOF as fallback.
            yaml = yaml + entry;
        } else {
            yaml = yaml.substring(0, insertAt) + entry + yaml.substring(insertAt);
        }

        Files.writeString(manifestPath, yaml, StandardCharsets.UTF_8);
    }

    /**
     * Locate the position inside {@code workspace.yaml} where a new
     * subproject entry should be inserted. The entry belongs at the
     * end of the {@code subprojects:} block — after any existing
     * subproject entries (and the placeholder comment) — and before
     * any subsequent top-level construct such as a comment block or
     * a sibling top-level key (e.g. {@code ide:}).
     *
     * <p>Operationally: find the {@code subprojects:} line, scan
     * forward through indented and blank lines (which belong to the
     * block), and stop at the first column-0 non-blank line. The
     * insertion point is the byte offset right after the last
     * non-blank line of the block.
     *
     * @param yaml the current manifest text
     * @return offset to insert before, or {@code -1} if no
     *         {@code subprojects:} key was found
     */
    static int findSubprojectsBlockInsertionPoint(String yaml) {
        // Locate the subprojects: line.
        int subprojectsLineStart = -1;
        int pos = 0;
        while (pos < yaml.length()) {
            int eol = yaml.indexOf('\n', pos);
            if (eol < 0) eol = yaml.length();
            String line = yaml.substring(pos, eol);
            if (line.startsWith("subprojects:")) {
                subprojectsLineStart = pos;
                pos = (eol < yaml.length()) ? eol + 1 : eol;
                break;
            }
            pos = (eol < yaml.length()) ? eol + 1 : eol;
        }
        if (subprojectsLineStart < 0) return -1;

        // Walk forward: indented and blank lines belong to the block;
        // a column-0 non-blank line ends it. Track the end-offset of
        // the last non-blank line so the insertion point is just after it.
        int lastNonBlankEnd = pos;
        while (pos < yaml.length()) {
            int eol = yaml.indexOf('\n', pos);
            boolean hasNewline = eol >= 0;
            if (eol < 0) eol = yaml.length();
            String line = yaml.substring(pos, eol);
            int nextStart = hasNewline ? eol + 1 : eol;

            if (line.isBlank()) {
                // Could be inside the block or trailing — keep scanning.
            } else if (!Character.isWhitespace(line.charAt(0))) {
                // Reached the next top-level construct — stop.
                return lastNonBlankEnd;
            } else {
                // Indented content line — extend the block.
                lastNonBlankEnd = nextStart;
            }
            pos = nextStart;
            if (!hasNewline) break;
        }

        // Hit EOF before another top-level construct.
        return lastNonBlankEnd;
    }

    /**
     * Update the depends-on section for an existing subproject in
     * workspace.yaml. Replaces the current depends-on block with
     * the newly derived dependencies.
     */
    static void updateSubprojectDependencies(Path manifestPath, String subprojectName,
                                             List<DerivedDep> derivedDeps) throws IOException {
        String yaml = Files.readString(manifestPath, StandardCharsets.UTF_8);
        String updated = rewriteDependsOnBlock(yaml, subprojectName, derivedDeps);
        if (!updated.equals(yaml)) {
            Files.writeString(manifestPath, updated, StandardCharsets.UTF_8);
        }
    }

    /**
     * Pure-string variant of {@link #updateSubprojectDependencies}: takes
     * the current YAML and returns a new string with the named
     * subproject's {@code depends-on} block replaced. Returns the input
     * unchanged when the subproject is not present in the YAML.
     */
    static String rewriteDependsOnBlock(String yaml, String subprojectName,
                                        List<DerivedDep> derivedDeps) {
        // Build the new depends-on block
        StringBuilder newDeps = new StringBuilder();
        if (derivedDeps != null && !derivedDeps.isEmpty()) {
            newDeps.append("    depends-on:\n");
            for (DerivedDep dep : derivedDeps) {
                newDeps.append("      - subproject: ").append(dep.subproject()).append("\n");
                newDeps.append("        relationship: build\n");
                if (dep.versionProperty() != null) {
                    newDeps.append("        version-property: ").append(dep.versionProperty()).append("\n");
                }
            }
        } else {
            newDeps.append("    depends-on: []\n");
        }

        // Replace the existing depends-on block for this subproject.
        // Match: "    depends-on: []\n" or "    depends-on:\n      - ...\n"
        // within this subproject's section.
        String escaped = Pattern.quote(subprojectName);
        Pattern depsPattern = Pattern.compile(
                "(" + escaped + ":[\\s\\S]*?)(    depends-on:.*(?:\\n      .*)*\\n)",
                Pattern.MULTILINE);
        Matcher m = depsPattern.matcher(yaml);
        if (m.find()) {
            return yaml.substring(0, m.start(2))
                    + newDeps
                    + yaml.substring(m.end(2));
        }
        return yaml;
    }

    // ── POM generation ───────────────────────────────────────────

    void addProfileToPom(Path pomPath) throws IOException {
        String pom = Files.readString(pomPath, StandardCharsets.UTF_8);

        // Check if profile already exists
        if (pom.contains("with-" + subproject)) {
            getLog().info("  Profile with-" + subproject + " already exists");
            return;
        }

        String profile = "\n"
                + "        <profile>\n"
                + "            <id>with-" + subproject + "</id>\n"
                + "            <activation>\n"
                + "                <file>\n"
                + "                    <exists>${project.basedir}/" + subproject + "/pom.xml</exists>\n"
                + "                </file>\n"
                + "            </activation>\n"
                + "            <subprojects>\n"
                + "                <subproject>" + subproject + "</subproject>\n"
                + "            </subprojects>\n"
                + "        </profile>\n";

        // Insert before closing </profiles>
        int closingIdx = pom.lastIndexOf("</profiles>");
        if (closingIdx >= 0) {
            pom = pom.substring(0, closingIdx) + profile + "\n    " + pom.substring(closingIdx);
        } else {
            getLog().warn("  No </profiles> tag found in pom.xml — add profile manually");
        }

        Files.writeString(pomPath, pom, StandardCharsets.UTF_8);
    }

    // ── Branch resolution ────────────────────────────────────────

    /**
     * Resolve the branch that the new subproject should land on.
     *
     * <p>Precedence (ike-issues#286):
     * <ol>
     *   <li>The workspace repo's current git branch is authoritative.
     *       If {@code -Dbranch=} was given and disagrees, fail — that's
     *       a request for heterogeneous branch state, which is not
     *       supported.</li>
     *   <li>If the workspace dir is not a git repo (no {@code .git}),
     *       use {@code -Dbranch=} if provided.</li>
     *   <li>Otherwise, fall back to the manifest's
     *       {@code defaults.branch}.</li>
     * </ol>
     *
     * @param wsDir        the workspace root directory
     * @param manifestPath path to workspace.yaml
     * @return the resolved branch name; never null or blank
     * @throws MojoException if {@code -Dbranch} disagrees with the
     *                       workspace repo's current branch, or if no
     *                       branch can be resolved at all
     */
    private String resolveBranch(Path wsDir, Path manifestPath) throws MojoException {
        String requested = (branch != null && !branch.isBlank()) ? branch : null;
        String wsBranch = workspaceHeadBranch(wsDir);

        if (wsBranch != null) {
            if (requested != null && !requested.equals(wsBranch)) {
                throw new MojoException(
                        "Requested branch '" + requested + "' disagrees with the "
                                + "workspace repo's current branch '" + wsBranch + "'. "
                                + "All subprojects in a workspace must track the same "
                                + "branch (ike-issues#286). Either run on the matching "
                                + "branch in the workspace repo, or omit -Dbranch= to "
                                + "use the workspace's current branch.");
            }
            return wsBranch;
        }

        if (requested != null) return requested;

        // No workspace git state, no -Dbranch — fall back to defaults.branch.
        try {
            String def = ManifestReader.read(manifestPath).defaults().branch();
            if (def != null && !def.isBlank()) return def;
        } catch (ManifestException e) {
            // fall through to error below
        }
        throw new MojoException(
                "Cannot resolve a branch for the new subproject: workspace repo "
                        + "has no git state, no -Dbranch was given, and "
                        + "defaults.branch is unset in workspace.yaml.");
    }

    /**
     * Read the workspace repo's current branch, or null if the workspace
     * directory is not a git repository.
     */
    private static String workspaceHeadBranch(Path wsDir) {
        if (!Files.isDirectory(wsDir.resolve(".git"))) return null;
        try {
            String b = VcsOperations.currentBranch(wsDir.toFile());
            return (b == null || b.isBlank()) ? null : b;
        } catch (MojoException e) {
            return null;
        }
    }

    // ── Clone ────────────────────────────────────────────────────

    /**
     * Clone the subproject onto the resolved workspace branch.
     *
     * <p>If the remote already has the branch, clones with {@code -b}.
     * If the branch is absent on the remote, clones the remote's default
     * branch and creates the workspace branch locally — mirroring what
     * {@code ws:feature-start-publish} would have done if this subproject
     * had been a workspace member at the time. The new branch is left
     * unpushed; a subsequent {@code ws:push} promotes it to origin
     * alongside the workspace.yaml change.
     */
    private void cloneSubproject(Path wsDir) throws MojoException {
        boolean remoteHasBranch = remoteHasBranch(wsDir, repo, branch);

        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("clone");
        cmd.add("--depth");
        cmd.add("1");
        if (remoteHasBranch) {
            cmd.add("-b");
            cmd.add(branch);
        }
        cmd.add(repo);
        cmd.add(subproject);
        ReleaseSupport.exec(wsDir.toFile(), getLog(), cmd.toArray(new String[0]));

        if (!remoteHasBranch) {
            getLog().info("  Remote has no branch '" + branch
                    + "' — creating it locally from the remote's default.");
            ReleaseSupport.exec(wsDir.resolve(subproject).toFile(), getLog(),
                    "git", "checkout", "-b", branch);
        }
    }

    /**
     * Probe whether {@code refs/heads/<branch>} exists on the given remote
     * URL via {@code git ls-remote --heads}. Returns false on any failure
     * (offline, auth, unknown repo) — the caller will surface the real
     * error from the subsequent {@code git clone} attempt.
     */
    private static boolean remoteHasBranch(Path wsDir, String repoUrl, String branch) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "ls-remote", "--heads", repoUrl, branch)
                    .directory(wsDir.toFile())
                    .redirectErrorStream(false);
            Process proc = pb.start();
            String stdout = new String(proc.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            int exit = proc.waitFor();
            return exit == 0 && !stdout.isEmpty();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    // ── POM-based dependency derivation ────────────────────────

    /**
     * Derive dependencies by scanning the new subproject's POMs for
     * referenced {@code groupId:artifactId} pairs and matching them
     * against the published artifact sets of already-registered
     * workspace subprojects.
     *
     * <p>This is artifact-level matching, not groupId-level — it
     * correctly handles subprojects that share a groupId (e.g.,
     * tinkar-core and tinkar-composer both use {@code dev.ikm.tinkar}).
     */
    static List<DerivedDep> deriveDependencies(Path wsDir, Path manifestPath,
                                                Path subprojectDir, String subprojectName)
            throws IOException {
        // Collect all groupId:artifactId pairs referenced by this subproject
        Set<String> referencedArtifacts = extractReferencedArtifacts(
                subprojectDir.resolve("pom.xml"));
        if (referencedArtifacts.isEmpty()) return null;

        // Read the new subproject's <properties> for version-property detection
        Map<String, String> newSubProperties;
        try {
            DocumentBuilder db = DBF.newDocumentBuilder();
            Document doc = db.parse(subprojectDir.resolve("pom.xml").toFile());
            newSubProperties = readProperties(doc.getDocumentElement());
        } catch (Exception e) {
            newSubProperties = Map.of();
        }

        Manifest manifest = ManifestReader.read(manifestPath);
        SubprojectResolver resolver = SubprojectResolver.scan(wsDir, manifest);

        // Map each referenced coordinate to the subproject that PRODUCES
        // it (full groupId+artifactId; external coordinates resolve to
        // empty and are skipped). Dedupe by producer, then emit in
        // manifest order to keep derived-dependency ordering stable.
        Map<String, String> versionPropertyByProducer = new LinkedHashMap<>();
        for (String coord : referencedArtifacts) {
            int colon = coord.indexOf(':');
            if (colon < 0) continue;
            Optional<String> producer = resolver.subprojectForCoordinate(
                    coord.substring(0, colon), coord.substring(colon + 1));
            if (producer.isEmpty()) continue;                   // external
            String producerName = producer.get();
            if (producerName.equals(subprojectName)) continue;  // never self
            if (!versionPropertyByProducer.containsKey(producerName)) {
                versionPropertyByProducer.put(producerName,
                        detectVersionProperty(
                                manifest.subprojects().get(producerName),
                                newSubProperties));
            }
        }

        List<DerivedDep> matched = new ArrayList<>();
        for (String name : manifest.subprojects().keySet()) {
            if (versionPropertyByProducer.containsKey(name)) {
                matched.add(new DerivedDep(
                        name, versionPropertyByProducer.get(name)));
            }
        }
        return matched.isEmpty() ? null : matched;
    }

    /**
     * Find a property declared in the newly added subproject whose value
     * equals the producer subproject's version — the
     * {@code version-property} hint recorded on a derived
     * {@code depends-on} edge so {@code ws:align} can track the upstream
     * version through a {@code ${...}} property. Returns null when the
     * producer has no known version or no matching property exists.
     *
     * @param producer        the upstream (depended-on) subproject, or null
     * @param newSubProperties the new subproject's {@code <properties>}
     * @return the matching property name, or null
     */
    private static String detectVersionProperty(Subproject producer,
                                                Map<String, String> newSubProperties) {
        if (producer == null) return null;
        String upstreamVersion = producer.version();
        if (upstreamVersion == null || newSubProperties.isEmpty()) return null;
        for (Map.Entry<String, String> prop : newSubProperties.entrySet()) {
            if (upstreamVersion.equals(prop.getValue())) {
                return prop.getKey();
            }
        }
        return null;
    }

    /**
     * Determine whether a new subproject's declared Maven {@code <parent>}
     * is itself a workspace subproject, returning that subproject's name
     * (its {@code workspace.yaml} key) for the {@code parent:} field.
     *
     * <p>Matching requires the parent's <strong>groupId AND
     * artifactId</strong> to match a workspace subproject's published
     * artifact set. groupId alone is insufficient: an external parent
     * such as {@code network.ike.platform:ike-parent} that merely shares
     * a groupId with a sibling subproject must be treated as having no
     * workspace parent, so no bogus {@code parent:} is recorded
     * (ike-issues#565). This mirrors the artifact-level matching used by
     * {@link #deriveDependencies} and the GA-matching rule the
     * {@code ws:align} reconcilers apply (issue #241).
     *
     * @param wsDir        the workspace root directory
     * @param manifestPath path to {@code workspace.yaml}
     * @param parentInfo   the new subproject's declared parent, or null
     *                     when the POM has no {@code <parent>} block
     * @return the workspace subproject name whose published artifact set
     *         contains the parent's {@code groupId:artifactId}, or null
     *         when the parent is external / not a workspace member
     * @throws IOException       if a candidate subproject's POM cannot be read
     * @throws ManifestException if the manifest cannot be parsed
     */
    static String detectWorkspaceParent(Path wsDir, Path manifestPath,
                                        PomParentSupport.ParentInfo parentInfo)
            throws IOException, ManifestException {
        if (parentInfo == null) {
            return null;
        }
        Manifest manifest = ManifestReader.read(manifestPath);
        return SubprojectResolver.scan(wsDir, manifest)
                .subprojectForCoordinate(
                        parentInfo.groupId(), parentInfo.artifactId())
                .orElse(null);
    }

    /**
     * Backward resolution: for each existing cloned subproject, check
     * whether its POMs reference any artifact published by the newly
     * added subproject. Uses artifact-level matching via
     * {@link PublishedArtifactSet} to avoid false positives from
     * shared groupIds.
     */
    private int backfillDependencies(Path wsDir, Path manifestPath,
                                     String newSubproject, Path newSubprojectDir)
            throws IOException {
        String yaml = Files.readString(manifestPath, StandardCharsets.UTF_8);
        Manifest manifest = ManifestReader.read(manifestPath);
        // The new subproject is already registered by the time backfill
        // runs, so the shared resolver maps its published coordinates
        // back to it (full GA — no false positives from shared groupIds).
        SubprojectResolver resolver = SubprojectResolver.scan(wsDir, manifest);
        int updated = 0;

        for (Map.Entry<String, Subproject> entry : manifest.subprojects().entrySet()) {
            String existingName = entry.getKey();
            Subproject existing = entry.getValue();

            // Skip the newly added subproject itself
            if (existingName.equals(newSubproject)) continue;

            // Skip if already depends on the new subproject
            if (existing.dependsOn() != null
                    && existing.dependsOn().stream()
                    .anyMatch(d -> newSubproject.equals(d.subproject()))) {
                continue;
            }

            // Check if this existing subproject references any artifact
            // produced by the new subproject (full GA via the resolver).
            Path existingPom = wsDir.resolve(existingName).resolve("pom.xml");
            if (!Files.exists(existingPom)) continue;

            boolean dependsOnNew = extractReferencedArtifacts(existingPom).stream()
                    .anyMatch(coord -> resolvesTo(resolver, coord, newSubproject));
            if (!dependsOnNew) continue;

            yaml = addDependencyEdge(yaml, existingName, newSubproject, null);
            updated++;
            getLog().info(Ansi.cyan("  → ") + existingName + " depends on " + newSubproject);
        }

        if (updated > 0) {
            Files.writeString(manifestPath, yaml, StandardCharsets.UTF_8);
        }

        return updated;
    }

    /**
     * Whether a {@code "groupId:artifactId"} coordinate resolves (full
     * GA) to the named subproject via the shared resolver.
     */
    private static boolean resolvesTo(SubprojectResolver resolver,
                                      String coord, String subprojectName) {
        int colon = coord.indexOf(':');
        if (colon < 0) return false;
        return resolver.subprojectForCoordinate(
                coord.substring(0, colon), coord.substring(colon + 1))
                .map(subprojectName::equals).orElse(false);
    }

    /**
     * Add a depends-on edge for an existing subproject in workspace.yaml.
     * Converts {@code depends-on: []} to a populated list, or appends
     * to an existing list.
     */
    static String addDependencyEdge(String yaml, String subprojectName,
                                    String dependsOnName, String versionProperty) {
        String versionPropertyLine = (versionProperty != null)
                ? "        version-property: " + versionProperty + "\n" : "";

        // Case 1: depends-on: [] — replace with populated entry
        String emptyDeps = "(" + subprojectName + ":[\\s\\S]*?)(depends-on:\\s*\\[])";
        Pattern emptyPattern = Pattern.compile(emptyDeps);
        Matcher emptyMatcher = emptyPattern.matcher(yaml);
        if (emptyMatcher.find()) {
            String replacement = emptyMatcher.group(1)
                    + "depends-on:\n"
                    + "      - subproject: " + dependsOnName + "\n"
                    + "        relationship: build\n"
                    + versionPropertyLine;
            return emptyMatcher.replaceFirst(Matcher.quoteReplacement(replacement));
        }

        // Case 2: existing depends-on list — append before next subproject
        // or section. Find the subproject's depends-on block and add an entry.
        String existingDeps = "(" + subprojectName
                + ":[\\s\\S]*?depends-on:\\n)((?:\\s+- subproject:.*\\n(?:\\s+relationship:.*\\n)(?:\\s+version-property:.*\\n)?)*)";
        Pattern existingPattern = Pattern.compile(existingDeps);
        Matcher existingMatcher = existingPattern.matcher(yaml);
        if (existingMatcher.find()) {
            String replacement = existingMatcher.group(1)
                    + existingMatcher.group(2)
                    + "      - subproject: " + dependsOnName + "\n"
                    + "        relationship: build\n"
                    + versionPropertyLine;
            return existingMatcher.replaceFirst(Matcher.quoteReplacement(replacement));
        }

        return yaml;
    }

    /**
     * Extract all {@code groupId:artifactId} pairs referenced as
     * build dependencies across the entire subproject (root POM +
     * all submodules/subprojects).
     *
     * <p>Uses DOM parsing to correctly read XML structure and
     * resolves Maven property references ({@code ${property.name}})
     * from the POM's {@code <properties>} section.
     *
     * <p>Scans {@code <parent>}, {@code <dependencies>}, BOM imports
     * inside {@code <dependencyManagement>} (entries with both
     * {@code <scope>import</scope>} and {@code <type>pom</type>}), and
     * build {@code <plugins>} (including {@code <pluginManagement>}).
     * Profile-scoped versions of these are scanned as well, since any
     * profile activation makes those dependencies real. Plain
     * version-constraint entries inside {@code <dependencyManagement>}
     * are excluded — they aren't build edges by themselves. (#239)
     *
     * @return set of "groupId:artifactId" strings
     */
    static Set<String> extractReferencedArtifacts(Path pomFile) throws IOException {
        Set<String> artifacts = new LinkedHashSet<>();
        scanPomForArtifacts(pomFile, artifacts);
        return artifacts;
    }

    /**
     * Recursively scan a POM and its submodules for referenced
     * groupId:artifactId pairs using DOM parsing with property
     * resolution.
     */
    static void scanPomForArtifacts(Path pomFile, Set<String> artifacts)
            throws IOException {
        if (!Files.exists(pomFile)) return;

        Document doc;
        try {
            DocumentBuilder db = DBF.newDocumentBuilder();
            doc = db.parse(pomFile.toFile());
        } catch (Exception e) {
            // If we can't parse, skip this POM
            return;
        }

        Element project = doc.getDocumentElement();

        // Read <properties> for ${...} resolution
        Map<String, String> properties = readProperties(project);

        // Scan the project root for parent/deps/BOMs/plugins.
        collectArtifactsFromContainer(project, properties, artifacts);

        // Scan each profile body — a profile's deps/plugins become real
        // when it activates, so they're legitimate build edges.
        Element profilesEl = firstChild(project, "profiles");
        if (profilesEl != null) {
            for (Element profile : children(profilesEl, "profile")) {
                collectArtifactsFromContainer(profile, properties, artifacts);
            }
        }

        // Recurse into subprojects (Maven 4.1.0) and modules (Maven 4.0.0)
        Path pomDir = pomFile.getParent();

        Element subprojects = firstChild(project, "subprojects");
        if (subprojects != null) {
            for (Element sub : children(subprojects, "subproject")) {
                String name = sub.getTextContent().trim();
                scanPomForArtifacts(pomDir.resolve(name).resolve("pom.xml"), artifacts);
            }
        }

        Element modules = firstChild(project, "modules");
        if (modules != null) {
            for (Element mod : children(modules, "module")) {
                String name = mod.getTextContent().trim();
                scanPomForArtifacts(pomDir.resolve(name).resolve("pom.xml"), artifacts);
            }
        }
    }

    /**
     * Pull groupId:artifactId pairs out of a container element — either
     * a {@code <project>} root or a {@code <profile>} body. Reads:
     * <ul>
     *   <li>{@code <parent>}</li>
     *   <li>{@code <dependencies><dependency>}</li>
     *   <li>{@code <dependencyManagement><dependencies><dependency>} —
     *       BOM imports only ({@code <scope>import</scope>} with
     *       {@code <type>pom</type>})</li>
     *   <li>{@code <build><plugins><plugin>}</li>
     *   <li>{@code <build><pluginManagement><plugins><plugin>}</li>
     * </ul>
     *
     * @param container  the {@code <project>} or {@code <profile>} element
     * @param properties resolved {@code <properties>} for {@code ${...}} references
     * @param artifacts  accumulator for discovered "groupId:artifactId" strings
     */
    static void collectArtifactsFromContainer(
            Element container,
            Map<String, String> properties,
            Set<String> artifacts) {

        // <parent> — present on project root; profiles don't allow it.
        addArtifactCoords(firstChild(container, "parent"), properties, artifacts);

        // Direct build dependencies.
        Element depsEl = firstChild(container, "dependencies");
        if (depsEl != null) {
            for (Element dep : children(depsEl, "dependency")) {
                addArtifactCoords(dep, properties, artifacts);
            }
        }

        // BOM imports — entries declared with scope=import + type=pom.
        // Plain version-constraint entries are skipped (they don't bind
        // anything until something else declares the dep).
        Element depMgmt = firstChild(container, "dependencyManagement");
        if (depMgmt != null) {
            Element dmDeps = firstChild(depMgmt, "dependencies");
            if (dmDeps != null) {
                for (Element dep : children(dmDeps, "dependency")) {
                    String scope = resolve(childText(dep, "scope"), properties);
                    String type = resolve(childText(dep, "type"), properties);
                    if ("import".equals(scope) && "pom".equals(type)) {
                        addArtifactCoords(dep, properties, artifacts);
                    }
                }
            }
        }

        // Build plugins — both top-level <plugins> and <pluginManagement>.
        Element build = firstChild(container, "build");
        if (build != null) {
            addPluginCoords(firstChild(build, "plugins"), properties, artifacts);
            Element pluginMgmt = firstChild(build, "pluginManagement");
            if (pluginMgmt != null) {
                addPluginCoords(firstChild(pluginMgmt, "plugins"),
                        properties, artifacts);
            }
        }
    }

    /**
     * Add every {@code <plugin>} child of the given {@code <plugins>}
     * element to the artifact accumulator. No-op if {@code pluginsEl}
     * is null.
     *
     * @param pluginsEl  the {@code <plugins>} element, or null
     * @param properties resolved {@code <properties>} for property substitution
     * @param artifacts  accumulator
     */
    private static void addPluginCoords(Element pluginsEl,
                                        Map<String, String> properties,
                                        Set<String> artifacts) {
        if (pluginsEl == null) return;
        for (Element plugin : children(pluginsEl, "plugin")) {
            addArtifactCoords(plugin, properties, artifacts);
        }
    }

    /**
     * Resolve an element's {@code <groupId>}/{@code <artifactId>} pair
     * (with property substitution) and add it to the accumulator. No-op
     * if {@code el} is null or either coord is missing.
     *
     * @param el         the element with groupId/artifactId children
     * @param properties resolved {@code <properties>} for property substitution
     * @param artifacts  accumulator
     */
    private static void addArtifactCoords(Element el,
                                          Map<String, String> properties,
                                          Set<String> artifacts) {
        if (el == null) return;
        String gid = resolve(childText(el, "groupId"), properties);
        String aid = resolve(childText(el, "artifactId"), properties);
        if (gid != null && aid != null) {
            artifacts.add(gid + ":" + aid);
        }
    }

    // ── DOM helpers ─────────────────────────────────────────────

    /**
     * Extract the first match of a regex pattern's first group.
     * Used by the version alignment code which operates on raw POM text.
     */
    private static String extractFirst(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static final javax.xml.parsers.DocumentBuilderFactory DBF;
    static {
        DBF = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        try {
            DBF.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DBF.setFeature("http://xml.org/sax/features/external-general-entities", false);
            DBF.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (javax.xml.parsers.ParserConfigurationException e) {
            // Non-fatal
        }
    }

    /**
     * Read {@code <properties>} from a POM's project element into
     * a map for {@code ${...}} resolution.
     */
    private static Map<String, String> readProperties(Element project) {
        Map<String, String> props = new LinkedHashMap<>();
        Element propsEl = firstChild(project, "properties");
        if (propsEl != null) {
            org.w3c.dom.NodeList children = propsEl.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                org.w3c.dom.Node node = children.item(i);
                if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                    String value = node.getTextContent().trim();
                    if (!value.isEmpty()) {
                        props.put(node.getNodeName(), value);
                    }
                }
            }
        }
        return props;
    }

    /**
     * Resolve {@code ${property.name}} references in a string using
     * the given property map. Returns the input unchanged if no
     * property reference is present or if the property is not found.
     */
    private static String resolve(String value, Map<String, String> properties) {
        if (value == null || !value.contains("${")) return value;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            value = value.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        // If still contains unresolved references, return as-is
        return value;
    }

    /**
     * Get the text content of a direct child element, or null.
     */
    private static String childText(Element parent, String tagName) {
        Element child = firstChild(parent, tagName);
        if (child == null) return null;
        String text = child.getTextContent().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * Get the first direct child element with the given tag name.
     */
    private static Element firstChild(Element parent, String tagName) {
        org.w3c.dom.NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node node = children.item(i);
            if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                    && tagName.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    /**
     * Get all direct child elements with the given tag name.
     */
    private static List<Element> children(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        org.w3c.dom.NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node node = children.item(i);
            if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                    && tagName.equals(node.getNodeName())) {
                result.add((Element) node);
            }
        }
        return result;
    }

    // ── Version alignment ───────────────────────────────────────

    /**
     * Align dependency versions in the newly added subproject's POMs
     * to match workspace SNAPSHOT versions. For each workspace subproject
     * that this subproject depends on, find explicit version declarations
     * and update them.
     *
     * @return the number of POM files modified
     */
    private int alignVersions(Path wsDir, Path subprojectDir,
                               String subprojectName, Manifest manifest)
            throws IOException {
        // Build a map: groupId:artifactId → workspace version
        // for all workspace subprojects (except the one being added)
        Map<String, String> artifactVersions = new LinkedHashMap<>();
        for (Map.Entry<String, Subproject> entry : manifest.subprojects().entrySet()) {
            if (entry.getKey().equals(subprojectName)) continue;
            Subproject sub = entry.getValue();
            if (sub.version() == null) continue;

            Path subDir = wsDir.resolve(entry.getKey());
            if (!Files.exists(subDir.resolve("pom.xml"))) continue;

            Set<PublishedArtifactSet.Artifact> published =
                    PublishedArtifactSet.scan(subDir);
            for (PublishedArtifactSet.Artifact artifact : published) {
                artifactVersions.put(
                        artifact.groupId() + ":" + artifact.artifactId(),
                        sub.version());
            }
        }

        if (artifactVersions.isEmpty()) return 0;

        // Walk all POM files in the new subproject and update versions
        int filesModified = 0;
        List<Path> pomFiles = findAllPomFiles(subprojectDir);

        for (Path pomFile : pomFiles) {
            String original = Files.readString(pomFile, StandardCharsets.UTF_8);
            String updated = alignDependencyVersions(original, artifactVersions);

            if (!updated.equals(original)) {
                Files.writeString(pomFile, updated, StandardCharsets.UTF_8);
                filesModified++;
                // Log each change
                Path relative = subprojectDir.getParent().relativize(pomFile);
                getLog().info("  Version alignment: " + relative);
            }
        }

        return filesModified;
    }

    /**
     * In a POM content string, find {@code <dependency>} blocks that
     * reference a known workspace artifact and update their
     * {@code <version>} to the workspace version. Skips dependencies
     * inside {@code <dependencyManagement>}.
     */
    static String alignDependencyVersions(String pom,
                                            Map<String, String> artifactVersions) {
        // Strip dependencyManagement to avoid modifying BOM imports
        // We'll process only dependencies outside of dependencyManagement
        StringBuilder result = new StringBuilder();
        Matcher dmMatcher = DEP_MGMT_BLOCK.matcher(pom);
        int lastEnd = 0;

        while (dmMatcher.find()) {
            // Process the segment before this dependencyManagement block
            String segment = pom.substring(lastEnd, dmMatcher.start());
            result.append(alignDepsInSegment(segment, artifactVersions));
            // Append the dependencyManagement block unchanged
            result.append(dmMatcher.group());
            lastEnd = dmMatcher.end();
        }
        // Process remaining content after last dependencyManagement
        result.append(alignDepsInSegment(pom.substring(lastEnd), artifactVersions));

        return result.toString();
    }

    private static String alignDepsInSegment(String segment,
                                              Map<String, String> artifactVersions) {
        Matcher depMatcher = DEPENDENCY_BLOCK.matcher(segment);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;

        while (depMatcher.find()) {
            sb.append(segment, lastEnd, depMatcher.start());

            String depBlock = depMatcher.group();
            String gid = extractFirst(GROUP_ID_PATTERN, depBlock);
            String aid = extractFirst(ARTIFACT_ID_PATTERN, depBlock);
            String key = gid + ":" + aid;

            String targetVersion = artifactVersions.get(key);
            if (targetVersion != null) {
                // Update the version in this dependency block
                String currentVersion = extractFirst(VERSION_PATTERN, depBlock);
                if (currentVersion != null && !currentVersion.equals(targetVersion)) {
                    depBlock = depBlock.replaceFirst(
                            "<version>" + Pattern.quote(currentVersion) + "</version>",
                            "<version>" + targetVersion + "</version>");
                }
            }

            sb.append(depBlock);
            lastEnd = depMatcher.end();
        }

        sb.append(segment.substring(lastEnd));
        return sb.toString();
    }

    /**
     * Find all pom.xml files in a subproject directory (root + submodules).
     */
    private List<Path> findAllPomFiles(Path subprojectDir) throws IOException {
        try (var stream = Files.walk(subprojectDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .toList();
        }
    }

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("<version>([^<]+)</version>");

    /**
     * Derive the Maven groupId from the subproject's root POM.
     * Strips the parent block first; if no groupId is declared
     * outside parent, falls back to the parent's groupId.
     */
    private String deriveGroupId(Path subprojectDir) {
        Path pomFile = subprojectDir.resolve("pom.xml");
        if (!Files.exists(pomFile)) return null;

        try {
            String content = Files.readString(pomFile, StandardCharsets.UTF_8);

            // Try groupId outside parent block first
            String stripped = PARENT_BLOCK.matcher(content).replaceFirst("");
            Matcher gm = GROUP_ID_PATTERN.matcher(stripped);
            if (gm.find()) return gm.group(1).trim();

            // Fall back to parent groupId
            Matcher parentBlock = PARENT_BLOCK.matcher(content);
            if (parentBlock.find()) {
                gm = GROUP_ID_PATTERN.matcher(parentBlock.group());
                if (gm.find()) return gm.group(1).trim();
            }
        } catch (IOException e) {
            // Non-fatal — groupId will be null in manifest
        }
        return null;
    }

    private static final Pattern PARENT_BLOCK =
            Pattern.compile("(?s)<parent>.*?</parent>");
    private static final Pattern GROUP_ID_PATTERN =
            Pattern.compile("<groupId>([^<]+)</groupId>");
    private static final Pattern ARTIFACT_ID_PATTERN =
            Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern DEPENDENCY_BLOCK =
            Pattern.compile("(?s)<dependency>.*?</dependency>");
    private static final Pattern DEP_MGMT_BLOCK =
            Pattern.compile("(?s)<dependencyManagement>.*?</dependencyManagement>");
    private static final Pattern SUBPROJECT_PATTERN =
            Pattern.compile("<subproject>([^<]+)</subproject>");
    private static final Pattern MODULE_PATTERN =
            Pattern.compile("<module>([^<]+)</module>");

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Derive a subproject name from a git URL.
     * {@code https://github.com/ikmdev/tinkar-core.git} → {@code tinkar-core}
     */
    static String deriveSubprojectName(String repoUrl) {
        String name = repoUrl;
        // Strip trailing .git
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        // Strip trailing slash
        if (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        // Take last path segment
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        return name;
    }

    private String readWorkspaceName(Path wsDir) {
        try {
            return ReleaseSupport.readPomArtifactId(wsDir.resolve("pom.xml").toFile());
        } catch (MojoException e) {
            return "Workspace";
        }
    }

    /**
     * Apply ike-issues#324 parent-coherence rules to a freshly-added
     * subproject. Warning-only at add time — the matching check in
     * {@code ws:scaffold-draft} (which folds verify per #393) is the
     * authoritative gate.
     *
     * @param wsDir          workspace root directory
     * @param subprojectDir  subproject's checked-out directory
     * @param subprojectName subproject name (workspace.yaml key)
     * @param parentInfo     subproject's declared parent
     */
    private void checkParentCoherenceAtAdd(Path wsDir,
                                            Path subprojectDir,
                                            String subprojectName,
                                            PomParentSupport.ParentInfo parentInfo) {
        PomParentSupport.ParentInfo wsParent;
        try {
            wsParent = PomParentSupport.readParent(wsDir.resolve("pom.xml"));
        } catch (Exception e) {
            // Workspace pom unreadable or no parent — nothing to enforce.
            return;
        }
        if (wsParent == null
                || wsParent.groupId() == null
                || wsParent.artifactId() == null) {
            return;
        }

        // Decision matrix gate: same GA as workspace's parent?
        if (!java.util.Objects.equals(wsParent.groupId(), parentInfo.groupId())
                || !java.util.Objects.equals(wsParent.artifactId(),
                        parentInfo.artifactId())) {
            return;
        }

        String coords = parentInfo.groupId() + ":" + parentInfo.artifactId();

        // Rule 2: version coherence
        if (!java.util.Objects.equals(wsParent.version(), parentInfo.version())) {
            getLog().warn("  WARN: " + subprojectName + " parent "
                    + coords + ":" + parentInfo.version()
                    + " != workspace " + coords + ":" + wsParent.version()
                    + " (#324 coherence violation — fix before "
                    + WsGoal.RELEASE_PUBLISH.qualified() + " or expect "
                    + WsGoal.SCAFFOLD_DRAFT.qualified()
                    + " to flag it)");
            return;
        }

        // Rule 1: cycle prevention
        boolean hasEmptyRelativePath;
        try {
            hasEmptyRelativePath = PomParentSupport.hasEmptyRelativePath(
                    subprojectDir.resolve("pom.xml"));
        } catch (Exception e) {
            return;
        }
        if (!hasEmptyRelativePath) {
            getLog().warn("  WARN: " + subprojectName + " parent "
                    + coords + ":" + parentInfo.version()
                    + " matches workspace parent but is missing empty "
                    + "<relativePath/> (#324 cycle prevention — Maven 4 "
                    + "will fail with \"parents form a cycle\" once the "
                    + "subproject participates in the reactor; add "
                    + "<relativePath/> inside the <parent> block)");
        }
    }

    private Path findWorkspaceRoot() throws MojoException {
        Path dir = Path.of(System.getProperty("user.dir"));
        while (dir != null) {
            if (Files.exists(dir.resolve("workspace.yaml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new MojoException(
                "Cannot find workspace.yaml. Run from within a workspace "
                + "directory or use " + WsGoal.SCAFFOLD_INIT.qualified()
                + " first.");
    }
}
