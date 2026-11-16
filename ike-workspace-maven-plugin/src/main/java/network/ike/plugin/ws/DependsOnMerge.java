package network.ike.plugin.ws;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merge freshly derived {@code depends-on} edges into a subproject's
 * existing {@code workspace.yaml} block instead of replacing it
 * (IKE-Network/ike-issues#964).
 *
 * <p>The old replace-wholesale rewrite silently discarded manual
 * edits: a hand-declared {@code relationship: bundle} edge (#963), a
 * {@code content} edge with no POM counterpart, and any explanatory
 * comments all vanished into a routine re-derivation commit. The merge
 * keeps machine ownership of what derivation actually knows —
 * {@code build} edges — and treats everything else as hand-authored:
 *
 * <ul>
 *   <li><b>Non-build entries are preserved verbatim</b> (text and
 *       attached comments), whatever the POMs say. A preserved entry
 *       also suppresses emitting a derived {@code build} duplicate for
 *       the same target, so a {@code bundle} edge is never downgraded
 *       back to {@code build}.</li>
 *   <li><b>Existing build entries that are still derived keep their
 *       original text</b> — comments included — except their
 *       {@code version-property}: a property derivation detects is
 *       written in, replacing a different one. A property derivation
 *       does not detect is kept, since a version mismatch at derivation
 *       time hides a property that is still right.</li>
 *   <li><b>Build entries no longer derived are removed</b> (the drift
 *       correction this sync exists for), and newly derived targets are
 *       appended as fresh entries carrying their derived
 *       relationship.</li>
 *   <li><b>Derivation may emit {@code bundle}</b> for plugin-staged
 *       references (ike-issues#965): a {@code build} entry whose
 *       derivation moved to {@code bundle} is superseded — removed and
 *       re-added as {@code relationship: bundle} — the idiom-migration
 *       path from a project dependency to {@code artifactItem} staging.
 *       Hand-authored non-build entries still cover their target and
 *       are never replaced.</li>
 * </ul>
 *
 * <p>The rewritten block gains a one-line managed marker above
 * {@code depends-on:} in the style of the {@code ws:scaffold-init}
 * header block, making the ownership boundary visible in the file
 * itself. The marker is regenerated idempotently.
 *
 * <p><b>A member's block is looked for only in that member's own
 * section</b> ({@link SubprojectSection}, IKE-Network/ike-issues#1387).
 * The search once ran from the member's name to the next
 * {@code depends-on:} anywhere later in the file, so a member with no
 * block of its own matched the block of a later member, and the edges
 * of every such member were merged, one over the other, into that one
 * block. A member with no {@code depends-on:} key now gains a block at
 * the end of its section, so a manifest written before blocks were
 * recorded gains its derived edges.
 */
final class DependsOnMerge {

    private DependsOnMerge() {}

    /** Managed-ownership marker written above every derived block. */
    static final String MARKER =
            "    # ── managed: depends-on is derived from POMs by ws goals; "
                    + "build edges are machine-owned, other relationships "
                    + "are preserved (ike-issues#964) ──";

    /**
     * Outcome of a merge: the updated YAML (identical to the input when
     * nothing changed) and each final entry's relationship (for
     * ordering-graph construction by the acyclicity gate).
     *
     * <p>What the merge added and removed is not part of the outcome.
     * A caller that reports a change reads the member's block back from
     * the text before and after ({@link #recorded}), so that the report
     * is of what the file holds (IKE-Network/ike-issues#1387).
     *
     * @param yaml               the updated workspace.yaml content
     * @param finalRelationships each final entry's relationship by
     *                           target: surviving entries first, then
     *                           added build edges, then added bundle
     *                           edges
     */
    record Result(String yaml, Map<String, String> finalRelationships) {

        /**
         * Whether the merge changed the text.
         *
         * @param original the content the merge was given
         * @return true when {@link #yaml} differs from {@code original}
         */
        boolean changed(String original) {
            return !yaml.equals(original);
        }
    }

    /**
     * One existing entry of a {@code depends-on} block: its target
     * name, relationship, and verbatim text chunk (attached comment
     * lines included).
     */
    private record Entry(String target, String relationship, String text) {}

    /**
     * One edge as a block records it.
     *
     * @param relationship    the entry's relationship; {@code build}
     *                        when the entry names none
     * @param versionProperty the entry's {@code version-property}, or
     *                        null when it has none
     */
    record Edge(String relationship, String versionProperty) {}

    /**
     * What a member's {@code depends-on} block records, read back from
     * manifest text.
     *
     * @param present whether the member has a block
     * @param text    the block verbatim — marker, {@code depends-on:}
     *                line, entries and comments; empty when there is
     *                no block
     * @param edges   the recorded edges by target, in block order
     */
    record Recorded(boolean present, String text, Map<String, Edge> edges) {}

    /** The first line of a managed marker, whatever its wording. */
    private static final String MARKER_PREFIX =
            "    # ── managed: depends-on";

    /** The {@code depends-on:} key at member-field indentation. */
    private static final String KEY = "    depends-on:";

    /**
     * A {@code depends-on} block at member-field indentation — an
     * optional managed marker line, the {@code depends-on:} line, and
     * every 6-space-indented continuation (entries, their fields, and
     * attached comments). Anchored at the start of a line, so the key's
     * text inside a deeper-indented line, such as a folded description,
     * is not the key.
     */
    private static final Pattern BLOCK = Pattern.compile(
            "^(?:" + Pattern.quote(MARKER_PREFIX) + "[^\n]*\n)?"
                    + Pattern.quote(KEY) + ".*(?:\n      .*)*(?:\n|\\z)",
            Pattern.MULTILINE);

    /**
     * Where a member's {@code depends-on} block is in the manifest
     * text or, for a member that has none, where it belongs.
     *
     * @param start offset of the block's first character, or the
     *              insertion point
     * @param end   offset just past the block; equal to {@code start}
     *              when there is no block
     * @param block the block verbatim; empty when there is none
     */
    private record Located(int start, int end, String block) {

        /** Whether the member has a block, as opposed to a place for one. */
        boolean present() {
            return !block.isEmpty();
        }
    }

    /**
     * Locate a member's block within the member's own section
     * (IKE-Network/ike-issues#1387).
     *
     * @param yaml           the full workspace.yaml content
     * @param subprojectName the member whose block to locate
     * @return the block, or the place for one at the end of the
     *         member's section when it has no {@code depends-on:} key;
     *         null when the member's section cannot be found, or when
     *         it has a {@code depends-on:} key this class cannot read
     *         (a manifest with carriage-return line endings) — such a
     *         member is left alone rather than given a second key
     */
    private static Located locate(String yaml, String subprojectName) {
        SubprojectSection section =
                SubprojectSection.find(yaml, subprojectName).orElse(null);
        if (section == null) return null;

        Matcher m = BLOCK.matcher(yaml)
                .region(section.bodyStart(), section.end());
        if (m.find()) {
            return new Located(m.start(), m.end(), m.group());
        }
        for (String line : yaml
                .substring(section.bodyStart(), section.end())
                .split("\n")) {
            if (line.startsWith(KEY)) return null;
        }
        return new Located(section.contentEnd(), section.contentEnd(), "");
    }

    /**
     * {@code yaml} with the located block replaced by {@code rendered},
     * or with {@code rendered} inserted where a block belongs. A text
     * whose last line has no line ending gets one before an insertion.
     */
    private static String splice(String yaml, Located at,
                                 CharSequence rendered) {
        String before = yaml.substring(0, at.start());
        String separator =
                before.isEmpty() || before.endsWith("\n") ? "" : "\n";
        return before + separator + rendered + yaml.substring(at.end());
    }

    /**
     * Read back what a member's block records.
     *
     * <p>This is how a re-derivation reports what it wrote: the same
     * member read from the text before and the text after
     * (IKE-Network/ike-issues#1387).
     *
     * @param yaml           the full workspace.yaml content
     * @param subprojectName the member whose block to read
     * @return the recorded block; not present when the member has no
     *         block, or its section cannot be found
     */
    static Recorded recorded(String yaml, String subprojectName) {
        Located located = locate(yaml, subprojectName);
        if (located == null || !located.present()) {
            return new Recorded(false, "", Map.of());
        }
        Map<String, Edge> edges = new LinkedHashMap<>();
        for (Entry entry : parseEntries(located.block()).entries()) {
            edges.put(entry.target(), new Edge(
                    entry.relationship() == null
                            ? "build" : entry.relationship(),
                    versionPropertyOf(entry)));
        }
        return new Recorded(true, located.block(), edges);
    }

    /**
     * Record one more build edge in a member's block, leaving every
     * entry already there as written. A member with no block gains
     * one. Used by {@code ws:add} to record that an existing member
     * builds against the member being added.
     *
     * @param yaml            the full workspace.yaml content
     * @param subprojectName  the member that depends on {@code target}
     * @param target          the member depended on
     * @param versionProperty the property that versions the dependency,
     *                        or null when there is none
     * @return the updated content; {@code yaml} itself when the block
     *         already has an entry for {@code target}, or the member's
     *         block cannot be located
     */
    static String addBuildEdge(String yaml, String subprojectName,
                               String target, String versionProperty) {
        Located located = locate(yaml, subprojectName);
        if (located == null) return yaml;

        ParsedBlock parsed = parseEntries(located.block());
        StringBuilder rendered = new StringBuilder();
        rendered.append(MARKER).append('\n');
        rendered.append(KEY).append('\n');
        for (Entry entry : parsed.entries()) {
            if (target.equals(entry.target())) return yaml;
            rendered.append(entry.text());
        }
        appendEntry(rendered, target, "build", versionProperty);
        rendered.append(parsed.trailingComments());
        return splice(yaml, located, rendered);
    }

    /**
     * Merge {@code derived} into {@code subprojectName}'s block within
     * {@code yaml}. A member with no {@code depends-on:} key gains a
     * block at the end of its section.
     *
     * @param yaml           the full workspace.yaml content
     * @param subprojectName the subproject whose block to merge
     * @param derived        the freshly derived edges (build and bundle)
     * @return the merge result; {@code yaml} is returned unchanged when
     *         the subproject's block cannot be located
     */
    static Result merge(String yaml, String subprojectName,
                        List<WsAddMojo.DerivedDep> derived) {
        Located located = locate(yaml, subprojectName);
        if (located == null) {
            return new Result(yaml, Map.of());
        }

        ParsedBlock parsed = parseEntries(located.block());
        List<Entry> current = parsed.entries();
        Set<String> derivedTargets = new LinkedHashSet<>();
        Map<String, String> derivedVersionProperty = new LinkedHashMap<>();
        Map<String, String> derivedRelationship = new LinkedHashMap<>();
        for (WsAddMojo.DerivedDep dep : derived) {
            derivedTargets.add(dep.subproject());
            derivedRelationship.put(dep.subproject(), dep.relationship());
            if (dep.versionProperty() != null) {
                derivedVersionProperty.put(
                        dep.subproject(), dep.versionProperty());
            }
        }

        // Survivors: every non-build entry, plus build entries still
        // derived as build. A preserved entry of any relationship covers
        // its target — no derived duplicate is emitted for it. A build
        // entry whose derivation moved to bundle is superseded: dropped
        // here and re-added below with the derived relationship (#965).
        List<Entry> kept = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();
        for (Entry entry : current) {
            boolean build = isBuild(entry.relationship());
            boolean stillBuild = "build".equalsIgnoreCase(
                    derivedRelationship.get(entry.target()));
            if (!build || stillBuild) {
                // The property is read from the POM like the edge
                // itself: a surviving entry of any relationship takes
                // the one derivation reads, or a release cannot
                // retarget the edge — a staged bundle pin included
                // (IKE-Network/ike-issues#1352). The relationship of a
                // non-build entry stays as declared.
                kept.add(withVersionProperty(entry,
                        derivedVersionProperty.get(entry.target())));
                covered.add(entry.target());
            }
        }

        List<String> addedBuild = new ArrayList<>();
        List<String> addedBundle = new ArrayList<>();
        List<String> toAdd = new ArrayList<>();
        for (String target : derivedTargets) {
            if (!covered.contains(target)) toAdd.add(target);
        }

        StringBuilder rendered = new StringBuilder();
        rendered.append(MARKER).append('\n');
        if (kept.isEmpty() && toAdd.isEmpty()) {
            rendered.append(KEY).append(" []\n");
        } else {
            rendered.append(KEY).append('\n');
            for (Entry entry : kept) {
                rendered.append(entry.text());
            }
            for (String target : toAdd) {
                String relationship = derivedRelationship
                        .getOrDefault(target, "build");
                if ("bundle".equalsIgnoreCase(relationship)) {
                    addedBundle.add(target);
                } else {
                    addedBuild.add(target);
                }
                appendEntry(rendered, target, relationship,
                        derivedVersionProperty.get(target));
            }
        }
        // Comments trailing the last entry (or standing in for a
        // deliberately absent edge) survive the rewrite — that text is
        // exactly what the old replace-wholesale behavior destroyed.
        rendered.append(parsed.trailingComments());

        Map<String, String> finalRelationships = new LinkedHashMap<>();
        for (Entry entry : kept) {
            finalRelationships.put(entry.target(),
                    entry.relationship() == null
                            ? "build" : entry.relationship());
        }
        for (String target : addedBuild) {
            finalRelationships.put(target, "build");
        }
        for (String target : addedBundle) {
            finalRelationships.put(target, "bundle");
        }

        return new Result(splice(yaml, located, rendered),
                finalRelationships);
    }

    private static boolean isBuild(String relationship) {
        return relationship == null || "build".equalsIgnoreCase(relationship);
    }

    /**
     * Append a freshly written entry to a block being rendered.
     *
     * @param rendered        the block text so far
     * @param target          the member depended on
     * @param relationship    the entry's relationship
     * @param versionProperty the entry's {@code version-property}, or
     *                        null to write none
     */
    private static void appendEntry(StringBuilder rendered, String target,
                                    String relationship,
                                    String versionProperty) {
        rendered.append("      - subproject: ").append(target).append('\n');
        rendered.append("        relationship: ").append(relationship)
                .append('\n');
        if (versionProperty != null) {
            rendered.append("        version-property: ")
                    .append(versionProperty).append('\n');
        }
    }

    /**
     * The {@code version-property} an entry records.
     *
     * @param entry the entry to read
     * @return the property, or null when the entry has none
     */
    private static String versionPropertyOf(Entry entry) {
        for (String line : entry.text().split("\n")) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("version-property:")) {
                return stripped
                        .substring("version-property:".length()).trim();
            }
        }
        return null;
    }

    /**
     * An entry carrying {@code property} as its {@code version-property}:
     * the existing field's value replaced, or a field added after the
     * {@code relationship:} line (after the {@code subproject:} line when
     * there is none). Every other line — comments included — is kept.
     *
     * @param entry    the surviving entry
     * @param property the derived property, or null when none was derived
     * @return {@code entry} itself when {@code property} is null or
     *         already recorded, otherwise the rewritten entry
     */
    private static Entry withVersionProperty(Entry entry, String property) {
        if (property == null) return entry;
        String[] lines = entry.text().split("\n", -1);
        int fieldAt = -1;
        int insertAfter = -1;
        String fieldIndent = null;
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].stripLeading();
            String indent = lines[i].substring(
                    0, lines[i].length() - stripped.length());
            if (stripped.startsWith("version-property:")) {
                fieldAt = i;
                fieldIndent = indent;
            } else if (stripped.startsWith("relationship:")) {
                insertAfter = i;
                fieldIndent = indent;
            } else if (stripped.startsWith("- subproject:")
                    && insertAfter < 0) {
                insertAfter = i;
                if (fieldIndent == null) fieldIndent = indent + "  ";
            }
        }
        String field = fieldIndent + "version-property: " + property;
        List<String> out = new ArrayList<>(List.of(lines));
        if (fieldAt >= 0) {
            String recorded = lines[fieldAt].stripLeading()
                    .substring("version-property:".length()).trim();
            if (recorded.equals(property)) return entry;
            out.set(fieldAt, field);
        } else {
            out.add(insertAfter + 1, field);
        }
        return new Entry(entry.target(), entry.relationship(),
                String.join("\n", out));
    }

    /** Parsed block: entry chunks plus any trailing comment lines. */
    private record ParsedBlock(List<Entry> entries, String trailingComments) {}

    /**
     * Split a {@code depends-on} block into per-entry chunks. Comment
     * lines at entry indent attach to the entry that follows them;
     * comment lines followed by no entry are the block's trailing
     * comments. Deeper-indented lines belong to the current entry. The
     * optional leading managed marker and the {@code depends-on:} line
     * itself are not part of any chunk.
     */
    private static ParsedBlock parseEntries(String block) {
        List<Entry> entries = new ArrayList<>();
        StringBuilder pendingComments = new StringBuilder();
        StringBuilder chunk = null;
        String target = null;
        String relationship = null;

        for (String line : block.split("\n", -1)) {
            if (line.startsWith("    # ── managed: depends-on")
                    || line.stripLeading().startsWith("depends-on:")) {
                continue;
            }
            String stripped = line.stripLeading();
            if (stripped.isEmpty()) continue;

            if (stripped.startsWith("- subproject:")) {
                if (chunk != null) {
                    entries.add(new Entry(target, relationship,
                            chunk.toString()));
                }
                chunk = new StringBuilder(pendingComments);
                pendingComments.setLength(0);
                chunk.append(line).append('\n');
                target = stripped.substring("- subproject:".length()).trim();
                relationship = null;
            } else if (stripped.startsWith("#")) {
                pendingComments.append(line).append('\n');
            } else if (chunk != null) {
                chunk.append(line).append('\n');
                if (stripped.startsWith("relationship:")) {
                    relationship = stripped
                            .substring("relationship:".length()).trim();
                }
            }
            // Anything before the first entry that isn't a comment
            // (e.g. the "[]" of an empty list) contributes nothing.
        }
        if (chunk != null) {
            entries.add(new Entry(target, relationship, chunk.toString()));
        }
        return new ParsedBlock(entries, pendingComments.toString());
    }
}
