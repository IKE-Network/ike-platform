package network.ike.plugin.ws.preflight;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.SnapshotScanner;
import network.ike.plugin.ws.WsGoal;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import network.ike.workspace.Dependency;
import network.ike.workspace.Subproject;
import network.ike.workspace.WorkspaceGraph;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Closed vocabulary of preflight checks that {@code ws:*} goals can
 * require before they mutate workspace state. Each entry declares a
 * human-readable description and a {@link #check(PreflightContext)}
 * implementation that returns {@link Optional#empty()} on success or a
 * remediation message on failure.
 *
 * <p>Drafts and publishes invoke the same {@code PreflightCondition}
 * sequence via {@link Preflight}; whether failure is a warning (draft)
 * or a hard error (publish) is decided at the call site via
 * {@link PreflightResult#requirePassed(WsGoal)} vs
 * {@link PreflightResult#warnIfFailed(Log, WsGoal)}.
 *
 * <p>New conditions are added here as goals adopt the contract from
 * issue #154. Each new entry must stay self-contained: it does not
 * depend on the mojo instance, only on the shared {@link PreflightContext}.
 */
public enum PreflightCondition {

    /**
     * Every subproject working tree (and the workspace root itself, if
     * it is a git repo) must have no uncommitted changes. Any draft or
     * publish goal that creates branches, edits POMs, or otherwise
     * mutates files requires this.
     */
    WORKING_TREE_CLEAN("All subproject working trees are clean") {
        @Override
        public Optional<String> check(PreflightContext ctx) {
            File root = ctx.workspaceRoot();
            List<String> uncommitted = new ArrayList<>();

            if (new File(root, ".git").exists()
                    && !gitStatus(root).isEmpty()) {
                uncommitted.add(WORKSPACE_ROOT_NAME);
            }
            for (String name : ctx.subprojects()) {
                File dir = new File(root, name);
                if (!new File(dir, ".git").exists()) continue;
                if (!gitStatus(dir).isEmpty()) {
                    uncommitted.add(name);
                }
            }

            if (uncommitted.isEmpty()) return Optional.empty();

            StringBuilder sb = new StringBuilder();
            sb.append(uncommitted.size())
                    .append(" subproject(s) have uncommitted changes:\n");
            boolean anyGhPagesLeak = false;
            for (String name : uncommitted) {
                File dir = WORKSPACE_ROOT_NAME.equals(name)
                        ? root : new File(root, name);
                String status = gitStatus(dir);
                if (containsGhPagesLeakPattern(status)) {
                    anyGhPagesLeak = true;
                }
                String files = status.lines()
                        .map(l -> "      " + l.strip())
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("");
                sb.append("    • ").append(name).append(":\n")
                  .append(files).append("\n");
            }
            sb.append("  To resolve:\n");
            sb.append("    mvn " + WsGoal.COMMIT_PUBLISH.qualified()
                    + " -Dmessage=\"<your message>\"\n");
            sb.append("  Or stash changes in each affected subproject.");
            if (anyGhPagesLeak) {
                sb.append("\n");
                sb.append("  ⚠ Some entries above match gh-pages-style site\n");
                sb.append("    output (paths like <artifactId>/<artifactId>/\n");
                sb.append("    or examples/<name>/, containing bom.json,\n");
                sb.append("    built-with.html, dependencies.html, etc.).\n");
                sb.append("    These do NOT belong in main — the canonical\n");
                sb.append("    published content lives on each repo's\n");
                sb.append("    gh-pages branch. Delete them and add\n");
                sb.append("    .gitignore entries to prevent recurrence.\n");
                sb.append("    NEVER use `git add -A` or `git add .` to\n");
                sb.append("    stage cascade bump commits — these sweep\n");
                sb.append("    such leaks into main. Use explicit paths:\n");
                sb.append("    `git add pom.xml`. ike-issues#358.");
            }
            return Optional.of(sb.toString());
        }
    },

    /**
     * No {@code <properties>} entry in any subproject root POM may hold a
     * value ending in {@code -SNAPSHOT}. Maven 4's consumer POM flattener
     * resolves properties and promotes {@code <pluginManagement>} into
     * {@code <plugins>} when writing the released artifact — a SNAPSHOT
     * property value would then be baked in as a literal and break
     * downstream consumers (e.g. {@code <ike-tooling.version>112-SNAPSHOT}
     * leaking into released {@code ike-parent-105.pom}). This check
     * forces the release operator to bump the property to a released
     * version before cutting the release.
     */
    NO_SNAPSHOT_PROPERTIES("No subproject root POM carries -SNAPSHOT property values") {
        @Override
        public Optional<String> check(PreflightContext ctx) {
            File root = ctx.workspaceRoot();
            List<SnapshotScanner.Violation> all = new ArrayList<>();

            for (String name : ctx.subprojects()) {
                File pom = new File(new File(root, name), "pom.xml");
                if (!pom.isFile()) continue;
                for (SnapshotScanner.Violation violation
                        : SnapshotScanner.scanSourceProperties(pom)) {
                    // Release-set-aware exemption (ike-issues#981): a
                    // property tracking a member that releases in this
                    // cycle is substituted with the member's released
                    // version by the loop's catch-up alignment before
                    // its consumer releases — it never leaks.
                    if (!resolvedByReleaseCycle(ctx, name, violation)) {
                        all.add(violation);
                    }
                }
            }

            if (all.isEmpty()) return Optional.empty();

            return Optional.of(SnapshotScanner.formatViolations(all, root,
                    all.size() + " SNAPSHOT property value(s) would leak into"
                            + " released POMs:",
                    "  These values are substituted by Maven 4's consumer POM\n"
                    + "  flattener and baked into released artifacts. Bump each\n"
                    + "  property to a released (non-SNAPSHOT) version before\n"
                    + "  re-running the release."));
        }
    },

    /**
     * No {@code .mvn/jvm.config} file in the workspace root or any
     * subproject may contain a line starting with {@code #}.
     *
     * <p>Maven parses {@code .mvn/jvm.config} as raw JVM arguments —
     * one token per line, with no comment syntax. A {@code #} at
     * column 0 is passed to the JVM launcher as a main-class name and
     * IntelliJ surfaces it as
     * {@code Error: Could not find or load main class #}. The fix is
     * to delete the offending line; comments belong in
     * {@code .mvn/jvm.config.notes} or similar adjacent files.
     *
     * <p>This is the gate referenced in ike-issues#217. The check fires
     * before the bad file can propagate to git or Syncthing — Maven's
     * own {@code validate} phase can't catch this in the project that
     * contains the bad file because the JVM dies before plugin code
     * runs.
     */
    JVM_CONFIG_NO_HASH_COMMENTS(
            "No .mvn/jvm.config file contains a # comment line") {
        @Override
        public Optional<String> check(PreflightContext ctx) {
            File root = ctx.workspaceRoot();
            List<String> violations = new ArrayList<>();

            collectJvmConfigViolations(root, WORKSPACE_ROOT_NAME, violations);
            for (String name : ctx.subprojects()) {
                collectJvmConfigViolations(new File(root, name), name,
                        violations);
            }

            if (violations.isEmpty()) return Optional.empty();

            StringBuilder sb = new StringBuilder();
            sb.append(violations.size())
                    .append(" .mvn/jvm.config file(s) contain # comment lines:\n");
            for (String v : violations) {
                sb.append("    • ").append(v).append('\n');
            }
            sb.append("  Maven parses .mvn/jvm.config as raw JVM arguments\n");
            sb.append("  — '#' at column 0 is passed to the JVM launcher as a\n");
            sb.append("  main-class name and crashes the build. Delete the\n");
            sb.append("  offending line; put commentary in an adjacent file.");
            return Optional.of(sb.toString());
        }
    },

    /**
     * Every workspace subproject's root POM must either declare
     * {@code <distributionManagement>} locally or have a {@code <parent>}
     * block to inherit from (#346, surfaced by #343 when {@code its/}
     * was missing both).
     *
     * <p>Site goals — specifically {@code site:stage}, which the
     * workspace release cascade runs — fail with
     * <em>"Missing distribution management in project ..."</em> when
     * neither is present. That failure surfaces deep inside the
     * release flow, after some subprojects have already tagged. This
     * preflight catches the missing declaration upfront so the
     * release-draft is authoritative.
     */
    SUBPROJECT_HAS_DISTRIBUTION_MANAGEMENT(
            "Every subproject root POM resolves <distributionManagement>") {
        @Override
        public Optional<String> check(PreflightContext ctx) {
            File root = ctx.workspaceRoot();
            List<String> violations = new ArrayList<>();

            for (String name : ctx.subprojects()) {
                File pom = new File(new File(root, name), "pom.xml");
                if (!pom.isFile()) continue;
                String content;
                try {
                    content = Files.readString(pom.toPath(),
                            StandardCharsets.UTF_8);
                } catch (IOException e) {
                    // Best-effort — preflight does not fail on read errors
                    continue;
                }
                if (!hasDistributionManagementOrParent(content)) {
                    violations.add(name + " (no <distributionManagement> "
                            + "or <parent> in its root POM)");
                }
            }

            if (violations.isEmpty()) return Optional.empty();

            StringBuilder sb = new StringBuilder();
            sb.append(violations.size())
                    .append(" subproject(s) missing <distributionManagement>:\n");
            for (String v : violations) {
                sb.append("    • ").append(v).append('\n');
            }
            sb.append("  site:stage during the workspace release cascade\n");
            sb.append("  will fail with \"Missing distribution management in\n");
            sb.append("  project ...\". Either declare\n");
            sb.append("  <distributionManagement><site>...</site>... directly\n");
            sb.append("  on the subproject's root POM, or add a <parent>\n");
            sb.append("  block inheriting from one of the IKE parents (e.g.\n");
            sb.append("  network.ike.platform:ike-parent).");
            return Optional.of(sb.toString());
        }
    },

    /**
     * No subproject's {@code <properties>} block may declare a
     * locally-overriding value for any IKE-foundation property name
     * (#346, surfaced by the {@code its/} pom property-shadowing in
     * the v150 cascade).
     *
     * <p>Foundation properties (
     * {@code ike-tooling.version},
     * {@code ike-docs.version},
     * {@code ike-platform.version}) are set by {@code ike-parent}'s
     * inheritance chain. Local overrides silently shadow the
     * workspace's intended versions and pin plugins to old releases
     * that may lack newer goals. Preferred discipline: namespace
     * local overrides under {@code it.*}, {@code local.*}, or a
     * project-specific prefix that doesn't collide with the
     * foundation set.
     */
    NO_FOUNDATION_PROPERTY_SHADOWING(
            "No subproject overrides ike-foundation property names") {
        @Override
        public Optional<String> check(PreflightContext ctx) {
            File root = ctx.workspaceRoot();
            List<String> violations = new ArrayList<>();

            for (String name : ctx.subprojects()) {
                File pom = new File(new File(root, name), "pom.xml");
                if (!pom.isFile()) continue;
                String content;
                try {
                    content = Files.readString(pom.toPath(),
                            StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                for (String prop : FOUNDATION_PROPERTY_NAMES) {
                    if (shadowsProperty(content, prop)) {
                        violations.add(name + "/pom.xml shadows <" + prop
                                + "> (inherited from ike-parent)");
                    }
                }
            }

            if (violations.isEmpty()) return Optional.empty();

            StringBuilder sb = new StringBuilder();
            sb.append(violations.size())
                    .append(" IKE-foundation property shadow(s):\n");
            for (String v : violations) {
                sb.append("    • ").append(v).append('\n');
            }
            sb.append("  These property names control plugin resolution for\n");
            sb.append("  every project inheriting ike-parent. Local overrides\n");
            sb.append("  pin plugins to whatever version the subproject\n");
            sb.append("  declares, often pre-dating goals the release cycle\n");
            sb.append("  expects (e.g. ike-tooling 126 lacks #335's\n");
            sb.append("  render-spdx-licenses). Rename the local property to\n");
            sb.append("  an `it.*` / `local.*` namespace and update any\n");
            sb.append("  references to use the new name.");
            return Optional.of(sb.toString());
        }
    },

    /**
     * No on-disk gh-pages-style site output leaks at
     * {@code <projectDir>/<artifactId>/<artifactId>/index.html} —
     * whether or not git tracks them.
     *
     * <p>Why this exists despite {@link #WORKING_TREE_CLEAN} already
     * detecting committed leaks: once an operator adds
     * {@code .gitignore} entries (the standing workaround in
     * ike-issues#358), the leak files are no longer reported by
     * {@code git status}, so {@link #WORKING_TREE_CLEAN} thinks the
     * tree is clean — but the files keep getting regenerated under
     * the working tree on every release flow. This check scans the
     * filesystem directly so the operator sees the leak even when
     * git has been told to ignore it.
     *
     * <p>Pattern: a directory named exactly the same as a known
     * cascade artifactId living inside another directory named the
     * same, containing an {@code index.html} — signature of an
     * {@code mvn site} render that escaped {@code target/}. We check
     * both the workspace root (where workspace-root and subproject
     * artifactIds can both shadow) and each subproject root.
     * ike-issues#358.
     */
    NO_ON_DISK_GHPAGES_LEAK(
            "No on-disk gh-pages-style site output leaks under any "
                    + "project's working tree") {
        @Override
        public Optional<String> check(PreflightContext ctx) {
            File root = ctx.workspaceRoot();
            List<String> artifactIds = collectCascadeArtifactIds(ctx);
            if (artifactIds.isEmpty()) return Optional.empty();

            List<Path> leaks = new ArrayList<>();

            // Workspace root: any artifactId in the cascade can shadow.
            detectGhPagesLeakDirs(root, artifactIds, leaks);

            // Each subproject root: typically just its own artifactId,
            // but cheap to check the full cascade set in case of
            // cross-shadowing during a multi-module workspace run.
            for (String name : ctx.subprojects()) {
                File sub = new File(root, name);
                if (!sub.isDirectory()) continue;
                detectGhPagesLeakDirs(sub, artifactIds, leaks);
            }

            if (leaks.isEmpty()) return Optional.empty();

            StringBuilder sb = new StringBuilder();
            sb.append(leaks.size())
                    .append(" on-disk gh-pages leak director")
                    .append(leaks.size() == 1 ? "y" : "ies")
                    .append(" found:\n");
            for (Path leak : leaks) {
                Path rel;
                try {
                    rel = root.toPath().relativize(leak);
                } catch (IllegalArgumentException ignore) {
                    rel = leak;
                }
                sb.append("    • ").append(rel).append('\n');
            }
            sb.append("  These directories hold rendered Maven Site\n");
            sb.append("  output (index.html + css/ + images/) that\n");
            sb.append("  escaped target/ during a release-flow\n");
            sb.append("  `mvn site:stage` invocation. .gitignore may\n");
            sb.append("  already block them from being committed, but\n");
            sb.append("  the files keep coming back. The underlying\n");
            sb.append("  cause was fixed in ike-platform's ike-parent\n");
            sb.append("  (see ike-issues#358 closing comment); when the\n");
            sb.append("  next ike-platform release ships and this\n");
            sb.append("  workspace bumps to it, the leak stops at the\n");
            sb.append("  source. Until then, safe to delete the\n");
            sb.append("  directories above — canonical published\n");
            sb.append("  content lives on each repo's gh-pages branch.\n");
            sb.append("  Copy-paste cleanup:\n");
            for (Path leak : leaks) {
                Path rel;
                try {
                    rel = root.toPath().relativize(leak);
                } catch (IllegalArgumentException ignore) {
                    rel = leak;
                }
                // Strip the trailing /<artifactId>/<artifactId> down to
                // /<artifactId> so the rm wipes the whole top-level
                // leak dir (which has nothing legitimate in it). For
                // workspace-root leaks the path is <artifactId>/; for
                // subproject leaks it's <subprojectDir>/<artifactId>/.
                Path parent = rel.getParent();
                if (parent != null) {
                    sb.append("    rm -rf ").append(parent).append('\n');
                } else {
                    sb.append("    rm -rf ").append(rel).append('\n');
                }
            }
            sb.append("  ike-issues#358.");
            return Optional.of(sb.toString());
        }
    },

    /**
     * No subproject root POM (nor the workspace root) declares a
     * {@code <distributionManagement><site><url>} starting with
     * {@code scpexe://} — that wagon was retired in
     * ike-issues#304 in favor of the GitHub Pages publish path
     * ({@code https://ike.network/<repo>/} via the org CNAME).
     *
     * <p>A surviving {@code scpexe://} is a silent release-blocker:
     * the goal that consumes the URL only fails at the step that
     * tries to use the wagon, after subproject releases have
     * already started. This check catches it at draft time so the
     * operator fixes the URL before any tags ship.
     *
     * <p>ike-issues#372.
     */
    NO_SCPEXE_SITE_URLS(
            "No POM declares a scpexe:// <site><url> "
                    + "(retired in #304)") {
        @Override
        public Optional<String> check(PreflightContext ctx) {
            File root = ctx.workspaceRoot();
            List<String> violations = new ArrayList<>();

            scanPomForScpexeSiteUrl(new File(root, "pom.xml"),
                    "workspace root", violations);
            for (String name : ctx.subprojects()) {
                scanPomForScpexeSiteUrl(
                        new File(new File(root, name), "pom.xml"),
                        name, violations);
            }

            if (violations.isEmpty()) return Optional.empty();

            StringBuilder sb = new StringBuilder();
            sb.append(violations.size())
                    .append(" POM(s) declare a scpexe:// <site><url>:\n");
            for (String v : violations) {
                sb.append("    • ").append(v).append('\n');
            }
            sb.append("  scpexe:// was retired in ike-issues#304.\n");
            sb.append("  Canonical site distribution is now GitHub Pages\n");
            sb.append("  served at https://ike.network/<repo>/ via the\n");
            sb.append("  org CNAME. Update each pom's\n");
            sb.append("  <distributionManagement><site><url> to the\n");
            sb.append("  https:// form (inheriting from ike-parent's\n");
            sb.append("  https://ike.network/${project.artifactId}/\n");
            sb.append("  default usually suffices — delete the override).\n");
            sb.append("  ike-issues#372.");
            return Optional.of(sb.toString());
        }
    },

    /**
     * When a subproject's {@code <parent>} declares the same GA as the
     * workspace aggregator's own {@code <parent>}, enforce the two
     * coherence rules from #324:
     *
     * <ol>
     *   <li>{@code <parent><version>} matches the workspace's.</li>
     *   <li>{@code <parent>} block includes an empty
     *       {@code <relativePath/>} to prevent the Maven 4
     *       parent-cycle error.</li>
     * </ol>
     *
     * <p>This preflight is the release-gate analog of
     * {@code ws:scaffold-draft}'s coherence check (#324; folded
     * from the retired {@code ws:verify} per #393). The scaffold-draft
     * check warns; the preflight blocks. Composed via Preflight so
     * release-draft surfaces it as a warning and release-publish
     * promotes it to a hard error.
     */
    PARENT_COHERENCE(
            "Subprojects sharing the workspace's parent GA have "
                    + "matching version + <relativePath/>") {
        @Override
        public Optional<String> check(PreflightContext ctx) {
            File root = ctx.workspaceRoot();
            File workspacePom = new File(root, "pom.xml");
            if (!workspacePom.isFile()) return Optional.empty();

            String workspaceContent;
            try {
                workspaceContent = Files.readString(workspacePom.toPath(),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                return Optional.empty();
            }

            String wsParentGA = extractParentGa(workspaceContent);
            String wsParentVersion = extractParentVersion(workspaceContent);
            if (wsParentGA == null) return Optional.empty();

            List<String> violations = new ArrayList<>();

            for (String name : ctx.subprojects()) {
                File pom = new File(new File(root, name), "pom.xml");
                if (!pom.isFile()) continue;
                String content;
                try {
                    content = Files.readString(pom.toPath(),
                            StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                String subParentGA = extractParentGa(content);
                String subParentVersion = extractParentVersion(content);
                if (subParentGA == null
                        || !subParentGA.equals(wsParentGA)) {
                    continue; // out of scope — different parent
                }

                if (wsParentVersion != null
                        && !wsParentVersion.equals(subParentVersion)) {
                    violations.add(name + " parent " + subParentGA + ":"
                            + subParentVersion + " != workspace " + wsParentGA
                            + ":" + wsParentVersion + " (#324)");
                    continue;
                }

                if (!network.ike.plugin.ws.PomParentSupport
                        .hasEmptyRelativePathInContent(content)) {
                    violations.add(name + " parent " + subParentGA + ":"
                            + subParentVersion + " missing empty "
                            + "<relativePath/> (#324 cycle prevention)");
                }
            }

            if (violations.isEmpty()) return Optional.empty();

            StringBuilder sb = new StringBuilder();
            sb.append(violations.size())
                    .append(" parent-coherence violation(s):\n");
            for (String v : violations) {
                sb.append("    • ").append(v).append('\n');
            }
            sb.append("  Subprojects sharing the workspace's parent GA must\n");
            sb.append("  agree on parent version AND declare an empty\n");
            sb.append("  <relativePath/> to prevent the Maven 4\n");
            sb.append("  \"parents form a cycle\" error. "
                    + WsGoal.SCAFFOLD_DRAFT.qualified() + "\n");
            sb.append("  reports the same coherence check as a warning (folded\n");
            sb.append("  from the retired ws:verify per #393); the release gate\n");
            sb.append("  promotes it to a hard requirement.");
            return Optional.of(sb.toString());
        }
    },

    /**
     * Every cloned subproject's three branch axes agree: the
     * {@code workspace.yaml} {@code branch:} declaration, the on-disk
     * checkout, and the {@code .ike/vcs-state} record. The three drifted
     * silently for a week on ike-starter-set — manifest declared a feature
     * branch while checkout and state file sat on {@code main}, and the
     * VCS-bridge sync actively enforced the state file OVER the manifest
     * (IKE-Network/ike-issues#904). Surfaced warn-only on every goal by
     * {@code AbstractWorkspaceMojo}; {@code ws:lint} reports it with the
     * rest of the conditions.
     */
    BRANCH_COHERENCE(
            "workspace.yaml, checkout, and .ike/vcs-state agree on every "
                    + "subproject's branch") {
        @Override
        public Optional<String> check(PreflightContext ctx) {
            WorkspaceGraph graph = ctx.graph();
            if (graph == null) return Optional.empty();
            File root = ctx.workspaceRoot();

            List<String> disagreements = new ArrayList<>();
            for (Map.Entry<String, Subproject> entry
                    : graph.manifest().subprojects().entrySet()) {
                String name = entry.getKey();
                String declared = entry.getValue().branch();
                if (declared == null) continue;
                File dir = new File(root, name);
                if (!new File(dir, ".git").exists()) continue;
                String checkout = currentBranchOf(dir);
                if (checkout.isEmpty()) continue; // detached/unreadable — not this check's call
                String recorded = VcsState.readFrom(dir.toPath())
                        .map(VcsState::branch).orElse(null);

                boolean coherent = declared.equals(checkout)
                        && (recorded == null || recorded.equals(declared));
                if (!coherent) {
                    disagreements.add(name + ": yaml=" + declared
                            + ", checkout=" + checkout
                            + ", vcs-state="
                            + (recorded == null ? "(none)" : recorded));
                }
            }

            if (disagreements.isEmpty()) return Optional.empty();

            StringBuilder sb = new StringBuilder();
            sb.append(disagreements.size())
                    .append(" subproject(s) disagree across workspace.yaml / ")
                    .append("checkout / .ike/vcs-state:\n");
            for (String d : disagreements) {
                sb.append("    • ").append(d).append('\n');
            }
            sb.append("  The VCS bridge treats .ike/vcs-state as the branch of\n");
            sb.append("  record: a checkout that disagrees with it is switched\n");
            sb.append("  back by the next ws:* goal's sync, so silent drift\n");
            sb.append("  compounds (ike-issues#904).\n");
            sb.append("  Align checkouts AND bridge state to the manifest:\n");
            sb.append("    mvn ").append(WsGoal.RECONCILE_BRANCHES_PUBLISH.qualified())
                    .append(" -Dfrom=manifest\n");
            sb.append("  Or accept the checkouts as truth and amend workspace.yaml:\n");
            sb.append("    mvn ").append(WsGoal.RECONCILE_BRANCHES_PUBLISH.qualified());
            return Optional.of(sb.toString());
        }
    },

    /**
     * Every member with an {@code origin} remote — and the workspace root
     * itself — must accept a push from this operator, established by a
     * {@code git push --dry-run} that transfers nothing and creates no
     * refs (IKE-Network/ike-issues#960).
     *
     * <p>Every other condition here inspects local state. This one alone
     * asks the remote a question only the remote can answer, because a
     * goal that ends in "push every member" can otherwise discover
     * unwritability only after it has already squashed, rewritten
     * {@code workspace.yaml}, committed, and pushed the members it
     * <em>could</em> write — which is exactly how a
     * {@code feature-finish-squash-publish} against {@code ike-komet-wsr}
     * stranded a half-landed working set. A mixed-org working set
     * (members under one org, the root under another) makes
     * partial-permission the normal case, not an exotic one.
     *
     * <p>Read probes cannot substitute: {@code ls-remote} and the
     * {@code remoteSha()} calls already in the finish path authenticate
     * and prove <em>read</em> access, which on a public repo succeeds for
     * an operator who cannot write — reporting green on a run that cannot
     * complete.
     *
     * <p>Costs one network round-trip per member, so it belongs on goals
     * that are about to do far more network work anyway.
     */
    PUSH_ACCESS("Every member's origin accepts a push from this operator") {
        @Override
        public Optional<String> check(PreflightContext ctx) {
            File root = ctx.workspaceRoot();
            Map<String, File> targets = new LinkedHashMap<>();
            if (ctx.subprojects() != null) {
                for (String name : ctx.subprojects()) {
                    targets.put(name, new File(root, name));
                }
            }
            targets.put(WORKSPACE_ROOT_NAME, root);

            List<String> denied = new ArrayList<>();
            List<String> undetermined = new ArrayList<>();
            for (Map.Entry<String, File> entry : targets.entrySet()) {
                File dir = entry.getValue();
                if (!new File(dir, ".git").exists()) continue;
                if (!ReleaseSupport.hasRemote(dir, DEFAULT_REMOTE)) continue;
                String branch = currentBranchOf(dir);
                if (branch.isEmpty()) continue; // detached — nothing to probe
                VcsOperations.PushAccess access =
                        VcsOperations.probePushAccess(dir, DEFAULT_REMOTE, branch);
                String label = entry.getKey() + " (" + branch + ")";
                switch (access.status()) {
                    case WRITABLE -> { }
                    case DENIED -> denied.add(label + ":\n"
                            + indent(access.detail()));
                    case UNDETERMINED -> undetermined.add(label + ":\n"
                            + indent(access.detail()));
                }
            }

            if (denied.isEmpty() && undetermined.isEmpty()) {
                return Optional.empty();
            }

            StringBuilder sb = new StringBuilder();
            if (!denied.isEmpty()) {
                sb.append(denied.size())
                        .append(" member(s) refuse a push from this operator:\n");
                for (String d : denied) {
                    sb.append("    • ").append(d).append('\n');
                }
            }
            if (!undetermined.isEmpty()) {
                sb.append(undetermined.size())
                        .append(" member(s) could not be probed ")
                        .append("(offline, or an unrecognized git error):\n");
                for (String u : undetermined) {
                    sb.append("    • ").append(u).append('\n');
                }
            }
            sb.append("  Nothing has been mutated — this check runs before\n");
            sb.append("  any squash, commit, or push, so the working set is\n");
            sb.append("  still coherent.\n");
            if (!denied.isEmpty()) {
                sb.append("  To resolve, for each member above either:\n");
                sb.append("    • have an admin grant write access to the\n");
                sb.append("      repository (a read-only collaborator can\n");
                sb.append("      fetch and clone but not push), or\n");
                sb.append("    • check the remote URL and the SSH identity it\n");
                sb.append("      selects — `git -C <member> remote -v` — since\n");
                sb.append("      a host alias can route to the wrong account.\n");
            }
            return Optional.of(sb.toString().stripTrailing());
        }
    };

    /** Special marker used when the workspace root itself has uncommitted changes. */
    public static final String WORKSPACE_ROOT_NAME = "workspace root";

    /** The remote every push-oriented condition probes. */
    static final String DEFAULT_REMOTE = "origin";

    /**
     * Indent a possibly multi-line git message so it nests under a
     * bullet in a remediation block.
     *
     * @param text the raw message
     * @return the message with every line indented, never {@code null}
     */
    static String indent(String text) {
        if (text == null || text.isBlank()) return "        (no output)";
        return text.lines()
                .map(l -> "        " + l.strip())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private final String description;

    PreflightCondition(String description) {
        this.description = description;
    }

    /** Short human description of what this condition enforces. */
    public String description() {
        return description;
    }

    /**
     * Evaluate the condition against the given context.
     *
     * @param ctx the preflight context
     * @return {@link Optional#empty()} if the condition is satisfied;
     *         a remediation message otherwise
     */
    public abstract Optional<String> check(PreflightContext ctx);

    // ── Shared helpers ──────────────────────────────────────────────

    static String gitStatus(File dir) {
        try {
            return ReleaseSupport.execCapture(dir,
                    "git", "status", "--porcelain").trim();
        } catch (MojoException e) {
            return "";
        }
    }

    /**
     * The current branch of the repository at {@code dir}, or an empty
     * string when HEAD is detached or the branch cannot be read — callers
     * treat absence of a branch name as nothing to compare.
     *
     * @param dir the repository root directory
     * @return the current branch name, or {@code ""} when unreadable
     */
    static String currentBranchOf(File dir) {
        try {
            String out = ReleaseSupport.execCapture(dir,
                    "git", "branch", "--show-current");
            return out == null ? "" : out.trim();
        } catch (MojoException e) {
            return "";
        }
    }

    /**
     * Return {@code true} when the given {@code git status --porcelain}
     * output mentions paths matching a gh-pages-style site output
     * layout: {@code <something>/<same>/<site files>} doubling, or
     * {@code examples/<name>/<site files>}. The signal files we look
     * for ({@code bom.json}, {@code built-with.html},
     * {@code dependencies.html}, {@code dependency-management.html},
     * {@code distribution-management.html}, {@code project-info.html})
     * are emitted by maven-project-info-reports-plugin during
     * site:stage and don't belong in source control.
     *
     * <p>Used by {@link #WORKING_TREE_CLEAN} to amplify the diagnostic
     * when an operator is about to bump a workspace whose main tree
     * contains leaked gh-pages content. ike-issues#358.
     *
     * @param porcelain raw {@code git status --porcelain} output
     * @return {@code true} when at least one entry matches
     */
    static boolean containsGhPagesLeakPattern(String porcelain) {
        if (porcelain == null || porcelain.isBlank()) return false;
        for (String line : porcelain.split("\n")) {
            String path = line.length() > 3 ? line.substring(3).trim() : "";
            if (path.endsWith("/bom.json")
                    || path.endsWith("/built-with.html")
                    || path.endsWith("/dependencies.html")
                    || path.endsWith("/dependency-management.html")
                    || path.endsWith("/distribution-management.html")
                    || path.endsWith("/project-info.html")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Read every {@code pom.xml} reachable via the workspace root or any
     * subproject in the context, extracting each one's {@code <artifactId>}.
     * Used to seed on-disk gh-pages leak detection — the leak directory
     * names always equal one of these.
     *
     * <p>Best-effort: unreadable POMs are silently skipped. Returns a
     * de-duplicated list in encounter order (workspace first, then
     * subprojects in their declared order).
     *
     * @param ctx the preflight context
     * @return distinct artifactIds discovered across the workspace
     */
    static List<String> collectCascadeArtifactIds(PreflightContext ctx) {
        File root = ctx.workspaceRoot();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
        addArtifactIdIfPresent(new File(root, "pom.xml"), seen);
        for (String name : ctx.subprojects()) {
            addArtifactIdIfPresent(new File(new File(root, name), "pom.xml"),
                    seen);
        }
        return new ArrayList<>(seen);
    }

    /**
     * Whether a SNAPSHOT property violation is resolved by the current
     * release cycle rather than leaking (ike-issues#981): the property
     * is the owning member's manifest-declared {@code version-property}
     * hint for a depends-on edge whose target member releases in this
     * cycle. The release loop's catch-up alignment substitutes the
     * target's released version before the owning consumer releases, so
     * the SNAPSHOT value never reaches a released POM. Everything else —
     * a property tracking a member outside the release set, or an
     * unrelated SNAPSHOT property — keeps failing the gate.
     *
     * @param ctx       the preflight context; exemptions require both
     *                  {@link PreflightContext#releaseSet()} and
     *                  {@link PreflightContext#graph()}
     * @param owner     the subproject whose POM carries the violation
     * @param violation the scanned SNAPSHOT property violation
     * @return true when the cycle's catch-up alignment resolves it
     */
    private static boolean resolvedByReleaseCycle(
            PreflightContext ctx, String owner,
            SnapshotScanner.Violation violation) {
        if (ctx.releaseSet() == null || ctx.graph() == null) {
            return false;
        }
        String location = violation.location();
        int slash = location.lastIndexOf('/');
        if (slash < 0
                || !location.substring(0, slash).endsWith("properties")) {
            return false;
        }
        String property = location.substring(slash + 1);
        Subproject ownerEntry =
                ctx.graph().manifest().subprojects().get(owner);
        if (ownerEntry == null || ownerEntry.dependsOn() == null) {
            return false;
        }
        for (Dependency dep : ownerEntry.dependsOn()) {
            if (property.equals(dep.versionProperty())
                    && ctx.releaseSet().contains(dep.subproject())) {
                return true;
            }
        }
        return false;
    }

    private static void addArtifactIdIfPresent(File pom,
                                               java.util.Set<String> accum) {
        if (!pom.isFile()) return;
        try {
            String content = Files.readString(pom.toPath(),
                    StandardCharsets.UTF_8);
            String artifactId = extractTopLevelArtifactId(content);
            if (artifactId != null && !artifactId.isBlank()) {
                accum.add(artifactId);
            }
        } catch (IOException ignore) {
            // best-effort
        }
    }

    /**
     * Extract the top-level {@code <artifactId>} from POM XML — the
     * project's own artifactId, not a {@code <parent>} or
     * {@code <dependency>} reference. Uses a tag-after-tag scan so it
     * doesn't drag in an XML parser dependency.
     *
     * @param pomContent POM XML as a string
     * @return the top-level artifactId, or {@code null} if not found
     */
    static String extractTopLevelArtifactId(String pomContent) {
        if (pomContent == null) return null;
        // Skip any leading <parent>...</parent> block so we don't return
        // the parent's artifactId.
        int searchFrom = 0;
        int parentOpen = pomContent.indexOf("<parent>");
        if (parentOpen >= 0) {
            int parentClose = pomContent.indexOf("</parent>", parentOpen);
            if (parentClose > parentOpen) {
                searchFrom = parentClose + "</parent>".length();
            }
        }
        int open = pomContent.indexOf("<artifactId>", searchFrom);
        if (open < 0) return null;
        int valueStart = open + "<artifactId>".length();
        int close = pomContent.indexOf("</artifactId>", valueStart);
        if (close < 0) return null;
        return pomContent.substring(valueStart, close).trim();
    }

    /**
     * Scan a single POM for a {@code <distributionManagement><site>}
     * block whose {@code <url>} starts with {@code scpexe://}. Used
     * by {@link #NO_SCPEXE_SITE_URLS}.
     *
     * <p>Quietly tolerates unreadable POMs — preflight is best-
     * effort. The check is substring-based on the
     * {@code <distributionManagement>...</distributionManagement>}
     * block so it doesn't false-positive on a scpexe wagon
     * reference outside the site URL (e.g., in a
     * {@code <repository>} block, though that's also gone).
     *
     * @param pom         pom.xml to scan (may not exist)
     * @param displayName label for the violation line
     *                    ({@link #WORKSPACE_ROOT_NAME} or
     *                    subproject name)
     * @param accum       accumulator for formatted violations
     */
    static void scanPomForScpexeSiteUrl(File pom, String displayName,
                                         List<String> accum) {
        if (!pom.isFile()) return;
        String content;
        try {
            content = Files.readString(pom.toPath(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            return; // best-effort
        }
        if (containsScpexeSiteUrl(content)) {
            accum.add(displayName + " (pom.xml has scpexe:// "
                    + "<site><url>)");
        }
    }

    /**
     * Pure-string test for an {@code scpexe://} URL inside a POM's
     * {@code <distributionManagement>} block. Extracted so it's
     * testable without filesystem fixtures.
     *
     * @param pomContent POM XML as a string
     * @return {@code true} when a scpexe:// site URL is present
     */
    static boolean containsScpexeSiteUrl(String pomContent) {
        if (pomContent == null) return false;
        int dmOpen = pomContent.indexOf("<distributionManagement>");
        if (dmOpen < 0) return false;
        int dmClose = pomContent.indexOf("</distributionManagement>",
                dmOpen);
        if (dmClose < dmOpen) return false;
        String block = pomContent.substring(dmOpen, dmClose);
        int siteOpen = block.indexOf("<site>");
        if (siteOpen < 0) return false;
        int siteClose = block.indexOf("</site>", siteOpen);
        if (siteClose < siteOpen) return false;
        String siteBlock = block.substring(siteOpen, siteClose);
        return siteBlock.contains("scpexe://");
    }

    /**
     * Within {@code projectDir}, look for {@code <id>/<id>/index.html}
     * for each {@code id} in {@code artifactIds}. Each hit is added to
     * {@code accum} as the directory containing the {@code index.html}
     * (i.e., {@code projectDir/<id>/<id>}).
     *
     * <p>{@code index.html} is the signal we trust — the maven-site
     * render always produces it, and we want to avoid flagging legit
     * subdirectories that happen to share a name. The doubled-name
     * pattern eliminates almost all collisions on its own; requiring
     * {@code index.html} closes the rest.
     *
     * @param projectDir  directory to search
     * @param artifactIds candidate names to probe
     * @param accum       accumulator for hit paths
     */
    static void detectGhPagesLeakDirs(File projectDir,
                                       List<String> artifactIds,
                                       List<Path> accum) {
        if (projectDir == null || !projectDir.isDirectory()) return;
        Path base = projectDir.toPath();
        for (String id : artifactIds) {
            Path probe = base.resolve(id).resolve(id);
            if (Files.isRegularFile(probe.resolve("index.html"))) {
                accum.add(probe);
            }
        }
    }

    /**
     * If {@code dir/.mvn/jvm.config} exists and contains any line whose
     * first non-empty character is {@code #}, append one entry per
     * offending line to {@code accum}. Format:
     * {@code <displayName>/.mvn/jvm.config:<lineNo>: <text>}.
     *
     * <p>Empty lines and whitespace-only lines are ignored. Quietly
     * tolerates {@link IOException} — preflight is best-effort and
     * shouldn't fail the gate over a transient read error.
     *
     * @param dir         the directory whose {@code .mvn/jvm.config} to scan
     * @param displayName name shown in the violation list
     *                    ({@link #WORKSPACE_ROOT_NAME} for the workspace
     *                    root, otherwise the subproject name)
     * @param accum       accumulator for formatted violation strings
     */
    static void collectJvmConfigViolations(File dir, String displayName,
                                           List<String> accum) {
        Path config = dir.toPath().resolve(".mvn").resolve("jvm.config");
        if (!Files.isRegularFile(config)) return;
        try {
            List<String> lines = Files.readAllLines(config, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.stripLeading();
                if (trimmed.startsWith("#")) {
                    accum.add(displayName + "/.mvn/jvm.config:"
                            + (i + 1) + ": " + line);
                }
            }
        } catch (IOException e) {
            // Best-effort — preflight does not fail on read errors.
        }
    }

    /**
     * IKE-foundation property names that
     * {@link #NO_FOUNDATION_PROPERTY_SHADOWING} flags as illegal local
     * overrides.
     */
    static final List<String> FOUNDATION_PROPERTY_NAMES = List.of(
            "ike-tooling.version",
            "ike-docs.version",
            "ike-platform.version");

    /**
     * Return {@code true} when the POM content contains either a
     * {@code <distributionManagement>} block or a {@code <parent>}
     * block. Used by
     * {@link #SUBPROJECT_HAS_DISTRIBUTION_MANAGEMENT} — site:stage
     * needs at least one of these to resolve the deploy target.
     *
     * @param pomContent the POM XML as a string
     * @return {@code true} when at least one block is present
     */
    static boolean hasDistributionManagementOrParent(String pomContent) {
        if (pomContent == null) return false;
        return DISTRIBUTION_MGMT_OR_PARENT.matcher(pomContent).find();
    }

    /**
     * Return {@code true} when the POM has a {@code <properties>} block
     * containing an element whose tag name equals {@code propertyName}.
     * Tolerates whitespace inside the tag value (including blank).
     *
     * @param pomContent   the POM XML as a string
     * @param propertyName the property tag name to scan for
     * @return {@code true} when the property is declared at the
     *         project's top-level {@code <properties>}
     */
    static boolean shadowsProperty(String pomContent, String propertyName) {
        if (pomContent == null || propertyName == null) return false;
        // Scope to <properties>...</properties> at top level — a
        // <properties> nested inside a plugin <configuration> isn't
        // a shadowing site.
        java.util.regex.Matcher matcher = TOP_LEVEL_PROPERTIES_BLOCK.matcher(pomContent);
        while (matcher.find()) {
            String block = matcher.group(1);
            if (java.util.regex.Pattern
                    .compile("<" + java.util.regex.Pattern.quote(propertyName)
                            + "\\b")
                    .matcher(block).find()) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.regex.Pattern DISTRIBUTION_MGMT_OR_PARENT =
            java.util.regex.Pattern.compile(
                    "(?s)<distributionManagement\\b|<parent\\b");

    /**
     * Extract the parent {@code groupId:artifactId} from a POM string,
     * or {@code null} when no {@code <parent>} block is present.
     *
     * @param pomContent the POM XML
     * @return {@code "groupId:artifactId"} or {@code null}
     */
    static String extractParentGa(String pomContent) {
        if (pomContent == null) return null;
        java.util.regex.Matcher parentMatcher = java.util.regex.Pattern.compile(
                "(?s)<parent\\b[^>]*>(.*?)</parent>").matcher(pomContent);
        if (!parentMatcher.find()) return null;
        String block = parentMatcher.group(1);
        java.util.regex.Matcher gMatch = java.util.regex.Pattern.compile(
                "<groupId>\\s*([^<]+?)\\s*</groupId>").matcher(block);
        java.util.regex.Matcher aMatch = java.util.regex.Pattern.compile(
                "<artifactId>\\s*([^<]+?)\\s*</artifactId>").matcher(block);
        if (!gMatch.find() || !aMatch.find()) return null;
        return gMatch.group(1).trim() + ":" + aMatch.group(1).trim();
    }

    /**
     * Extract the parent {@code <version>} from a POM string, or
     * {@code null} when no {@code <parent>} block is present or it
     * lacks an explicit version.
     *
     * @param pomContent the POM XML
     * @return the version string or {@code null}
     */
    static String extractParentVersion(String pomContent) {
        if (pomContent == null) return null;
        java.util.regex.Matcher parentMatcher = java.util.regex.Pattern.compile(
                "(?s)<parent\\b[^>]*>(.*?)</parent>").matcher(pomContent);
        if (!parentMatcher.find()) return null;
        String block = parentMatcher.group(1);
        java.util.regex.Matcher vMatch = java.util.regex.Pattern.compile(
                "<version>\\s*([^<]+?)\\s*</version>").matcher(block);
        if (!vMatch.find()) return null;
        return vMatch.group(1).trim();
    }

    /**
     * Match the project's top-level {@code <properties>} block.
     * Anchored to follow either {@code </artifactId>},
     * {@code </version>}, or {@code </name>} so it doesn't match
     * nested {@code <properties>} inside plugin {@code <configuration>}
     * blocks. Best-effort — handles the common shape of IKE POMs.
     */
    private static final java.util.regex.Pattern TOP_LEVEL_PROPERTIES_BLOCK =
            java.util.regex.Pattern.compile(
                    "(?s)<properties>\\s*(.*?)\\s*</properties>");
}
