package network.ike.plugin.ws;

import java.util.regex.Pattern;

/**
 * How a working set names its release tags. A workspace whose members
 * are repositories the IKE project does not own must tag them the way
 * those repositories already tag themselves — the komet working set's
 * ikmdev members carry bare version tags ({@code 1.127.1}), while IKE's
 * own repositories carry {@code v}-prefixed ones ({@code v160}).
 *
 * <p>The style governs both directions: the tags a release mission
 * writes, and the tags detection reads back as "the last release".
 * Getting only one side right would either orphan a repository's
 * release history or write a second, competing convention beside it.
 *
 * <p>Select per workspace in the root pom:
 * {@code <ike.release.tagStyle>BARE</ike.release.tagStyle>}.
 */
public enum ReleaseTagStyle {

    /** IKE's own convention: {@code v160}, {@code v1.127.2}. */
    V_PREFIXED("v"),

    /** The ikmdev convention: {@code 1.127.2}, no prefix. */
    BARE("");

    private final String prefix;
    private final Pattern releaseTagPattern;

    ReleaseTagStyle(String prefix) {
        this.prefix = prefix;
        this.releaseTagPattern = Pattern.compile(
                "^" + Pattern.quote(prefix) + "\\d+(\\.\\d+)*$");
    }

    /**
     * The tag naming this release version in this style.
     *
     * @param version the release version, e.g. {@code 1.127.2}
     * @return the tag name, e.g. {@code v1.127.2} or {@code 1.127.2}
     */
    public String tagFor(String version) {
        return prefix + version;
    }

    /**
     * The version a tag in this style names — the inverse of
     * {@link #tagFor}.
     *
     * @param tag a tag known to match {@link #isReleaseTag}
     * @return the version portion, with any prefix removed
     */
    public String versionOf(String tag) {
        return tag.startsWith(prefix) ? tag.substring(prefix.length()) : tag;
    }

    /**
     * The {@code git tag -l} glob selecting candidate release tags.
     * Deliberately loose — {@link #isReleaseTag} does the real
     * filtering, because a glob cannot express "digits and dots only"
     * and a repository's tag namespace holds far more than releases
     * (checkpoint tags, dated tags, hand-cut development tags).
     *
     * @return the glob pattern
     */
    public String tagGlob() {
        return prefix + "[0-9]*";
    }

    /**
     * Whether a tag names a release in this style: the prefix followed
     * by a dotted run of digits and nothing else. This is what keeps
     * legacy neighbours out of release detection — {@code 1.43.0} is a
     * release, {@code 19-sep-2023-0542pm} sitting beside it is not,
     * though both begin with a digit and the latter sorts higher.
     *
     * @param tag the tag name to test
     * @return {@code true} when the tag names a release
     */
    public boolean isReleaseTag(String tag) {
        return tag != null && releaseTagPattern.matcher(tag.strip()).matches();
    }
}
