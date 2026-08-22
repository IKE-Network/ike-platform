package network.ike.plugin.ws;

import network.ike.lease.core.LeaseProtocol;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The {@code ws:} goals' bridge to the working-set lease protocol —
 * in-process on {@code ike-lease-core} since the protocol port
 * (IKE-Network/ike-issues#1067), which is what retired this class's
 * original shape: a Maven-published plugin shelling out to a
 * {@code $HOME} script. The single-implementation rule survives intact —
 * the very same {@code LeaseProtocol} the {@code lease.sh} wrapper execs
 * and the IDE plugin embeds runs here, so the fencing system's halves
 * cannot drift.
 *
 * <p>A working set — any project root under {@code ~/ike-dev} — has one
 * writer at a time across the machines that share that folder. A
 * {@code ws:} goal that rewrites branches, versions and history is
 * emphatically a writer, so it confirms it holds the lease before it
 * starts; a finish that lands in the <em>parent</em> working set
 * short-holds that lease too ({@link ParentLeaseHold}). Design:
 * {@code dev-working-set-lease} in ike-lab-documents,
 * IKE-Network/ike-issues#1002; this half is #1005.
 *
 * <p><strong>Inert outside that setup.</strong> No
 * {@code ~/.ike-machine-id}, no development folder, or a path that does
 * not resolve to a working set, and the goal proceeds exactly as it did
 * before — this plugin is published and run by people with no
 * Syncthing-paired fleet, for whom the lease protocol does not exist. It
 * fails open for the same reason the Claude fence does: a coordination
 * aid that can wedge a build is worse than no coordination aid.
 *
 * <p>System properties {@code ike.lease.home}, {@code ike.lease.ikeDev}
 * and {@code ike.lease.settleSeconds} override the environment
 * ({@code HOME}, {@code IKE_DEV}, {@code IKE_LEASE_SETTLE_SECONDS}) —
 * the seam sandboxed tests set, since a JVM cannot re-point its
 * environment per test.
 */
final class WorkingSetLease {

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
     * A read-only look at a working set's lease, for draft goals and
     * listings that must not acquire anything.
     *
     * @param liveElsewhere {@code true} when another machine holds the
     *                      lease live — the one state that refuses a write
     * @param line          the protocol's own one-line description
     */
    record Status(boolean liveElsewhere, String line) { }

    /**
     * Confirms this machine holds the working set's lease, waiting out the
     * sync layer's propagation window and reading the record back — the
     * #1005 confirmed acquisition, for consequential steps.
     *
     * @param workingSetRoot the directory the goal is about to write to
     * @return the decision; never {@code null}
     */
    static Decision confirm(Path workingSetRoot) {
        Optional<Context> context = Context.of(workingSetRoot);
        if (context.isEmpty()) {
            return new Decision(Verdict.NOT_APPLICABLE, "");
        }
        LeaseProtocol.Outcome outcome =
                context.get().protocol().ensure(context.get().workingSet(), true);
        return switch (outcome.exitCode()) {
            case 0 -> new Decision(Verdict.HELD, context.get().workingSet());
            case 1 -> new Decision(Verdict.FENCED, outcome.stdout().trim());
            default -> new Decision(Verdict.NOT_APPLICABLE, "");
        };
    }

    /**
     * Reads a working set's lease state without changing it.
     *
     * @param workingSetRoot the working set's directory
     * @return the status, or empty when the lease machinery is absent or
     *         the path is not a working set — the not-applicable cases
     */
    static Optional<Status> status(Path workingSetRoot) {
        return Context.of(workingSetRoot).map(context -> {
            LeaseProtocol.Outcome outcome =
                    context.protocol().status(context.workingSet());
            return new Status(outcome.exitCode() == 1,
                    outcome.stdout().trim());
        });
    }

    /**
     * Releases a lease this machine holds — the closing half of a
     * short-hold. The protocol refuses politely when the lease is not
     * held here; anything but a clean release is the caller's to log,
     * never to fail on.
     *
     * @param workingSetRoot the working set's directory
     * @return {@code true} when the lease was released (or no record
     *         existed); {@code false} otherwise
     */
    static boolean release(Path workingSetRoot) {
        return Context.of(workingSetRoot)
                .map(context -> context.protocol()
                        .release(context.workingSet()).exitCode() == 0)
                .orElse(true);
    }

    /**
     * The resolved protocol instance and working-set name for a path, or
     * empty in every not-applicable case.
     *
     * @param protocol   the in-process protocol
     * @param workingSet the resolved working-set name
     */
    private record Context(LeaseProtocol protocol, String workingSet) {

        static Optional<Context> of(Path workingSetRoot) {
            if (workingSetRoot == null) {
                return Optional.empty();
            }
            String home = firstNonBlank(
                    System.getProperty("ike.lease.home"),
                    System.getenv("HOME"),
                    System.getProperty("user.home"));
            String ikeDev = firstNonBlank(
                    System.getProperty("ike.lease.ikeDev"),
                    System.getenv("IKE_DEV"),
                    home + "/ike-dev");
            if (!Files.isRegularFile(Path.of(home, ".ike-machine-id"))
                    || !Files.isDirectory(Path.of(ikeDev))) {
                return Optional.empty();
            }
            long settle = parseLong(firstNonBlank(
                    System.getProperty("ike.lease.settleSeconds"),
                    System.getenv("IKE_LEASE_SETTLE_SECONDS"),
                    "25"), 25L);
            String ttl = firstNonBlank(System.getenv("IKE_LEASE_TTL"),
                    "PT10M");
            LeaseProtocol protocol = new LeaseProtocol(Path.of(ikeDev),
                    Path.of(home), Path.of(System.getProperty("user.dir")),
                    ttl, settle);
            LeaseProtocol.Outcome resolved =
                    protocol.resolve(workingSetRoot.toString());
            if (resolved.exitCode() != 0) {
                return Optional.empty();
            }
            return Optional.of(new Context(protocol,
                    resolved.stdout().trim()));
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return "";
        }

        private static long parseLong(String value, long fallback) {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
