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
                          String parentVersion) {}

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
        xml.append("  Every subproject is inside a file-activated profile so the reactor\n");
        xml.append("  automatically includes only the repos that are physically cloned.\n");
        xml.append("  Clone more repos and they join the reactor on the next build.\n");

        xml.append("\n");
        xml.append("  Usage:\n");
        xml.append("    mvn clean install                        # All cloned repos\n");
        xml.append("    mvn " + WsGoal.SCAFFOLD_INIT.qualified()
                + "                     # Clone all repos\n");
        xml.append("    mvn " + WsGoal.OVERVIEW.qualified()
                + "                          # Workspace overview\n");
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
        xml.append("    <!-- Profiles are added by " + WsGoal.ADD.qualified()
                + " -->\n");
        xml.append("    <profiles>\n");
        xml.append("    </profiles>\n\n");
        xml.append("</project>\n");
        return xml.toString();
    }

    /**
     * Generate the {@code workspace.yaml} manifest content. Package-private
     * for tests.
     *
     * @return the YAML text
     */
    String generateManifest() {
        String today = LocalDate.now().toString();
        String orgName = params.org() != null ? params.org() : "<org>";

        StringBuilder yaml = new StringBuilder(1024);
        yaml.append("# workspace.yaml — ").append(params.name()).append("\n");
        yaml.append("# ").append("═".repeat(params.name().length() + 22)).append("\n");
        yaml.append("#\n");
        yaml.append("# ").append(params.description()).append("\n");
        yaml.append("#\n");
        yaml.append("# Bootstrap:\n");
        yaml.append("#   git clone https://github.com/").append(orgName).append("/").append(params.name()).append(".git\n");
        yaml.append("#   cd ").append(params.name()).append("\n");
        yaml.append("#   mvn " + WsGoal.SCAFFOLD_INIT.qualified() + "\n");
        yaml.append("#   mvn clean install\n\n");
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
        yaml.append("# ide:\n");
        yaml.append("#   language-level: JDK_25_PREVIEW\n");
        yaml.append("#   jdk-name: \"25\"\n");
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
     * User-specific state ({@code workspace.xml}, {@code shelf/},
     * {@code httpRequests/}) is excluded by IntelliJ's own
     * {@code .idea/.gitignore}.
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
        gi.append("# Maven reload or per workspace membership.\n");
        gi.append("!.idea/\n");
        gi.append("!.idea/.gitignore\n");
        gi.append("!.idea/misc.xml\n");
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
