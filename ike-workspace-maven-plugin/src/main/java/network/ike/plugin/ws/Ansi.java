package network.ike.plugin.ws;

/**
 * ANSI escape code constants for colored terminal output.
 *
 * <p>Maven passes {@code -Dstyle.color=always} in IntelliJ by default,
 * so ANSI codes render correctly in both terminal and IDE console.
 * These are used for status markers in ws: goal output to provide
 * quick visual scanning of success/warning/error states.
 *
 * <p>Color assignments:
 * <ul>
 *   <li>Green — success (✓ checkmarks, completion, alignment)</li>
 *   <li>Yellow — warning (⚠ non-critical issues, prompts)</li>
 *   <li>Red — error (✗ failures, blocking problems)</li>
 *   <li>Cyan — action (↻ sync, ↓ download, in-progress)</li>
 * </ul>
 */
public final class Ansi {

    private Ansi() {}

    /** Green ANSI escape prefix. */
    public static final String GREEN  = "[32m";
    /** Yellow ANSI escape prefix. */
    public static final String YELLOW = "[33m";
    /** Red ANSI escape prefix. */
    public static final String RED    = "[31m";
    /** Cyan ANSI escape prefix. */
    public static final String CYAN   = "[36m";
    /** ANSI reset suffix. */
    public static final String RESET  = "[0m";

    /**
     * Wrap text in green (success).
     *
     * @param text the content to colorize
     * @return ANSI-wrapped string
     */
    public static String green(String text)  { return GREEN + text + RESET; }

    /**
     * Wrap text in yellow (warning).
     *
     * @param text the content to colorize
     * @return ANSI-wrapped string
     */
    public static String yellow(String text) { return YELLOW + text + RESET; }

    /**
     * Wrap text in red (error).
     *
     * @param text the content to colorize
     * @return ANSI-wrapped string
     */
    public static String red(String text)    { return RED + text + RESET; }

    /**
     * Wrap text in cyan (action/progress).
     *
     * @param text the content to colorize
     * @return ANSI-wrapped string
     */
    public static String cyan(String text)   { return CYAN + text + RESET; }
}
