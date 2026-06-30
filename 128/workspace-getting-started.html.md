---
date_published: 2026-06-29
date_modified: 2026-06-29
canonical_url: https://ike.network/ike-platform/workspace-getting-started.html
---

# Workspace Developer Getting Started

This guide walks you through setting up an IKE workspace and working on tinkar/komet components day to day. For conventions and architecture rationale, see the [Workspace Conventions](workspace-conventions.html)[1] reference.

## [#prerequisites](#prerequisites)Prerequisites

Java 25 Download from [https://jdk.java.net/25/](https://jdk.java.net/25/)[2]. The workspace builds with `--enable-preview` across all modules. Maven 4.0.0-rc-5 or later Download from [https://maven.apache.org/download.cgi](https://maven.apache.org/download.cgi)[3]. All POMs use model version `4.1.0`. Git Any recent version. SSH access to `github.com/ikmdev` and `github.com/IKE-Community` orgs. Maven settings — IKE plugin groups Add to `~/.m2/settings.xml` so that `ike:`, `ws:`, and `idoc:` prefix goals resolve:

```
<settings>
  <pluginGroups>
    <pluginGroup>network.ike.tooling</pluginGroup>
    <pluginGroup>network.ike.docs</pluginGroup>
    <pluginGroup>network.ike.platform</pluginGroup>
  </pluginGroups>
</settings>
```

## [#first-time-workspace-setup](#first-time-workspace-setup)First-Time Workspace Setup

### [#clone-the-workspace-repository](#clone-the-workspace-repository)Clone the workspace repository

```
git clone git@github.com:IKE-Community/ike-workspace.git
cd ike-workspace
```

The workspace repo contains `pom.xml`, `workspace.yaml`, and file-activated profiles for every component.

### [#initialize-components](#initialize-components)Initialize components

Clone all components declared in `workspace.yaml`:

```
mvn ws:scaffold-init
```

This clones every component into a subdirectory matching its `name` field in the manifest. Each clone lands on the branch declared in `workspace.yaml` (default: `main`).

For a smaller initial checkout, use a group:

```
mvn ws:scaffold-init -Dgroup=core
```

This clones only `ike-platform` and `tinkar-core` — enough to build the foundation and start working.

### [#open-in-intellij](#open-in-intellij)Open in IntelliJ

Open the workspace `pom.xml` as a project. File-activated profiles automatically include only the components you have checked out. Missing components are silently skipped — no red underlines, no broken reactor.

## [#daily-workflow](#daily-workflow)Daily Workflow

### [#sync-all-repositories](#sync-all-repositories)Sync all repositories

```
mvn ws:pull
```

Runs `git pull --rebase` in every checked-out component.

### [#full-overview](#full-overview)Full overview

```
mvn ws:overview
```

Consolidated dashboard: replaces the former separate `ws:dashboard`, `ws:status`, and `ws:graph` goals. Loads the manifest once and shows manifest consistency, dependency graph, per-subproject git state (branch, SHA, clean/uncommitted), and cascade analysis for release-pending components.

## [#starting-a-feature](#starting-a-feature)Starting a Feature

### [#create-the-feature-branch](#create-the-feature-branch)Create the feature branch

```
mvn ws:feature-start-publish -Dfeature=my-feature
```

This creates a `feature/my-feature` branch in every checked-out component and sets branch-qualified POM versions (e.g., `24-my-feature-SNAPSHOT`). Use `ws:feature-start-draft` to preview without writing.

### [#work-and-commit](#work-and-commit)Work and commit

Work across repos, commit normally with `git add` / `git commit`. Branch-qualified versions are already set — no manual POM edits needed.

### [#save-a-checkpoint](#save-a-checkpoint)Save a checkpoint

```
mvn ws:checkpoint-publish -Dlabel=progress
```

Tags every subproject at its current HEAD with a checkpoint tag and records the snapshot in a YAML manifest. Not a release — no POM version changes, no Nexus deploy. Useful before risky operations or as a team-visible progress marker. Use `ws:checkpoint-draft` to preview without writing.

### [#preview-the-merge](#preview-the-merge)Preview the merge

```
mvn ws:feature-finish-squash-draft -Dfeature=my-feature
```

Shows what would happen: which branches merge, version changes, tag names. Use `ws:feature-finish-merge-draft` instead if you want a no-fast-forward merge that preserves the feature-branch history.

### [#finish-the-feature](#finish-the-feature)Finish the feature

```
mvn ws:feature-finish-squash-publish -Dfeature=my-feature
```

Merges `feature/my-feature` to `main` with `--no-ff` in every affected component, strips the branch qualifier from POM versions, tags the merge commit, and pushes to origin.

## [#releasing](#releasing)Releasing

### [#preview-the-release-plan](#preview-the-release-plan)Preview the release plan

```
mvn ws:release-draft
```

Output shows which components are release-pending, their version transitions, and the topological release order:

```
[INFO] === Workspace Release Plan (DRY RUN) ===
[INFO] Release-pending components (topo order):
[INFO]   1. ike-platform       1-SNAPSHOT → 1 → 2-SNAPSHOT
[INFO]   2. tinkar-core         1.80.0-SNAPSHOT → 1.80.0 → 1.81.0-SNAPSHOT
[INFO] Cross-reference updates:
[INFO]   tinkar-core: ike-platform parent 1-SNAPSHOT → 1
[INFO] === No changes made (dry run) ===
```

### [#execute-the-release](#execute-the-release)Execute the release

```
mvn ws:release-publish
```

For each release-pending component in dependency order:

1. Strips `-SNAPSHOT` from the version
2. Builds and verifies
3. Tags the release commit
4. Pushes to origin
5. Bumps to next SNAPSHOT version
6. Updates cross-references in downstream POMs

A pre-release checkpoint is created automatically.

## [#multi-machine-development-syncthing](#multi-machine-development-syncthing)Multi-Machine Development (Syncthing)

Syncthing keeps working trees in sync between machines. Git state, build output, and IDE config are per-machine.

### [#generate-ignore-patterns](#generate-ignore-patterns)Generate ignore patterns

```
mvn ws:stignore
```

Writes `.stignore` files that exclude `target/`, `.git/`, `.idea/`, `.DS_Store`, `.claude/worktrees/`, and `.mvn/local-repo/`.

### [#resume-on-another-machine](#resume-on-another-machine)Resume on another machine

Walk away from machine A. Syncthing propagates source files to machine B. On machine B:

```
cd ike-workspace
mvn ws:pull
```

This syncs Git history for all components. `ws:scaffold-init` is Syncthing-aware: if a directory already exists (synced files, but no `.git`), it runs `git init` + `git reset` instead of `git clone`.

## [#troubleshooting](#troubleshooting)Troubleshooting

### [#ws-release-publish-fails-mid-cascade](#ws-release-publish-fails-mid-cascade)`ws:release-publish` fails mid-cascade

Run `mvn ws:release-status` first — it’s read-only and reports what state each subproject is in (release branches, unpushed tags, etc.) plus a recommended recovery step.

The release goal records per-subproject state as it goes, so re-running `mvn ws:release-publish` skips components that were already tagged and deployed and picks up where it left off. For deeper recovery, see `IKE-RELEASE-RECOVERY.md` in the workspace scaffold.

### [#merge-conflict-during-feature-finish](#merge-conflict-during-feature-finish)Merge conflict during `feature-finish`

Resolve the conflict manually in the affected repository, commit the merge resolution, then re-run:

```
mvn ws:feature-finish-squash-publish -Dfeature=my-feature
```

The goal detects already-merged components and skips them.

### [#ws-scaffold-init-on-a-syncthing-directory](#ws-scaffold-init-on-a-syncthing-directory)`ws:scaffold-init` on a Syncthing directory

Handled automatically. When a component directory already exists but has no `.git` directory, `ws:scaffold-init` runs `git init` followed by `git reset` to the manifest branch instead of cloning.

### [#plugin-not-found-ike-goals-fail](#plugin-not-found-ike-goals-fail)Plugin not found: `ike:*` goals fail

Verify that `~/.m2/settings.xml` declares the IKE plugin groups:

```
<pluginGroups>
  <pluginGroup>network.ike.tooling</pluginGroup>
  <pluginGroup>network.ike.docs</pluginGroup>
  <pluginGroup>network.ike.platform</pluginGroup>
</pluginGroups>
```

Also confirm that `ike-maven-plugin` is declared in the workspace `pom.xml`.

### [#updating-the-parent-version-after-an-ike-platform-](#updating-the-parent-version-after-an-ike-platform-)Updating the parent version after an ike-platform release

After a new ike-platform release, the recommended way to pick up the new parent + matching standard properties together is via the scaffold drift workflow (`#345`):

```
mvn ike:scaffold-draft     # report parent + property drift
mvn ike:scaffold-publish   # apply (post-#348)
```

The scaffold zip embeds the foundation versions ike-tooling captured at its release moment — parent, `ike-tooling.version`, `ike-docs.version`, `ike-platform.version` — so a single command bumps everything together.

For specific-version overrides (testing against an older `ike-parent`, partial-cycle rollback), pin the parent version explicitly on `ws:scaffold-publish`:

```
mvn ws:scaffold-draft -DparentVersion=22    # preview cascade pinned to parent 22
mvn ws:scaffold-publish -DparentVersion=22  # apply cascade pinned to parent 22
```

`ws:scaffold-publish` reconciles the `<parent>` reference together with the standard properties in one converged cascade. Omit `-DparentVersion` to take the default cascade (the versions ike-tooling captured at its release moment).

### [#build-warnings-about-bom-imports](#build-warnings-about-bom-imports)Build warnings about BOM imports

There should be zero warnings about BOM imports. If you see them, check for stale `ike-bom` references in dependent POMs. The BOM is auto-generated from `ike-parent` — manual version references can drift after a release.
