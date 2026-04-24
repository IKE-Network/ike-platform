package network.ike.plugin.ws;

import org.apache.maven.api.plugin.annotations.Mojo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-reference check: {@link WsGoal} must stay in lockstep with the
 * {@link Mojo @Mojo} declarations on the ws plugin's mojo classes.
 *
 * <p>Two failure modes are both guarded:
 * <ul>
 *   <li>An enum entry's {@code goalName} doesn't match its mojo class's
 *       {@code @Mojo(name = ...)} — caught by
 *       {@link #every_enum_entry_matches_its_mojo_annotation()}.</li>
 *   <li>A new {@code @Mojo} class is added without a matching enum
 *       entry, or vice versa — caught by
 *       {@link #enum_names_cover_every_mojo_in_source()}.</li>
 * </ul>
 *
 * <p>See issue #165.
 */
class WsGoalExhaustivenessTest {

    private static final Pattern MOJO_NAME = Pattern.compile(
            "@(?:[\\w.]*\\.)?Mojo\\s*\\(\\s*name\\s*=\\s*\"([a-z][a-z0-9-]*)\"");

    @Test
    void every_enum_entry_matches_its_mojo_annotation() {
        for (WsGoal goal : WsGoal.values()) {
            Mojo annotation = goal.mojoClass().getAnnotation(Mojo.class);
            assertThat(annotation)
                    .withFailMessage("WsGoal.%s mojoClass %s has no @Mojo annotation",
                            goal.name(), goal.mojoClass().getSimpleName())
                    .isNotNull();
            assertThat(annotation.name())
                    .withFailMessage("WsGoal.%s declares goalName=\"%s\""
                                    + " but @Mojo on %s says \"%s\"",
                            goal.name(), goal.goalName(),
                            goal.mojoClass().getSimpleName(), annotation.name())
                    .isEqualTo(goal.goalName());
        }
    }

    /**
     * Regression guard for issue #237.
     *
     * <p>Every {@code ws:*} goal is workspace-level — it operates on
     * the root workspace POM, not per reactor subproject. Without
     * {@code aggregator = true}, Maven re-resolves the {@code ws:}
     * plugin prefix inside each subproject's effective plugin list.
     * In a reactor that mixes a {@code network.ike.platform:ike-parent}
     * root with subprojects still inheriting from the pre-split
     * {@code network.ike.pipeline:ike-parent}, this causes {@code ws:}
     * to bind to the OLD {@code network.ike.pipeline:ike-workspace-maven-plugin}
     * on subprojects — which then fails to resolve any goal renamed
     * in the new plugin (e.g. {@code scaffold-upgrade-publish}).
     *
     * <p>Marking every ws mojo as aggregator pins execution to the
     * reactor root, where {@code ws:} resolves to this plugin.
     */
    @Test
    void every_ws_mojo_is_aggregator() {
        for (WsGoal goal : WsGoal.values()) {
            Mojo annotation = goal.mojoClass().getAnnotation(Mojo.class);
            assertThat(annotation)
                    .withFailMessage("WsGoal.%s mojoClass %s has no @Mojo annotation",
                            goal.name(), goal.mojoClass().getSimpleName())
                    .isNotNull();
            assertThat(annotation.aggregator())
                    .withFailMessage("WsGoal.%s (%s) must be aggregator = true"
                                    + " — ws: goals are workspace-level and must"
                                    + " run at the reactor root only (see #237)",
                            goal.name(), goal.mojoClass().getSimpleName())
                    .isTrue();
        }
    }

    @Test
    void enum_names_cover_every_mojo_in_source() throws IOException {
        Set<String> fromSource = scanMojoNamesInSource();
        Set<String> fromEnum = Arrays.stream(WsGoal.values())
                .map(WsGoal::goalName)
                .collect(Collectors.toSet());

        Set<String> missingFromEnum = new HashSet<>(fromSource);
        missingFromEnum.removeAll(fromEnum);
        Set<String> missingFromSource = new HashSet<>(fromEnum);
        missingFromSource.removeAll(fromSource);

        assertThat(missingFromEnum)
                .withFailMessage("@Mojo names without a WsGoal entry: %s",
                        missingFromEnum)
                .isEmpty();
        assertThat(missingFromSource)
                .withFailMessage("WsGoal entries without a matching @Mojo"
                                + " in source: %s",
                        missingFromSource)
                .isEmpty();
    }

    private static Set<String> scanMojoNamesInSource() throws IOException {
        Path srcMain = Path.of("src/main/java");
        Set<String> names = new HashSet<>();
        try (Stream<Path> files = Files.walk(srcMain)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(file)
                        || !file.toString().endsWith(".java")) {
                    continue;
                }
                Matcher m = MOJO_NAME.matcher(Files.readString(file));
                while (m.find()) {
                    names.add(m.group(1));
                }
            }
        }
        return names;
    }
}
