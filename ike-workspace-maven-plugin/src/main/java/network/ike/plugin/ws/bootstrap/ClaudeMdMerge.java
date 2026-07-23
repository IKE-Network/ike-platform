package network.ike.plugin.ws.bootstrap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Non-destructive regeneration of a subproject {@code CLAUDE.md}
 * (IKE-Network/ike-issues#917, #920).
 *
 * <p>{@code ws:scaffold-init} regenerates each subproject's {@code CLAUDE.md}
 * from a template on every run. A naive full overwrite destroyed two kinds of
 * content the template does not own:
 *
 * <ul>
 *   <li><b>#920</b> — the {@code ike-managed} regions that
 *       {@code ike:scaffold-publish} appends
 *       ({@code <!-- BEGIN ike-managed: <name> --> … <!-- END ike-managed: <name> -->}),
 *       causing the two generators to ping-pong the file on alternate runs.</li>
 *   <li><b>#917</b> — hand-authored {@code ##} sections a developer added
 *       (e.g. a project's fast-dev build notes), silently dropped on
 *       regeneration.</li>
 * </ul>
 *
 * <p>{@link #merge(String, String)} is a pure function so it can be unit-tested
 * without touching the filesystem. It:
 *
 * <ol>
 *   <li>extracts every {@code ike-managed} region from the existing file
 *       <em>verbatim</em> (byte-for-byte, on its own lines, so the emitting
 *       plugin's line-exact marker matching still locates it) and re-appends
 *       them after the freshly generated template;</li>
 *   <li>returns any top-level ({@code ## }) section whose heading the template
 *       does not itself produce as a {@linkplain Section rescued} section, for
 *       the caller to preserve in {@code CLAUDE-<name>.md} — the file the
 *       scaffold never overwrites — rather than carrying it into the
 *       regenerated {@code CLAUDE.md}.</li>
 * </ol>
 *
 * <p>Template-owned sections are regenerated from the template (that is the
 * point of regeneration); only sections the template does not emit are treated
 * as hand-authored. A section added as prose <em>inside</em> a template-owned
 * section (no new {@code ## } heading) is a residual gap — it is regenerated
 * with its section — but the reported real-world losses were whole sections,
 * which this preserves.
 */
final class ClaudeMdMerge {

    private ClaudeMdMerge() {}

    /** Matches a {@code <!-- BEGIN ike-managed: <name> -->} marker line. */
    private static final Pattern MANAGED_BEGIN = Pattern.compile(
            "^\\s*<!--\\s*BEGIN ike-managed:.*-->\\s*$");

    /** Matches a {@code <!-- END ike-managed: <name> -->} marker line. */
    private static final Pattern MANAGED_END = Pattern.compile(
            "^\\s*<!--\\s*END ike-managed:.*-->\\s*$");

    /**
     * A hand-authored section rescued from a regenerated {@code CLAUDE.md}.
     *
     * @param title the heading text after {@code ## } (used for logging/dedupe)
     * @param text  the full section including its {@code ## } heading line,
     *              with a trailing newline
     */
    record Section(String title, String text) {}

    /**
     * The result of merging an existing {@code CLAUDE.md} with a freshly
     * generated template.
     *
     * @param claudeMd the content to write back to {@code CLAUDE.md}
     * @param rescued  hand-authored sections to preserve elsewhere; empty when
     *                 the existing file held nothing the template does not own
     */
    record Result(String claudeMd, List<Section> rescued) {}

    /**
     * Merge {@code existing} CLAUDE.md content with a freshly {@code generated}
     * template.
     *
     * @param existing  the current on-disk {@code CLAUDE.md}, or {@code null}
     *                  when the file does not exist yet (fresh write)
     * @param generated the freshly generated template content
     * @return the merged content plus any hand-authored sections to rescue
     */
    static Result merge(String existing, String generated) {
        if (existing == null || existing.isBlank()) {
            return new Result(generated, List.of());
        }

        List<String> managed = new ArrayList<>();
        String withoutManaged = extractManaged(existing, managed);

        Set<String> templateTitles = sectionTitles(generated);
        List<Section> rescued = new ArrayList<>();
        for (Section section : parseSections(withoutManaged)) {
            if (!templateTitles.contains(section.title())) {
                rescued.add(section);
            }
        }

        StringBuilder sb = new StringBuilder(generated);
        for (String block : managed) {
            while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
                sb.deleteCharAt(sb.length() - 1);
            }
            sb.append("\n\n").append(block.stripTrailing()).append('\n');
        }
        return new Result(sb.toString(), rescued);
    }

    /**
     * Split off every {@code ike-managed} region from {@code content},
     * collecting each verbatim (from its {@code BEGIN} line through its
     * {@code END} line) into {@code managed}, and returning the remaining
     * (non-managed) content.
     *
     * <p>An unterminated {@code BEGIN} (no matching {@code END}) is left in the
     * remaining content rather than swallowing the rest of the file — the
     * emitter always writes balanced pairs, so this only guards against
     * hand-corrupted markers.
     */
    private static String extractManaged(String content, List<String> managed) {
        String[] lines = content.split("\n", -1);
        StringBuilder remaining = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            if (MANAGED_BEGIN.matcher(lines[i]).matches()) {
                int end = -1;
                for (int j = i + 1; j < lines.length; j++) {
                    if (MANAGED_END.matcher(lines[j]).matches()) {
                        end = j;
                        break;
                    }
                }
                if (end >= 0) {
                    StringBuilder block = new StringBuilder();
                    for (int k = i; k <= end; k++) {
                        if (k > i) {
                            block.append('\n');
                        }
                        block.append(lines[k]);
                    }
                    managed.add(block.toString());
                    i = end + 1;
                    // Skip a single blank separator line left behind.
                    if (i < lines.length && lines[i].isBlank()) {
                        i++;
                    }
                    continue;
                }
            }
            remaining.append(lines[i]);
            if (i < lines.length - 1) {
                remaining.append('\n');
            }
            i++;
        }
        return remaining.toString();
    }

    /**
     * Parse the top-level ({@code ## }) sections of {@code content}. Content
     * before the first {@code ## } heading (the {@code # } title, description)
     * is the preamble and is not returned — only headed sections can be
     * hand-authored additions.
     */
    private static List<Section> parseSections(String content) {
        String[] lines = content.split("\n", -1);
        List<Section> sections = new ArrayList<>();
        StringBuilder current = null;
        String currentTitle = null;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                if (current != null) {
                    sections.add(new Section(currentTitle,
                            current.toString().stripTrailing() + "\n"));
                }
                current = new StringBuilder();
                currentTitle = line.substring(3).strip();
            }
            if (current != null) {
                current.append(line).append('\n');
            }
        }
        if (current != null) {
            sections.add(new Section(currentTitle,
                    current.toString().stripTrailing() + "\n"));
        }
        return sections;
    }

    /**
     * The set of {@code ## } section titles the template itself emits — the
     * headings a regenerated file legitimately owns.
     */
    private static Set<String> sectionTitles(String generated) {
        Set<String> titles = new LinkedHashSet<>();
        for (Section section : parseSections(generated)) {
            titles.add(section.title());
        }
        return titles;
    }
}
