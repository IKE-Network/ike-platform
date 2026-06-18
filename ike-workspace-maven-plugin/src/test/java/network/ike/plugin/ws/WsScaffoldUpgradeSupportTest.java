package network.ike.plugin.ws;

import network.ike.plugin.ws.reconcile.ScaffoldConventionReconciler;
import network.ike.workspace.IdeSettings;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for pure functions on {@link ScaffoldConventionReconciler}:
 * sectioned {@code .gitignore} additions and {@code .idea/misc.xml}
 * attribute updates driven by {@link IdeSettings}.
 *
 * <p>These helpers were extracted from the retired
 * {@code WsScaffoldUpgradeDraftMojo} when its 9 upgrade steps were
 * folded into the convergence pattern (IKE-Network/ike-issues#393).
 */
class WsScaffoldUpgradeSupportTest {

    // ── computeGitignoreAdditions ──────────────────────────────────────

    @Test
    void gitignore_emptyInputGetsAllSectionsWithHeaders() {
        String additions = ScaffoldConventionReconciler.computeGitignoreAdditions("");
        assertThat(additions)
                .contains("# ── Whitelist workspace-level files")
                .contains("# ── Whitelist workspace-owned directories")
                .contains("# ── IntelliJ project config (curated slice)")
                .contains("!.gitignore\n")
                .contains("!.gitattributes\n")
                .contains("!pom.xml\n")
                .contains("!.mvn/\n")
                .contains("!.idea/\n")
                .contains("!.idea/jarRepositories.xml\n")
                .doesNotContain("!.idea/misc.xml");
    }

    @Test
    void gitignore_fullyCurrentReturnsEmpty() {
        String existing = """
                *

                !.gitignore
                !.gitattributes
                !pom.xml
                !workspace.yaml

                !.mvn/
                !.mvn/**
                !checkpoints/
                !checkpoints/**

                !.idea/
                !.idea/.gitignore
                !.idea/misc.xml
                !.idea/kotlinc.xml
                !.idea/encodings.xml
                !.idea/jarRepositories.xml
                """;
        assertThat(ScaffoldConventionReconciler.computeGitignoreAdditions(existing)).isEmpty();
    }

    @Test
    void gitignore_missingOnlyIdeaSectionGetsHeader() {
        String existing = """
                *
                !.gitignore
                !.gitattributes
                !pom.xml
                !workspace.yaml
                !.mvn/
                !.mvn/**
                !checkpoints/
                !checkpoints/**
                """;
        String additions = ScaffoldConventionReconciler.computeGitignoreAdditions(existing);
        assertThat(additions)
                .contains("# ── IntelliJ project config (curated slice)")
                .contains("!.idea/\n")
                .contains("!.idea/jarRepositories.xml\n")
                .doesNotContain("!.idea/misc.xml")
                .doesNotContain("# ── Whitelist workspace-level files")
                .doesNotContain("# ── Whitelist workspace-owned directories");
    }

    @Test
    void gitignore_partialIdeaSectionGetsOnlyMissingEntriesWithoutHeader() {
        // Workspace already has some .idea/ entries but not all.
        // Upgrade should add only the missing ones, not re-emit the header
        // (otherwise we'd duplicate the section comment).
        String existing = """
                *
                !.gitignore
                !.gitattributes
                !pom.xml
                !workspace.yaml
                !.mvn/
                !.mvn/**
                !checkpoints/
                !checkpoints/**
                !.idea/
                !.idea/misc.xml
                """;
        String additions = ScaffoldConventionReconciler.computeGitignoreAdditions(existing);
        assertThat(additions)
                .doesNotContain("# ── IntelliJ project config")
                .contains("!.idea/.gitignore\n")
                .contains("!.idea/kotlinc.xml\n")
                .doesNotContain("!.idea/misc.xml\n")   // already present
                .doesNotContain("!.idea/\n");          // already present (as standalone line)
    }

    @Test
    void gitignore_lineBasedMatchAvoidsFalsePositives() {
        // `!.mvn/` is a prefix of `!.mvn/**`. Substring matching would
        // treat both as "present" from just `!.mvn/**`. We want the
        // check to operate on full lines to detect the missing entry.
        String existing = """
                *
                !.gitignore
                !.gitattributes
                !pom.xml
                !workspace.yaml
                !.mvn/**
                !checkpoints/
                !checkpoints/**
                """;
        String additions = ScaffoldConventionReconciler.computeGitignoreAdditions(existing);
        assertThat(additions).contains("!.mvn/\n");
    }

    @Test
    void gitignore_addsGitattributesToBlacklistWhitelist() {
        // Workspace uses whitelist pattern but was created before the
        // .gitattributes convention. Upgrade must whitelist !.gitattributes
        // as part of the workspace-level-files section — otherwise the
        // gitattributes-standard step's new file would be invisible to git.
        String existing = """
                *
                !.gitignore
                !pom.xml
                !workspace.yaml
                !.mvn/
                !.mvn/**
                !checkpoints/
                !checkpoints/**
                !.idea/
                !.idea/.gitignore
                !.idea/misc.xml
                !.idea/kotlinc.xml
                !.idea/encodings.xml
                !.idea/jarRepositories.xml
                """;
        String additions = ScaffoldConventionReconciler.computeGitignoreAdditions(existing);
        assertThat(additions)
                .contains("!.gitattributes\n")
                .doesNotContain("# ── Whitelist workspace-level files");
    }

    // ── computeGitattributesAdditions ─────────────────────────────────

    @Test
    void gitattributes_emptyInputGetsHeaderAndAllRules() {
        String additions = ScaffoldConventionReconciler.computeGitattributesAdditions("");
        assertThat(additions)
                .contains("# Line-ending policy")
                .contains("*.cmd  text eol=crlf\n")
                .contains("*.bat  text eol=crlf\n")
                .contains("*.sh   text eol=lf\n")
                .contains("mvnw   text eol=lf\n")
                .contains("* text=auto\n");
    }

    @Test
    void gitattributes_fullyCurrentReturnsEmpty() {
        // Simulates the file ws:scaffold-init would write (mirrors komet-ws's
        // real .gitattributes added as the manual fix for #189).
        String existing = """
                # Line-ending policy for workspace
                *.cmd  text eol=crlf
                *.bat  text eol=crlf
                *.sh   text eol=lf
                mvnw   text eol=lf
                * text=auto
                """;
        assertThat(ScaffoldConventionReconciler.computeGitattributesAdditions(existing)).isEmpty();
    }

    @Test
    void gitattributes_partialFileGetsOnlyMissingRulesNoHeader() {
        // User has *.cmd CRLF rule (the critical one) but not the others.
        // Upgrade should append the missing rules only, without re-emitting
        // the header block (the existing file may carry the user's own
        // comments that we must preserve verbatim).
        String existing = """
                # user's own preamble
                *.cmd text eol=crlf
                """;
        String additions = ScaffoldConventionReconciler.computeGitattributesAdditions(existing);
        assertThat(additions)
                .doesNotContain("# Line-ending policy")   // no duplicate header
                .doesNotContain("*.cmd")                    // already present
                .contains("*.bat  text eol=crlf\n")
                .contains("*.sh   text eol=lf\n")
                .contains("mvnw   text eol=lf\n")
                .contains("* text=auto\n");
    }

    @Test
    void gitattributes_matchesByPatternTokenNotExactLine() {
        // A user who pinned a custom attribute alongside the pattern
        // should not trigger a duplicate rule. Detection is by the
        // leading whitespace-separated pattern token, not the whole line.
        String existing = """
                *.cmd text eol=crlf working-tree-encoding=UTF-8
                *.bat text eol=crlf
                *.sh text eol=lf
                mvnw text eol=lf
                * text=auto
                """;
        assertThat(ScaffoldConventionReconciler.computeGitattributesAdditions(existing)).isEmpty();
    }

    @Test
    void gitattributes_commentsAndBlankLinesIgnored() {
        // Comment lines must not be parsed as patterns — otherwise a
        // comment starting with `*` would shadow the `* text=auto` rule.
        String existing = """
                # * this looks like a pattern but it's a comment

                *.cmd  text eol=crlf
                *.bat  text eol=crlf
                *.sh   text eol=lf
                mvnw   text eol=lf
                """;
        String additions = ScaffoldConventionReconciler.computeGitattributesAdditions(existing);
        assertThat(additions).contains("* text=auto\n");
    }

    // ── applyIdeSettings ──────────────────────────────────────────────

    private static final String MISC_XML_JDK_25 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4">
              <component name="ExternalStorageConfigurationManager" enabled="true" />
              <component name="ProjectRootManager" version="2" languageLevel="JDK_25" default="true" project-jdk-name="25" project-jdk-type="JavaSDK" />
            </project>
            """;

    @Test
    void ideSettings_updatesLanguageLevelWhenDifferent() {
        IdeSettings ide = new IdeSettings("JDK_25_PREVIEW", null, false);
        String updated = ScaffoldConventionReconciler.applyIdeSettings(MISC_XML_JDK_25, ide);
        assertThat(updated).contains("languageLevel=\"JDK_25_PREVIEW\"");
        assertThat(updated).doesNotContain("languageLevel=\"JDK_25\"");
        // Other attributes untouched
        assertThat(updated).contains("project-jdk-name=\"25\"");
    }

    @Test
    void ideSettings_idempotentWhenLanguageLevelMatches() {
        IdeSettings ide = new IdeSettings("JDK_25", null, false);
        String updated = ScaffoldConventionReconciler.applyIdeSettings(MISC_XML_JDK_25, ide);
        assertThat(updated).isEqualTo(MISC_XML_JDK_25);
    }

    @Test
    void ideSettings_updatesJdkNameWhenProvided() {
        IdeSettings ide = new IdeSettings(null, "corretto-25", false);
        String updated = ScaffoldConventionReconciler.applyIdeSettings(MISC_XML_JDK_25, ide);
        assertThat(updated).contains("project-jdk-name=\"corretto-25\"");
        assertThat(updated).doesNotContain("project-jdk-name=\"25\" ");
        // languageLevel untouched when only jdkName specified
        assertThat(updated).contains("languageLevel=\"JDK_25\"");
    }

    @Test
    void ideSettings_updatesBothWhenBothProvided() {
        IdeSettings ide = new IdeSettings("JDK_21", "temurin-21", false);
        String updated = ScaffoldConventionReconciler.applyIdeSettings(MISC_XML_JDK_25, ide);
        assertThat(updated).contains("languageLevel=\"JDK_21\"");
        assertThat(updated).contains("project-jdk-name=\"temurin-21\"");
    }

    @Test
    void ideSettings_emptyIsNoOp() {
        String updated = ScaffoldConventionReconciler.applyIdeSettings(MISC_XML_JDK_25, IdeSettings.EMPTY);
        assertThat(updated).isEqualTo(MISC_XML_JDK_25);
    }

    @Test
    void ideSettings_noProjectRootManagerIsNoOp() {
        String other = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project version="4">
                  <component name="SomethingElse" value="foo" />
                </project>
                """;
        String updated = ScaffoldConventionReconciler.applyIdeSettings(
                other, new IdeSettings("JDK_25_PREVIEW", null, false));
        assertThat(updated).isEqualTo(other);
    }

    // ── reconcileMiscXmlWhitelist (#571) ──────────────────────────────

    @Test
    void miscXml_stripsLineWhenNotTracking() {
        // The "rogue line" scenario: misc.xml whitelisted at EOF, after
        // the goal-report ignore lines. Not tracking → it must be removed.
        String existing = """
                *
                !.idea/
                !.idea/.gitignore

                # ike goal reports
                ike-report.md
                !.idea/misc.xml
                """;
        String updated = ScaffoldConventionReconciler
                .reconcileMiscXmlWhitelist(existing, false);
        assertThat(updated)
                .doesNotContain("!.idea/misc.xml")
                .contains("ike-report.md\n")
                .contains("!.idea/.gitignore\n");
    }

    @Test
    void miscXml_noOpWhenAbsentAndNotTracking() {
        String existing = """
                *
                !.idea/
                !.idea/.gitignore
                """;
        assertThat(ScaffoldConventionReconciler
                .reconcileMiscXmlWhitelist(existing, false)).isEqualTo(existing);
    }

    @Test
    void miscXml_addsLineWhenTrackingAndAbsent() {
        String existing = """
                *
                !.idea/
                !.idea/.gitignore
                """;
        String updated = ScaffoldConventionReconciler
                .reconcileMiscXmlWhitelist(existing, true);
        assertThat(updated)
                .contains("!.idea/.gitignore\n")
                .contains("!.idea/misc.xml\n");
    }

    @Test
    void miscXml_noOpWhenPresentAndTracking() {
        String existing = """
                *
                !.idea/
                !.idea/misc.xml
                """;
        assertThat(ScaffoldConventionReconciler
                .reconcileMiscXmlWhitelist(existing, true)).isEqualTo(existing);
    }

    @Test
    void miscXml_strippingIsIdempotent() {
        String existing = """
                *
                !.idea/
                !.idea/misc.xml
                """;
        String once = ScaffoldConventionReconciler
                .reconcileMiscXmlWhitelist(existing, false);
        String twice = ScaffoldConventionReconciler
                .reconcileMiscXmlWhitelist(once, false);
        assertThat(twice).isEqualTo(once);
        assertThat(once).doesNotContain("!.idea/misc.xml");
    }

    // ── stripIdeSyncBlock (#460/#696) ──────────────────────────────────

    @Test
    void stripIdeSyncBlock_removes_managed_block_keeping_the_rest() {
        String config = """
                -T 1C
                # >>> ws:ide-sync managed >>>
                -P?with-alpha,?with-beta
                # <<< ws:ide-sync managed <<<
                """;
        assertThat(ScaffoldConventionReconciler.stripIdeSyncBlock(config))
                .isEqualTo("-T 1C\n");
    }

    @Test
    void stripIdeSyncBlock_is_idempotent_and_noop_without_block() {
        String clean = "-T 1C\n";
        assertThat(ScaffoldConventionReconciler.stripIdeSyncBlock(clean))
                .isEqualTo(clean);
        // Idempotent: stripping an already-stripped config is a no-op.
        String config = """
                -T 1C
                # >>> ws:ide-sync managed >>>
                -P?with-alpha
                # <<< ws:ide-sync managed <<<
                """;
        String once = ScaffoldConventionReconciler.stripIdeSyncBlock(config);
        assertThat(ScaffoldConventionReconciler.stripIdeSyncBlock(once))
                .isEqualTo(once);
    }
}
