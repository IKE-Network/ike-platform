package network.ike.plugin.ws;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>The {@code mvnw} / {@code mvnw.cmd} scripts are the <b>standard
 * Apache Maven Wrapper</b> launchers, vendored verbatim as plugin
 * resources under {@code wrapper/}. The {@code only-script} distribution
 * type is used — the scripts download Maven themselves, so no
 * {@code maven-wrapper.jar} binary is committed to a workspace repo.
 * The properties file carries the standard {@code wrapperVersion},
 * {@code distributionType}, and {@code distributionUrl} keys, which is
 * what IDEs (IntelliJ) key on to auto-adopt the wrapper.
 *
 * <p>Used by {@code ws:scaffold-init} when scaffolding a new workspace,
 * and by {@code ScaffoldConventionReconciler}'s {@code mvnw} step (run
 * as part of {@code ws:scaffold-publish}) — both to fill in missing
 * files and to replace the legacy custom "minimal bootstrap" wrapper
 * that earlier plugin versions generated (IKE-Network/ike-issues#405).
 */
public final class MavenWrapper {

    /**
     * Apache Maven Wrapper version of the vendored {@code mvnw} /
     * {@code mvnw.cmd} scripts. Written into {@code wrapperVersion} of
     * the generated {@code maven-wrapper.properties}.
     */
    public static final String WRAPPER_VERSION = "3.3.2";

    /**
     * Wrapper distribution type. {@code only-script} keeps the wrapper
     * jar-free — the launcher scripts download Maven directly.
     */
    public static final String DISTRIBUTION_TYPE = "only-script";

    /** Extracts the Maven version from a wrapper {@code distributionUrl}. */
    private static final Pattern DISTRIBUTION_VERSION =
            Pattern.compile("apache-maven-(.+?)-bin\\.(?:zip|tar\\.gz)");

    private MavenWrapper() {}

    /**
     * Install any of the three wrapper files that are missing from the
     * given workspace directory. Never overwrites an existing file — the
     * user may have pinned a specific version in
     * {@code maven-wrapper.properties} or customized the launcher scripts.
     * To replace a legacy wrapper in full, use {@link #writeAll}.
     *
     * @param wsDir        workspace root
     * @param mavenVersion Maven version to write into
     *                     {@code maven-wrapper.properties} when creating it
     * @return the number of files created (0 when all three already exist)
     * @throws IOException if writing any of the files fails
     */
    public static int writeMissingFiles(Path wsDir, String mavenVersion) throws IOException {
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
     * Unconditionally (re)write all three wrapper files, overwriting any
     * that already exist. Used to replace the legacy custom wrapper with
     * the standard one — see {@link #isLegacyWrapper}.
     *
     * @param wsDir        workspace root
     * @param mavenVersion Maven version to write into
     *                     {@code maven-wrapper.properties}
     * @throws IOException if writing any of the files fails
     */
    public static void writeAll(Path wsDir, String mavenVersion) throws IOException {
        writePropertiesFile(
                wsDir.resolve(".mvn").resolve("wrapper").resolve("maven-wrapper.properties"),
                mavenVersion);
        writeMvnwScript(wsDir.resolve("mvnw"));
        writeMvnwCmdScript(wsDir.resolve("mvnw.cmd"));
    }

    /**
     * Report whether the workspace carries the legacy custom "minimal
     * bootstrap" {@code mvnw} that pre-standardization plugin versions
     * generated. Detected by the {@code minimal bootstrap} marker in the
     * script header — the standard Apache launcher does not carry it,
     * and neither would a user's own hand-written {@code mvnw}.
     *
     * @param wsDir workspace root
     * @return true when {@code mvnw} exists and is the legacy custom
     *         launcher; false when absent, standard, or user-authored
     * @throws IOException if {@code mvnw} exists but cannot be read
     */
    public static boolean isLegacyWrapper(Path wsDir) throws IOException {
        Path mvnw = wsDir.resolve("mvnw");
        if (!Files.exists(mvnw)) {
            return false;
        }
        return Files.readString(mvnw, StandardCharsets.UTF_8).contains("minimal bootstrap");
    }

    /**
     * Read the pinned Maven version from an existing
     * {@code .mvn/wrapper/maven-wrapper.properties}, or return null if the
     * file does not exist or no version can be determined.
     *
     * <p>The version is parsed from the standard {@code distributionUrl}
     * key. A legacy wrapper's explicit {@code maven.version} key is used
     * as a fallback so that migrating an old workspace preserves its
     * pinned version.
     *
     * @param wsDir workspace root
     * @return the pinned Maven version, or null when not determinable
     * @throws IOException if the properties file exists but cannot be read
     */
    public static String readPinnedVersion(Path wsDir) throws IOException {
        Path propsFile = wsDir.resolve(".mvn").resolve("wrapper").resolve("maven-wrapper.properties");
        if (!Files.exists(propsFile)) {
            return null;
        }
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(propsFile, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        String url = props.getProperty("distributionUrl");
        if (url != null) {
            Matcher m = DISTRIBUTION_VERSION.matcher(url);
            if (m.find()) {
                return m.group(1);
            }
        }
        return props.getProperty("maven.version");
    }

    /**
     * Write {@code maven-wrapper.properties} in the standard
     * {@code only-script} form for the given Maven version. Creates the
     * parent directory as needed. Overwrites any existing file.
     *
     * @param propsFile    target path (typically
     *                     {@code .mvn/wrapper/maven-wrapper.properties})
     * @param mavenVersion Maven version (e.g. {@code 4.0.0-rc-5})
     * @throws IOException if writing fails
     */
    public static void writePropertiesFile(Path propsFile, String mavenVersion) throws IOException {
        Files.createDirectories(propsFile.getParent());
        String props = "# Maven Wrapper properties — managed by "
                + WsGoal.SCAFFOLD_INIT.qualified() + " from workspace.yaml\n"
                + "wrapperVersion=" + WRAPPER_VERSION + "\n"
                + "distributionType=" + DISTRIBUTION_TYPE + "\n"
                + "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/"
                + "apache-maven/" + mavenVersion + "/apache-maven-" + mavenVersion
                + "-bin.zip\n";
        Files.writeString(propsFile, props, StandardCharsets.UTF_8);
    }

    /**
     * Write the standard Apache POSIX {@code mvnw} launcher script and
     * mark it executable. The script reads {@code distributionUrl} from
     * {@code .mvn/wrapper/maven-wrapper.properties} at runtime and
     * downloads Maven on first use. Overwrites any existing file.
     *
     * @param mvnw target path (typically {@code mvnw} at workspace root)
     * @throws IOException if writing fails
     */
    public static void writeMvnwScript(Path mvnw) throws IOException {
        copyResource("wrapper/mvnw", mvnw);
        mvnw.toFile().setExecutable(true);
    }

    /**
     * Write the standard Apache Windows {@code mvnw.cmd} launcher script.
     * The workspace {@code .gitattributes} {@code *.cmd text eol=crlf}
     * rule is what keeps this file usable on Windows after checkout —
     * without it, cmd.exe chokes on LF line endings
     * (IKE-Network/ike-issues#189). Overwrites any existing file.
     *
     * @param mvnwCmd target path (typically {@code mvnw.cmd} at workspace root)
     * @throws IOException if writing fails
     */
    public static void writeMvnwCmdScript(Path mvnwCmd) throws IOException {
        copyResource("wrapper/mvnw.cmd", mvnwCmd);
    }

    /**
     * Copy a vendored wrapper resource verbatim to the target path,
     * preserving its byte content (and thus line endings).
     *
     * @param resource resource name relative to this class's package
     * @param target   destination path
     * @throws IOException if the resource is missing or the copy fails
     */
    private static void copyResource(String resource, Path target) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try (InputStream in = MavenWrapper.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Bundled Maven wrapper resource not found: " + resource);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
