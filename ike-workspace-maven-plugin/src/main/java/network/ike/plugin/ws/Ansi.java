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
final class Ansi {

    private Ansi() {}

    static final String GREEN  = "\u001B[32m";
    static final String YELLOW = "\u001B[33m";
    static final String RED    = "\u001B[31m";
    static final String CYAN   = "\u001B[36m";
    static final String RESET  = "\u001B[0m";

    /** Wrap text in green (success). */
    static String green(String text)  { return GREEN + text + RESET; }

    /** Wrap text in yellow (warning). */
    static String yellow(String text) { return YELLOW + text + RESET; }

    /** Wrap text in red (error). */
    static String red(String text)    { return RED + text + RESET; }

    /** Wrap text in cyan (action/progress). */
    static String cyan(String text)   { return CYAN + text + RESET; }
}
