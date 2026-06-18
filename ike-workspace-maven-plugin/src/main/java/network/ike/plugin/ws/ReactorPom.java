package network.ike.plugin.ws;

import org.openrewrite.xml.XmlParser;
import org.openrewrite.xml.XmlVisitor;
import org.openrewrite.xml.tree.Content;
import org.openrewrite.xml.tree.Xml;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * AST-aware editor for a workspace <em>reactor</em> POM's subproject
 * membership, using OpenRewrite's XML LST so formatting, comments, and
 * whitespace survive each edit (no regex on POMs).
 *
 * <p>Where {@link network.ike.plugin.PomRewriter} owns release-path POM
 * edits (dependency/parent/plugin/property versions), this class owns
 * the workspace-reactor concern: the top-level {@code <subprojects>}
 * block and the legacy {@code with-<name>} file-activated profiles that
 * preceded it.
 *
 * <h2>Two membership encodings</h2>
 * <ul>
 *   <li><b>Modern</b> — unconditional top-level
 *       {@code <subprojects><subproject>name</subproject></subprojects>}.
 *       {@code ike-workspace-extension}'s {@code SubprojectPruneTransformer}
 *       (IKE-Network/ike-issues#460) prunes entries whose directory is
 *       absent at model-read time, so the declaration is safe even when
 *       a subproject has not been cloned yet.</li>
 *   <li><b>Legacy</b> — one {@code <profile>} per subproject, id
 *       {@code with-<name>}, {@code <file><exists>}-activated, declaring
 *       the member inside the profile's own {@code <subprojects>}. This
 *       is what {@code ws:add} historically generated; this class
 *       migrates it away.</li>
 * </ul>
 *
 * <p>All methods are pure: they take POM text and return POM text,
 * leaving the input unchanged when there is nothing to do.
 *
 * @see network.ike.plugin.ws.reconcile.ReactorSubprojectsReconciler
 * @see network.ike.plugin.PomRewriter
 */
public final class ReactorPom {

    private ReactorPom() {}

    private static final XmlParser PARSER = new XmlParser();

    /**
     * One legacy subproject-membership profile: its {@code <id>} and the
     * subproject name(s) declared inside its {@code <subprojects>} block.
     *
     * @param id          the profile id (e.g. {@code with-komet})
     * @param subprojects the member name(s) the profile activates
     */
    public record ProfileEntry(String id, List<String> subprojects) {}

    // ── Reads ────────────────────────────────────────────────────

    /**
     * List the reactor POM's top-level {@code <subprojects>} entries in
     * declaration order.
     *
     * @param pomContent the raw POM text
     * @return the subproject names; empty when there is no top-level
     *         {@code <subprojects>} block
     */
    public static List<String> listSubprojects(String pomContent) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) {
            return List.of();
        }
        Optional<Xml.Tag> subprojects = doc.getRoot().getChild("subprojects");
        if (subprojects.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Xml.Tag sub : subprojects.get().getChildren("subproject")) {
            sub.getValue().map(String::trim)
                    .filter(v -> !v.isEmpty())
                    .ifPresent(names::add);
        }
        return names;
    }

    /**
     * List the reactor POM's legacy subproject-membership profiles — the
     * {@code <profile>}s that declare one or more members inside their own
     * {@code <subprojects>} block (the {@code with-<name>} pattern that
     * {@code ws:add} generated before the #460 migration).
     *
     * <p>A profile qualifies when it carries a non-empty
     * {@code <subprojects>}; the {@code with-} id and {@code <file><exists>}
     * activation are the convention but are not required for the match, so
     * a hand-edited profile is recognized too.
     *
     * @param pomContent the raw POM text
     * @return the qualifying profiles in declaration order; empty when none
     */
    public static List<ProfileEntry> listSubprojectProfiles(String pomContent) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) {
            return List.of();
        }
        Optional<Xml.Tag> profiles = doc.getRoot().getChild("profiles");
        if (profiles.isEmpty()) {
            return List.of();
        }
        List<ProfileEntry> result = new ArrayList<>();
        for (Xml.Tag profile : profiles.get().getChildren("profile")) {
            Optional<Xml.Tag> sp = profile.getChild("subprojects");
            if (sp.isEmpty()) {
                continue;
            }
            List<String> members = new ArrayList<>();
            for (Xml.Tag sub : sp.get().getChildren("subproject")) {
                sub.getValue().map(String::trim)
                        .filter(v -> !v.isEmpty())
                        .ifPresent(members::add);
            }
            if (members.isEmpty()) {
                continue;
            }
            String id = profile.getChildValue("id").map(String::trim).orElse(null);
            result.add(new ProfileEntry(id, members));
        }
        return result;
    }

    // ── Writes ───────────────────────────────────────────────────

    /**
     * Set the reactor POM's top-level {@code <subprojects>} block to
     * exactly {@code names}, in the given order. Replaces an existing
     * block in place (preserving its surrounding whitespace) or inserts a
     * new one — before {@code <profiles>} when present, otherwise at the
     * end of {@code <project>}.
     *
     * <p>This single operation covers add ({@code ws:add}), remove
     * ({@code ws:remove}), and full reconciliation against
     * {@code workspace.yaml}: callers compute the target list and let this
     * method materialize it idempotently.
     *
     * @param pomContent the raw POM text
     * @param names      the exact subproject membership to declare
     * @return updated POM text; unchanged when the block already matches
     */
    public static String setSubprojects(String pomContent, List<String> names) {
        if (listSubprojects(pomContent).equals(names)) {
            return pomContent;
        }
        Xml.Document doc = parse(pomContent);
        if (doc == null) {
            return pomContent;
        }
        String blockXml = renderSubprojectsBlock(names);

        Xml.Document updated = (Xml.Document) new XmlVisitor<Integer>() {
            @Override
            public Xml visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (!"project".equals(t.getName())) {
                    return t;
                }
                Optional<Xml.Tag> existing = t.getChild("subprojects");
                if (existing.isPresent()) {
                    Xml.Tag block = Xml.Tag.build(blockXml)
                            .withPrefix(existing.get().getPrefix());
                    return replaceChild(t, existing.get(), block);
                }
                Xml.Tag block = Xml.Tag.build(blockXml).withPrefix("\n\n    ");
                List<Content> content = new ArrayList<>(t.getContent());
                content.add(insertionIndex(content), block);
                return t.withContent(content);
            }
        }.visitNonNull(doc, 0);

        return print(updated);
    }

    /**
     * Remove a single {@code <profile>} (matched by {@code <id>}) from the
     * reactor POM, and drop the {@code <profiles>} block entirely if it
     * becomes empty.
     *
     * @param pomContent the raw POM text
     * @param profileId  the profile id to remove (e.g. {@code with-komet})
     * @return updated POM text; unchanged when no such profile exists
     */
    public static String removeProfile(String pomContent, String profileId) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) {
            return pomContent;
        }

        Xml.Document updated = (Xml.Document) new XmlVisitor<Integer>() {
            @Override
            public Xml visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (!"project".equals(t.getName())) {
                    return t;
                }
                Optional<Xml.Tag> profilesOpt = t.getChild("profiles");
                if (profilesOpt.isEmpty()) {
                    return t;
                }
                Xml.Tag profiles = profilesOpt.get();
                boolean present = profiles.getChildren("profile").stream()
                        .anyMatch(p -> profileId.equals(
                                p.getChildValue("id").map(String::trim).orElse(null)));
                if (!present) {
                    return t;
                }
                List<Content> keptProfileContent = profiles.getContent().stream()
                        .filter(c -> !(c instanceof Xml.Tag p
                                && "profile".equals(p.getName())
                                && profileId.equals(p.getChildValue("id")
                                        .map(String::trim).orElse(null))))
                        .map(c -> (Content) c)
                        .toList();
                boolean anyProfilesLeft = keptProfileContent.stream()
                        .anyMatch(c -> c instanceof Xml.Tag p
                                && "profile".equals(p.getName()));
                if (!anyProfilesLeft) {
                    // Drop the whole <profiles> element (and the whitespace
                    // node immediately preceding it) so no empty husk lingers.
                    List<Content> projectContent =
                            new ArrayList<>(t.getContent());
                    int idx = projectContent.indexOf(profiles);
                    projectContent.remove(idx);
                    if (idx > 0
                            && projectContent.get(idx - 1) instanceof Xml.CharData) {
                        projectContent.remove(idx - 1);
                    }
                    return t.withContent(projectContent);
                }
                return replaceChild(t, profiles,
                        profiles.withContent(keptProfileContent));
            }
        }.visitNonNull(doc, 0);

        return print(updated);
    }

    // ── Rendering / tree helpers ─────────────────────────────────

    /**
     * Render the {@code <subprojects>} block body for {@code names},
     * indented two levels (4-space block, 8-space entries) to match the
     * workspace POM template emitted by {@code WorkspaceBootstrap}.
     */
    private static String renderSubprojectsBlock(List<String> names) {
        if (names.isEmpty()) {
            return "<subprojects>\n    </subprojects>";
        }
        StringBuilder sb = new StringBuilder("<subprojects>\n");
        for (String name : names) {
            sb.append("        <subproject>").append(name)
                    .append("</subproject>\n");
        }
        sb.append("    </subprojects>");
        return sb.toString();
    }

    /**
     * Choose where to insert a freshly created {@code <subprojects>} block
     * in {@code <project>} content: just before the {@code <profiles>}
     * element (and its leading whitespace) when present, otherwise before
     * the trailing whitespace that precedes {@code </project>}.
     */
    private static int insertionIndex(List<Content> content) {
        for (int i = 0; i < content.size(); i++) {
            if (content.get(i) instanceof Xml.Tag t
                    && "profiles".equals(t.getName())) {
                return (i > 0 && content.get(i - 1) instanceof Xml.CharData)
                        ? i - 1 : i;
            }
        }
        int last = content.size();
        if (last > 0 && content.get(last - 1) instanceof Xml.CharData) {
            return last - 1;
        }
        return last;
    }

    private static Xml.Tag replaceChild(Xml.Tag parent, Xml.Tag oldChild,
                                        Xml.Tag newChild) {
        List<Content> newContent = new ArrayList<>(parent.getContent().size());
        for (Content c : parent.getContent()) {
            newContent.add(c == oldChild ? newChild : c);
        }
        return parent.withContent(newContent);
    }

    private static Xml.Document parse(String pomContent) {
        return PARSER.parse(pomContent)
                .findFirst()
                .map(t -> (Xml.Document) t)
                .orElse(null);
    }

    private static String print(Xml.Document doc) {
        return doc.printAll();
    }
}
