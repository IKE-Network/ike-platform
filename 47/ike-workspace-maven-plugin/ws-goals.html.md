---
date_published: 2026-05-11
date_modified: 2026-05-11
canonical_url: https://ike.network/ike-platform/ike-workspace-maven-plugin/ws-goals.html
---

# ws:* Goal Reference

The `ws:*` plugin goals coordinate cross-repository operations across an IKE workspace — a directory containing one or more git repositories described by a single `workspace.yaml` manifest. Where bare git only sees one repo at a time, `ws:*` goals fan out across every checked-out subproject in topological order: feature branches stay aligned, parent versions cascade in lockstep, and releases hold their own tag history even when seven repositories ship together.

This page is the comprehensive reference. For a narrative tour of how the goals fit together day-to-day, see the [Workspace Lifecycle](workspace-lifecycle.html)[1] page. For a quick intro to running goals from IntelliJ vs. the command line, see the [Quick Start sections](index.html#_quick_start_intellij_idea)[2] on the workspace plugin home.

## [#conventions](#conventions)Conventions

Draft / publish split Most state-mutating goals come in two forms — `**-draft**`** (preview only, writes a markdown report, makes no on-disk changes) and ``**`-publish` (executes the action). The bare goal name (e.g. `ws:align`) is wired to the draft variant. This is a deliberate convention from ike-issues#200: every workspace mutation is two-phase, with a real chance to audit before committing. When you see `ws:align-publish`, treat the missing `-draft` suffix as the verb. Aggregator goals Most `ws:*` goals are declared as Maven aggregators (`@Mojo(aggregator = true)`). They run once at the workspace root, not once per Maven module. Invoking from any subdirectory works — the goal walks up the filesystem looking for `workspace.yaml`. Topological order Subprojects are processed in dependency order. A change to an upstream component is visible to its downstream consumers in the same operation. Reverse-topological order is used for destructive operations (`feature-abandon`, `cleanup`) so downstream is removed first. Per-goal markdown reports Every `ws:*` goal writes its output to a markdown file alongside `workspace.yaml` (e.g., `ws꞉overview.md`, `ws꞉release-draft.md`). The colon in filenames uses the modifier-letter form (`U+A789`) so unix tooling treats the names as plain identifiers. Use `ws:report` to list and open them.  

## [#quick-reference](#quick-reference)Quick Reference

| Goal | Phase | Purpose |
| --- | --- | --- |
| [ws:add — add a subproject](#add) | setup | Add a subproject to the workspace from a git URL |
| [ws:adopt-root — migrate placeholder GAV](#adopt-root) | setup | Migrate workspace root to real Maven coordinates |
| [align](#align-draft) | alignment | Sync inter-subproject dependency versions (preview) |
| [ws:align — sync inter-subproject dependency versions](#align-publish) | alignment | Apply the alignment changes |
| [ws:check-branch — defensive git hook](#check-branch) | hooks | Defensive post-checkout hook — warn on out-of-band branch ops |
| [checkpoint](#checkpoint-draft) | release | Tag every subproject at HEAD without releasing (preview) |
| [ws:checkpoint — tag without releasing](#checkpoint-publish) | release | Apply the checkpoint tags |
| [cleanup](#cleanup-draft) | cleanup | List merged feature branches across the workspace |
| [ws:cleanup-publish — interactive cleanup](#cleanup-publish) | cleanup | Delete merged feature branches interactively |
| [ws:commit — stage + commit workspace-wide](#commit) | sync | Stage + commit across all subprojects with VCS-bridge preamble |
| [feature-abandon](#feature-abandon-draft) | feature | Preview deletion of a feature branch workspace-wide |
| [ws:feature-abandon — discard a feature branch](#feature-abandon-publish) | feature | Delete a feature branch workspace-wide |
| [feature-finish-merge](#feature-finish-merge-draft) | feature | Preview a no-fast-forward merge back to main |
| [ws:feature-finish-merge — no-fast-forward merge](#feature-finish-merge-publish) | feature | Execute the no-ff merge |
| [feature-finish-squash](#feature-finish-squash-draft) | feature | Preview a squash-merge back to main (recommended) |
| [ws:feature-finish-squash — squash-merge back to main](#feature-finish-squash-publish) | feature | Execute the squash-merge |
| [feature-start](#feature-start-draft) | feature | Preview a coordinated feature branch |
| [ws:feature-start — coordinated feature branch](#feature-start-publish) | feature | Create the feature branch with auto-alignment |
| [ws:fix — resync workspace.yaml denormalized fields](#fix) | inspection | Sync `workspace.yaml` denormalized fields with POM truth |
| [ws:graph — dependency graph](#graph) | inspection | Print or DOT-render the workspace dependency graph |
| [ws:init — clone subprojects](#init) | setup | Clone all subprojects from `workspace.yaml` |
| [ws:lint — preflight hygiene gate](#lint) | inspection | Surface preflight hygiene conditions (report-only) |
| [ws:overview — consolidated dashboard](#overview) | inspection | Consolidated manifest + graph + status + cascade |
| [ws:post-release — bump to next development version](#post-release) | release | Bump every subproject to the next development version |
| [ws:pull — git pull across the workspace](#pull) | sync | `git pull --rebase` across the workspace |
| [ws:push — git push across the workspace](#push) | sync | `git push` across the workspace |
| [reconcile-branches](#reconcile-branches-draft) | recovery | Reconcile `workspace.yaml` branch fields with on-disk git |
| [ws:reconcile-branches — recover yaml/git mismatch](#reconcile-branches-publish) | recovery | Apply branch reconciliation |
| [ws:refresh-main — refresh local main from origin](#refresh-main) | sync | Refresh local main from origin/main across the workspace |
| [cascade-foundation-publish](cascade-foundation-publish.html)[3] | release | Cross-repo cascade: release `ike-tooling → ike-docs → ike-platform → workspace`, with upstream-property catch-up. See the [dedicated reference page](cascade-foundation-publish.html)[3] for layout, anchor-vs-membership semantics, and bootstrap-invocation notes. |
| [ws:release-draft — preview a coordinated release](#release-draft) | release | Preview a coordinated multi-repo release |
| [ws:release-notes — milestone-derived release notes](#release-notes) | release | Generate notes from a GitHub milestone |
| [ws:release-publish — execute the coordinated release](#release-publish) | release | Execute the coordinated release |
| [ws:release-status — diagnose in-flight releases](#release-status) | inspection | Diagnose any in-flight or partial release |
| [ws:remove — remove a subproject](#remove) | setup | Remove a subproject from `workspace.yaml` |
| [ws:report — open per-goal reports](#report) | inspection | List and open per-goal markdown reports |
| [scaffold-upgrade](#scaffold-upgrade-draft) | upgrades | Preview scaffold convention upgrades |
| [ws:scaffold-upgrade — refresh scaffold conventions](#scaffold-upgrade-publish) | upgrades | Apply scaffold upgrades |
| [set-parent](#set-parent-draft) | alignment | Preview an aggregator parent version cascade |
| [ws:set-parent — cascade aggregator parent version](#set-parent-publish) | alignment | Apply the parent version cascade |
| [ws:stignore — generate Syncthing ignore files](#stignore) | setup | Generate Syncthing `.stignore` files |
| [switch](#switch-draft) | feature | Preview a coordinated branch checkout |
| [ws:switch — coordinated branch checkout](#switch-publish) | feature | Execute the coordinated checkout |
| [ws:sync — pull + refresh-main + push](#sync) | sync | Pull, refresh-main, then push — the everyday daily-driver |
| [update-feature](#update-feature-draft) | feature | Preview merging main into the current feature |
| [ws:update-feature — incorporate main into feature](#update-feature-publish) | feature | Execute the main-into-feature merge |
| [ws:verify-convergence — transitive dependency convergence](#verify-convergence) | inspection | Check transitive dependency convergence across subprojects |
| [ws:verify — manifest + git consistency check](#verify) | inspection | Check workspace manifest consistency and git state |
| [versions-upgrade](#versions-upgrade-draft) | upgrades | Preview parent/property/plugin version upgrades |
| [ws:versions-upgrade-publish — apply the version upgrades](#versions-upgrade-publish) | upgrades | Apply the version upgrades |

## [#setup-goals](#setup-goals)Setup Goals

Goals for adding repositories to a workspace, removing them, and keeping the manifest in sync with the on-disk reality.

### [#ws-init--clone-subprojects](#ws-init--clone-subprojects)ws:init — clone subprojects

Clone every subproject declared in `workspace.yaml`. Three modes per subproject:

1. **Already cloned** — directory has `.git/`; skip.
2. **Syncthing working tree** — directory exists but has no `.git/`. Initializes git in-place: `git init`, adds the remote, fetches, and resets to match the remote branch. This preserves file content synced from another machine, avoiding a re-clone overwrite.
3. **Fresh clone** — no directory; runs `git clone`.

Components are processed in topological order so dependencies are present before dependents.

```
mvn ws:init
```

### [#ws-add--add-a-subproject](#ws-add--add-a-subproject)ws:add — add a subproject

Add a single repository to an existing workspace. Given a git URL, the goal:

1. Clones the repository into the workspace.
2. Derives the subproject name from the URL (or accepts `-Dsubproject=<name>`).
3. Scans the POM to derive groupId and inter-subproject dependencies.
4. Appends an entry to `workspace.yaml`.

```
mvn ws:add -Drepo=git@github.com:IKE-Network/new-component.git
mvn ws:add -Drepo=... -Dsubproject=custom-name
```

### [#ws-remove--remove-a-subproject](#ws-remove--remove-a-subproject)ws:remove — remove a subproject

Remove a subproject from `workspace.yaml`. Fails with a clear error if any other workspace subproject still depends on the target, unless `-Dforce=true` is passed. The on-disk directory is left in place — the developer chooses whether to delete it.

```
mvn ws:remove -Dsubproject=old-component
mvn ws:remove -Dsubproject=old-component -Dforce=true
```

### [#ws-adopt-root--migrate-placeholder-gav](#ws-adopt-root--migrate-placeholder-gav)ws:adopt-root — migrate placeholder GAV

Migrate an existing workspace from the pre-#183 placeholder `local.aggregate:<name>:1.0.0-SNAPSHOT` coordinates to real Maven coordinates. The placeholder GAV signals "throwaway" and prevents `ws:release-publish` from releasing the workspace root, `ws:align-publish` from aligning to it, and site deploy from publishing under a stable address. Runs once per workspace.

```
mvn ws:adopt-root -DgroupId=network.ike.example -DartifactId=ike-example-ws -Dversion=1
```

See ike-issues#184 for the migration rationale.

### [#ws-stignore--generate-syncthing-ignore-files](#ws-stignore--generate-syncthing-ignore-files)ws:stignore — generate Syncthing ignore files

Generate Syncthing `.stignore` files for the workspace. Syncthing should sync source files across a developer’s own machines but must **not** sync build artifacts or git metadata (those are machine-specific and trying to sync them produces packfile corruption).

The goal generates two files: a workspace-level `.stignore` and a template that includes a shared `stignore-shared` block. Re-run any time the workspace shape changes.

```
mvn ws:stignore
```

## [#inspection-goals](#inspection-goals)Inspection Goals

Read-only goals that report on workspace state without changing anything. Safe to run any time. Most write a markdown report alongside `workspace.yaml`; use `ws:report` to list them.

### [#ws-overview--consolidated-dashboard](#ws-overview--consolidated-dashboard)ws:overview — consolidated dashboard

Consolidated workspace overview, replacing the former `ws:dashboard`, `ws:status`, and `ws:graph`. Loads the manifest once and presents four sections:

1. **Manifest** — subproject count, consistency check.
2. **Graph** — dependency order with direct dependencies.
3. **Status** — branch, SHA, clean/uncommitted per subproject.
4. **Cascade** — downstream rebuild impact of components with uncommitted changes.

Use `-Dformat=dot` to output Graphviz DOT format instead of the text overview (delegates to `graph` rendering).

```
mvn ws:overview
mvn ws:overview -Dformat=dot
```

This is the right starting point for almost any workspace operation — run it first to see what’s there before mutating anything.

### [#ws-graph--dependency-graph](#ws-graph--dependency-graph)ws:graph — dependency graph

Print the workspace dependency graph. Displays subprojects in topological order with their direct dependencies. Optional DOT output for Graphviz rendering.

```
mvn ws:graph
mvn ws:graph -Dformat=dot | dot -Tsvg > workspace-graph.svg
```

### [#ws-verify--manifest-git-consistency-check](#ws-verify--manifest-git-consistency-check)ws:verify — manifest + git consistency check

Verify workspace manifest consistency and subproject git state. Checks:

- All dependency references resolve.
- No cycles exist in the dependency graph.
- All group members are valid.
- All subproject types are defined.
- Subproject git state, Syncthing health, environment presence.

Always read-only. Pair with `ws:fix` to repair denormalized fields.

```
mvn ws:verify
```

### [#ws-verify-convergence--transitive-dependency-conve](#ws-verify-convergence--transitive-dependency-conve)ws:verify-convergence — transitive dependency convergence

Check transitive dependency convergence across workspace subprojects. Runs `mvn dependency:tree` for each subproject in topological order, then compares resolved versions of shared dependencies. Divergences (the same artifact resolving to different versions in different components) are reported in the terminal and written to a markdown report. Useful before a release to confirm the workspace is internally consistent.

```
mvn ws:verify-convergence
```

### [#ws-fix--resync-workspace-yaml-denormalized-fields](#ws-fix--resync-workspace-yaml-denormalized-fields)ws:fix — resync workspace.yaml denormalized fields

Synchronize denormalized `workspace.yaml` fields with the actual POM values on disk. An alias for `ws:verify --update` — reads each cloned subproject’s root POM and updates the workspace manifest’s `version` and `groupId` fields when they drift from the POM truth.

Run after manual POM edits or after a release that changed versions outside the workspace orchestration.

```
mvn ws:fix
```

### [#ws-lint--preflight-hygiene-gate](#ws-lint--preflight-hygiene-gate)ws:lint — preflight hygiene gate

Surface workspace-hygiene preflight conditions as a standalone gate (ike-issues#217). Runs every preflight condition in **report-only** mode against the current workspace and emits a markdown summary. Always exits 0 — the goal is visibility, not gating.

Catches problems like typo’d `.mvn/jvm.config` comments, uncommitted state, and leaking SNAPSHOT properties before they propagate to git or Syncthing.

```
mvn ws:lint
```

### [#ws-report--open-per-goal-reports](#ws-report--open-per-goal-reports)ws:report — open per-goal reports

List and open the `ws꞉**.md**`** goal reports at the workspace root. Each `ws:`** goal writes its latest output to a per-goal file (e.g., `ws꞉overview.md`, `ws꞉release-draft.md`). This goal lists those reports newest-first and opens the workspace root in the default file manager so you can browse them.

```
mvn ws:report
```

### [#ws-release-status--diagnose-in-flight-releases](#ws-release-status--diagnose-in-flight-releases)ws:release-status — diagnose in-flight releases

Read-only diagnostic for any in-flight or partial workspace release. Walks every checked-out subproject in `workspace.yaml`, collects git artifacts that indicate an interrupted release (`release/****`** branches and unpushed `v`** tags), and prints a punch list with one line per subproject. The footer recommends a next action — typically pointing at `IKE-RELEASE-RECOVERY.md` for the matching state.

Performs no mutations. Run this any time you suspect a release went sideways.

```
mvn ws:release-status
```

### [#ws-check-branch--defensive-git-hook](#ws-check-branch--defensive-git-hook)ws:check-branch — defensive git hook

Defensive git hook — warns when a branch is created or switched outside the workspace tooling. Intended to be called from a `post-checkout` git hook:

```
#!/bin/sh
mvn -q ws:check-branch -- "$@"
```

In workspace mode, compares the current branch to the expected branch in `workspace.yaml` and warns on mismatch. Provides copy-pasteable undo commands. In bare mode (no `workspace.yaml`), silently exits — nothing to check. Never blocks; always exits 0.

## [#sync-goals](#sync-goals)Sync Goals

Daily git fan-out — pull, push, commit, and the combined `sync`. All operate in topological order across every checked-out subproject.

### [#ws-sync--pull-refresh-main-push](#ws-sync--pull-refresh-main-push)ws:sync — pull + refresh-main + push

Pull then push across the workspace — the everyday "sync" operation: bring down what teammates have committed, then push up what I have committed. Replaces the daily two-step of `ws:pull` followed by `ws:push`.

Between the pull and the push, this goal also refreshes local `main` from `origin/main` across the workspace via the same mechanism `ws:refresh-main` uses. This keeps local main coherent with the remote even on machines where Syncthing carries an out-of-band checkout.

```
mvn ws:sync
```

### [#ws-pull--git-pull-across-the-workspace](#ws-pull--git-pull-across-the-workspace)ws:pull — git pull across the workspace

Pull latest changes across the workspace. When the workspace root is itself a git repository (i.e. has a `.git` directory), it is pulled first so any changes to the root POM or `workspace.yaml` land before subproject operations run. Runs `git pull --rebase` in each cloned subproject directory in topological order. Uninitialized components are skipped with a warning.

```
mvn ws:pull
```

### [#ws-push--git-push-across-the-workspace](#ws-push--git-push-across-the-workspace)ws:push — git push across the workspace

Push with a VCS-bridge catch-up preamble. When run from a workspace root, iterates all subproject repositories in topological order and pushes each. When run from a single repository, operates on the current directory only.

```
mvn ws:push
mvn ws:push -DskipUpToDate=false   # show "already up to date" lines
```

### [#ws-commit--stage-commit-workspace-wide](#ws-commit--stage-commit-workspace-wide)ws:commit — stage + commit workspace-wide

Commit with a VCS-bridge catch-up preamble. By default stages all tracked-modified and untracked-not-ignored files before committing — workspace-wide goals routinely create new files (scaffold writes, IDE settings cleanup, generated configs) and a staged-only default silently dropped them. Pass `-DstagedOnly` to commit only what is already in the index.

Each subproject’s commit line includes a count of modified vs. new files, with the new file paths listed inline so the developer can see what was pulled in without running `git status` after the fact:

```
  ✓ komet-ws — 7 modified, 1 new (.idea/kotlinc.xml)
```

```
mvn ws:commit -Dmessage="fix: deploy-path bug"
mvn ws:commit -Dmessage="..." -DstagedOnly
```

### [#ws-refresh-main--refresh-local-main-from-origin](#ws-refresh-main--refresh-local-main-from-origin)ws:refresh-main — refresh local main from origin

Refresh local `main` from `origin/main` across the workspace. For each subproject, fetches origin and reconciles local main with `origin/main`:

- Fast-forward when behind.
- Leave alone when purely ahead (unpushed work).
- Auto-resolve via merge when diverged. The merge stays local until pushed via `ws:push` or `ws:sync`.

Used internally by `ws:sync`, `ws:feature-start`, and `ws:feature-finish-*`. Available standalone for the case where you want to refresh main without pulling subproject branches.

```
mvn ws:refresh-main
```

## [#feature-flow-goals](#feature-flow-goals)Feature Flow Goals

Coordinated feature branches across multiple subprojects. The flow: `feature-start` → `update-feature` (as needed) → `feature-finish-{merge,squash}` or `feature-abandon`.

### [#ws-feature-start--coordinated-feature-branch](#ws-feature-start--coordinated-feature-branch)ws:feature-start — coordinated feature branch

Create a feature branch with a consistent name across all workspace subprojects, optionally setting branch-qualified SNAPSHOT versions in each POM.

Before branching, the goal refreshes local `main` from `origin/main` so the new feature branch starts from current main rather than whatever stale state happens to be on the local machine. If the refresh would produce file conflicts, the goal hard-errors before any branch is created (ike-issues#284).

In workspace mode (workspace.yaml found):

1. Refreshes local main from origin/main.
2. Validates the working tree is clean.
3. Creates branch `feature/<name>` from the current HEAD.
4. If the subproject has a Maven version, sets a branch-qualified version (e.g., `1.2.0-my-feature-SNAPSHOT`).

The publish variant additionally runs `ws:align-publish` so the new feature branch starts from a consistent inter-subproject state.

```
mvn ws:feature-start-draft -Dfeature=my-feature       # preview
mvn ws:feature-start-publish -Dfeature=my-feature     # execute
```

### [#ws-update-feature--incorporate-main-into-feature](#ws-update-feature--incorporate-main-into-feature)ws:update-feature — incorporate main into feature

Update the current feature branch by incorporating changes from main. For long-lived feature branches, main may advance significantly. This goal brings the feature branch up to date, surfacing merge conflicts incrementally rather than at feature-finish time.

Uses merge (not rebase) by default to incorporate main — this preserves all commit hashes and is safe for branches shared via Syncthing or pushed to origin. Pass `-Dstrategy=rebase` for a linear history when no one else has pulled the branch.

```
mvn ws:update-feature-draft                       # preview
mvn ws:update-feature-publish                     # merge (default)
mvn ws:update-feature-publish -Dstrategy=rebase   # rebase
```

### [#ws-feature-finish-squash--squash-merge-back-to-mai](#ws-feature-finish-squash--squash-merge-back-to-mai)ws:feature-finish-squash — squash-merge back to main

Squash-merge a feature branch back to the target branch. **The default and recommended strategy for finishing features.** The feature branch’s full commit history is compressed into a single commit on the target branch. The feature branch is deleted after merge because squash creates divergent history — continuing on the branch would cause conflicts.

Pass `-DkeepBranch=true` only if you understand that the branch can no longer be cleanly merged again.

Before squash-merging, refreshes local `main` from `origin/main` so the feature is not landed on top of stale main (ike-issues#284). If the refresh would conflict, the goal hard-errors before touching any feature branch.

When to use Most features. Feature branch history is disposable. Target branch gets one clean commit.

```
mvn ws:feature-finish-squash-draft -Dfeature=done
mvn ws:feature-finish-squash-publish -Dfeature=done -Dmessage="Ship it"
```

### [#ws-feature-finish-merge--no-fast-forward-merge](#ws-feature-finish-merge--no-fast-forward-merge)ws:feature-finish-merge — no-fast-forward merge

No-fast-forward merge of a feature branch, preserving full history. Creates a merge commit on the target branch containing the complete feature branch history. The feature branch is **kept alive** by default because histories stay connected — the branch can continue to receive work and be merged again later.

Same `origin/main` refresh preamble as `feature-finish-squash` (ike-issues#284).

When to use Long-lived feature branches that periodically merge intermediate work to the target branch. Use when you need traceability of individual feature commits on the target branch.

```
mvn ws:feature-finish-merge-draft -Dfeature=long-running
mvn ws:feature-finish-merge-publish -Dfeature=long-running
```

### [#ws-feature-abandon--discard-a-feature-branch](#ws-feature-abandon--discard-a-feature-branch)ws:feature-abandon — discard a feature branch

Abandon a feature branch across all workspace subprojects. The draft variant previews what would be abandoned — which components, how many unmerged commits, what would be lost. The publish variant prompts for confirmation then executes the deletion.

Components are processed in **reverse** topological order (downstream first) to avoid transient dependency issues.

```
mvn ws:feature-abandon-draft                       # preview
mvn ws:feature-abandon-publish                     # with confirmation
mvn ws:feature-abandon-publish -Dforce=true        # skip confirmation
mvn ws:feature-abandon-publish -DdeleteRemote=true # also delete remote branches
```

### [#ws-switch--coordinated-branch-checkout](#ws-switch--coordinated-branch-checkout)ws:switch — coordinated branch checkout

Switch all workspace subprojects to a different branch with optional auto-stash. Discovers all local feature branches across subprojects and presents an interactive menu. The selected branch is checked out in every subproject that has it locally; subprojects without the branch are skipped with a warning.

Pass `-Dbranch=<name>` to skip the interactive menu.

```
mvn ws:switch-draft                              # preview
mvn ws:switch-publish                            # interactive
mvn ws:switch-publish -Dbranch=feature/foo       # non-interactive
```

## [#alignment-goals](#alignment-goals)Alignment Goals

Goals that keep inter-subproject dependency declarations in step. The two-axis split (POM versions vs. git branches; ike-issues#200) puts daily-driver behavior in `align` and recovery behavior in `reconcile-branches`.

### [#ws-align--sync-inter-subproject-dependency-version](#ws-align--sync-inter-subproject-dependency-version)ws:align — sync inter-subproject dependency versions

Align inter-subproject dependency versions in POM files. For each subproject on disk, scans POM dependency declarations. When a dependency’s `groupId:artifactId` matches another workspace subproject, updates the version to match that subproject’s current POM version.

Daily-use, safe, idempotent. The draft variant writes the would-be changes to a report; the publish variant applies them.

```
mvn ws:align-draft                              # preview
mvn ws:align-publish                            # apply
```

### [#ws-set-parent--cascade-aggregator-parent-version](#ws-set-parent--cascade-aggregator-parent-version)ws:set-parent — cascade aggregator parent version

For routine "bump to the latest tested-together foundation" work, prefer `ike:scaffold-draft` (drift report, available now) and `ike:scaffold-publish` (drift apply, post-`#348`). The scaffold manifest’s `foundation:` section pins the parent version + standard properties (`ike-tooling.version`, `ike-docs.version`, `ike-platform.version`) baked at ike-tooling release time — a single source of truth that bumps everything together.

Use `ws:set-parent-publish` when you need to bump to a **specific non-current** version (reproducibility testing against an older `ike-parent`, partial-cycle rollback, etc.) — scaffold always points at the latest baked-in pin and can’t be overridden.

Set the aggregator parent version (typically `ike-parent`) across the root POM and all subproject POMs in one operation. Cascades the parent version from the root POM to every cloned subproject, including submodule POMs that reference the same parent.

Does **not** modify inter-subproject dependency versions — use `ws:align-publish` for that. Does **not** update properties (`ike-tooling.version`, `ike-docs.version`, `ike-platform.version`) — those need a separate `ws:versions-upgrade-publish` or manual edit. Scaffold-publish (post-#348) handles parent + properties in one operation.

```
mvn ws:set-parent-draft -DnewVersion=21
mvn ws:set-parent-publish -DnewVersion=21
mvn ws:set-parent-publish -DnewVersion=21 -Dmessage="bump ike-parent"
```

### [#ws-reconcile-branches--recover-yaml-git-mismatch](#ws-reconcile-branches--recover-yaml-git-mismatch)ws:reconcile-branches — recover yaml/git mismatch

Reconcile `workspace.yaml` branch fields against on-disk git state. Recovery / rare-use, separated from `ws:align` per ike-issues#200’s two-axis split. Each goal name describes its audience: `ws:align` is the safe daily POM convergence; `ws:reconcile-branches` is the recovery operation when the YAML’s recorded branch and the actual git checkout have drifted apart.

```
mvn ws:reconcile-branches-draft                # preview
mvn ws:reconcile-branches-publish              # apply
```

## [#release-goals](#release-goals)Release Goals

Coordinated multi-repo releases. The flow: `release-draft` → `release-publish` → `post-release`. `checkpoint-{draft,publish}` is the no-deploy variant: tag everything at HEAD without changing POM versions or pushing artifacts.

### [#ws-release-draft--preview-a-coordinated-release](#ws-release-draft--preview-a-coordinated-release)ws:release-draft — preview a coordinated release

Workspace-level release — releases all release-pending checked-out components (those with unreleased commits since their last tag, or cascaded as transitive downstream of one) in topological order. Scans for commits since each subproject’s last release tag. The release set is the union of:

- **source-changed** — subprojects with unreleased commits.
- **cascade-pulled** — subprojects whose upstream got released, even if they themselves had no commits.

The draft variant writes the planned actions to `ws꞉release-draft.md` and exits without changes.

```
mvn ws:release-draft
```

### [#ws-release-publish--execute-the-coordinated-releas](#ws-release-publish--execute-the-coordinated-releas)ws:release-publish — execute the coordinated release

Execute a workspace release with per-subproject catch-up alignment. The release loop performs **per-subproject** catch-up alignment immediately before each subproject’s release: every workspace-internal upstream version reference (parent and version properties) is bumped to the upstream’s current target version. This means a downstream component released second sees the actual just-released upstream version, not the snapshot it had when the release started.

Each subproject’s release runs the single-repo `ike:release-publish` under the hood, so site deploy, Nexus deploy, GitHub Release, and the workspace VCS state file all update in lockstep.

```
mvn ws:release-publish
```

### [#ws-post-release--bump-to-next-development-version](#ws-post-release--bump-to-next-development-version)ws:post-release — bump to next development version

Post-release version bump across workspace subprojects. After a release, this goal bumps every checked-out subproject’s POM version to the specified `nextVersion`, commits the change, pushes if a remote exists, then updates `workspace.yaml` to reflect the new development versions.

Components are processed in topological order so that upstream components bump before their downstreams.

```
mvn ws:post-release -DnextVersion=22-SNAPSHOT
```

### [#ws-release-notes--milestone-derived-release-notes](#ws-release-notes--milestone-derived-release-notes)ws:release-notes — milestone-derived release notes

Generate release notes from a GitHub milestone’s closed issues. Queries the GitHub REST API to find the named milestone, lists its closed issues, and categorizes them by label into Fixes, Enhancements, and Internal sections. Delegates to the same support class used by `ike:release` to auto-populate GitHub Release notes.

```
mvn ws:release-notes -Dmilestone="my-component v17"
```

### [#ws-checkpoint--tag-without-releasing](#ws-checkpoint--tag-without-releasing)ws:checkpoint — tag without releasing

Create a workspace checkpoint — tag every subproject at its current HEAD and record the snapshot in a YAML manifest. A checkpoint records the current state of the workspace for reproduction. **It is not a build or a release** — no POM version changes, no compilation, no deployment. TeamCity watches for checkpoint tags on the workspace repo and handles CI verification.

The publish variant runs `ws:align-publish` first so the checkpoint captures a consistent inter-subproject state.

```
mvn ws:checkpoint-draft -Dlabel=before-major-refactor
mvn ws:checkpoint-publish -Dlabel=before-major-refactor
```

## [#upgrade-goals](#upgrade-goals)Upgrade Goals

Goals that pull a workspace forward to the current standards — both the build-tooling versions a workspace uses and the scaffolding files (gitignore, hooks, IDE configs).

### [#ws-versions-upgrade-draft--preview-version-upgrade](#ws-versions-upgrade-draft--preview-version-upgrade)ws:versions-upgrade-draft — preview version upgrades

Preview the version upgrades that `ws:versions-upgrade-publish` would apply across every subproject in the workspace. Walks `workspace.yaml` in topological order, scans each cloned subproject’s root `pom.xml` for `<parent>`, version properties, and literal plugin/dependency versions, and consults the workspace-level `versions-upgrade-rules.yaml` to compute the target version for each.

Writes a `versions-upgrade-plan.yaml` that the publish variant consumes — separating the plan from execution lets you eyeball the plan before applying.

```
mvn ws:versions-upgrade-draft
```

### [#ws-versions-upgrade-publish--apply-the-version-upg](#ws-versions-upgrade-publish--apply-the-version-upg)ws:versions-upgrade-publish — apply the version upgrades

Apply a previously drafted workspace `versions-upgrade-plan.yaml` across every subproject in the workspace. Walks `workspace.yaml` in topological order. For each node named in the plan, locates the matching subproject directory, confirms the on-disk POM exists, and rewrites `<parent><version>`, `<properties>` entries, and literal plugin/dependency versions per the plan.

Uses OpenRewrite (PomRewriter) for POM edits, never sed/regex.

```
mvn ws:versions-upgrade-publish
```

### [#ws-scaffold-upgrade--refresh-scaffold-conventions](#ws-scaffold-upgrade--refresh-scaffold-conventions)ws:scaffold-upgrade — refresh scaffold conventions

Upgrade workspace scaffold conventions to the current plugin version. As workspace scaffold conventions evolve across plugin releases, this goal applies incremental upgrades to bring an existing workspace in line with current standards. Each upgrade step is idempotent — running the goal twice produces the same result.

Addresses the **scaffold** layer only: gitignore, git hooks, `.mvn/maven.config`, IDE settings. POM versions are handled by `ws:versions-upgrade`.

```
mvn ws:scaffold-upgrade-draft                  # preview
mvn ws:scaffold-upgrade-publish                # apply
```

## [#cleanup-goals](#cleanup-goals)Cleanup Goals

### [#ws-cleanup-draft--list-merged-feature-branches](#ws-cleanup-draft--list-merged-feature-branches)ws:cleanup-draft — list merged feature branches

Scan all workspace subprojects for merged feature branches and report them. Lists feature branches across all subprojects, classifies each as merged (into the target branch) or active, and displays last-commit timestamps. Read-only; the publish variant does the deletion.

```
mvn ws:cleanup-draft                           # list (default target=main)
mvn ws:cleanup-draft -DtargetBranch=develop    # check against develop
```

### [#ws-cleanup-publish--interactive-cleanup](#ws-cleanup-publish--interactive-cleanup)ws:cleanup-publish — interactive cleanup

Execute workspace cleanup — delete merged feature branches. Prompts interactively for each candidate.

```
mvn ws:cleanup-publish
mvn ws:cleanup-publish -Dforce=true            # delete without prompting
```

## [#see-also](#see-also)See also

- [Workspace Lifecycle](workspace-lifecycle.html)[1] — narrative tour showing how the goals connect across a typical day, week, and release.
- [Workspace Getting Started](../workspace-getting-started.html)[4] — hands-on first-time setup walkthrough.
- [Workspace Plugin Home](index.html)[5] — module overview.
- `ws:help` — runtime help generated from the `WsGoal` enum (single source of truth; never drifts from the actual plugin).
