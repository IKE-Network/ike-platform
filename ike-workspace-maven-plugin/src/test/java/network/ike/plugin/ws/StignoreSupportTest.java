package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for pure functions extracted from {@link StignoreWorkspaceMojo}:
 * pattern lists and content generation.
 */
class StignoreSupportTest {

    // ── commonIgnorePatterns ─────────────────────────────────────────

    @Test
    void commonIgnorePatterns_containsBuildArtifacts() {
        assertThat(StignoreWorkspaceMojo.commonIgnorePatterns())
                .contains("**/target");
    }

    @Test
    void commonIgnorePatterns_containsGitMetadata() {
        assertThat(StignoreWorkspaceMojo.commonIgnorePatterns())
                .contains("**/.git");
    }

    @Test
    void commonIgnorePatterns_containsIdeFiles() {
        List<String> patterns = StignoreWorkspaceMojo.commonIgnorePatterns();
        assertThat(patterns)
                .contains("**/.idea")
                .contains("**/.vscode")
                .contains("**/*.iml");
    }

    @Test
    void commonIgnorePatterns_containsOsMetadata() {
        List<String> patterns = StignoreWorkspaceMojo.commonIgnorePatterns();
        assertThat(patterns)
                .contains(".DS_Store")
                .contains("Thumbs.db");
    }

    @Test
    void commonIgnorePatterns_containsNodeModules() {
        assertThat(StignoreWorkspaceMojo.commonIgnorePatterns())
                .contains("**/node_modules");
    }

    @Test
    void commonIgnorePatterns_containsClaudeWorktrees() {
        assertThat(StignoreWorkspaceMojo.commonIgnorePatterns())
                .contains("**/.claude/worktrees");
    }

    @Test
    void commonIgnorePatterns_containsMavenWrapper() {
        List<String> patterns = StignoreWorkspaceMojo.commonIgnorePatterns();
        assertThat(patterns)
                .contains("**/.mvn/local-repo")
                .contains("**/.mvn/wrapper/maven-wrapper.jar");
    }

    @Test
    void commonIgnorePatterns_isNotEmpty() {
        assertThat(StignoreWorkspaceMojo.commonIgnorePatterns())
                .isNotEmpty();
    }

    // ── workspaceIgnorePatterns ──────────────────────────────────────

    @Test
    void workspaceIgnorePatterns_containsAllCommonPatterns() {
        List<String> workspace = StignoreWorkspaceMojo.workspaceIgnorePatterns();
        List<String> common = StignoreWorkspaceMojo.commonIgnorePatterns();

        assertThat(workspace).containsAll(common);
    }

    @Test
    void workspaceIgnorePatterns_addsCheckpointsEntry() {
        assertThat(StignoreWorkspaceMojo.workspaceIgnorePatterns())
                .contains("checkpoints");
    }

    @Test
    void workspaceIgnorePatterns_largerThanCommon() {
        assertThat(StignoreWorkspaceMojo.workspaceIgnorePatterns().size())
                .isGreaterThan(StignoreWorkspaceMojo.commonIgnorePatterns().size());
    }

    // ── buildStignoreContent ─────────────────────────────────────────

    @Test
    void buildStignoreContent_joinsWithNewlines() {
        String content = StignoreWorkspaceMojo.buildStignoreContent(
                List.of("alpha", "beta", "gamma"));

        assertThat(content).isEqualTo("alpha\nbeta\ngamma\n");
    }

    @Test
    void buildStignoreContent_trailingNewline() {
        String content = StignoreWorkspaceMojo.buildStignoreContent(
                List.of("single"));

        assertThat(content).endsWith("\n");
    }

    @Test
    void buildStignoreContent_emptyList_justNewline() {
        String content = StignoreWorkspaceMojo.buildStignoreContent(List.of());

        assertThat(content).isEqualTo("\n");
    }

    @Test
    void buildStignoreContent_preservesBlankLines() {
        String content = StignoreWorkspaceMojo.buildStignoreContent(
                List.of("first", "", "second"));

        assertThat(content).isEqualTo("first\n\nsecond\n");
    }

    @Test
    void buildStignoreContent_preservesComments() {
        String content = StignoreWorkspaceMojo.buildStignoreContent(
                List.of("// comment", "**/target"));

        assertThat(content).isEqualTo("// comment\n**/target\n");
    }

    @Test
    void buildStignoreContent_fromCommonPatterns_hasExpectedStructure() {
        String content = StignoreWorkspaceMojo.buildStignoreContent(
                StignoreWorkspaceMojo.commonIgnorePatterns());

        assertThat(content)
                .startsWith("// IKE Workspace .stignore\n")
                .contains("**/target\n")
                .contains("**/.git\n")
                .endsWith("**/node_modules\n");
    }
}
