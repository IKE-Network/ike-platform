package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.Log;

import java.io.File;

/**
 * Per-subproject checkpoint engine — tag a single repo at its current HEAD.
 *
 * <p>This is the internal engine invoked by {@link WsCheckpointDraftMojo}
 * for each workspace subproject. It is not exposed as a standalone Maven goal.
 *
 * <p>A checkpoint records the current state for reproduction — it is not
 * a build or a release. The workflow for a single subproject:
 * <ol>
 *   <li>Tag current HEAD with {@code checkpoint/<name>}</li>
 *   <li>Push tag to origin</li>
 * </ol>
 *
 * <p>No POM version changes, no builds, no deploys. TeamCity watches
 * for checkpoint tags and handles CI.
 */
class CheckpointSupport {

    private CheckpointSupport() {}

    /**
     * Tag a single subproject at its current HEAD.
     *
     * @param dir     subproject git root directory
     * @param tagName the tag to create (e.g., {@code checkpoint/post-migration})
     * @param log     Maven logger
     * @throws MojoException if tagging or pushing fails
     */
    static void checkpoint(File dir, String tagName, Log log)
            throws MojoException {
        File gitRoot = ReleaseSupport.gitRoot(dir);

        // Tag current HEAD
        ReleaseSupport.exec(gitRoot, log,
                "git", "tag", "-a", tagName,
                "-m", "Checkpoint " + tagName);

        // Push tag and current branch to origin (#96)
        boolean hasOrigin = ReleaseSupport.hasRemote(gitRoot, "origin");
        if (hasOrigin) {
            String branch = ReleaseSupport.currentBranch(gitRoot);
            ReleaseSupport.exec(gitRoot, log,
                    "git", "push", "origin", branch);
            ReleaseSupport.exec(gitRoot, log,
                    "git", "push", "origin", tagName);
        } else {
            log.info("    No 'origin' remote — skipping push");
        }
    }

    /**
     * Log a draft summary for a single subproject.
     *
     * @param dir     subproject git root directory
     * @param tagName the tag that would be created
     * @param log     Maven logger
     */
    static void preview(File dir, String tagName, Log log)
            throws MojoException {
        File gitRoot = ReleaseSupport.gitRoot(dir);
        String shortSha = ReleaseSupport.execCapture(gitRoot,
                "git", "rev-parse", "--short", "HEAD");
        log.info("    [DRAFT] Would tag " + shortSha + " as " + tagName);
        log.info("    [DRAFT] Would push branch and tag to origin");
    }
}
