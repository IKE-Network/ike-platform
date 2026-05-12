---
date_published: 2026-05-11
date_modified: 2026-05-11
canonical_url: https://ike.network/ike-platform/cutting-a-release.html
---

# Cutting a release

How to ship a release of an IKE Network repo or a full workspace cascade. Read this before your first release; skim before each subsequent one — the gotchas at the bottom are what bite returning operators.

## [#three-flavors](#three-flavors)Three flavors

| Flavor | When |
| --- | --- |
| Single-repo release (`mvn ike:release-publish`) | You’re inside one repo and want to ship just that repo’s artifact. Used for one-off promotions of a single workspace subproject, or when a single foundation repo has changes that need to ship in isolation. |
| Foundation cascade (`mvn ws:cascade-foundation-publish`) | From any workspace, walk `ike-tooling → ike-docs → ike-platform` as siblings of the workspace and release each one that has unreleased changes, then release the workspace itself. Skips foundation repos that are up-to-date or not checked out. Pass `-DskipWorkspace=true` for foundation-only (rare case). ike-issues#375. |
| Workspace cascade (`mvn ws:release-publish`) | You’re inside a workspace aggregator (a `-ws` repo) and want to release every subproject whose source has changed since its last tag, in topological order. Each subproject runs through `ike:release-publish` internally; the workspace root tags itself last so the cycle has a single anchor. |

## [#cascade-order-across-the-ike-network](#cascade-order-across-the-ike-network)Cascade order across the IKE Network

```
ike-tooling  →  ike-docs  →  ike-platform  →  { downstream consumers }
```

Always upstream-first. `ike-docs` consumes `ike-tooling’s `ike-maven-plugin`. `ike-platform’s `ike-parent` consumes both. Downstream consumer repos (workspace aggregators, doc-only projects, ike-example-its) inherit from `ike-parent`. Every release of an upstream causes a property bump in everything downstream — that bump is what triggers a downstream release in the same cycle.

This cascade is **structural**, not driven by extension-realm timing. See [Design rationale](index.html#design_rationale)[1] on the overview page for why no plugin in this ecosystem uses `<extensions>true</extensions>`.

## [#wall-clock-budget](#wall-clock-budget)Wall-clock budget

Plan for **30–45 minutes** for a full foundation-plus-consumer cascade. The dominant time costs:

- Per-repo `mvn clean install` + `mvn site site:stage` runs.
- GitHub Pages publishes (gh-pages branch force-push per repo).
- Org-site auto-registration on each release ([ike.network/](https://ike.network/)[2] landing page rebuild).
- Nexus deploys with GPG signing (Bouncy Castle).

Single-repo releases of a foundation repo are typically **5–10 minutes** end to end.

## [#the-standard-sequence](#the-standard-sequence)The standard sequence

### [#1-preflight-read-only](#1-preflight-read-only)1. Preflight (read-only)

Run from inside a workspace aggregator:

```
mvn ws:release-draft           # what would happen
mvn ws:overview                # what cascade impact looks like
```

Or from inside a single repo:

```
mvn ike:release-status         # current/in-flight release state
mvn ike:release-draft          # what the release will do
```

The draft writes a markdown report and makes no on-disk changes. Both gate variants run the full preflight check set (`WORKING_TREE_CLEAN`, `NO_ON_DISK_GHPAGES_LEAK`, `NO_SNAPSHOT_PROPERTIES`, `PARENT_COHERENCE`, etc.) so a green draft means a green publish on the same workspace state.

### [#2-publish](#2-publish)2. Publish

```
# Foundation + workspace, end to end (default behavior):
mvn ws:cascade-foundation-publish

# Foundation only (skip workspace release):
mvn ws:cascade-foundation-publish -DskipWorkspace=true

# Workspace cascade (foundations assumed already released):
mvn ws:release-publish

# Single repo (foundation or workspace subproject):
mvn ike:release-publish
```

All goals are resumable: on transient failure (network blip, Nexus timeout, pre-commit hook failure) re-run the same command and already-shipped phases are skipped.

### [#3-verification](#3-verification)3. Verification

Run from the released repo:

```
mvn ike:verify-release-published          # uses pom defaults
mvn ike:verify-release-published -DprojectId=ike-tooling -Dversion=163
```

The goal hits six public-URL targets in one pass and reports green/red for each. Exits non-zero on any failure so it composes into release scripts and CI. ike-issues#374.

| Target | What it confirms |
| --- | --- |
| `[https://ike.network/<repo>/](https://ike.network/<repo>/)[3]` | Current release served at the root path. |
| `[https://ike.network/<repo>/<N>/](https://ike.network/<repo>/<N>/)[4]` | Version-pinned snapshot for the just-released `<N>`. |
| `[https://ike.network/<repo>/latest/](https://ike.network/<repo>/latest/)[5]` | Mirror of the just-released `<N>` (auto-updated by ike-issues#303). |
| `[https://ike.network/](https://ike.network/)[2]` | Org landing page (auto-updated by ike-issues#367’s wiring of `ike:register-site-publish`). |
| Nexus | Artifact at the released version is resolvable. |
| GitHub release | Tag `v<N>` with auto-generated notes. |

## [#gotchas](#gotchas)Gotchas

### [#workspace-root-release-runs-last-in-ws-release-pub](#workspace-root-release-runs-last-in-ws-release-pub)Workspace-root release runs LAST in `ws:release-publish`

After every subproject in the cascade tags, the workspace root itself tags — anchoring the cycle to a single commit on the workspace’s main. Don’t be surprised by the extra commit at the end. It’s intentional (ike-issues#326, #328).

The workspace root release is gated on `hasUnreleasedWorkspaceChanges(root)` — it only runs if the workspace has its own meaningful commits since its last tag.

### [#foundation-repos-release-outside-the-workspace](#foundation-repos-release-outside-the-workspace)Foundation repos release outside the workspace

`ike-tooling`, `ike-docs`, `ike-platform` are intentionally **not** workspace subprojects. They release from their own checkouts via `mvn ike:release-publish`. Workspaces consume their released versions from Nexus. If you accidentally include one as a workspace subproject you’ll trigger a Maven 4 reactor parent-cycle.

### [#site-url-conventions-are-post-304-only](#site-url-conventions-are-post-304-only)Site URL conventions are post-#304 only

Pre-#304 the platform used `scpexe://proxy/srv/ike-site/…​` site URLs. Post-#304 the canonical site distribution is GitHub Pages served at `[https://ike.network/<repo>/](https://ike.network/<repo>/)[3]` via the org CNAME. A `<site>` URL with a `scpexe://` scheme on a new module is a release-blocker — the wagon is no longer wired up.

A preflight check that fails on `scpexe://` in any `<site>` URL is a tracked followup. Until it lands, scan new modules manually with `grep -r 'scpexe:' .`.

### [#on-disk-gh-pages-leak-ike-issues358](#on-disk-gh-pages-leak-ike-issues358)On-disk gh-pages leak (ike-issues#358)

The release flow’s `mvn site site:stage` step has a long-standing quirk where some renders escape `target/` and land at `<projectDir>/<artifactId>/<artifactId>/…​`. `.gitignore` blocks the commit, and `PreflightCondition.NO_ON_DISK_GHPAGES_LEAK` detects the on-disk presence regardless of git state. If the preflight flags one, just delete the leak directory — the canonical published content lives on `gh-pages` of each repo and the leak is regenerated content with no source-of-truth status.

Root cause analysis is still open on #358; the preflight detection is the operational safety net.

### [#resumable-not-idempotent-at-the-seam](#resumable-not-idempotent-at-the-seam)Resumable, not idempotent at the seam

If a release fails mid-cascade, the partial state on disk is:

- Released subprojects: tagged, deployed, gh-pages pushed.
- Failed subproject: depends on where it failed (preflight, pre-flight site, Nexus deploy, GitHub release creation).
- Workspace root: may have catch-up alignment commits accumulated but not yet released.

Re-run the same command. The `meaningfulCommitsSinceTag` filter (ike-issues#347) recognizes the cadence commits from a previous successful subproject and skips re-releasing it.

### [#dont-git-add--a-cascade-bump-commits](#dont-git-add--a-cascade-bump-commits)Don’t `git add -A` cascade bump commits

Manually-staged cascade bump commits in the past swept rendered gh-pages output into main (ike-issues#358 history). Always stage specific files:

```
git add pom.xml
git commit -m "chore: bump ike-parent N -> N+1"
```

The release flow’s own commits already use explicit paths; the risk is in human-typed catch-up commits.

## [#if-something-goes-wrong](#if-something-goes-wrong)If something goes wrong

| Symptom | First move |
| --- | --- |
| Preflight fails on `WORKING_TREE_CLEAN` | Commit (or stash) the listed changes. `mvn ws:commit -Dmessage=…​` on the workspace, or `git commit` in the listed repo. |
| Preflight fails on `NO_ON_DISK_GHPAGES_LEAK` | `rm -rf <listed directories>`. Safe — see the gotcha above. |
| `mvn site:stage` fails with "untracked content" error | You’re hitting ike-issues#358. Run the on-disk leak detection above and delete the leak directory. |
| Nexus deploy fails with auth error | Check `~/.m2/settings.xml` for current credentials. Bouncy Castle GPG signing is wired through the same settings. |
| GitHub Pages publishes but ike.network landing page didn’t update | Auto-register may have hit a transient git lock or network issue. From a checkout of the release tag, run `mvn ike:register-site-publish`. The release itself is complete; this is just the org-site sync. |
| `git checkout main` fails after a release | Your worktree has uncommitted changes (likely something a tool wrote mid-flight). Commit or stash them, then push the release tag + main, then create the GitHub release manually: `gh release create v<N> --title <N> --generate-notes --verify-tag`. See feedback_release_roll_forward in the operator memory. |

## [#see-also](#see-also)See also

- [Workspace getting started](workspace-getting-started.html)[6] — setting up a `-ws` aggregator the first time.
- [ike-maven-plugin docs](https://ike.network/ike-tooling/ike-maven-plugin/)[7] — single-repo release goal.
- [Self-host bootstrap pattern](https://ike.network/ike-tooling/ike-maven-plugin/self-host-bootstrap.html)[8] — why `ike-tooling’s own release has the extra X-SNAPSHOT step.
- [Issue tracker](https://github.com/IKE-Network/ike-issues)[9] — release-flow bugs and enhancement tracking.
