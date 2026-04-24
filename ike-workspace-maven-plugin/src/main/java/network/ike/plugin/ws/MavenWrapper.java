package network.ike.plugin.ws;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Shared generator for the three Maven Wrapper files that every workspace
 * needs at the root:
 *
 * <ul>
 *   <li>{@code .mvn/wrapper/maven-wrapper.properties}</li>
 *   <li>{@code mvnw} (POSIX launcher — must be LF; executable bit set)</li>
 *   <li>{@code mvnw.cmd} (Windows launcher — must be CRLF on Windows; see
 *       {@code .gitattributes} {@code *.cmd text eol=crlf} rule)</li>
 * </ul>
 *
 * <p>Used by {@code ws:init} when scaffolding a new workspace, and by
 * {@code ws:scaffold-upgrade-*}'s {@code mvnw-standard} step when any of the three
 * files is missing from an existing workspace.
 *
 * <p>All methods are <b>file-level idempotent</b>: they unconditionally
 * write the file at the given path. Callers are responsible for the
 * "install only if missing" check — see {@link #writeMissingFiles}.
 */
final class MavenWrapper {

    private MavenWrapper() {}

    /**
     * Install any of the three wrapper files that are missing from the
     * given workspace directory. Never overwrites an existing file — the
     * user may have pinned a specific {@code maven.version} in
     * {@code maven-wrapper.properties} or customized the launcher scripts.
     *
     * @param wsDir        workspace root
     * @param mavenVersion Maven version to write into
     *                     {@code maven-wrapper.properties} when creating it
     * @return the number of files created (0 when all three already exist)
     * @throws IOException if writing any of the files fails
     */
    static int writeMissingFiles(Path wsDir, String mavenVersion) throws IOException {
        int written = 0;

        Path propsFile = wsDir.resolve(".mvn").resolve("wrapper").resolve("maven-wrapper.properties");
        if (!Files.exists(propsFile)) {
            writePropertiesFile(propsFile, mavenVersion);
            written++;
        }

        Path mvnw = wsDir.resolve("mvnw");
        if (!Files.exists(mvnw)) {
            writeMvnwScript(mvnw);
            written++;
        }

        Path mvnwCmd = wsDir.resolve("mvnw.cmd");
        if (!Files.exists(mvnwCmd)) {
            writeMvnwCmdScript(mvnwCmd);
            written++;
        }

        return written;
    }

    /**
     * Read {@code maven.version} from an existing
     * {@code .mvn/wrapper/maven-wrapper.properties}, or return null if the
     * file does not exist or the property is missing.
     *
     * <p>Used by the {@code mvnw-standard} upgrade step to preserve a
     * user's pinned Maven version when regenerating a missing launcher
     * script next to an existing properties file.
     *
     * @param wsDir workspace root
     * @return the pinned Maven version, or null when not pinned
     * @throws IOException if the properties file exists but cannot be read
     */
    static String readPinnedVersion(Path wsDir) throws IOException {
        Path propsFile = wsDir.resolve(".mvn").resolve("wrapper").resolve("maven-wrapper.properties");
        if (!Files.exists(propsFile)) {
            return null;
        }
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(propsFile, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props.getProperty("maven.version");
    }

    /**
     * Write {@code maven-wrapper.properties} with the canonical distribution
     * URL for the given version. Creates the parent directory as needed.
     *
     * @param propsFile    target path (typically
     *                     {@code .mvn/wrapper/maven-wrapper.properties})
     * @param mavenVersion Maven version (e.g. {@code 4.0.0-rc-5})
     * @throws IOException if writing fails
     */
    static void writePropertiesFile(Path propsFile, String mavenVersion) throws IOException {
        Files.createDirectories(propsFile.getParent());
        String props = "# Maven Wrapper properties — managed by ws:init from workspace.yaml\n"
                + "maven.version=" + mavenVersion + "\n"
                + "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/"
                + "apache-maven/" + mavenVersion + "/apache-maven-" + mavenVersion
                + "-bin.zip\n";
        Files.writeString(propsFile, props, StandardCharsets.UTF_8);
    }

    /**
     * Write the POSIX {@code mvnw} launcher script and mark it executable.
     * The script reads {@code maven.version} and {@code distributionUrl}
     * from {@code .mvn/wrapper/maven-wrapper.properties} at runtime and
     * downloads Maven on first use.
     *
     * @param mvnw target path (typically {@code mvnw} at workspace root)
     * @throws IOException if writing fails
     */
    static void writeMvnwScript(Path mvnw) throws IOException {
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
    }

    /**
     * Write the Windows {@code mvnw.cmd} launcher script. The workspace
     * {@code .gitattributes} {@code *.cmd text eol=crlf} rule is what keeps
     * this file usable on Windows after checkout — without it, cmd.exe
     * chokes on LF line endings (IKE-Network/ike-issues#189).
     *
     * @param mvnwCmd target path (typically {@code mvnw.cmd} at workspace root)
     * @throws IOException if writing fails
     */
    static void writeMvnwCmdScript(Path mvnwCmd) throws IOException {
        String script = """
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
        Files.writeString(mvnwCmd, script, StandardCharsets.UTF_8);
    }
}
