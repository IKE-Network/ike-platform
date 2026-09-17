package network.ike.plugin.ws.vcs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VcsOperations#parseMergeTreeNameOnly}: the conflicted paths of a
 * {@code git merge-tree --write-tree --name-only} run are the lines between
 * the toplevel tree id and the informational messages
 * (IKE-Network/ike-issues#1099).
 */
class VcsOperationsMergeTreeParseTest {

    private static final String TREE = "ed5cb1cb10ad4fc4cbdfc1afd565fc674950d10a";

    @Test
    void pathsSitBetweenTheTreeIdAndTheBlankLine() {
        String stdout = TREE + "\n"
                + "workspace.yaml\n"
                + "\n"
                + "Auto-merging pom.xml\n"
                + "Auto-merging workspace.yaml\n"
                + "CONFLICT (content): Merge conflict in workspace.yaml";

        assertThat(VcsOperations.parseMergeTreeNameOnly(stdout))
                .containsExactly("workspace.yaml");
    }

    @Test
    void severalPathsKeepTheirOrder() {
        String stdout = TREE + "\n"
                + "kview/src/main/java/A.java\n"
                + "workspace.yaml\n"
                + "\n"
                + "CONFLICT (content): Merge conflict in kview/src/main/java/A.java\n"
                + "CONFLICT (content): Merge conflict in workspace.yaml";

        assertThat(VcsOperations.parseMergeTreeNameOnly(stdout))
                .containsExactly("kview/src/main/java/A.java", "workspace.yaml");
    }

    @Test
    void messagesAreDroppedEvenWithoutTheBlankSeparator() {
        String stdout = TREE + "\n"
                + "workspace.yaml\n"
                + "Auto-merging workspace.yaml\n"
                + "CONFLICT (content): Merge conflict in workspace.yaml";

        assertThat(VcsOperations.parseMergeTreeNameOnly(stdout))
                .containsExactly("workspace.yaml");
    }

    @Test
    void aTreeIdAloneYieldsNoPaths() {
        assertThat(VcsOperations.parseMergeTreeNameOnly(TREE)).isEqualTo(List.of());
    }
}
