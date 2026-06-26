package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pull then push across the workspace — the everyday "sync" operation:
 * bring down what teammates have committed, then push up what I have
 * committed. Replaces the daily two-step of {@code ws:pull} followed by
 * {@code ws:push}.
 *
 * <p>Between the pull and the push, this goal also refreshes local
 * {@code main} from {@code origin/main} across the workspace via
 * {@link RefreshMainSupport}. This keeps local main coherent with the
 * remote even when the user is working on a feature branch and never
 * checks main out directly &mdash; especially relevant in the
 * Syncthing + independent-{@code .git} architecture where each machine
 * evolves its local main ref independently. See ike-issues#284.
 *
 * <p>The push half runs in fail-fast mode: a non-fast-forward (or any
 * other push failure) halts the goal with a clear error rather than
 * reporting partial success and continuing. This keeps the workspace
 * in a known state for the user to resolve, rather than leaving some
 * subprojects pushed and others not.
 *
 * <p>Use {@code -DpullOnly} to run only the pull half (still refreshes
 * main), or {@code -DpushOnly} to run only the push half (skips both
 * pull and refresh-main). For the standalone operations, prefer
 * {@link PullWorkspaceMojo ws:pull},
 * {@link WsRefreshMainMojo ws:refresh-main}, or
 * {@link PushMojo ws:push} directly.
 *
 * <p><strong>Single repo (no {@code workspace.yaml})</strong>: syncs the
 * current repository only — a working set of one. The pull and push halves
 * each resolve their own single-repo scope; the workspace-only
 * refresh-main and {@link PostMutationSync} steps are skipped
 * (IKE-Network/ike-issues#703).
 *
 * <pre>{@code
 * mvn ws:sync                       # pull, refresh main, push
 * mvn ws:sync -DpullOnly            # pull and refresh main only
 * mvn ws:sync -DpushOnly            # push only (fail-fast)
 * mvn ws:sync -Dremote=upstream     # push to a non-default remote
 * }</pre>
 *
 * <p>See issue #194 and the {@code dev-workspace-ops-completion} topic
 * in {@code ike-lab-documents} for the design rationale.
 */
@Mojo(name = "sync", projectRequired = false, aggregator = true)
public class WsSyncMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public WsSyncMojo() {}

    /**
     * Remote name to push to. Forwarded to the push half.
     */
    @Parameter(property = "remote", defaultValue = "origin")
    String remote;

    /**
     * Run the pull half only and skip the push. Mutually exclusive
     * with {@link #pushOnly}.
     */
    @Parameter(property = "pullOnly", defaultValue = "false")
    boolean pullOnly;

    /**
     * Run the push half only and skip the pull. Mutually exclusive
     * with {@link #pullOnly}.
     */
    @Parameter(property = "pushOnly", defaultValue = "false")
    boolean pushOnly;

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        if (pullOnly && pushOnly) {
            throw new MojoException(
                    "-DpullOnly and -DpushOnly are mutually exclusive —"
                            + " use " + WsGoal.PULL.qualified() + " or "
                            + WsGoal.PUSH.qualified() + " directly if you"
                            + " want only one half");
        }

        getLog().info("");
        getLog().info(header("Sync"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");

        String pullPhaseBody = null;
        String pushPhaseBody = null;
        boolean pushFailed = false;
        String pushFailureMessage = null;

        if (!pushOnly) {
            PullWorkspaceMojo pull = new PullWorkspaceMojo();
            pull.setLog(getLog());
            pull.manifest = this.manifest;
            pull.execute();
            // The child mojo wrote its own ws:pull.md report — read it
            // back so the sync report can inline the per-subproject
            // detail (#542). Failing to read is non-fatal: the child
            // report still exists on disk for the user to inspect.
            pullPhaseBody = readChildReport(WsGoal.PULL);

            // Refresh local main from origin/main between pull and push,
            // so a feature-branch sync also keeps main coherent. Skipped
            // on push-only, where the user has explicitly opted out of
            // any pull-side work. See ike-issues#284.
            if (isWorkspaceMode()) {
                WorkspaceGraph graph = loadGraph();
                File root = workspaceRoot();
                Set<String> targets = graph.manifest().subprojects().keySet();
                List<String> sorted = graph.topologicalSort(
                        new LinkedHashSet<>(targets));
                RefreshMainSupport.refreshOrThrow(root, sorted, "main", getLog());
            }
        }

        if (!pullOnly) {
            PushMojo push = new PushMojo();
            push.setLog(getLog());
            push.manifest = this.manifest;
            push.remote = this.remote;
            push.failFast = true;
            try {
                push.execute();
            } catch (MojoException e) {
                // failFast=true means the push half hard-fails on any
                // subproject error. We still want to emit a sync report
                // that surfaces what happened on the pull side and the
                // push failure message, so we capture and rethrow after
                // writing.
                pushFailed = true;
                pushFailureMessage = e.getMessage();
            }
            // Try to read the push report even when the push failed —
            // the child mojo writes its report before any throw.
            pushPhaseBody = readChildReport(WsGoal.PUSH);
        }

        // PostMutationSync re-derives workspace.yaml depends-on from POM
        // reality and is workspace-only — a single-repo sync (working set
        // of one, IKE-Network/ike-issues#703) has none. It runs after the
        // push half, so a manifest commit it makes is not covered by that
        // push; when the sync pushed (not pull-only) and did not already
        // fail, push the root again so the re-derived manifest reaches
        // origin in the same sync rather than waiting for the next one
        // (#774).
        if (isWorkspaceMode()) {
            File root = workspaceRoot();
            boolean manifestCommitted =
                    PostMutationSync.refresh(root, getLog());
            if (manifestCommitted && !pullOnly && !pushFailed) {
                VcsOperations.pushSafe(root, getLog(), remote,
                        VcsOperations.currentBranch(root));
            }
        }

        String body = buildSyncReport(pullPhaseBody, pushPhaseBody,
                pushFailed, pushFailureMessage);

        if (pushFailed) {
            // Re-throw so the build reflects the failure — the report
            // body is written by AbstractWorkspaceMojo.execute() only
            // on success. Emit the sync report manually first so the
            // user still has the combined audit trail.
            try {
                WorkspaceReport.write(resolveWorkingSet().root(),
                        WsGoal.SYNC.qualified(), body, getLog());
            } catch (MojoException writeException) {
                getLog().debug("Could not write sync report on failure: "
                        + writeException.getMessage());
            }
            throw new MojoException(pushFailureMessage);
        }

        return new WorkspaceReportSpec(WsGoal.SYNC, body);
    }

    /**
     * Read a child mojo's just-written report from disk so the sync
     * report can inline its per-subproject detail. Returns {@code null}
     * when the file is unreadable so the caller can degrade to a
     * placeholder rather than throwing.
     */
    private String readChildReport(WsGoal childGoal) {
        try {
            Path reportFile = WorkspaceReport.reportPath(
                    resolveWorkingSet().root(), childGoal.qualified());
            if (!Files.isRegularFile(reportFile)) return null;
            return Files.readString(reportFile, StandardCharsets.UTF_8);
        } catch (IOException | MojoException e) {
            getLog().debug("Could not read child report for "
                    + childGoal + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Compose the sync markdown report from the pull and push phase
     * bodies. Each phase appears under an {@code H2} header so a
     * reviewer can tell at a glance whether a subproject's state
     * change happened on the pull or the push side (#542). Skipped
     * phases get an explicit "(skipped)" note rather than being
     * absorbed into a one-line summary.
     */
    private String buildSyncReport(String pullBody, String pushBody,
                                    boolean pushFailed,
                                    String pushFailureMessage) {
        StringBuilder out = new StringBuilder();

        if (pushOnly) {
            out.append("## Pull phase\n\n_skipped (`-DpushOnly`)_\n\n");
        } else {
            out.append("## Pull phase\n\n");
            out.append(stripChildHeader(pullBody,
                    "_ws:pull.md not available — see the workspace root for the child report._"));
            out.append("\n\n");
        }

        if (pullOnly) {
            out.append("## Push phase\n\n_skipped (`-DpullOnly`)_\n\n");
        } else {
            out.append("## Push phase\n\n");
            if (pushFailed) {
                out.append("**Push failed** — `")
                        .append(pushFailureMessage)
                        .append("`\n\n");
            }
            out.append(stripChildHeader(pushBody,
                    "_ws:push.md not available — see the workspace root for the child report._"));
            out.append("\n");
        }

        return out.toString();
    }

    /**
     * Strip the goal-name {@code H1} that AbstractWorkspaceMojo's
     * report header writes, so that the sync report's H1 stays
     * authoritative and the child's H2 sections (Subprojects, Merged
     * commits, Failures, etc.) nest cleanly under the phase H2.
     * Returns the {@code fallback} text when {@code body} is null.
     */
    private static String stripChildHeader(String body, String fallback) {
        if (body == null) return fallback;
        // The child report starts with `# ws:pull\n_<timestamp>_\n\n`
        // (or push). Drop the H1 + timestamp blank line so the body
        // can nest under the sync report's phase header.
        int firstBlank = body.indexOf("\n\n");
        if (firstBlank < 0) return body;
        return body.substring(firstBlank + 2);
    }
}
