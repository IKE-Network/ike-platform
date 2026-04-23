package network.ike.plugin.ws;

/**
 * Immutable snapshot of a single workspace subproject at checkpoint time.
 *
 * <p>Decouples checkpoint YAML generation from git subprocess calls
 * so the formatting logic is testable with plain records.
 *
 * @param name    subproject directory name
 * @param sha     full commit SHA (or "unknown" if unavailable)
 * @param shortSha abbreviated commit SHA
 * @param branch  current branch name
 * @param version POM version (may be null)
 * @param modified   true if working tree has uncommitted changes
 * @param type    subproject type from workspace manifest
 * @param compositeCheckpoint true if checkpoint mechanism is "composite"
 */
public record SubprojectSnapshot(String name, String sha, String shortSha,
                                  String branch, String version, boolean modified,
                                  String type, boolean compositeCheckpoint) {}
