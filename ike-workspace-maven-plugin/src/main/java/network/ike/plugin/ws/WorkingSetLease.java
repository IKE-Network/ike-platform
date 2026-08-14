package network.ike.plugin.ws;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bridge to the working-set lease protocol in {@code ~/ike-dev/scripts/lease.sh}.
 *
 * <p>A working set — any project root under {@code ~/ike-dev} — has one
 * writer at a time across the machines that share that folder. A
 * {@code ws:} goal that rewrites branches, versions and history is
 * emphatically a writer, so it confirms it holds the lease before it
 * starts. Design: {@code dev-working-set-lease} in ike-lab-documents,
 * IKE-Network/ike-issues#1002; this half is #1005.
 *
 * <p><strong>Inert outside that setup.</strong> Every check is an
 * existence check first: no {@code lease.sh}, no {@code ~/.ike-machine-id},
 * or a project that is not under {@code ~/ike-dev}, and the goal proceeds
 * exactly as it did before. That matters because this plugin is published
 * and run by people with no Syncthing-paired fleet, for whom the lease
 * protocol does not exist. It fails open for the same reason the Claude
 * fence does: a coordination aid that can wedge a build is worse than no
 * coordination aid.
 *
 * <p><strong>The protocol is not reimplemented here.</strong> Epoch
 * arithmetic, staleness horizons and conflict reconciliation live once, in
 * the shell script that the IDE plugin and the Claude hook also call. A
 * fencing system whose halves disagree is worse than one with no fencing
 * at all.
 */
final class WorkingSetLease {

    /**
     * Seconds to allow the confirming call. It deliberately sleeps out the
     * sync layer's propagation window — about 25 seconds — before reading
     * the record back, so this has to be generous. Cutting it short would
     * report a race lost that was never run.
     */
    private static final long CONFIRM_TIMEOUT_SECONDS = 120L;

    private WorkingSetLease() { }

    /**
     * The outcome of asking the lease protocol whether this machine may write.
     *
     * @param verdict  what the protocol decided
     * @param detail   the protocol's own explanation, for the operator; empty
     *                 when there is nothing to say
     */
    record Decision(Verdict verdict, String detail) { }

    /** What {@link #confirm} concluded. */
    enum Verdict {
        /** This machine holds the lease; proceed. */
        HELD,
        /** No lease machinery, or not a working set. Proceed. */
        NOT_APPLICABLE,
        /** Another machine holds it, or won the race. Do not write. */
        FENCED
    }

    /**
     * Confirms this machine holds the working set's lease, waiting out the
     * sync layer's propagation window and reading the record back.
     *
     * @param workspaceRoot the directory the goal is about to write to
     * @return the decision; never {@code null}
     */
    static Decision confirm(Path workspaceRoot) {
        Path script = script();
        if (script == null || workspaceRoot == null) {
            return new Decision(Verdict.NOT_APPLICABLE, "");
        }
        Result resolved = run(script, 10L, "resolve", workspaceRoot.toString());
        if (resolved.exitCode() != 0 || resolved.output().isBlank()) {
            return new Decision(Verdict.NOT_APPLICABLE, "");
        }
        String workingSet = resolved.output().trim();
        Result confirmed = run(script, CONFIRM_TIMEOUT_SECONDS,
                "ensure", workingSet, "--confirm");
        if (confirmed.exitCode() == 0) {
            return new Decision(Verdict.HELD, workingSet);
        }
        // A negative exit is this bridge failing to run the script at all —
        // not the protocol refusing. Fail open.
        if (confirmed.exitCode() < 0) {
            return new Decision(Verdict.NOT_APPLICABLE, "");
        }
        return new Decision(Verdict.FENCED, confirmed.output().trim());
    }

    /**
     * Locates the lease script, requiring the machine identity beside it.
     *
     * @return the script path, or {@code null} when this machine has no
     *         lease machinery
     */
    private static Path script() {
        String home = System.getProperty("user.home");
        if (home == null) {
            return null;
        }
        Path script = Path.of(home, "ike-dev", "scripts", "lease.sh");
        Path identity = Path.of(home, ".ike-machine-id");
        return Files.isExecutable(script) && Files.exists(identity) ? script : null;
    }

    private static Result run(Path script, long timeoutSeconds, String... args) {
        try {
            Process process = new ProcessBuilder(
                    concat(script.toString(), args))
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(-1, output.toString());
            }
            return new Result(process.exitValue(), output.toString());
        } catch (IOException e) {
            return new Result(-1, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "");
        }
    }

    private static List<String> concat(String head, String... tail) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(head);
        command.addAll(List.of(tail));
        return command;
    }

    private record Result(int exitCode, String output) { }
}
