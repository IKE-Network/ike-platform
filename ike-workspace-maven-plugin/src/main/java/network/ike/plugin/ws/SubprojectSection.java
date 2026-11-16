package network.ike.plugin.ws;

import java.util.Optional;

/**
 * The span of one member's entry under {@code subprojects:} in the text
 * of a workspace manifest (IKE-Network/ike-issues#1387).
 *
 * <p>Edits to a member's fields are made in the manifest's text, so that
 * comments and layout survive. Such an edit has to stay inside the
 * member it is for. Searching from the member's name to the next
 * occurrence of a field anywhere later in the file does not: a member
 * that lacks the field matches the same field of a later member, and a
 * name matched anywhere matches inside a longer name
 * ({@code gudid-kb:} inside {@code solor-gudid-kb:}). This record is the
 * one place that says where a member's entry starts and stops.
 *
 * <p>The layout is the one the {@code ws:} goals write: {@code subprojects:}
 * at column 0, each member's key on a line of its own at two spaces of
 * indentation, and the member's fields indented deeper. A section starts
 * at the member's key line and ends at the next member's key line (the
 * next line at member indentation that is not a comment), at the first
 * line at column 0 that is not a comment (the next top-level key, which
 * ends {@code subprojects:}), or at the end of the text.
 *
 * @param keyStart   offset of the first character of the member's key line
 * @param bodyStart  offset of the line after the key line, where the
 *                   member's fields begin
 * @param contentEnd offset just past the member's last own line: the
 *                   last line indented deeper than its key. Blank lines
 *                   and member-level comments that follow, which
 *                   separate or introduce the next member, lie beyond
 *                   it. A field appended to the member is inserted
 *                   here. Equal to {@code bodyStart} for a member with
 *                   no fields
 * @param end        offset at which the section ends
 */
record SubprojectSection(int keyStart, int bodyStart, int contentEnd,
                         int end) {

    /** The top-level key under which members are declared. */
    private static final String SUBPROJECTS_KEY = "subprojects:";

    /** Indentation of a member's key line. */
    private static final int MEMBER_INDENT = 2;

    /**
     * Locate a member's section.
     *
     * <p>The member's key line is two spaces, the name, a colon, and
     * nothing after it but white space or a comment. A name is therefore
     * never found inside a longer name, in a field value, or in a
     * comment.
     *
     * @param yaml           the full text of the workspace manifest
     * @param subprojectName the member to locate
     * @return the member's section, or empty when the manifest has no
     *         {@code subprojects:} key or declares no such member
     */
    static Optional<SubprojectSection> find(String yaml,
                                            String subprojectName) {
        String key = " ".repeat(MEMBER_INDENT) + subprojectName + ":";
        boolean inSubprojects = false;
        int keyStart = -1;
        int bodyStart = -1;
        int contentEnd = -1;

        int pos = 0;
        while (pos < yaml.length()) {
            int newline = yaml.indexOf('\n', pos);
            int lineEnd = newline < 0 ? yaml.length() : newline;
            int next = newline < 0 ? yaml.length() : newline + 1;
            String line = yaml.substring(pos, lineEnd);

            if (!inSubprojects) {
                inSubprojects = line.startsWith(SUBPROJECTS_KEY);
            } else if (endsSubprojects(line)) {
                // The next top-level key ends subprojects: — and with
                // it the member's section, if it was found.
                return keyStart < 0
                        ? Optional.empty()
                        : Optional.of(new SubprojectSection(
                                keyStart, bodyStart, contentEnd, pos));
            } else if (keyStart < 0) {
                if (isKeyLine(line, key)) {
                    keyStart = pos;
                    bodyStart = next;
                    contentEnd = next;
                }
            } else if (isMemberKeyLine(line)) {
                return Optional.of(new SubprojectSection(
                        keyStart, bodyStart, contentEnd, pos));
            } else if (!line.isBlank() && indentOf(line) > MEMBER_INDENT) {
                contentEnd = next;
            }
            pos = next;
        }
        return keyStart < 0
                ? Optional.empty()
                : Optional.of(new SubprojectSection(
                        keyStart, bodyStart, contentEnd, yaml.length()));
    }

    /**
     * Whether {@code line} is the key line {@code key} names: the key
     * followed by nothing but white space or a comment.
     */
    private static boolean isKeyLine(String line, String key) {
        if (!line.startsWith(key)) return false;
        String rest = line.substring(key.length()).strip();
        return rest.isEmpty() || rest.startsWith("#");
    }

    /**
     * Whether {@code line} ends {@code subprojects:}: content at column
     * 0 that is not a comment. A comment at column 0 does not close a
     * YAML mapping, so members may follow it.
     */
    private static boolean endsSubprojects(String line) {
        return !line.isBlank()
                && indentOf(line) == 0
                && line.charAt(0) != '#';
    }

    /**
     * Whether {@code line} opens a member: content at exactly member
     * indentation that is not a comment.
     */
    private static boolean isMemberKeyLine(String line) {
        return !line.isBlank()
                && indentOf(line) == MEMBER_INDENT
                && line.charAt(MEMBER_INDENT) != '#';
    }

    /** The number of leading spaces of {@code line}. */
    private static int indentOf(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        return indent;
    }
}
