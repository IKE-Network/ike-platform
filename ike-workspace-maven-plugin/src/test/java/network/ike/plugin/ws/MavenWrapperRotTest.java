package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard against re-introducing the wrapper-lookup bug from issues
 * {@code #181} and {@code #182}. New mojo code must resolve the Maven
 * wrapper via {@code ReleaseSupport.resolveMavenWrapper(File, Log)} —
 * never via an inline {@code new File(<dir>, "mvnw")} lookup that
 * silently breaks on Windows.
 *
 * <p>The class of bug this catches: a private {@code resolveMvn(File)}
 * helper inside a mojo that hardcodes {@code new File(root, "mvnw")},
 * skipping {@code mvnw.cmd} on Windows and the {@code where mvn.cmd}
 * fallback. The compiler cannot see this — only a source scan can.
 *
 * <p>The lookup pattern this test forbids is specifically
 * {@code new File(<x>, "mvnw"...)}. Path-based <em>writers</em> like
 * {@code Path.resolve("mvnw")} (used by {@code WsCreateMojo} and
 * {@code InitWorkspaceMojo} to scaffold wrapper scripts) are
 * legitimate and must remain allowed.
 */
class MavenWrapperRotTest {

    /**
     * The bug pattern: {@code new File(<dir>, "mvnw")} or
     * {@code new File(<dir>, "mvnw.cmd")}. This is how an inline lookup
     * is written; writers use {@code Path.resolve(...)} instead.
     */
    private static final Pattern LOOKUP_PATTERN = Pattern.compile(
            "new\\s+File\\s*\\([^)]*?,\\s*\"mvnw(?:\\.cmd)?\"\\s*\\)");

    /**
     * Files that legitimately contain a wrapper-resolution function for
     * historical reasons. These have their own focused unit tests
     * ({@code WsReleaseSupportTest.resolveMvnCommand_*}) and a separate
     * follow-up will consolidate them onto
     * {@code ReleaseSupport.resolveMavenWrapper}.
     */
    private static final Set<String> ALLOWLIST = Set.of(
            "WsReleaseDraftMojo.java"
    );

    @Test
    void no_inline_wrapper_lookup_in_mojo_sources() throws IOException {
        Path srcMain = Path.of("src/main/java");
        List<String> violations;
        try (Stream<Path> files = Files.walk(srcMain)) {
            violations = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !ALLOWLIST.contains(p.getFileName().toString()))
                    .flatMap(MavenWrapperRotTest::matchesIn)
                    .toList();
        }

        assertThat(violations)
                .withFailMessage(
                        "Inline wrapper lookup found — use "
                                + "ReleaseSupport.resolveMavenWrapper(File, Log) "
                                + "instead (see issues #181, #182):%n  %s",
                        String.join(System.lineSeparator() + "  ", violations))
                .isEmpty();
    }

    private static Stream<String> matchesIn(Path file) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Matcher m = LOOKUP_PATTERN.matcher(content);
        return m.results().map(r -> file + " — " + r.group());
    }
}
