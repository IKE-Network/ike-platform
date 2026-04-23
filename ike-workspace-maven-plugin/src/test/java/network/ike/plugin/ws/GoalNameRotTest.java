package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard against stale goal-name references in source — subprocess exec
 * strings, javadoc examples, and generated output. The plugin's goals use
 * the {@code -draft} / {@code -publish} suffix convention; any
 * {@code -apply} reference is rot left over from an earlier naming scheme.
 *
 * <p>Catches the class of bug where a mojo rename (e.g.
 * {@code WsAlignApplyMojo} → {@code WsAlignPublishMojo}) leaves subprocess
 * exec strings or javadoc lines pointing at the old goal name — an error
 * the compiler cannot see. See issue #164 for the incident that prompted
 * this guard.
 */
class GoalNameRotTest {

    /** Matches stale goal suffixes to reject (e.g. {@code ws:align-apply}). */
    private static final Pattern STALE =
            Pattern.compile("\\b(?:ws|ike):[a-z][a-z0-9-]*-apply\\b");

    @Test
    void no_stale_apply_suffix_in_source() throws IOException {
        Path srcMain = Path.of("src/main/java");
        List<String> violations;
        try (Stream<Path> files = Files.walk(srcMain)) {
            violations = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .flatMap(GoalNameRotTest::matchesIn)
                    .toList();
        }

        assertThat(violations)
                .withFailMessage(
                        "Stale goal references found (goal rename rot — "
                                + "see issue #164):%n  %s",
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
        Matcher m = STALE.matcher(content);
        return m.results().map(r -> file + " — " + r.group());
    }
}
