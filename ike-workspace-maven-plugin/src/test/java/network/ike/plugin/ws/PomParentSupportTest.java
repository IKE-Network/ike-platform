package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PomParentSupport}'s relativePath detection
 * (ike-issues#324).
 *
 * <p>Path-based readers and the model-API parent read are exercised
 * by higher-level tests that need a real pom.xml on disk; this
 * suite covers the pure-string helper {@code
 * hasEmptyRelativePathInContent}.
 */
class PomParentSupportTest {

    @Test
    void selfClosingRelativePath_isEmpty() {
        String pom = """
                <project>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>35</version>
                    <relativePath/>
                  </parent>
                </project>
                """;
        assertThat(PomParentSupport.hasEmptyRelativePathInContent(pom)).isTrue();
    }

    @Test
    void emptyPairedRelativePath_isEmpty() {
        String pom = """
                <project>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>35</version>
                    <relativePath></relativePath>
                  </parent>
                </project>
                """;
        assertThat(PomParentSupport.hasEmptyRelativePathInContent(pom)).isTrue();
    }

    @Test
    void whitespaceInsidePaired_isStillEmpty() {
        String pom = """
                <project>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>35</version>
                    <relativePath>   </relativePath>
                  </parent>
                </project>
                """;
        assertThat(PomParentSupport.hasEmptyRelativePathInContent(pom)).isTrue();
    }

    @Test
    void nonEmptyRelativePath_isNotEmpty() {
        String pom = """
                <project>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>35</version>
                    <relativePath>../pom.xml</relativePath>
                  </parent>
                </project>
                """;
        assertThat(PomParentSupport.hasEmptyRelativePathInContent(pom)).isFalse();
    }

    @Test
    void absentRelativePath_isNotEmpty() {
        // No <relativePath> at all — Maven uses its default
        // (../pom.xml). Coherence-wise, this is the "cycle risk"
        // case for IKE workspaces.
        String pom = """
                <project>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>35</version>
                  </parent>
                </project>
                """;
        assertThat(PomParentSupport.hasEmptyRelativePathInContent(pom)).isFalse();
    }

    @Test
    void noParentBlock_isNotEmpty() {
        String pom = """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>orphan</artifactId>
                  <version>1</version>
                </project>
                """;
        assertThat(PomParentSupport.hasEmptyRelativePathInContent(pom)).isFalse();
    }

    @Test
    void emptyRelativePathOutsideParent_isIgnored() {
        // An <relativePath/> appearing somewhere other than inside
        // <parent> (e.g., inside a plugin configuration or a
        // distributionManagement.site element) should NOT count
        // as a parent's empty relativePath.
        String pom = """
                <project>
                  <parent>
                    <groupId>x</groupId>
                    <artifactId>y</artifactId>
                    <version>1</version>
                    <relativePath>../pom.xml</relativePath>
                  </parent>
                  <build>
                    <plugins>
                      <plugin>
                        <configuration>
                          <relativePath/>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """;
        assertThat(PomParentSupport.hasEmptyRelativePathInContent(pom)).isFalse();
    }

    @Test
    void nullOrBlank_isNotEmpty() {
        assertThat(PomParentSupport.hasEmptyRelativePathInContent(null)).isFalse();
        assertThat(PomParentSupport.hasEmptyRelativePathInContent("")).isFalse();
        assertThat(PomParentSupport.hasEmptyRelativePathInContent("   ")).isFalse();
    }
}
