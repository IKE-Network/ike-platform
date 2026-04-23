package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.workspace.Subproject;
import network.ike.workspace.Dependency;
import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestException;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.PublishedArtifactSet;

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
 * @see WsCreateMojo for creating a new workspace
 * @see InitWorkspaceMojo for cloning all subprojects
 */
@Mojo(name = "add", projectRequired = false)
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
     * Subproject type. Must match a value in
     * {@link network.ike.workspace.SubprojectType} (e.g.
     * {@code software}, {@code infrastructure}, {@code document}).
     */
    @Parameter(property = "type", defaultValue = "software")
    private String type;

    /**
     * Short description of the subproject.
     */
    @Parameter(property = "description")
    private String description;

    /**
     * Branch to track. If omitted, uses the workspace default.
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
     * so they will be empty. Use {@code ws:init} to clone later.
     */
    @Parameter(property = "skipClone", defaultValue = "false")
    private boolean skipClone;

    /** Derived dependency with optional version-property name. */
    record DerivedDep(String subproject, String versionProperty) {}

    /** Creates this goal instance. */
    public WsAddMojo() {}

    @Override
    public void execute() throws MojoException {
        repo = requireParam(repo, "repo", "Git repository URL");

        // Resolve workspace root
        Path wsDir = findWorkspaceRoot();
        Path manifestPath = wsDir.resolve("workspace.yaml");
        Path pomPath = wsDir.resolve("pom.xml");

        if (!Files.exists(manifestPath)) {
            throw new MojoException(
                    "No workspace.yaml found in " + wsDir
                    + ". Run ws:create first.");
        }

        // Derive subproject name from URL if not specified
        if (subproject == null || subproject.isBlank()) {
            subproject = deriveSubprojectName(repo);
        }

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

            // Detect parent POM — match against workspace subprojects
            try {
                PomParentSupport.ParentInfo parentInfo =
                        PomParentSupport.readParent(subprojectDir.resolve("pom.xml"));
                if (parentInfo != null) {
                    Manifest existing = ManifestReader.read(manifestPath);
                    for (Map.Entry<String, Subproject> candidate :
                            existing.subprojects().entrySet()) {
                        if (candidate.getValue().groupId() != null
                                && candidate.getValue().groupId().equals(parentInfo.groupId())) {
                            detectedParent = candidate.getKey();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                // Non-fatal — parent detection is best-effort
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
        getLog().info("  Type:       " + type);
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
            getLog().info("  Subproject added. Run 'mvn ws:init' to clone.");
        }
        getLog().info("");
        writeReport(WsGoal.ADD, "Added subproject **" + subproject + "**\n\n"
                + "| Field | Value |\n|-------|-------|\n"
                + "| Repo | " + repo + " |\n"
                + "| Type | " + type + " |\n"
                + "| Cloned | " + (cloned ? "yes" : "no — run ws:init") + " |\n");
    }

    // ── YAML generation ──────────────────────────────────────────

    void appendSubprojectToManifest(Path manifestPath, List<DerivedDep> derivedDeps,
                                     String detectedParent)
            throws IOException {
        String yaml = Files.readString(manifestPath, StandardCharsets.UTF_8);

        StringBuilder entry = new StringBuilder();
        entry.append("\n  ").append(subproject).append(":\n");
        entry.append("    type: ").append(type).append("\n");
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

        // Append at end of file — groups:/component-types: are no longer
        // part of the schema (#167, #150), so there is no section to
        // insert before.
        yaml = yaml + entry;

        Files.writeString(manifestPath, yaml, StandardCharsets.UTF_8);
    }

    /**
     * Update the depends-on section for an existing subproject in
     * workspace.yaml. Replaces the current depends-on block with
     * the newly derived dependencies.
     */
    void updateSubprojectDependencies(Path manifestPath, String subprojectName,
                                      List<DerivedDep> derivedDeps) throws IOException {
        String yaml = Files.readString(manifestPath, StandardCharsets.UTF_8);

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
            yaml = yaml.substring(0, m.start(2))
                    + newDeps
                    + yaml.substring(m.end(2));
        }

        Files.writeString(manifestPath, yaml, StandardCharsets.UTF_8);
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

    // ── Clone ────────────────────────────────────────────────────

    private void cloneSubproject(Path wsDir) throws MojoException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("clone");
        cmd.add("--depth");
        cmd.add("1");
        if (branch != null && !branch.isBlank()) {
            cmd.add("-b");
            cmd.add(branch);
        }
        cmd.add(repo);
        cmd.add(subproject);
        ReleaseSupport.exec(wsDir.toFile(), getLog(), cmd.toArray(new String[0]));
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
    private List<DerivedDep> deriveDependencies(Path wsDir, Path manifestPath,
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

        List<DerivedDep> matched = new ArrayList<>();
        for (Map.Entry<String, Subproject> entry : manifest.subprojects().entrySet()) {
            String existingName = entry.getKey();
            Subproject existingSub = entry.getValue();

            // Never depend on yourself
            if (existingName.equals(subprojectName)) continue;

            Path existingDir = wsDir.resolve(existingName);
            if (!Files.exists(existingDir.resolve("pom.xml"))) continue;

            // Build the published artifact set for the existing subproject
            Set<PublishedArtifactSet.Artifact> published =
                    PublishedArtifactSet.scan(existingDir);

            // Check if any referenced artifact is published by this subproject
            for (PublishedArtifactSet.Artifact artifact : published) {
                String key = artifact.groupId() + ":" + artifact.artifactId();
                if (referencedArtifacts.contains(key)) {
                    // Try to find a property whose value matches the upstream version
                    String versionProperty = null;
                    String upstreamVersion = existingSub.version();
                    if (upstreamVersion != null && !newSubProperties.isEmpty()) {
                        for (Map.Entry<String, String> prop : newSubProperties.entrySet()) {
                            if (upstreamVersion.equals(prop.getValue())) {
                                versionProperty = prop.getKey();
                                break;
                            }
                        }
                    }
                    matched.add(new DerivedDep(existingName, versionProperty));
                    break;
                }
            }
        }

        return matched.isEmpty() ? null : matched;
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
        // Build the published artifact set for the new subproject
        Set<PublishedArtifactSet.Artifact> newPublished =
                PublishedArtifactSet.scan(newSubprojectDir);
        if (newPublished.isEmpty()) return 0;

        // Build a lookup set of "groupId:artifactId" strings
        Set<String> newArtifactKeys = new LinkedHashSet<>();
        for (PublishedArtifactSet.Artifact a : newPublished) {
            newArtifactKeys.add(a.groupId() + ":" + a.artifactId());
        }

        String yaml = Files.readString(manifestPath, StandardCharsets.UTF_8);
        Manifest manifest = ManifestReader.read(manifestPath);
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
            // published by the new subproject
            Path existingPom = wsDir.resolve(existingName).resolve("pom.xml");
            if (!Files.exists(existingPom)) continue;

            Set<String> referenced = extractReferencedArtifacts(existingPom);
            boolean dependsOnNew = referenced.stream()
                    .anyMatch(newArtifactKeys::contains);

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
     * <p>Scans {@code <parent>} and {@code <dependencies>} blocks,
     * but excludes {@code <dependencyManagement>} (which contains
     * BOM imports and version constraints, not build dependencies).
     *
     * @return set of "groupId:artifactId" strings
     */
    private Set<String> extractReferencedArtifacts(Path pomFile) throws IOException {
        Set<String> artifacts = new LinkedHashSet<>();
        scanPomForArtifacts(pomFile, artifacts);
        return artifacts;
    }

    /**
     * Recursively scan a POM and its submodules for referenced
     * groupId:artifactId pairs using DOM parsing with property
     * resolution.
     */
    private void scanPomForArtifacts(Path pomFile, Set<String> artifacts)
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

        // Extract parent groupId:artifactId
        Element parentEl = firstChild(project, "parent");
        if (parentEl != null) {
            String gid = resolve(childText(parentEl, "groupId"), properties);
            String aid = resolve(childText(parentEl, "artifactId"), properties);
            if (gid != null && aid != null) {
                artifacts.add(gid + ":" + aid);
            }
        }

        // Extract dependency groupId:artifactId — skip dependencyManagement
        Element depsEl = firstChild(project, "dependencies");
        if (depsEl != null) {
            for (Element dep : children(depsEl, "dependency")) {
                String gid = resolve(childText(dep, "groupId"), properties);
                String aid = resolve(childText(dep, "artifactId"), properties);
                if (gid != null && aid != null) {
                    artifacts.add(gid + ":" + aid);
                }
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
                + "directory or use ws:create first.");
    }
}
