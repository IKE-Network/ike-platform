package network.ike.plugin.ws.bootstrap;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.Ansi;
import network.ike.plugin.ws.MavenWrapper;
import network.ike.plugin.ws.WsGoal;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Generates the on-disk scaffold for a brand-new IKE workspace.
 *
 * <p>Subsumes the file-generation half of the retired
 * {@code WsCreateMojo} (folded into {@code ws:scaffold-init} per
 * IKE-Network/ike-issues#393): given a target directory and a small
 * parameter bag, writes the standard workspace files
 * ({@code pom.xml}, {@code workspace.yaml}, {@code .gitignore},
 * {@code .mvn/maven.config}, {@code .mvn/jvm.config},
 * {@code README.adoc}) and installs the Maven wrapper via
 * {@link MavenWrapper#writeMissingFiles}.
 *
 * <p>The generated files follow current IKE conventions:
 * <ul>
 *   <li>POM uses Maven 4.1.0 model with {@code root="true"}</li>
 *   <li>.gitignore uses whitelist strategy (ignore everything,
 *       whitelist workspace-owned files)</li>
 *   <li>workspace.yaml has schema-version 1.1 with a typed
 *       {@code workspace-root:} block holding the workspace's GAV
 *       (ike-issues#183) and an empty {@code subprojects:} list</li>
 *   <li>.mvn/maven.config sets {@code -T 1C}</li>
 * </ul>
 *
 * <p>This class is a pure scaffold writer — it does not consult
 * {@code workspace.yaml} (none exists yet), does not iterate
 * subprojects, and never extends {@code AbstractWorkspaceMojo}.
 *
 * @see SubprojectInitializer for the "workspace.yaml already exists"
 *      half of {@code ws:scaffold-init}
 */
public final class WorkspaceBootstrap {

    /** Parameters captured from the user-facing mojo. */
    public record Params(String name,
                          String description,
                          String org,
                          String group,
                          String artifactId,
                          String version,
                          String mavenVersion,
                          String defaultBranch,
                          boolean skipGit,
                          String parentVersion,
                          String extensionVersion) {}

    private final Params params;
    private final Log log;

    /**
     * Create a workspace bootstrapper bound to a parameter bag and a
     * logger. Construction is cheap; the actual filesystem writes
     * happen in {@link #createAt(Path)}.
     *
     * @param params the resolved create-time parameters
     * @param log    the mojo logger
     */
    public WorkspaceBootstrap(Params params, Log log) {
        this.params = params;
        this.log = log;
    }

    /**
     * Write the full workspace scaffold into {@code wsDir} and
     * optionally initialize git. The directory must not already
     * contain {@code pom.xml} or {@code workspace.yaml}; the caller
     * is responsible for that pre-check.
     *
     * @param wsDir the workspace directory to populate
     * @throws MojoException on file or git failures
     */
    public void createAt(Path wsDir) throws MojoException {
        try {
            Files.createDirectories(wsDir);
            Files.createDirectories(wsDir.resolve(".mvn"));

            writeFile(wsDir.resolve("pom.xml"), generatePom());
            writeFile(wsDir.resolve("workspace.yaml"), generateManifest());
            writeFile(wsDir.resolve(".gitignore"), generateGitignore());
            writeFile(wsDir.resolve(".mvn/maven.config"), "-T 1C\n");
            writeFile(wsDir.resolve(".mvn/extensions.xml"), generateExtensionsXml());
            // .mvn/jvm.config is parsed as raw JVM args, one token per line,
            // with NO comment syntax. A `#` at column 0 is passed to the JVM
            // as a main-class name and fails with ClassNotFoundException: #.
            // Seed a single standard flag so downstream hand-edits start
            // from a correct baseline. The flag suppresses sun.misc.Unsafe
            // deprecation warnings from JRuby/AsciidoctorJ on JDK 24+.
            writeFile(wsDir.resolve(".mvn/jvm.config"),
                    "--sun-misc-unsafe-memory-access=allow\n");
            writeFile(wsDir.resolve("README.adoc"), generateReadme());
            MavenWrapper.writeMissingFiles(wsDir, params.mavenVersion());

            log.info(Ansi.green("  ✓ ") + "pom.xml");
            log.info(Ansi.green("  ✓ ") + "workspace.yaml");
            log.info(Ansi.green("  ✓ ") + ".gitignore");
            log.info(Ansi.green("  ✓ ") + ".mvn/maven.config");
            log.info(Ansi.green("  ✓ ") + ".mvn/extensions.xml");
            log.info(Ansi.green("  ✓ ") + ".mvn/jvm.config");
            log.info(Ansi.green("  ✓ ") + "README.adoc");
            log.info(Ansi.green("  ✓ ") + "mvnw (Maven " + params.mavenVersion() + ")");

        } catch (IOException e) {
            throw new MojoException(
                    "Failed to create workspace files: " + e.getMessage(), e);
        }

        if (!params.skipGit()) {
            try {
                initGit(wsDir);
            } catch (Exception e) {
                log.warn("  Git init failed (non-fatal): " + e.getMessage());
                log.warn("  Initialize git manually in " + wsDir);
            }
        }
    }

    // ── File generators (pure, testable) ─────────────────────────

    /**
     * Generate the workspace root POM content. Package-private so
     * tests can invoke it directly.
     *
     * @return the {@code pom.xml} text
     */
    String generatePom() {
        // ike-parent version is the same as ike-platform version (reactor sibling).
        String parentVersion = params.parentVersion();

        StringBuilder xml = new StringBuilder(2048);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<!--\n");
        xml.append("  ").append(params.description()).append("\n");
        xml.append("\n");
        xml.append("  Inherits from ike-parent to get plugin version management for\n");
        xml.append("  ike-maven-plugin and ike-workspace-maven-plugin automatically.\n");
        xml.append("\n");
        xml.append("  Subprojects are declared unconditionally at top level.\n");
        xml.append("  ike-workspace-extension (registered in .mvn/extensions.xml)\n");
        xml.append("  prunes entries whose directory is missing from disk before\n");
        xml.append("  Maven's model validator gets to them — so a fresh clone\n");
        xml.append("  bootstraps via ").append(WsGoal.SCAFFOLD_INIT.qualified()).append(" without manual edits.\n");
        xml.append("  See IKE-Network/ike-issues#460.\n");
        xml.append("\n");
        xml.append("  Usage:\n");
        xml.append("    mvn clean install                        # All cloned repos\n");
        xml.append("    mvn ").append(WsGoal.SCAFFOLD_INIT.qualified())
                .append("                     # Clone all repos\n");
        xml.append("    mvn ").append(WsGoal.OVERVIEW.qualified())
                .append("                          # Workspace overview\n");
        xml.append("-->\n");
        xml.append("<project xmlns=\"http://maven.apache.org/POM/4.1.0\"\n");
        xml.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        xml.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.1.0\n");
        xml.append("                             https://maven.apache.org/xsd/maven-4.1.0.xsd\"\n");
        xml.append("         root=\"true\">\n");
        xml.append("    <modelVersion>4.1.0</modelVersion>\n\n");
        xml.append("    <parent>\n");
        xml.append("        <groupId>network.ike.platform</groupId>\n");
        xml.append("        <artifactId>ike-parent</artifactId>\n");
        xml.append("        <version>").append(parentVersion).append("</version>\n");
        xml.append("        <relativePath/>\n");
        xml.append("    </parent>\n\n");
        xml.append("    <groupId>").append(params.group()).append("</groupId>\n");
        xml.append("    <artifactId>").append(params.artifactId()).append("</artifactId>\n");
        xml.append("    <version>").append(params.version()).append("</version>\n");
        xml.append("    <packaging>pom</packaging>\n\n");
        xml.append("    <name>").append(params.description()).append("</name>\n\n");
        xml.append("    <build>\n");
        xml.append("        <plugins>\n");
        xml.append("            <plugin>\n");
        xml.append("                <groupId>network.ike.tooling</groupId>\n");
        xml.append("                <artifactId>ike-maven-plugin</artifactId>\n");
        xml.append("                <!-- version from ike-parent pluginManagement -->\n");
        xml.append("            </plugin>\n");
        xml.append("            <plugin>\n");
        xml.append("                <groupId>network.ike.platform</groupId>\n");
        xml.append("                <artifactId>ike-workspace-maven-plugin</artifactId>\n");
        xml.append("                <!-- version from ike-parent pluginManagement -->\n");
        xml.append("            </plugin>\n");
        xml.append("        </plugins>\n");
        xml.append("    </build>\n\n");
        xml.append("    <!-- Subprojects are added by ").append(WsGoal.ADD.qualified())
                .append(" -->\n");
        xml.append("    <subprojects>\n");
        xml.append("    </subprojects>\n\n");
        xml.append("</project>\n");
        return xml.toString();
    }

    // ── .mvn/extensions.xml ─────────────────────────────────────

    /**
     * Sentinel marking the start of the managed extension block in
     * {@code .mvn/extensions.xml}. The block between this marker and
     * {@link #EXTENSIONS_MANAGED_END} is regenerated by
     * {@code ws:scaffold-init} and {@code ws:scaffold-publish}
     * (IKE-Network/ike-issues#460) so the ike-workspace-extension
     * version stays in lockstep with the {@code ike-parent} property.
     */
    public static final String EXTENSIONS_MANAGED_BEGIN =
            "    <!-- ── managed by ws:scaffold-publish (ike-issues#460) ── -->";

    /** Sentinel marking the end of the managed extension block. */
    public static final String EXTENSIONS_MANAGED_END =
            "    <!-- ── /managed ── -->";

    /**
     * Generate the workspace {@code .mvn/extensions.xml}. Carries the
     * {@code wagon-ssh-external} declaration that ike workspaces have
     * shipped since #338, plus the {@code ike-workspace-extension}
     * entry that does the {@code <subprojects>} pruning (#460).
     *
     * <p>The {@code ike-workspace-extension} entry is wrapped in
     * sentinel markers ({@link #EXTENSIONS_MANAGED_BEGIN} /
     * {@link #EXTENSIONS_MANAGED_END}) so subsequent
     * {@code ws:scaffold-publish} runs can refresh the version
     * literal in place. Maven 4 does not interpolate POM properties
     * inside {@code .mvn/extensions.xml} at extension-load time —
     * the version must be a literal — so the literal is sourced
     * from the {@code ike-workspace-extension.version} property
     * declared in {@code ike-parent}.
     *
     * @return the {@code .mvn/extensions.xml} text
     */
    String generateExtensionsXml() {
        StringBuilder xml = new StringBuilder(1024);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<!--\n");
        xml.append("  IKE-Network — .mvn/extensions.xml\n");
        xml.append("\n");
        xml.append("  Maven build extensions loaded at Maven startup, before any\n");
        xml.append("  goal can run. The block between the managed markers below\n");
        xml.append("  is refreshed by ").append(WsGoal.SCAFFOLD_PUBLISH.qualified())
                .append(".\n");
        xml.append("-->\n");
        xml.append("<extensions xmlns=\"http://maven.apache.org/EXTENSIONS/1.2.0\"\n");
        xml.append("            xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        xml.append("            xsi:schemaLocation=\"http://maven.apache.org/EXTENSIONS/1.2.0\n");
        xml.append("                                https://maven.apache.org/xsd/core-extensions-1.2.0.xsd\">\n");
        xml.append("\n");
        xml.append("    <!-- scpexe:// transport for site:deploy to the internal\n");
        xml.append("         mirror. Required by ike-parent's site flow (#338). -->\n");
        xml.append("    <extension>\n");
        xml.append("        <groupId>org.apache.maven.wagon</groupId>\n");
        xml.append("        <artifactId>wagon-ssh-external</artifactId>\n");
        xml.append("        <version>3.5.3</version>\n");
        xml.append("    </extension>\n");
        xml.append("\n");
        xml.append(EXTENSIONS_MANAGED_BEGIN).append("\n");
        xml.append("    <extension>\n");
        xml.append("        <groupId>network.ike.tooling</groupId>\n");
        xml.append("        <artifactId>ike-workspace-extension</artifactId>\n");
        xml.append("        <version>").append(params.extensionVersion()).append("</version>\n");
        xml.append("    </extension>\n");
        xml.append(EXTENSIONS_MANAGED_END).append("\n");
        xml.append("\n");
        xml.append("</extensions>\n");
        return xml.toString();
    }

    /**
     * Refresh the managed ike-workspace-extension block in an
     * existing {@code .mvn/extensions.xml}. Replaces the
     * sentinel-bounded block with a freshly-generated one carrying
     * the current {@code extensionVersion}. When the file predates
     * the sentinel convention (or lacks an extension entry
     * entirely), one-time migrates by appending the managed block
     * before the closing {@code </extensions>} tag.
     *
     * <p>Idempotent. A no-op write is suppressed.
     *
     * @param extensionsXmlPath path to the {@code .mvn/extensions.xml}
     *                          to refresh
     * @param extensionVersion  the literal version to write
     * @return {@code true} if the file was rewritten, {@code false}
     *         when the existing content already matches
     * @throws IOException on read/write failure
     */
    public static boolean refreshExtensionsManagedBlock(Path extensionsXmlPath,
                                                         String extensionVersion)
            throws IOException {
        String existing = Files.readString(extensionsXmlPath, StandardCharsets.UTF_8);
        String freshBlock = EXTENSIONS_MANAGED_BEGIN + "\n"
                + "    <extension>\n"
                + "        <groupId>network.ike.tooling</groupId>\n"
                + "        <artifactId>ike-workspace-extension</artifactId>\n"
                + "        <version>" + extensionVersion + "</version>\n"
                + "    </extension>\n"
                + EXTENSIONS_MANAGED_END + "\n";

        int begin = existing.indexOf(EXTENSIONS_MANAGED_BEGIN);
        int end = existing.indexOf(EXTENSIONS_MANAGED_END);
        String rebuilt;
        if (begin >= 0 && end > begin) {
            int after = end + EXTENSIONS_MANAGED_END.length();
            if (after < existing.length() && existing.charAt(after) == '\n') after++;
            rebuilt = existing.substring(0, begin) + freshBlock + existing.substring(after);
        } else {
            // Migrate: strip any pre-existing standalone
            // ike-workspace-extension <extension> block (operators may
            // have added the entry by hand before this goal existed),
            // then insert the managed block immediately before
            // </extensions>. Maven 4 refuses duplicate <extension>
            // entries by groupId:artifactId, so dedup is essential.
            String stripped = stripExistingExtensionEntry(existing,
                    "network.ike.tooling", "ike-workspace-extension");
            int close = stripped.lastIndexOf("</extensions>");
            if (close < 0) {
                throw new IOException("Cannot locate </extensions> in "
                        + extensionsXmlPath);
            }
            rebuilt = stripped.substring(0, close)
                    + "\n" + freshBlock + "\n"
                    + stripped.substring(close);
        }

        if (rebuilt.equals(existing)) return false;
        Files.writeString(extensionsXmlPath, rebuilt, StandardCharsets.UTF_8);
        return true;
    }

    /**
     * Remove every {@code <extension>...</extension>} block in
     * {@code xml} whose {@code <groupId>} and {@code <artifactId>}
     * children equal the supplied values. Used by the
     * managed-block migration path to drop hand-added entries before
     * inserting the sentinel-wrapped replacement.
     *
     * @param xml         the original XML content
     * @param groupId     the groupId to match
     * @param artifactId  the artifactId to match
     * @return the content with matching extension blocks removed
     */
    private static String stripExistingExtensionEntry(String xml,
                                                       String groupId,
                                                       String artifactId) {
        // Match <extension>...</extension> non-greedily, then test the
        // contents for the GA pair. A regex pass is sufficient here:
        // extension entries are always written as self-contained blocks,
        // never interleaved.
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?s)[ \\t]*<extension>.*?</extension>\\s*");
        java.util.regex.Matcher m = p.matcher(xml);
        StringBuilder out = new StringBuilder(xml.length());
        int last = 0;
        while (m.find()) {
            String block = m.group();
            boolean matches = block.contains("<groupId>" + groupId + "</groupId>")
                    && block.contains("<artifactId>" + artifactId + "</artifactId>");
            out.append(xml, last, m.start());
            if (!matches) {
                out.append(block);
            }
            last = m.end();
        }
        out.append(xml, last, xml.length());
        return out.toString();
    }

    /**
     * Sentinel marking the start of the managed comment header in
     * {@code workspace.yaml}. The block between this marker and
     * {@link #MANAGED_END} is regenerated by {@code ws:scaffold-init}
     * on every run (IKE-Network/ike-issues#458) so the bootstrap
     * instructions stay in lockstep with current goal names.
     */
    public static final String MANAGED_BEGIN =
            "# ── managed: ws:scaffold-init regenerates this block ──";

    /** Sentinel marking the end of the managed comment header. */
    public static final String MANAGED_END = "# ── /managed ──";

    /**
     * Build the managed comment header that leads {@code workspace.yaml}.
     * Wrapped in {@link #MANAGED_BEGIN} / {@link #MANAGED_END} sentinels
     * so {@link #refreshManagedHeader} can replace it in place on every
     * {@code ws:scaffold-init} run (ike-issues#458). Hardcoded goal-name
     * strings are forbidden — they are pulled from {@link WsGoal} so a
     * goal rename propagates.
     *
     * @param name        the workspace name
     * @param description the workspace description; falls back to
     *                    {@code name} when blank
     * @param org         the GitHub org for the clone URL; rendered as
     *                    {@code <org>} when {@code null} or blank
     * @return the sentinel-bounded header, ending in a blank line
     */
    public static String managedHeader(String name, String description, String org) {
        String desc = (description == null || description.isBlank()) ? name : description;
        String orgName = (org == null || org.isBlank()) ? "<org>" : org;

        StringBuilder yaml = new StringBuilder(512);
        yaml.append(MANAGED_BEGIN).append("\n");
        yaml.append("# workspace.yaml — ").append(name).append("\n");
        yaml.append("# ").append("═".repeat(name.length() + 22)).append("\n");
        yaml.append("#\n");
        yaml.append("# ").append(desc).append("\n");
        yaml.append("#\n");
        yaml.append("# Bootstrap:\n");
        yaml.append("#   git clone https://github.com/").append(orgName).append("/").append(name).append(".git\n");
        yaml.append("#   cd ").append(name).append("\n");
        yaml.append("#   mvn ").append(WsGoal.SCAFFOLD_INIT.qualified()).append("\n");
        yaml.append("#   mvn clean install\n");
        yaml.append(MANAGED_END).append("\n\n");
        return yaml.toString();
    }

    /**
     * Refresh the managed comment header in an existing
     * {@code workspace.yaml}. Replaces the block between
     * {@link #MANAGED_BEGIN} and {@link #MANAGED_END} with freshly-
     * generated text. When the file predates the sentinel convention,
     * one-time migrates by stripping the legacy leading {@code #}-prefix
     * comment block (consecutive comment-or-blank lines from the start
     * of file, terminated by the first non-comment non-blank line) and
     * prepending the new sentinel-bounded block.
     *
     * <p>Idempotent. A no-op write is suppressed.
     *
     * @param yamlPath     path to the {@code workspace.yaml} to refresh
     * @param name         the workspace name (typically the workspace
     *                     directory name)
     * @param description  the workspace description; falls back to
     *                     {@code name} when blank
     * @param org          the GitHub org; {@code null} renders as
     *                     {@code <org>} placeholder
     * @return {@code true} if the file was rewritten, {@code false} when
     *         the existing header already matches
     * @throws IOException on read/write failure
     */
    public static boolean refreshManagedHeader(Path yamlPath, String name,
                                                String description, String org)
            throws IOException {
        String existing = Files.readString(yamlPath, StandardCharsets.UTF_8);
        String fresh = managedHeader(name, description, org);

        int begin = existing.indexOf(MANAGED_BEGIN);
        int end = existing.indexOf(MANAGED_END);
        String rebuilt;
        if (begin >= 0 && end > begin) {
            // Sentinel-bounded — replace the block (including trailing
            // newline after MANAGED_END and one optional blank line).
            int after = end + MANAGED_END.length();
            if (after < existing.length() && existing.charAt(after) == '\n') after++;
            if (after < existing.length() && existing.charAt(after) == '\n') after++;
            rebuilt = existing.substring(0, begin) + fresh + existing.substring(after);
        } else {
            // Legacy file with no sentinels — strip the leading comment
            // block (consecutive #/blank lines from start of file).
            int i = 0;
            while (i < existing.length()) {
                int eol = existing.indexOf('\n', i);
                if (eol < 0) eol = existing.length();
                String line = existing.substring(i, eol);
                if (!line.isBlank() && !line.startsWith("#")) break;
                i = eol + (eol < existing.length() ? 1 : 0);
            }
            rebuilt = fresh + existing.substring(i);
        }

        if (rebuilt.equals(existing)) return false;
        Files.writeString(yamlPath, rebuilt, StandardCharsets.UTF_8);
        return true;
    }

    /**
     * Generate the {@code workspace.yaml} manifest content. Package-private
     * for tests.
     *
     * @return the YAML text
     */
    String generateManifest() {
        String today = LocalDate.now().toString();

        StringBuilder yaml = new StringBuilder(1024);
        yaml.append(managedHeader(params.name(), params.description(), params.org()));
        yaml.append("schema-version: \"1.1\"\n");
        yaml.append("generated: ").append(today).append("\n\n");
        // Workspace root coordinates (#183) — real GAV for ws:release-publish,
        // ws:align-publish, and site deploy to address. Single-segment monotonic
        // version (not semver — per feedback_no_semver_assumption).
        yaml.append("workspace-root:\n");
        yaml.append("  groupId: ").append(params.group()).append("\n");
        yaml.append("  artifactId: ").append(params.artifactId()).append("\n");
        yaml.append("  version: ").append(params.version()).append("\n\n");
        yaml.append("defaults:\n");
        yaml.append("  branch: ").append(params.defaultBranch()).append("\n");
        yaml.append("  maven-version: \"").append(params.mavenVersion()).append("\"\n\n");
        yaml.append("subprojects:\n");
        yaml.append("  # Add subprojects with: mvn " + WsGoal.ADD.qualified()
                + " -Drepo=<git-url>\n\n");
        yaml.append("# Optional: IntelliJ project settings shared across collaborators.\n");
        yaml.append("# Uncomment and set to have `" + WsGoal.SCAFFOLD_PUBLISH.qualified()
                + "` enforce these values in\n");
        yaml.append("# .idea/misc.xml on every run. Useful when the project uses\n");
        yaml.append("# --enable-preview (set language-level to JDK_NN_PREVIEW).\n");
        yaml.append("# track-misc-xml commits .idea/misc.xml to git; leave it false\n");
        yaml.append("# (the default) when developers toggle Maven profiles locally,\n");
        yaml.append("# since those toggles live in misc.xml (ike-issues#571).\n");
        yaml.append("# ide:\n");
        yaml.append("#   language-level: JDK_25_PREVIEW\n");
        yaml.append("#   jdk-name: \"25\"\n");
        yaml.append("#   track-misc-xml: false\n");
        return yaml.toString();
    }

    /**
     * Generate the workspace {@code .gitignore} using the whitelist
     * strategy: ignore everything by default, then allowlist only
     * workspace-owned files. Subproject repos are independent git
     * repos cloned by {@code ws:scaffold-init}, so they must stay
     * ignored at the workspace level.
     *
     * <p>The generated file includes a curated {@code .idea/} slice so
     * that fresh checkouts land at the correct IntelliJ project
     * settings (JDK, language level including preview mode, encoding,
     * Maven repositories) without per-collaborator manual setup.
     * {@code compiler.xml} and {@code vcs.xml} are intentionally not
     * allowlisted — they regenerate on every Maven reload or per
     * workspace membership and would cause constant diff churn.
     * {@code misc.xml} is also excluded by default — it co-mingles
     * per-machine Maven profile selection; opt in to tracking it with
     * {@code ide.track-misc-xml: true} in {@code workspace.yaml}
     * (IKE-Network/ike-issues#571). User-specific state
     * ({@code workspace.xml}, {@code shelf/}, {@code httpRequests/}) is
     * excluded by IntelliJ's own {@code .idea/.gitignore}.
     *
     * @return the {@code .gitignore} content
     */
    String generateGitignore() {
        StringBuilder gi = new StringBuilder(768);
        gi.append("# ").append(params.name()).append(" .gitignore\n");
        gi.append("# ").append("═".repeat(params.name().length() + 11)).append("\n");
        gi.append("#\n");
        gi.append("# Ignore everything, whitelist only workspace-owned files.\n");
        gi.append("# Subproject repos are independent git repos cloned by "
                + WsGoal.SCAFFOLD_INIT.qualified() + ".\n\n");
        gi.append("# ── Ignore everything by default ─────────────────────────────────\n");
        gi.append("*\n\n");
        gi.append("# ── Whitelist workspace-level files ──────────────────────────────\n");
        gi.append("!.gitignore\n");
        gi.append("!pom.xml\n");
        gi.append("!workspace.yaml\n");
        gi.append("!README.adoc\n");
        gi.append("!GOALS.md\n");
        gi.append("!WS-REFERENCE.md\n");
        gi.append("!mvnw\n");
        gi.append("!mvnw.cmd\n\n");
        gi.append("# ── Whitelist workspace-owned directories ────────────────────────\n");
        gi.append("!.mvn/\n");
        gi.append("!.mvn/**\n");
        gi.append("!checkpoints/\n");
        gi.append("!checkpoints/**\n");
        gi.append("!.run/\n");
        gi.append("!.run/**\n\n");
        gi.append("# ── IntelliJ project config (curated slice) ──────────────────────\n");
        gi.append("# Small, stable project-wide settings shared across collaborators.\n");
        gi.append("# compiler.xml and vcs.xml are excluded — they regenerate per\n");
        gi.append("# Maven reload or per workspace membership. misc.xml is excluded\n");
        gi.append("# by default (per-machine Maven profile selection); opt in with\n");
        gi.append("# `ide.track-misc-xml: true` in workspace.yaml (ike-issues#571).\n");
        gi.append("!.idea/\n");
        gi.append("!.idea/.gitignore\n");
        gi.append("!.idea/kotlinc.xml\n");
        gi.append("!.idea/encodings.xml\n");
        gi.append("!.idea/jarRepositories.xml\n");
        return gi.toString();
    }

    /**
     * Generate the boilerplate {@code README.adoc}.
     *
     * @return the AsciiDoc content
     */
    String generateReadme() {
        String orgName = params.org() != null ? params.org() : "<org>";

        StringBuilder adoc = new StringBuilder(1024);
        adoc.append("= ").append(params.description()).append("\n");
        adoc.append(":toc:\n");
        adoc.append(":toc-placement!:\n\n");
        adoc.append(params.description()).append("\n\n");
        adoc.append("toc::[]\n\n");
        adoc.append("== Bootstrap\n\n");
        adoc.append("[source,bash]\n");
        adoc.append("----\n");
        adoc.append("git clone https://github.com/").append(orgName).append("/").append(params.name()).append(".git\n");
        adoc.append("cd ").append(params.name()).append("\n");
        adoc.append("mvn " + WsGoal.SCAFFOLD_INIT.qualified() + "   # <1>\n");
        adoc.append("mvn clean install      # <2>\n");
        adoc.append("----\n");
        adoc.append("<1> Clones all subproject repos in dependency order; installs Maven\n");
        adoc.append("    wrapper and JVM config per subproject.\n");
        adoc.append("<2> Builds the full stack.\n\n");
        adoc.append("== Workspace Commands\n\n");
        adoc.append("All `ws:` goals appear in the IntelliJ Maven tool window\n");
        adoc.append("(under _Plugins > ws_). Double-click any goal to run it.\n\n");
        adoc.append("[source,bash]\n");
        adoc.append("----\n");
        adoc.append("mvn " + WsGoal.OVERVIEW.qualified()
                + "          # Workspace overview\n");
        adoc.append("mvn " + WsGoal.ADD.qualified()
                + " -Drepo=      # Add a subproject repo\n");
        adoc.append("mvn " + WsGoal.SCAFFOLD_DRAFT.qualified()
                + "          # Preview scaffold and reconciliation drift\n");
        adoc.append("----\n");
        return adoc.toString();
    }

    // ── Git init ────────────────────────────────────────────────

    private void initGit(Path wsDir) throws MojoException {
        ReleaseSupport.exec(wsDir.toFile(), log, "git", "init");
        log.info(Ansi.green("  ✓ ") + "git init");

        if (params.org() != null && !params.org().isBlank()) {
            String remoteUrl = "https://github.com/" + params.org() + "/"
                    + params.name() + ".git";
            ReleaseSupport.exec(wsDir.toFile(), log,
                    "git", "remote", "add", "origin", remoteUrl);
            log.info(Ansi.green("  ✓ ") + "remote: " + remoteUrl);
        }

        // Auto-commit scaffold so ws:add and ws:feature-start have a
        // baseline commit to work from.
        try {
            ReleaseSupport.exec(wsDir.toFile(), log,
                    "git", "add", ".");
            ReleaseSupport.exec(wsDir.toFile(), log,
                    "git", "commit", "-m", "workspace: initial scaffold");
            log.info(Ansi.green("  ✓ ") + "initial commit");
        } catch (MojoException e) {
            log.warn("  Auto-commit failed (set git user.email/user.name): "
                    + e.getMessage());
        }
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
