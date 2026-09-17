package network.ike.plugin.ws;

import network.ike.workspace.WorkingSetResolver;

import java.io.File;
import java.nio.file.Path;

/**
 * The working set's manifest, under whichever name it carries.
 *
 * <p>The file is {@code working-set.yaml}; a working set the scaffold has
 * not upgraded yet still carries {@code workspace.yaml}, and that name is
 * read forever — manifests written before the rename live in tagged trees
 * (IKE-Network/ike-issues#1054). Goals ask here rather than spelling a
 * name, so a working set is never half-read under one name and staged
 * under the other.
 */
final class Manifests {

    private Manifests() {}

    /**
     * The manifest path for a working-set root: the file already there,
     * or the current name when the root has none yet.
     *
     * @param root the working-set root directory
     * @return the manifest path (never {@code null}; may not exist)
     */
    static Path path(Path root) {
        return WorkingSetResolver.manifestToWrite(root);
    }

    /**
     * The manifest path for a working-set root.
     *
     * @param root the working-set root directory
     * @return the manifest path (never {@code null}; may not exist)
     */
    static Path path(File root) {
        return path(root.toPath());
    }

    /**
     * The manifest's file name at a working-set root — what to hand git,
     * which must stage the file that is actually there.
     *
     * @param root the working-set root directory
     * @return {@code working-set.yaml} or {@code workspace.yaml}
     */
    static String name(Path root) {
        return path(root).getFileName().toString();
    }

    /**
     * The manifest's file name at a working-set root.
     *
     * @param root the working-set root directory
     * @return {@code working-set.yaml} or {@code workspace.yaml}
     */
    static String name(File root) {
        return name(root.toPath());
    }

    /**
     * Whether a directory holds a manifest under either name.
     *
     * @param dir the directory to test
     * @return {@code true} when the directory is a working-set root
     */
    static boolean exists(Path dir) {
        return WorkingSetResolver.manifestIn(dir) != null;
    }

    /**
     * The nearest manifest at or above a directory.
     *
     * @param startDir the directory to search upward from
     * @return the manifest path, or {@code null} when none is found
     */
    static Path findUpward(Path startDir) {
        Path dir = startDir.toAbsolutePath().normalize();
        while (dir != null) {
            Path candidate = WorkingSetResolver.manifestIn(dir);
            if (candidate != null) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
