package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;

import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Create a new IKE workspace from scratch.
 *
 * <p>Generates the standard workspace scaffolding in the current
 * directory (or a named subdirectory): reactor POM, workspace.yaml,
 * .gitignore, .mvn/maven.config, and a README.adoc. Optionally
 * initializes git and sets the remote.
 *
 * <p>The generated files follow current IKE conventions:
 * <ul>
 *   <li>POM uses Maven 4.1.0 model with {@code root="true"}</li>
 *   <li>.gitignore uses whitelist strategy (ignore everything,
 *       whitelist workspace-owned files)</li>
 *   <li>workspace.yaml has schema-version 1.0 with empty subprojects</li>
 *   <li>.mvn/maven.config sets {@code -T 1C}</li>
 * </ul>
 *
 * <p>After creation, use {@code ws:add} to add subproject repos,
 * then {@code ws:init} to clone them.
 *
 * <pre>{@code
 * mvn ws:create -Dname=my-workspace
 * mvn ws:create -Dname=my-workspace -Dorg=knowledge-graphlet
 * }</pre>
 *
 * @see WsAddMojo for adding subprojects to an existing workspace
 * @see WsUpgradeDraftMojo for upgrading workspace conventions
 */
@org.apache.maven.api.plugin.annotations.Mojo(name = "create", projectRequired = false)
public class WsCreateMojo implements Mojo {

    /** Maven logger, injected by the Maven 4 DI container. */
    @Inject
    private Log log;

    /** Access the Maven logger. */
    private Log getLog() { return log; }

    /**
     * Workspace name. Used as the directory name, Maven artifactId,
     * and in generated documentation. Prompted if omitted.
     */
    @Parameter(property = "name")
    private String name;

    /**
     * Short description of the workspace purpose. Defaults to the
     * workspace name, which is also used as the Maven POM {@code <name>}
     * element (shown in Maven build output).
     */
    @Parameter(property = "description")
    private String description;

    /**
     * GitHub organization or user for the remote URL.
     * If set, the remote is configured as
     * {@code https://github.com/<org>/<name>.git}.
     */
    @Parameter(property = "org")
    private String org;

    /**
     * Default Maven version for subprojects. Written to
     * {@code defaults.maven-version} in workspace.yaml.
     */
    @Parameter(property = "mavenVersion", defaultValue = "4.0.0-rc-5")
    private String mavenVersion;

    /**
     * Default branch for subprojects. Written to
     * {@code defaults.branch} in workspace.yaml.
     */
    @Parameter(property = "branch", defaultValue = "main")
    private String defaultBranch;

    /**
     * Skip git init and remote setup.
     */
    @Parameter(property = "skipGit", defaultValue = "false")
    private boolean skipGit;

    /** Creates this goal instance. */
    public WsCreateMojo() {}

    @Override
    public void execute() throws MojoException {
        if (name == null || name.isBlank()) {
            name = promptParam("name", "Workspace name");
        }

        // Default description to the workspace name
        if (description == null || description.isBlank()) {
            description = name;
        }

        Path wsDir = Path.of(System.getProperty("user.dir")).resolve(name);

        // Fail if workspace files already exist (prevent silent overwrite)
        if (Files.exists(wsDir.resolve("pom.xml"))
                || Files.exists(wsDir.resolve("workspace.yaml"))) {
            throw new MojoException(
                    "Workspace already exists at " + wsDir
                    + " (pom.xml or workspace.yaml found). "
                    + "Remove the directory first or choose a different name.");
        }

        getLog().info("");
        getLog().info(name + " — Create");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Name:      " + name);
        getLog().info("  Directory: " + wsDir);
        if (org != null && !org.isBlank()) {
            getLog().info("  Remote:    https://github.com/" + org + "/" + name + ".git");
        }
        getLog().info("");

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
            installMavenWrapper(wsDir);

            getLog().info(Ansi.green("  ✓ ") + "pom.xml");
            getLog().info(Ansi.green("  ✓ ") + "workspace.yaml");
            getLog().info(Ansi.green("  ✓ ") + ".gitignore");
            getLog().info(Ansi.green("  ✓ ") + ".mvn/maven.config");
            getLog().info(Ansi.green("  ✓ ") + ".mvn/jvm.config");
            getLog().info(Ansi.green("  ✓ ") + "README.adoc");
            getLog().info(Ansi.green("  ✓ ") + "mvnw (Maven " + mavenVersion + ")");

        } catch (IOException e) {
            throw new MojoException(
                    "Failed to create workspace files: " + e.getMessage(), e);
        }

        // Git init
        if (!skipGit) {
            try {
                initGit(wsDir);
            } catch (Exception e) {
                getLog().warn("  Git init failed (non-fatal): " + e.getMessage());
                getLog().warn("  Initialize git manually in " + wsDir);
            }
        }

        getLog().info("");
        getLog().info(Ansi.green("  ✓ ") + "Workspace created: " + wsDir);
        getLog().info("");
        getLog().info(Ansi.yellow("  ⚠  You must change into the workspace directory before running ws: goals:"));
        getLog().info("");
        getLog().info("    " + Ansi.cyan("cd " + name));
        getLog().info("    mvn ws:add -Drepo=<git-url>    # add components");
        getLog().info("    mvn ws:init                     # clone components");
        getLog().info("");
        WorkspaceReport.write(wsDir, WsGoal.CREATE.qualified(),
                "Created workspace **" + name + "**\n\nDirectory: `" + wsDir + "`\n", null);
    }

    // ── File generators (pure, testable) ─────────────────────────

    String generatePom() {
        // ike-parent version is the same as ike-platform version (reactor sibling).
        String parentVersion = loadBuildProperty("ike-platform.version");

        StringBuilder xml = new StringBuilder(2048);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<!--\n");
        xml.append("  ").append(description).append("\n");
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
        xml.append("    mvn ws:init                              # Clone all repos\n");
        xml.append("    mvn ws:overview                          # Workspace overview\n");
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
        xml.append("    <groupId>local.aggregate</groupId>\n");
        xml.append("    <artifactId>").append(name).append("</artifactId>\n");
        xml.append("    <version>1-SNAPSHOT</version>\n");
        xml.append("    <packaging>pom</packaging>\n\n");
        xml.append("    <name>").append(description).append("</name>\n\n");
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
        xml.append("    <!-- Profiles are added by ws:add -->\n");
        xml.append("    <profiles>\n");
        xml.append("    </profiles>\n\n");
        xml.append("</project>\n");
        return xml.toString();
    }

    String generateManifest() {
        String today = LocalDate.now().toString();
        String orgName = org != null ? org : "<org>";

        StringBuilder yaml = new StringBuilder(1024);
        yaml.append("# workspace.yaml — ").append(name).append("\n");
        yaml.append("# ").append("═".repeat(name.length() + 22)).append("\n");
        yaml.append("#\n");
        yaml.append("# ").append(description).append("\n");
        yaml.append("#\n");
        yaml.append("# Bootstrap:\n");
        yaml.append("#   git clone https://github.com/").append(orgName).append("/").append(name).append(".git\n");
        yaml.append("#   cd ").append(name).append("\n");
        yaml.append("#   mvn ws:init\n");
        yaml.append("#   mvn clean install\n\n");
        yaml.append("schema-version: \"1.0\"\n");
        yaml.append("generated: ").append(today).append("\n\n");
        yaml.append("defaults:\n");
        yaml.append("  branch: ").append(defaultBranch).append("\n");
        yaml.append("  maven-version: \"").append(mavenVersion).append("\"\n\n");
        yaml.append("subprojects:\n");
        yaml.append("  # Add subprojects with: mvn ws:add -Drepo=<git-url>\n\n");
        yaml.append("# Optional: IntelliJ project settings shared across collaborators.\n");
        yaml.append("# Uncomment and set to have `ws:upgrade` enforce these values in\n");
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
     * repos cloned by {@code ws:init}, so they must stay ignored at
     * the workspace level.
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
        gi.append("# ").append(name).append(" .gitignore\n");
        gi.append("# ").append("═".repeat(name.length() + 11)).append("\n");
        gi.append("#\n");
        gi.append("# Ignore everything, whitelist only workspace-owned files.\n");
        gi.append("# Subproject repos are independent git repos cloned by ws:init.\n\n");
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

    String generateReadme() {
        String orgName = org != null ? org : "<org>";

        StringBuilder adoc = new StringBuilder(1024);
        adoc.append("= ").append(description).append("\n");
        adoc.append(":toc:\n");
        adoc.append(":toc-placement!:\n\n");
        adoc.append(description).append("\n\n");
        adoc.append("toc::[]\n\n");
        adoc.append("== Bootstrap\n\n");
        adoc.append("[source,bash]\n");
        adoc.append("----\n");
        adoc.append("git clone https://github.com/").append(orgName).append("/").append(name).append(".git\n");
        adoc.append("cd ").append(name).append("\n");
        adoc.append("mvn ws:init         # <1>\n");
        adoc.append("mvn clean install   # <2>\n");
        adoc.append("----\n");
        adoc.append("<1> Clones all subproject repos in dependency order; installs Maven\n");
        adoc.append("    wrapper and JVM config per subproject.\n");
        adoc.append("<2> Builds the full stack.\n\n");
        adoc.append("== Workspace Commands\n\n");
        adoc.append("All `ws:` goals appear in the IntelliJ Maven tool window\n");
        adoc.append("(under _Plugins > ws_). Double-click any goal to run it.\n\n");
        adoc.append("[source,bash]\n");
        adoc.append("----\n");
        adoc.append("mvn ws:overview          # Workspace overview\n");
        adoc.append("mvn ws:add -Drepo=      # Add a subproject repo\n");
        adoc.append("mvn ws:upgrade          # Upgrade workspace conventions\n");
        adoc.append("----\n");
        return adoc.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void initGit(Path wsDir) throws MojoException {
        ReleaseSupport.exec(wsDir.toFile(), getLog(),
                "git", "init");
        getLog().info(Ansi.green("  ✓ ") + "git init");

        if (org != null && !org.isBlank()) {
            String remoteUrl = "https://github.com/" + org + "/" + name + ".git";
            ReleaseSupport.exec(wsDir.toFile(), getLog(),
                    "git", "remote", "add", "origin", remoteUrl);
            getLog().info(Ansi.green("  ✓ ") + "remote: " + remoteUrl);
        }

        // Auto-commit scaffold so ws:add and ws:feature-start
        // have a baseline commit to work from.
        try {
            ReleaseSupport.exec(wsDir.toFile(), getLog(),
                    "git", "add", ".");
            ReleaseSupport.exec(wsDir.toFile(), getLog(),
                    "git", "commit", "-m", "workspace: initial scaffold");
            getLog().info(Ansi.green("  ✓ ") + "initial commit");
        } catch (MojoException e) {
            getLog().warn("  Auto-commit failed (set git user.email/user.name): "
                    + e.getMessage());
        }
    }

    /**
     * Install Maven wrapper (mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties)
     * at the workspace root so the aggregator POM resolves the correct Maven version.
     */
    private void installMavenWrapper(Path wsDir) throws IOException {
        Path wrapperDir = wsDir.resolve(".mvn").resolve("wrapper");
        Files.createDirectories(wrapperDir);

        String props = "# Maven Wrapper properties — managed by ws:init from workspace.yaml\n"
                + "maven.version=" + mavenVersion + "\n"
                + "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/"
                + "apache-maven/" + mavenVersion + "/apache-maven-" + mavenVersion
                + "-bin.zip\n";
        Files.writeString(wrapperDir.resolve("maven-wrapper.properties"), props,
                StandardCharsets.UTF_8);

        Path mvnw = wsDir.resolve("mvnw");
        String script = """
                #!/bin/sh
                # Maven Wrapper launcher — installed by ws:init
                # Downloads and caches the Maven version specified in
                # .mvn/wrapper/maven-wrapper.properties
                #
                # This is a minimal bootstrap. For the full-featured wrapper script,
                # run: mvn wrapper:wrapper

                set -e

                PROPS_FILE="$(dirname "$0")/.mvn/wrapper/maven-wrapper.properties"
                if [ ! -f "$PROPS_FILE" ]; then
                    echo "Error: $PROPS_FILE not found" >&2
                    exit 1
                fi

                DIST_URL=$(grep '^distributionUrl=' "$PROPS_FILE" | cut -d'=' -f2-)
                MAVEN_VERSION=$(grep '^maven.version=' "$PROPS_FILE" | cut -d'=' -f2-)

                WRAPPER_HOME="${HOME}/.m2/wrapper/dists/apache-maven-${MAVEN_VERSION}"
                MAVEN_HOME="${WRAPPER_HOME}/apache-maven-${MAVEN_VERSION}"

                if [ ! -d "$MAVEN_HOME" ]; then
                    echo "Downloading Maven ${MAVEN_VERSION}..."
                    mkdir -p "$WRAPPER_HOME"
                    ZIP_FILE="${WRAPPER_HOME}/apache-maven-${MAVEN_VERSION}-bin.zip"
                    curl -fsSL -o "$ZIP_FILE" "$DIST_URL"
                    unzip -qo "$ZIP_FILE" -d "$WRAPPER_HOME"
                    rm -f "$ZIP_FILE"
                    echo "Maven ${MAVEN_VERSION} installed to ${MAVEN_HOME}"
                fi

                exec "$MAVEN_HOME/bin/mvn" "$@"
                """;
        Files.writeString(mvnw, script, StandardCharsets.UTF_8);
        mvnw.toFile().setExecutable(true);

        Path mvnwCmd = wsDir.resolve("mvnw.cmd");
        String cmdScript = """
                @REM Maven Wrapper launcher — installed by ws:init
                @REM Downloads and caches the Maven version specified in
                @REM .mvn/wrapper/maven-wrapper.properties
                @echo off
                setlocal

                set "PROPS_FILE=%~dp0.mvn\\wrapper\\maven-wrapper.properties"
                if not exist "%PROPS_FILE%" (
                    echo Error: %PROPS_FILE% not found >&2
                    exit /b 1
                )

                for /f "tokens=1,* delims==" %%a in ('findstr "^maven.version=" "%PROPS_FILE%"') do set "MAVEN_VERSION=%%b"
                for /f "tokens=1,* delims==" %%a in ('findstr "^distributionUrl=" "%PROPS_FILE%"') do set "DIST_URL=%%b"

                set "WRAPPER_HOME=%USERPROFILE%\\.m2\\wrapper\\dists\\apache-maven-%MAVEN_VERSION%"
                set "MAVEN_HOME=%WRAPPER_HOME%\\apache-maven-%MAVEN_VERSION%"

                if not exist "%MAVEN_HOME%" (
                    echo Downloading Maven %MAVEN_VERSION%...
                    mkdir "%WRAPPER_HOME%" 2>nul
                    set "ZIP_FILE=%WRAPPER_HOME%\\apache-maven-%MAVEN_VERSION%-bin.zip"
                    powershell -Command "Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%ZIP_FILE%'"
                    powershell -Command "Expand-Archive -Path '%ZIP_FILE%' -DestinationPath '%WRAPPER_HOME%' -Force"
                    del "%ZIP_FILE%"
                    echo Maven %MAVEN_VERSION% installed to %MAVEN_HOME%
                )

                "%MAVEN_HOME%\\bin\\mvn.cmd" %*
                """;
        Files.writeString(mvnwCmd, cmdScript, StandardCharsets.UTF_8);
    }

    /**
     * Load a build-time property from ws-plugin.properties (resolved
     * by Maven resource filtering during the build).
     */
    private String loadBuildProperty(String key) {
        try (var is = getClass().getResourceAsStream("ws-plugin.properties")) {
            if (is != null) {
                var props = new java.util.Properties();
                props.load(is);
                String value = props.getProperty(key);
                if (value != null && !value.isBlank() && !value.startsWith("${")) {
                    return value;
                }
            }
        } catch (IOException e) {
            // Fall through to fallback
        }
        // Fallback: use JAR manifest version
        String jarVersion = getClass().getPackage().getImplementationVersion();
        return jarVersion != null ? jarVersion : "66";
    }

    private void writeFile(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private String promptParam(String propertyName, String label)
            throws MojoException {
        java.io.Console console = System.console();
        if (console != null) {
            String input = console.readLine(label + ": ");
            if (input != null && !input.isBlank()) {
                return input.trim();
            }
        }
        throw new MojoException(
                propertyName + " is required. Specify -D" + propertyName + "=<value>");
    }
}
