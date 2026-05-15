---
date_published: 2026-05-14
date_modified: 2026-05-14
canonical_url: https://ike.network/ike-platform/ike-workspace-maven-plugin/cascade-foundation-publish.html
---

# ws:cascade-foundation-publish

Walk the IKE foundation cascade — `ike-tooling → ike-docs → ike-platform` — in topological order. For each repo that has unreleased changes, align upstream-version properties to the latest-released upstreams, then run `ike:release-publish`. Optionally chain `ws:release-publish` on the current workspace after the foundation cascade completes.

ike-issues#375; cascade order made declarative in ike-issues#402.

The cascade order is read from the declarative `release-cascade.yaml` manifest (`ike-build-standards’s `cascade` classified artifact, unpacked by `ike-parent` to `target/release-cascade.yaml`). When the manifest cannot be found, the goal falls back to the built-in `ike-tooling,ike-docs,ike-platform` order. `-Dfoundations=<csv>` still overrides both. To change the cascade permanently, edit `release-cascade.yaml` — not this goal’s source. See ike-issues#402.

## [#tl-dr](#tl-dr)TL;DR

```
# From any workspace, release every foundation that has unreleased
# changes, then release the workspace itself:
mvn ws:cascade-foundation-publish

# Foundations only (skip the workspace half):
mvn ws:cascade-foundation-publish -DskipWorkspace=true

# Override the foundations base directory:
mvn ws:cascade-foundation-publish -Dike.release.cascade.basedir=/path/to/foundations

# Override the cascade order (rare):
mvn ws:cascade-foundation-publish -Dfoundations=ike-tooling,ike-platform
```

Pre-`v44` of `ike-workspace-maven-plugin`, the goal is reachable only via explicit coordinates (see [Bootstrap invocation (during the v44 transition)](#bootstrap-invocation)).

## [#mental-model](#mental-model)Mental model

### [#foundations-are-siblings-not-subprojects](#foundations-are-siblings-not-subprojects)Foundations are siblings, not subprojects

`ike-tooling`, `ike-docs`, and `ike-platform` are **independent git repositories** that release as standalone artifacts. They are intentionally NOT workspace subprojects — putting a foundation inside a workspace triggers a Maven 4 reactor parent-cycle (the foundation is its own parent’s parent, transitively). Each foundation has its own release lifecycle driven by `ike:release-publish`.

This goal does NOT make foundations into subprojects. It locates them by **filesystem convention** and orchestrates their existing single-repo release goals.

### [#the-canonical-layout](#the-canonical-layout)The canonical layout

```
~/ike-dev/
├── ike-tooling/          ← foundation (sibling)
├── ike-docs/             ← foundation (sibling)
├── ike-platform/         ← foundation (sibling)
├── ike-example-ws/       ← workspace ← you invoke from here
│   └── workspace.yaml
└── ike-komet-ws/         ← another workspace (sibling)
    └── workspace.yaml
```

The goal:

1. Resolves the **current workspace** via `resolveManifest()` (the inherited helper that walks up looking for `workspace.yaml`).
2. Uses the **parent of that workspace** as the foundations base directory — `~/ike-dev/` in the canonical layout.
3. For each name in `-Dfoundations=` (default: `ike-tooling, ike-docs,ike-platform`), looks at `<baseDir>/<name>/` and treats it as a foundation repo.

Step 3’s `<baseDir>/<name>/` resolution is overridable. The base directory is the `ike.release.cascade.basedir` property; an individual repo checked out somewhere else is pointed at with the `cascadeRepoDirs` map parameter (see [Local single-process walk vs. CI build chains](#local-vs-ci)).

The workspace is just the **anchor** — the place the goal stands so it knows where to look. The foundations have no membership relationship with the workspace’s `workspace.yaml`. They are discovered by path, not by manifest.

### [#local-single-process-walk-vs-ci-build-chains](#local-single-process-walk-vs-ci-build-chains)Local single-process walk vs. CI build chains

The cascade has two parts, and only one of them is location-bound:

- **Topology** — *which* repos, in *what* order. This lives in `release-cascade.yaml` (keyed off `groupId` + `artifactId`, [shipped by ike-build-standards](../../ike-tooling/ike-build-standards/)[1]). It is environment-neutral and identical everywhere.
- **Execution** — *where* the repos are and *what process* drives them. This is environment-specific.

This goal is the **local, single-process** execution model: one developer, all foundation repos checked out as siblings, one `mvn` invocation walking them. Location resolution is fully property-driven so it adapts to non-standard layouts:

| `ike.release.cascade.basedir` | Base directory holding the foundation checkouts. Defaults to the parent of the current workspace. |
| --- | --- |
| `cascadeRepoDirs` (map parameter) | Per-repo absolute-path overrides for repos that are **not** co-located under the base directory. |

```
<plugin>
  <groupId>network.ike.platform</groupId>
  <artifactId>ike-workspace-maven-plugin</artifactId>
  <configuration>
    <cascadeRepoDirs>
      <ike-tooling>/agent/work/a1/ike-tooling</ike-tooling>
      <ike-docs>/agent/work/b2/ike-docs</ike-docs>
    </cascadeRepoDirs>
  </configuration>
</plugin>
```

On a **CI server (e.g. TeamCity)** the picture is different and this goal is generally **not** used:

- Each foundation repo is its own build configuration with its own VCS checkout — there is no co-located sibling tree to walk.
- The cascade **topology** is mirrored as CI build-chain dependencies (snapshot/artifact dependencies): `ike-docs’s build config depends on `ike-tooling’s, `ike-platform’s on both. `release-cascade.yaml` is the specification you build those edges from — and you need not build them by hand: `ike:cascade-export` emits the topology as JSON or `.properties` for a CI meta-runner to generate the edges from (`mvn ike:cascade-export -Dformat=properties`). See IKE-Network/ike-issues#403.
- Each build config runs the standalone `ike:release-publish`. That goal is **location-independent**: it identifies itself from its own reactor-root POM coordinates and reads the manifest via the `ike.release.cascade.manifest` property (resolvable from the unpacked `cascade` artifact, or a CI-set path). It prints the cascade footer naming the next repo — the signal a finish-build trigger acts on.
- Artifact handoff between stages is via Nexus, not the filesystem: `ike-docs` resolves `ike-tooling’s **released** artifact from the repository, exactly as any consumer would.

In short: the manifest is shared; `ws:cascade-foundation-publish` is the local walker; CI uses build-chain triggers over the same topology. See IKE-Network/ike-issues#402.

### [#cross-workspace-scope](#cross-workspace-scope)Cross-workspace scope

Running from `ike-example-ws` will:

- Release the foundations (shared infrastructure — fine to release from any workspace).
- Release `ike-example-ws` itself (unless `skipWorkspace=true`).

Running from `ike-komet-ws` would:

- Release the foundations again if they have new changes (otherwise skip — see the up-to-date detection below).
- Release `ike-komet-ws` itself (NOT `ike-example-ws`).

**Sibling workspaces never cross-release.** Each workspace’s owner decides when to absorb the new foundations. The mechanism for sibling-workspace propagation is to run this cascade goal from each workspace in turn — or to use `ws:align-publish` (which only bumps property values, doesn’t release).

## [#behavior](#behavior)Behavior

### [#per-foundation-walk](#per-foundation-walk)Per-foundation walk

For each foundation `N` in cascade order:

1. **Locate.** If `<baseDir>/<N>/` doesn’t exist (or lacks `.git` or `pom.xml`), report SKIPPED with the reason. Downstream still resolves `N` from Nexus at its current released version.
2. **Catch-up alignment.** Scan `N/pom.xml` for `<<X>.version>` properties referencing earlier-in-cascade foundations. If any property’s value is older than the latest-released `X` (either this-cycle release tracked in `releasedVersions`, or `X’s tip `v*` tag), update it via OpenRewrite (`PomRewriter.updateProperty`) and commit:
  
  ```
  chore: align upstream versions before release
  ```
  
  The alignment commit is itself a meaningful commit, so a foundation that previously had no source changes will release once an upstream bump catches up — by construction.
3. **Up-to-date check.** If `N` has zero meaningful commits since its latest `v*` tag (release-cadence commits are filtered the same way `ws:release-publish` does), report UP_TO_DATE and skip.
4. **Release.** Run `mvn ike:release-publish` in `N/`. Record the released version so subsequent foundations can align against it.

If a foundation fails, the cascade stops immediately and emits a resume command:

```
Resume after fixing ike-platform:
  cd /Users/kec/ike-dev/ike-platform && mvn ike:release-publish
Then re-run this cascade to continue with the remaining foundations.
```

The cascade is **resumable**: once the failing foundation is fixed, re-running this goal sees the already-released foundations as UP_TO_DATE and resumes at the failed one.

### [#workspace-release-optional](#workspace-release-optional)Workspace release (optional)

After the foundation cascade succeeds, if `skipWorkspace` is `false` (the default), the goal runs `mvn ws:release-publish` on the current workspace. `ws:release-publish` has its own catch-up alignment (see `WsReleaseDraftMojo.updateParentVersions`) which folds the just-released foundation versions into the workspace’s subprojects on the way to their releases.

If `skipWorkspace=true`, only the foundations release. The current workspace stays at its current state — operator runs `ws:release-publish` (or another cascade) on their own schedule.

## [#parameters](#parameters)Parameters

| Property | Default | Notes |
| --- | --- | --- |
| `foundations` | `ike-tooling,ike-docs,ike-platform` | Comma-separated names in topological order. Override only when testing or when the canonical foundation set changes. |
| `foundationsDir` | *parent of workspace* | Directory containing the foundation repos. Standard `~/ike-dev/<name>/` layout uses the parent of the invoking workspace; override for non-standard layouts. |
| `skipWorkspace` | `false` | When `true`, runs the foundation cascade only and skips the workspace’s own `ws:release-publish` at the end. |
| `pushRelease` | `true` | Forwarded to each `ike:release-publish` invocation (and to the chained `ws:release-publish`). Pass `false` for a local-only dry-run that stops before pushing tags. |

## [#composition-with-other-goals](#composition-with-other-goals)Composition with other goals

| Goal | When to use it instead |
| --- | --- |
| `ike:release-publish` (in a foundation dir) | Releasing a single foundation in isolation, with no cascade. Used manually when only one foundation has changes and you don’t want the workspace release. |
| `ws:release-publish` (in a workspace) | Releasing the workspace’s subprojects assuming foundations are already at the desired versions. This is what `cascade-foundation-publish` chains to internally when `skipWorkspace=false`. |
| `ws:align-publish` (in a workspace) | Bumping upstream `<X.version>` properties to current released versions WITHOUT releasing anything. Useful for "absorb new foundations into this workspace without cutting a workspace release right now." |
| `ike:verify-release-published` | Post-release verification. Run after the cascade to confirm Nexus + gh-pages + GitHub release + org-site are all reachable. See [ike-tooling/ike-maven-plugin docs](https://ike.network/ike-tooling/ike-maven-plugin/index.html#verify-release-published)[2]. |

## [#bootstrap-invocation-during-the-v44-transition](#bootstrap-invocation-during-the-v44-transition)Bootstrap invocation (during the v44 transition)

The plugin prefix form `mvn ws:cascade-foundation-publish` resolves the plugin version through `ike-parent’s `<pluginManagement>`. Until your workspace’s `ike-parent` is at v44 or later, the prefix form will fail with:

```
Could not find goal 'cascade-foundation-publish' in plugin
network.ike.platform:ike-workspace-maven-plugin:43 ...
```

Until then, invoke with explicit coordinates to bypass the prefix resolution:

```
mvn network.ike.platform:ike-workspace-maven-plugin:44:cascade-foundation-publish
```

After your workspace consumes `ike-parent` v44 (typically via the next foundation cascade), the prefix form just works.

## [#failure-modes](#failure-modes)Failure modes

### [#pre-flight-test-failures-inside-a-foundation](#pre-flight-test-failures-inside-a-foundation)Pre-flight test failures inside a foundation

`ike:release-publish` runs `mvn clean install` as its pre-flight verify step. Any test failure here halts the foundation’s release before any tag or commit ships. The cascade reports the foundation as FAILED and exits. The remaining foundations (and the workspace release, if not skipped) do not run.

Recovery: fix the test, then re-run this cascade. Already-released foundations report UP_TO_DATE and the cascade resumes at the previously-failed one.

### [#catch-up-alignment-found-no-upstream-to-align-agai](#catch-up-alignment-found-no-upstream-to-align-agai)Catch-up alignment found no upstream to align against

If a foundation references an upstream version property like `<ike-tooling.version>` but the cascade can find neither a this-cycle release nor a `v*` tag on the upstream repo, the property is **left alone** and a debug log line is emitted. This is the right behavior — we don’t invent versions.

A more likely cause: the upstream foundation is missing from disk (not checked out). The cascade walk reports it as SKIPPED earlier and the alignment falls back to whatever the upstream’s tip tag is.

### [#worktree-not-clean-failure](#worktree-not-clean-failure)Worktree-not-clean failure

If a foundation has uncommitted changes when the cascade reaches it, `ike:release-publish’s preflight rejects with an "uncommitted changes" message. Recovery: commit or stash the changes in that foundation, then re-run.

## [#see-also](#see-also)See also

- [ws:* Goal Reference](ws-goals.html)[3] — the full goal table.
- [Workspace Lifecycle](workspace-lifecycle.html)[4] — narrative tour of how the goals fit together.
- [Cutting a Release](https://ike.network/ike-platform/cutting-a-release.html)[5] — the operator runbook covering the full release flow.
- `ws:release-publish` — within-workspace cascade.
- `ike:release-publish` — single-repo release.
- `ws:align-publish` — property alignment without releasing.
