package network.ike.plugin.ws.preflight;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@code JVM_CONFIG_NO_HASH_COMMENTS} preflight check
 * (ike-issues#217).
 *
 * <p>Validates the file-scanning helper directly so the rule can be
 * exercised without spinning up a full {@link PreflightContext}.
 */
class JvmConfigHashCommentTest {

    @TempDir
    Path tempDir;

    private List<String> violations;

    @BeforeEach
    void setUp() {
        violations = new ArrayList<>();
    }

    @Test
    void no_violations_when_file_absent() {
        PreflightCondition.collectJvmConfigViolations(
                tempDir.toFile(), "ws-root", violations);
        assertThat(violations).isEmpty();
    }

    @Test
    void no_violations_when_file_has_no_comments() throws Exception {
        writeJvmConfig("--sun-misc-unsafe-memory-access=allow\n");
        PreflightCondition.collectJvmConfigViolations(
                tempDir.toFile(), "ws-root", violations);
        assertThat(violations).isEmpty();
    }

    @Test
    void flags_hash_at_column_zero() throws Exception {
        writeJvmConfig("""
                # this comment crashes the JVM
                --sun-misc-unsafe-memory-access=allow
                """);
        PreflightCondition.collectJvmConfigViolations(
                tempDir.toFile(), "ws-root", violations);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0))
                .contains("ws-root/.mvn/jvm.config:1:")
                .contains("# this comment crashes the JVM");
    }

    @Test
    void flags_indented_hash_too() throws Exception {
        // Even a leading-whitespace # is invalid because the JVM
        // launcher splits on whitespace; the resulting "#" arg
        // becomes a main-class name. Stricter than necessary but
        // matches the documented rule.
        writeJvmConfig("    # indented comment\n");
        PreflightCondition.collectJvmConfigViolations(
                tempDir.toFile(), "ws-root", violations);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("ws-root/.mvn/jvm.config:1:");
    }

    @Test
    void allows_non_hash_lines_with_leading_whitespace() throws Exception {
        writeJvmConfig("    --add-modules=java.base\n");
        PreflightCondition.collectJvmConfigViolations(
                tempDir.toFile(), "ws-root", violations);
        assertThat(violations).isEmpty();
    }

    @Test
    void flags_each_offending_line_separately() throws Exception {
        writeJvmConfig("""
                # first comment
                --good-flag
                # second comment
                --another
                """);
        PreflightCondition.collectJvmConfigViolations(
                tempDir.toFile(), "ws-root", violations);
        assertThat(violations).hasSize(2);
        assertThat(violations.get(0)).contains(":1:").contains("# first comment");
        assertThat(violations.get(1)).contains(":3:").contains("# second comment");
    }

    @Test
    void uses_provided_display_name_in_violation_path() throws Exception {
        writeJvmConfig("# comment\n");
        PreflightCondition.collectJvmConfigViolations(
                tempDir.toFile(), "my-subproject", violations);
        assertThat(violations.get(0)).startsWith("my-subproject/.mvn/jvm.config:");
    }

    private void writeJvmConfig(String content) throws Exception {
        Path mvn = tempDir.resolve(".mvn");
        Files.createDirectories(mvn);
        Files.writeString(mvn.resolve("jvm.config"), content,
                StandardCharsets.UTF_8);
    }
}
