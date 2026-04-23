package network.ike.plugin.ws;

import network.ike.plugin.ws.PomSiteScanner.PomSiteSurvey;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks a Maven reactor from one or more root POMs, collecting every
 * POM's site survey into a single {@link ReactorScan}.
 *
 * <p>Each root POM is the entry point to a reactor tree. The walker
 * reads the root's {@code <modules>} / {@code <subprojects>} children,
 * descends into each, and repeats. POMs already visited (by absolute
 * normalized path) are skipped to prevent cycles.
 *
 * <p>Thread-safe: all methods are stateless.
 *
 * @see PomSiteScanner
 * @see ReleasePlan
 */
final class ReactorWalker {

    private ReactorWalker() {}

    /**
     * An ordered collection of {@link PomSiteSurvey} from every POM
     * reachable via the reactor roots passed to {@link #walk}.
     *
     * <p>Iteration order is deterministic: rooted-DFS from each root in
     * the order roots were supplied, with child modules in declaration
     * order.
     *
     * @param surveys one survey per POM in the reactor
     */
    record ReactorScan(List<PomSiteSurvey> surveys) {

        ReactorScan {
            surveys = List.copyOf(surveys);
        }
    }

    /**
     * Walk a single reactor rooted at {@code rootPom}, returning a scan
     * of every POM reachable via {@code <modules>} / {@code <subprojects>}.
     *
     * @param rootPom absolute path to the reactor root pom.xml
     * @return scan of every POM in the reactor tree
     * @throws IOException if any POM cannot be read or parsed
     */
    static ReactorScan walk(Path rootPom) throws IOException {
        return walkAll(List.of(rootPom));
    }

    /**
     * Walk multiple reactor trees and concatenate their scans. Roots
     * are walked in list order; within each root the walk is
     * depth-first following declared module order.
     *
     * <p>If two roots share a POM (one is a module of the other, or
     * both transitively include the same module), the POM is scanned
     * once; first-visit order wins.
     *
     * @param rootPoms absolute paths to each reactor root pom.xml
     * @return concatenated scan of every unique POM visited
     * @throws IOException if any POM cannot be read or parsed
     */
    static ReactorScan walkAll(List<Path> rootPoms) throws IOException {
        Set<Path> visited = new LinkedHashSet<>();
        List<PomSiteSurvey> surveys = new ArrayList<>();

        for (Path root : rootPoms) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Deque<Path> stack = new ArrayDeque<>();
            stack.push(normalizedRoot);

            while (!stack.isEmpty()) {
                Path pomPath = stack.pop();
                if (!visited.add(pomPath)) continue;

                surveys.add(PomSiteScanner.scan(pomPath));

                PomModel pom = PomModel.parse(pomPath);
                List<String> subs = pom.subprojects();
                if (subs == null || subs.isEmpty()) continue;

                // Push in reverse so declaration order is preserved in DFS
                Path pomDir = pomPath.getParent();
                List<Path> children = new ArrayList<>();
                for (String sub : subs) {
                    Path childPom = pomDir.resolve(sub).resolve("pom.xml")
                            .toAbsolutePath().normalize();
                    children.add(childPom);
                }
                Collections.reverse(children);
                for (Path child : children) {
                    stack.push(child);
                }
            }
        }

        return new ReactorScan(Collections.unmodifiableList(surveys));
    }
}
