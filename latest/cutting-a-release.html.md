---
date_published: 2026-08-16
date_modified: 2026-08-16
canonical_url: https://ike.network/ike-platform/cutting-a-release.html
---

# Cutting a release

How to ship a release of an IKE Network repo or a full workspace cascade. Read this before your first release; skim before each subsequent one — the gotchas at the bottom are what bite returning operators.

This page is the **public release how-to** — the goals, what they do, and how to verify. The **maintainer operations** layer — the TeamCity foundation cascade, the `op`/1Password credential flow, signing-agent provisioning, and hands-off monitoring — lives in `ike-infrastructure` (`release-operations.adoc`), because it carries internal URLs, agent names, and secret paths that do not belong on the public site.

## [#run-modes--local-or-ci](#run-modes--local-or-ci)Run modes — local or CI

Any release runs **two ways** behind the same engine (`ike:release-publish` / `ws:release-publish`) with the same irreversible outcome (tag + Nexus + Maven Central). Pick per situation:

| Mode | When |
| --- | --- |
| **Local** — run the goal at the repo (or workspace) root | Hands-on, a single repo, or when CI is unavailable; you watch the console live. Deploy credentials are supplied at launch (one approval, then it runs unattended). |
| **CI** — trigger the matching release build on the CI server | Hands-off, the full foundation cascade, or releasing without a local checkout. The cascade fans out to downstream repos automatically. See `ike-infrastructure` for the trigger mechanics. |

**When asked to release, decide local-or-CI first** — both must work, and the choice changes nothing about the artifact, only where the build runs.

## [#three-flavors](#three-flavors)Three flavors

| Flavor | When |
| --- | --- |
| Single-repo release (`mvn ike:release-publish`) | You’re inside one repo and want to ship just that repo’s artifact. Used for one-off promotions of a single workspace subproject, or when a single foundation repo has changes that need to ship in isolation. |
| Foundation cascade (`mvn ike:release-cascade`) | From any foundation repo, walk `ike-tooling → ike-docs → ike-platform` as sibling checkouts and release each one that has unreleased changes. The order is assembled from each repo’s own `src/main/cascade/release-cascade.yaml`; `ike:release-publish` aligns each repo to its upstreams before cutting its release. ike-issues#375. |
| Workspace cascade (`mvn ws:release-publish`) | You’re inside a workspace aggregator (a `-ws` repo) and want to release every subproject whose source has changed since its last tag, in topological order. Each subproject runs through `ike:release-publish` internally; the workspace root tags itself last so the mission has a single anchor. |

## [#cascade-order-across-the-ike-network](#cascade-order-across-the-ike-network)Cascade order across the IKE Network

```
ike-tooling  →  ike-docs  →  ike-platform  →  { downstream consumers }
```

Always upstream-first. `ike-docs` consumes `ike-tooling’s `ike-maven-plugin`. `ike-platform’s `ike-parent` consumes both. Downstream consumer repos (workspace aggregators, doc-only projects, ike-example-its) inherit from `ike-parent`. Every release of an upstream causes a property bump in everything downstream — that bump is what triggers a downstream release in the same mission.

This cascade is **structural**, not driven by extension-realm timing. See [Design rationale](index.html#design_rationale)[1] on the overview page for why no plugin in this ecosystem uses `<extensions>true</extensions>`.

## [#what-a-publish-does-to-your-version-pins-the-auto-](#what-a-publish-does-to-your-version-pins-the-auto-)What a publish does to your version pins (the auto-upgrade)

`ike:release-publish` **rewrites your `*GA*` upstream version pins to the latest released upstream before it cuts the release** — the "align upstream cascade versions" step (B8). This is deliberate: it guarantees a single-repo release never ships against a stale foundation. You do not need to bump `network.ike.tooling*GA*ike-tooling__VERSION` (and friends) by hand before releasing — the publish does it, commits the bump with a message naming each upgrade (`release: align upstream cascade — ike-tooling 221→222, ike-docs 75→76`), and surfaces a **Foundation upgrades** section in the GitHub release notes so a cascade-only rebuild announces what it was rebuilt against rather than "no changes" (IKE-Network/ike-issues#706).

The bump respects each upstream’s declared **policy**: `integrate` (the default) and `release` are auto-aligned; `notify`, `verify`, and `propose` are hand-gated and left for you to act on. `ike:release-draft` previews exactly which pins a publish would raise.

The "latest released" lookup uses **fresh metadata** — a cache-less resolve — so a stale local cache (or a just-deployed upstream that Nexus hasn’t finished indexing) can’t silently leave a pin behind (IKE-Network/ike-issues#705).

## [#the-coherence-gate](#the-coherence-gate)The coherence gate

After the Nexus deploy and **before** the tag is pushed, `ike:release-publish` confirms its own just-published artifact actually resolves — cold, from a cache-less resolver — at the demanded **resolution scope** (`ike.resolutionScope`, default `nexus`):

| Scope | Confirms the artifact in |
| --- | --- |
| `nexus` *(default)* | The shared, consumer-resolvable Nexus group — the source of truth a downstream’s "resolve latest" actually reads. The cross-repo cascade guarantee. |
| `central` | Maven Central — the public-availability gate (a bounded, non-blocking poll, since Central is eventually consistent). |
| `local` | The local cache only. Verifies nothing (the build’s own `install` put it there) — a draft/dev opt-out, **rejected for a publish**. |

If the artifact does not resolve at the demanded scope, the build **fails before the tag is pushed** — so no tag, no GitHub release, and no downstream cascade fires. A coherence problem becomes a red build on the responsible repo, never a silently-wrong downstream. The gate also re-checks that this repo’s own auto-aligned upstream pins caught up to the latest released upstream (IKE-Network/ike-issues#705).

The order above is not maintained by hand, and not maintained centrally. Each foundation repo version-controls its own `src/main/cascade/release-cascade.yaml` declaring only its own `upstream` and `downstream` edges; the full ordered graph is assembled by traversal (IKE-Network/ike-issues#420). `ike:release-draft` previews the downstream repos a release will make stale; `ike:release-publish` aligns upstream `${X.version}` pins and prints a footer naming the next cascade step; `ike:release-cascade` assembles the graph and walks it end to end. To change the cascade, edit the relevant repo’s own manifest.

## [#before-a-cascade--scope-by-coherence-not-jar-linka](#before-a-cascade--scope-by-coherence-not-jar-linka)Before a cascade — scope by coherence, not jar-linkage

A release ships a **coherent versioned state: code + standards  
docs.** A change in `ike-build-standards` (the `claude`/`docs`/`scaffold` bundles — `IKE-WORKSPACE.md`, `MAVEN.md`, scaffold templates) is a real versioned change: `ike-parent` pins the **GA** build-standards version and unpacks it at `validate`, so a new standard reaches consumers **only after a tooling release bumps that pin down the cascade.** Do not scope a release by "does the code link against it?" — scope by coherence. Shipping platform code whose behavior its own embedded standard contradicts is an incomplete release.

Before firing a cascade, check **every** foundation repo for unreleased changes since its last `vN` tag. A clean post-release repo shows only machinery commits (`post-release: bump…`, `merge: release…`, `release: restore source pom state`); anything else is a real change that must ship:

```
for repo in ike-base-parent ike-java-support ike-version-management-extension \
            ike-workspace-extension ike-tooling ike-docs ike-platform; do
  [ -d "$repo/.git" ] || continue
  git -C "$repo" fetch -q origin --tags
  tag=$(git -C "$repo" tag -l 'v*' | sort -V | tail -1)
  echo "=== $repo (last $tag) ==="
  git -C "$repo" log --oneline "$tag"..origin/main   # ignore machinery commits
done
```

Then trigger the **lowest cascade node that covers every changed repo** — the finish-triggers carry it downstream so all consumers re-release and re-pin coherently. A `ike-build-standards` change ⇒ start at the `ike-tooling` release, not `ike-platform`. When in doubt, go one node lower.

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
# Foundation cascade, end to end (from any foundation repo):
mvn ike:release-cascade

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

After every subproject in the cascade tags, the workspace root itself tags — anchoring the mission to a single commit on the workspace’s main. Don’t be surprised by the extra commit at the end. It’s intentional (ike-issues#326, #328).

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
| Preflight fails on `WORKING_TREE_CLEAN` | Commit (or stash) the listed changes. `mvn ws:commit-publish -Dmessage=…​` on the workspace, or `git commit` in the listed repo. |
| Preflight fails on `NO_ON_DISK_GHPAGES_LEAK` | `rm -rf <listed directories>`. Safe — see the gotcha above. |
| `mvn site:stage` fails with "untracked content" error | You’re hitting ike-issues#358. Run the on-disk leak detection above and delete the leak directory. |
| Nexus deploy fails with auth error | Check `~/.m2/settings.xml` for current credentials. Bouncy Castle GPG signing is wired through the same settings. |
| GitHub Pages publishes but ike.network landing page didn’t update | Auto-register may have hit a transient git lock or network issue. From a checkout of the release tag, run `mvn ike:register-site-publish`. The release itself is complete; this is just the org-site sync. |
| `git checkout main` fails after a release | Your worktree has uncommitted changes (likely something a tool wrote mid-flight). Commit or stash them, then push the release tag + main, then create the GitHub release manually: `gh release create v<N> --title <N> --generate-notes --verify-tag`. See feedback_release_roll_forward in the operator memory. |

## [#tests-run-during-the-release--keep-sandboxes-herme](#tests-run-during-the-release--keep-sandboxes-herme)Tests run during the release — keep sandboxes hermetic

The release verify (`mvn clean install`) runs the full suite. Any test that shells out to real `git` **must not inherit host git config** — a CI agent with a failing global `core.hooksPath` hook, `commit.gpgsign` without a key, or a different `core.excludesFile` will break the release verify on that agent but not on yours. Make the sandbox hermetic (`GIT_CONFIG_GLOBAL` → a checked-in test gitconfig, `GIT_CONFIG_NOSYSTEM=1`, per-repo empty `core.hooksPath`  
`gpgsign=false`); mirror `TestWorkspaceHelper.configureHermetic`. Builds must be machine-independent (IKE-Network/ike-issues#560).

## [#see-also](#see-also)See also

- The [release cascade model](https://ike.network/ike-tooling/ike-maven-plugin/release-cascade.html)[6] — the loosely-coupled, manifest-per-repo design behind the cascade order above (authored in `ike-tooling`).
- **Maintainer operations** (in `ike-infrastructure`, `release-operations.adoc`) — the TeamCity foundation cascade, REST triggers, `op`/1Password credential flow, signing-agent provisioning, hands-off monitoring, and the Maven Central catch-up procedure.
- [Workspace getting started](workspace-getting-started.html)[7] — setting up a `-ws` aggregator the first time.
- [ike-maven-plugin docs](https://ike.network/ike-tooling/ike-maven-plugin/)[8] — single-repo release goal.
- [Self-host bootstrap pattern](https://ike.network/ike-tooling/ike-maven-plugin/self-host-bootstrap.html)[9] — why `ike-tooling’s own release has the extra X-SNAPSHOT step.
- [Issue tracker](https://github.com/IKE-Network/ike-issues)[10] — release-flow bugs and enhancement tracking.
