package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Rolls a working set back from a failed release mission
 * (IKE-Network/ike-issues#1010) — the mechanized form of the hand
 * resets that a mid-mission failure previously demanded across every
 * repository.
 *
 * <p>A mission's local footprint is exactly its release-cadence commits
 * ({@code release: set version to …}, {@code post-release: bump to …},
 * the alignment and manifest-sync subjects) plus any tags pointing at
 * them. Rollback walks each repository's <em>unpushed</em> range from
 * {@code HEAD} downward, discarding consecutive cadence commits and
 * the local tags on them, and resets to the first non-cadence commit.
 * Pushed history is never touched: a cadence commit already on origin
 * belongs to a completed mission, and a failed mission that got as far as
 * pushing is a publication event a human must judge.
 *
 * <p>Refusals (per repository, and the publish is all-or-nothing over
 * the set): a working tree with uncommitted changes, no upstream to
 * define the unpushed range, or mission commits buried beneath later
 * non-cadence work — each names the repository and the remediation.
 */
final class WorkspaceReleaseRollback {

    /** Subjects the release mission authors; anything else is work. */
    private static final List<String> CADENCE_PREFIXES = List.of(
            "release: set version to ",
            "post-release: bump to ",
            "post-release: sync workspace.yaml",
            "workspace: align inter-subproject versions",
            "workspace: pin working-set state");

    private WorkspaceReleaseRollback() {}

    /**
     * One repository's rollback plan.
     *
     * @param name      the working-set member name (or the root's label)
     * @param dir       the repository directory
     * @param targetSha the commit to reset to; {@code null} when there
     *                  is nothing to discard
     * @param discards  {@code <sha> <subject>} lines to discard,
     *                  newest first
     * @param tags      local tags on the discarded commits
     * @param refusal   why this repository blocks the rollback, or
     *                  {@code null} when it does not
     */
    record RepoPlan(String name, File dir, String targetSha,
                    List<String> discards, List<String> tags,
                    String refusal) {

        /** @return whether this repository has anything to discard */
        boolean hasWork() {
            return targetSha != null && !discards.isEmpty();
        }
    }

    /**
     * Inspect one repository and plan its rollback without mutating
     * anything.
     *
     * @param name the member name used in reports
     * @param dir  the repository directory
     * @return the plan, carrying a refusal instead of work when the
     *         repository cannot be rolled back safely
     * @throws MojoException when git itself fails
     */
    static RepoPlan plan(String name, File dir) throws MojoException {
        if (!VcsOperations.isClean(dir)) {
            return new RepoPlan(name, dir, null, List.of(), List.of(),
                    "uncommitted changes — commit or stash by hand first");
        }
        String branch = VcsOperations.currentBranch(dir);
        String remote = VcsOperations
                .remoteSha(dir, "origin", branch).orElse(null);
        if (remote == null) {
            return new RepoPlan(name, dir, null, List.of(), List.of(),
                    "no origin/" + branch
                            + " — cannot define the unpushed range");
        }

        List<String> unpushed = logOneline(dir, remote + "..HEAD");
        List<String> discards = new ArrayList<>();
        int walked = 0;
        for (String line : unpushed) {
            if (!isCadence(subjectOf(line))) break;
            discards.add(line);
            walked++;
        }
        for (int i = walked; i < unpushed.size(); i++) {
            if (isCadence(subjectOf(unpushed.get(i)))) {
                return new RepoPlan(name, dir, null, List.of(), List.of(),
                        "mission commits buried beneath later work ("
                                + subjectOf(unpushed.get(walked))
                                + " sits above them) — resolve by hand");
            }
        }
        if (discards.isEmpty()) {
            return new RepoPlan(name, dir, null, List.of(), List.of(),
                    null);
        }

        String lastDiscarded = shaOf(discards.get(discards.size() - 1));
        String target = capture(dir,
                "git", "rev-parse", lastDiscarded + "^").strip();
        List<String> tags = new ArrayList<>();
        for (String line : discards) {
            for (String tag : capture(dir, "git", "tag", "--points-at",
                    shaOf(line)).split("\n")) {
                if (!tag.isBlank()) tags.add(tag.strip());
            }
        }
        return new RepoPlan(name, dir, target, List.copyOf(discards),
                List.copyOf(tags), null);
    }

    /**
     * Apply a plan: delete its local tags, then hard-reset to the
     * target. Call only on plans where {@link RepoPlan#hasWork()} is
     * true and {@link RepoPlan#refusal()} is null.
     *
     * @param plan the repository plan to apply
     * @param log  Maven logger for the reset trace
     * @throws MojoException when git fails mid-apply
     */
    static void apply(RepoPlan plan, Log log) throws MojoException {
        for (String tag : plan.tags()) {
            capture(plan.dir(), "git", "tag", "-d", tag);
            log.info("    tag deleted: " + tag);
        }
        VcsOperations.resetHard(plan.dir(), log, plan.targetSha());
    }

    private static boolean isCadence(String subject) {
        for (String prefix : CADENCE_PREFIXES) {
            if (subject.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String subjectOf(String onelineEntry) {
        int space = onelineEntry.indexOf(' ');
        return space < 0 ? "" : onelineEntry.substring(space + 1);
    }

    private static String shaOf(String onelineEntry) {
        int space = onelineEntry.indexOf(' ');
        return space < 0 ? onelineEntry : onelineEntry.substring(0, space);
    }

    private static List<String> logOneline(File dir, String range)
            throws MojoException {
        String out = capture(dir, "git", "log", range,
                "--oneline", "--no-decorate");
        return out.isBlank() ? List.of() : List.of(out.split("\n"));
    }

    // Local capture: VcsOperations has no tag or rev-parse helpers, and
    // this class's needs are read-mostly one-liners.
    private static String capture(File dir, String... command)
            throws MojoException {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(dir).redirectErrorStream(true).start();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                in.transferTo(buffer);
            }
            int exit = process.waitFor();
            String output = buffer.toString(StandardCharsets.UTF_8);
            if (exit != 0) {
                throw new MojoException(String.join(" ", command)
                        + " failed in " + dir + ": " + output.strip());
            }
            return output;
        } catch (IOException e) {
            throw new MojoException("Could not run git in " + dir
                    + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoException("Interrupted running git in " + dir, e);
        }
    }
}
