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
 *       original text</b> — comments and {@code version-property}
 *       included.</li>
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
     * nothing changed), the derived targets added per relationship, the
     * build targets removed (a target in both {@code removedBuild} and
     * {@code addedBundle} was superseded, not dropped), and each final
     * entry's relationship (for ordering-graph construction by the
     * acyclicity gate).
     */
    record Result(
            String yaml,
            List<String> addedBuild,
            List<String> addedBundle,
            List<String> removedBuild,
            Map<String, String> finalRelationships) {

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
     * Matches a subproject section up to and including its
     * {@code depends-on} block — an optional managed marker line, the
     * {@code depends-on:} line, and every 6-space-indented continuation
     * (entries, their fields, and attached comments).
     */
    private static Pattern blockPattern(String subprojectName) {
        return Pattern.compile(
                "(" + Pattern.quote(subprojectName) + ":[\\s\\S]*?)"
                        + "((?:    # ── managed: depends-on[^\n]*\n)?"
                        + "    depends-on:.*(?:\n      .*)*\n)",
                Pattern.MULTILINE);
    }

    /**
     * Merge {@code derived} into {@code subprojectName}'s block within
     * {@code yaml}.
     *
     * @param yaml           the full workspace.yaml content
     * @param subprojectName the subproject whose block to merge
     * @param derived        the freshly derived edges (build and bundle)
     * @return the merge result; {@code yaml} is returned unchanged when
     *         the subproject's block cannot be located
     */
    static Result merge(String yaml, String subprojectName,
                        List<WsAddMojo.DerivedDep> derived) {
        Matcher m = blockPattern(subprojectName).matcher(yaml);
        if (!m.find()) {
            return new Result(yaml, List.of(), List.of(), List.of(),
                    Map.of());
        }
        String block = m.group(2);

        ParsedBlock parsed = parseEntries(block);
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
        List<String> removedBuild = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();
        for (Entry entry : current) {
            boolean build = isBuild(entry.relationship());
            boolean stillBuild = "build".equalsIgnoreCase(
                    derivedRelationship.get(entry.target()));
            if (!build || stillBuild) {
                kept.add(entry);
                covered.add(entry.target());
            } else {
                removedBuild.add(entry.target());
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
            rendered.append("    depends-on: []\n");
        } else {
            rendered.append("    depends-on:\n");
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
                rendered.append("      - subproject: ").append(target)
                        .append('\n');
                rendered.append("        relationship: ")
                        .append(relationship).append('\n');
                String property = derivedVersionProperty.get(target);
                if (property != null) {
                    rendered.append("        version-property: ")
                            .append(property).append('\n');
                }
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

        String updated = yaml.substring(0, m.start(2))
                + rendered
                + yaml.substring(m.end(2));
        return new Result(updated, addedBuild, addedBundle, removedBuild,
                finalRelationships);
    }

    private static boolean isBuild(String relationship) {
        return relationship == null || "build".equalsIgnoreCase(relationship);
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
