package network.ike.plugin.ws.vcs;

import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Test harness (ike-issues#691) that scripts git-command failures against
 * {@link VcsOperations}.
 *
 * <p>It installs a {@link VcsOperations.CommandInterceptor} via the
 * package-private {@link VcsOperations#setCommandInterceptor} seam and, for
 * each scripted {@link Rule} that matches an intercepted invocation, throws a
 * {@link MojoException} whose message mirrors the real failure shape produced
 * by {@code VcsOperations} ({@code "Command failed (exit 1): <command>"}). A
 * rule that fires does so <em>instead of</em> running the real subprocess, so
 * the caller's error handling is exercised without any actual git mutation.
 *
 * <p>{@code FaultableExec} is {@link AutoCloseable}: {@link #close()} resets the
 * interceptor to the default no-op, so tests use try-with-resources and never
 * leak injected state across cases. Example:
 *
 * <pre>{@code
 * try (FaultableExec fault = FaultableExec.install()
 *         .failWhenCommandContains("merge").inDirNamed("lib-b").onOccurrence(1)) {
 *     // ... drive a VcsOperations op that runs `git merge` in lib-b ...
 * }
 * }</pre>
 *
 * <p>Rules are evaluated in declaration order; the first matching rule that
 * fires wins. Counters use {@link AtomicInteger} and rule bookkeeping is
 * synchronized, so the harness is safe to share across threads.
 */
public final class FaultableExec implements AutoCloseable {

    /** A single scripted failure: a command match plus optional refinements. */
    public static final class Rule {
        private final String commandToken;
        private Predicate<File> dirPredicate;
        private String dirDescription = "<any dir>";
        private int targetOccurrence;             // 0 = every match
        private final AtomicInteger matches = new AtomicInteger();

        private Rule(String commandToken) {
            this.commandToken = commandToken;
        }

        private boolean commandMatches(String[] command) {
            for (String arg : command) {
                if (arg.contains(commandToken)) {
                    return true;
                }
            }
            return false;
        }

        private boolean dirMatches(File workDir) {
            return dirPredicate == null || dirPredicate.test(workDir);
        }

        /**
         * Decide whether this rule fires for the given invocation, advancing
         * its own match counter as a side effect when command+dir match.
         *
         * @param workDir the working directory of the intercepted command
         * @param command the intercepted command and its arguments
         * @return {@code true} if this invocation should be failed
         */
        private boolean fires(File workDir, String[] command) {
            if (!commandMatches(command) || !dirMatches(workDir)) {
                return false;
            }
            int n = matches.incrementAndGet();
            return targetOccurrence == 0 || n == targetOccurrence;
        }

        /** @return how many command+dir matches this rule has seen */
        public int matchCount() {
            return matches.get();
        }
    }

    private final List<Rule> rules = new ArrayList<>();
    private final AtomicInteger interceptedCount = new AtomicInteger();

    private FaultableExec() {
        VcsOperations.setCommandInterceptor(this::intercept);
    }

    /**
     * Install a fresh harness on {@link VcsOperations}.
     *
     * @return the harness, ready to have failure rules scripted on it
     */
    public static FaultableExec install() {
        return new FaultableExec();
    }

    // ── Fluent rule scripting ────────────────────────────────────

    /**
     * Add a rule that fails any command containing {@code token} in one of its
     * arguments (e.g. {@code "commit"}, {@code "merge"}).
     *
     * @param token the substring to look for across the command's arguments
     * @return this harness, for chaining refinements ({@link #inDirNamed},
     *         {@link #inDir}, {@link #onOccurrence})
     */
    public FaultableExec failWhenCommandContains(String token) {
        synchronized (rules) {
            rules.add(new Rule(token));
        }
        return this;
    }

    /**
     * Restrict the most recently added rule to commands whose working directory
     * has the given simple name (matched against {@link File#getName()}).
     *
     * @param dirName the expected {@code workDir.getName()}
     * @return this harness, for chaining
     */
    public FaultableExec inDirNamed(String dirName) {
        Rule r = lastRule();
        r.dirPredicate = dir -> dir != null && dirName.equals(dir.getName());
        r.dirDescription = "name=" + dirName;
        return this;
    }

    /**
     * Restrict the most recently added rule to commands whose working directory
     * satisfies {@code predicate}.
     *
     * @param predicate the working-directory test
     * @return this harness, for chaining
     */
    public FaultableExec inDir(Predicate<File> predicate) {
        Rule r = lastRule();
        r.dirPredicate = predicate;
        r.dirDescription = "predicate";
        return this;
    }

    /**
     * Restrict the most recently added rule to fire only on its {@code nth}
     * command+dir match (1-based). By default a rule fires on every match.
     *
     * @param nth the 1-based occurrence to fail on
     * @return this harness, for chaining
     */
    public FaultableExec onOccurrence(int nth) {
        if (nth < 1) {
            throw new IllegalArgumentException("occurrence must be >= 1, was " + nth);
        }
        lastRule().targetOccurrence = nth;
        return this;
    }

    private Rule lastRule() {
        synchronized (rules) {
            if (rules.isEmpty()) {
                throw new IllegalStateException(
                        "no rule to refine — call failWhenCommandContains(...) first");
            }
            return rules.get(rules.size() - 1);
        }
    }

    // ── Interception ─────────────────────────────────────────────

    private void intercept(File workDir, String[] command) throws MojoException {
        interceptedCount.incrementAndGet();
        Rule fired = null;
        synchronized (rules) {
            for (Rule rule : rules) {
                if (rule.fires(workDir, command)) {
                    fired = rule;
                    break;
                }
            }
        }
        if (fired != null) {
            throw new MojoException(
                    "Command failed (exit 1): " + String.join(" ", command)
                            + "  [FaultableExec injected]");
        }
    }

    // ── Observability ────────────────────────────────────────────

    /**
     * Total number of git invocations seen by the interceptor since install
     * (whether or not any rule fired).
     *
     * @return the intercepted-invocation count
     */
    public int interceptedCount() {
        return interceptedCount.get();
    }

    /**
     * Total command+dir matches summed across all scripted rules. A rule counts
     * a match whenever its command/dir predicates hold, even on occurrences it
     * chose not to fail.
     *
     * @return the aggregate per-rule match count
     */
    public int matchCount() {
        int total = 0;
        synchronized (rules) {
            for (Rule rule : rules) {
                total += rule.matchCount();
            }
        }
        return total;
    }

    /**
     * Reset {@link VcsOperations} to its default no-op interceptor. Idempotent;
     * safe to call from a try-with-resources {@code finally}.
     */
    @Override
    public void close() {
        VcsOperations.setCommandInterceptor(null);
    }
}
