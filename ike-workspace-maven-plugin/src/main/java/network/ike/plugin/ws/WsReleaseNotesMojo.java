package network.ike.plugin.ws;

import network.ike.plugin.ReleaseNotesSupport;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generate release notes from a GitHub milestone's closed issues.
 *
 * <p>Queries the GitHub REST API to find the named milestone, lists
 * its closed issues, and categorizes them by label into Fixes,
 * Enhancements, and Internal sections.
 *
 * <p>Delegates to {@link ReleaseNotesSupport} which is also used by
 * {@code ike:release} to auto-populate GitHub Release notes.
 *
 * <pre>{@code
 * mvn ws:release-notes -Dmilestone="ike-tooling v57"
 * mvn ws:release-notes -Dmilestone="ike-tooling v57" -Doutput=release-notes.md
 * }</pre>
 */
@Mojo(name = "release-notes", projectRequired = false)
public class WsReleaseNotesMojo extends AbstractWorkspaceMojo {

    /**
     * Milestone name (e.g., "ike-tooling v57"). Prompts if omitted.
     */
    @Parameter(property = "milestone")
    String milestone;

    /**
     * GitHub repository in owner/repo format for the issue tracker.
     */
    @Parameter(property = "repo", defaultValue = "IKE-Network/ike-issues")
    String repo;

    /**
     * Output file path. If omitted, prints to Maven log (stdout).
     */
    @Parameter(property = "output")
    String output;

    /** Creates this goal instance. */
    public WsReleaseNotesMojo() {}

    @Override
    public void execute() throws MojoException {
        milestone = requireParam(milestone, "milestone", "Milestone name");

        String notes = ReleaseNotesSupport.generate(repo, milestone, getLog());

        if (notes == null) {
            throw new MojoException(
                    "Milestone not found: \"" + milestone + "\" in " + repo);
        }

        if (output != null && !output.isBlank()) {
            try {
                Path outPath = Path.of(output);
                Files.writeString(outPath, notes, StandardCharsets.UTF_8);
                getLog().info("Release notes written to " + outPath.toAbsolutePath());
            } catch (IOException e) {
                throw new MojoException(
                        "Failed to write release notes: " + e.getMessage(), e);
            }
        } else {
            for (String line : notes.lines().toList()) {
                getLog().info(line);
            }
        }

        long issueCount = notes.lines().filter(l -> l.startsWith("- ")).count();
        writeReport(WsGoal.RELEASE_NOTES, "**Milestone:** " + milestone
                + " | **Repo:** " + repo
                + " | **Issues:** " + issueCount + "\n");
    }
}
