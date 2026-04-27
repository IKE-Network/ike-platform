package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

/**
 * Pull then push across the workspace — the everyday "sync" operation:
 * bring down what teammates have committed, then push up what I have
 * committed. Replaces the daily two-step of {@code ws:pull} followed by
 * {@code ws:push}.
 *
 * <p>The push half runs in fail-fast mode: a non-fast-forward (or any
 * other push failure) halts the goal with a clear error rather than
 * reporting partial success and continuing. This keeps the workspace
 * in a known state for the user to resolve, rather than leaving some
 * subprojects pushed and others not.
 *
 * <p>Use {@code -DpullOnly} to run only the pull half, or
 * {@code -DpushOnly} to run only the push half (positive-form flags
 * per the compiler-visibility principle). For the standalone
 * operations, prefer {@link PullWorkspaceMojo ws:pull} or
 * {@link PushMojo ws:push} directly.
 *
 * <pre>{@code
 * mvn ws:sync                       # pull then push
 * mvn ws:sync -DpullOnly            # equivalent to ws:pull
 * mvn ws:sync -DpushOnly            # equivalent to ws:push (fail-fast)
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
    public void execute() throws MojoException {
        if (pullOnly && pushOnly) {
            throw new MojoException(
                    "-DpullOnly and -DpushOnly are mutually exclusive —"
                            + " use ws:pull or ws:push directly if you"
                            + " want only one half");
        }

        getLog().info("");
        getLog().info(header("Sync"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");

        if (!pushOnly) {
            PullWorkspaceMojo pull = new PullWorkspaceMojo();
            pull.setLog(getLog());
            pull.manifest = this.manifest;
            pull.execute();
        }

        if (!pullOnly) {
            PushMojo push = new PushMojo();
            push.setLog(getLog());
            push.manifest = this.manifest;
            push.remote = this.remote;
            push.failFast = true;
            push.execute();
        }

        StringBuilder summary = new StringBuilder();
        summary.append(pushOnly ? "skipped pull" : "pulled");
        summary.append(" then ");
        summary.append(pullOnly ? "skipped push" : "pushed");
        summary.append(".\n");
        writeReport(WsGoal.SYNC, summary.toString());

        IdeProfileSync.run(workspaceRoot(), getLog());
    }
}
