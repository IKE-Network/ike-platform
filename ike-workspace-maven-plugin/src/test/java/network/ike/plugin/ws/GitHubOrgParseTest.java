package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WsScaffoldInitMojo#parseGitHubOrg(String)}: org
 * extraction from every remote form Git produces, including SSH
 * host aliases (#916).
 */
class GitHubOrgParseTest {

    @Test
    void httpsRemote() {
        assertThat(WsScaffoldInitMojo.parseGitHubOrg(
                "https://github.com/IKE-Network/ike-komet-wsr.git"))
                .isEqualTo("IKE-Network");
    }

    @Test
    void httpsRemote_noGitSuffix() {
        assertThat(WsScaffoldInitMojo.parseGitHubOrg(
                "https://github.com/IKE-Network/ike-komet-wsr"))
                .isEqualTo("IKE-Network");
    }

    @Test
    void scpRemote() {
        assertThat(WsScaffoldInitMojo.parseGitHubOrg(
                "git@github.com:IKE-Network/ike-komet-wsr.git"))
                .isEqualTo("IKE-Network");
    }

    @Test
    void scpRemote_hostAlias() {
        assertThat(WsScaffoldInitMojo.parseGitHubOrg(
                "git@github.com-graphlet:IKE-Network/ike-komet-wsr.git"))
                .isEqualTo("IKE-Network");
    }

    @Test
    void sshUrlRemote() {
        assertThat(WsScaffoldInitMojo.parseGitHubOrg(
                "ssh://git@github.com/IKE-Network/ike-komet-wsr.git"))
                .isEqualTo("IKE-Network");
    }

    @Test
    void sshUrlRemote_hostAlias() {
        assertThat(WsScaffoldInitMojo.parseGitHubOrg(
                "ssh://git@github.com-graphlet/IKE-Network/ike-komet-wsr.git"))
                .isEqualTo("IKE-Network");
    }

    @Test
    void nonGitHubHost_returnsNull() {
        assertThat(WsScaffoldInitMojo.parseGitHubOrg(
                "git@gitlab.com:some-org/repo.git")).isNull();
    }

    @Test
    void nonGitHubAlias_returnsNull() {
        assertThat(WsScaffoldInitMojo.parseGitHubOrg(
                "gh-work:some-org/repo.git")).isNull();
    }

    @Test
    void hostThatMerelyContainsGitHub_returnsNull() {
        assertThat(WsScaffoldInitMojo.parseGitHubOrg(
                "git@evil-github.com:some-org/repo.git")).isNull();
        assertThat(WsScaffoldInitMojo.parseGitHubOrg(
                "https://github.com.evil.example/some-org/repo.git")).isNull();
    }

    @Test
    void missingOrgSegment_returnsNull() {
        assertThat(WsScaffoldInitMojo.parseGitHubOrg(
                "git@github.com:repo-without-org")).isNull();
        assertThat(WsScaffoldInitMojo.parseGitHubOrg(
                "https://github.com/")).isNull();
    }

    @Test
    void degenerateInput_returnsNull() {
        assertThat(WsScaffoldInitMojo.parseGitHubOrg("")).isNull();
        assertThat(WsScaffoldInitMojo.parseGitHubOrg(null)).isNull();
        assertThat(WsScaffoldInitMojo.parseGitHubOrg("not a url")).isNull();
    }
}
