---
date_published: 2026-08-21
date_modified: 2026-08-21
canonical_url: https://ike.network/ike-platform/ike-workspace-maven-plugin/workspace-version-resolution.html
---

# Workspace Version Resolution

This page is the durable design record for how a working set decides which version an intra-set dependency binds to — the competition between version-aligned snapshot modules co-built in the reactor and released modules with the same group and artifact available from a repository. Settled 2026-08-16; tracked as IKE-Network/ike-issues#1019. For the release cycle this rule serves, see [the ws:* goal reference](ws-goals.html)[1]; for the day-to-day states, see [the workspace lifecycle](workspace-lifecycle.html)[2].

## [#the-competition](#the-competition)The competition

A working set deliberately makes its BOM a living member: komet-bom is both the version authority its members import and an artifact the set releases. Maven 4 tolerates a reactor-internal BOM import with a named model problem (`bom-import-from-reactor`, accepted by design in the build-report ledger, one occurrence per importer), but the tolerance has a cost with two faces:

- An intra-set dependency declared without a version — its version managed by the co-built BOM — has no version at model-building time. The reactor sorter matches dependencies against reactor members by full identity (group, artifact, version), so it creates no producer-before-consumer edge, and the two modules sort arbitrarily.
- Resolution of such a dependency then succeeds or fails by luck of the local repository: a previously built version masks the missing edge until the first build that needs a version existing nowhere yet — which is precisely what a release cycle creates.

Both shapes of the competition are legitimate and coexist in one reactor. The komet framework module must bind to the co-built OWL extension; the Komet Claude plugin correctly imports the **released** komet-bom from the repository beside the co-built snapshot. Identity matching by group and artifact alone would invent false edges; the decision must be made per artifact, by rule.

## [#the-rule](#the-rule)The rule

| Mode | Binding |
| --- | --- |
| **Build** (development) | Every intra-set dependency binds to the reactor’s snapshot — always align with snapshots. |
| **Release**, module unchanged | Consumers bind to the module’s **released version** — the module is a bystander; nothing about it is being republished. |
| **Release**, module changed | Consumers bind to the **reactor’s current version** — the release basis. Phrased this way so the rule is correct both at development snapshots and mid-cycle, when the version pass has already set release versions. |

"Changed" is decided by manifest state plus release detection, never by raw git status alone: a tag-aligned member is unchanged **by declaration**, whatever its checkout holds.

## [#requirements](#requirements)Requirements

The requirements were surfaced empirically by the komet working set’s first three release cycles (2026-08-14, cycles `ike-komet-wsr-1` through `-3`).

| Requirement | Statement | Evidence |
| --- | --- | --- |
| R1 Ordering completeness | Every dependency satisfied by a co-built member yields a reactor edge. | Cycle 3 failed 16 modules into its verify: the framework → OWL extension edge was absent, and the consumer built first against a version that existed nowhere yet. |
| R2 Per-artifact binding | Snapshot-vs-released is decided per artifact, never globally. | One reactor legitimately held both shapes at once (see above). |
| R3 Model-time truth | Version truth exists when effective models are built — where the sorter and resolver consume it. | Goal-time POM rewriting is too late for ordering, and episodic rewriting is where the defect cluster lived. |
| R4 Release-set awareness | The changed-plus-cascade versus bystander distinction is the rule’s input. | The selective release model, settled on IKE-Network/ike-issues#997. |
| R5 Repository-true deployment | Deployed POMs carry literal released versions. | Maven 4 flattens consumer POMs; the SNAPSHOT-leak preflights guard exactly this. |
| R6 Upstream-canonical members | No working-set concern is exported into member POMs. | Members are upstream-shaped repositories; the BOM-as-member design stands (IKE-Network/ike-issues#977). |
| R7 No magic | The same build works locally and on CI, and the resolution is printed, never silent. | A release must always be runnable locally — a reliable Maven build, nothing more. |
| R8 Anti-drift | No maintained artifact whose silent decay changes semantics. | Declaration-order tie-breaking was rejected on exactly this ground. |
| R9 Pinned members by declaration | Tag-aligned means unchanged, from manifest state. | The pre-release alignment walk once mutated a pinned member; the rule must not repeat that class. |

## [#the-solution](#the-solution)The solution

The workspace extension — already registered in `.mvn/extensions.xml` and already participating before model validation — computes the binding for every intra-set dependency during model building, before the reactor sorter runs. With full identities present, the sorter’s edges are complete (R1), and binding follows the rule (R2) rather than repository luck.

**Goals compute; the extension applies.** Release detection walks git history and must not run on every build. A development build needs no plan at all — the rule degenerates to "all snapshots." A release cycle signals release mode and hands the extension its computed plan; the extension applies it and prints the resolution table it used.

The handoff contract is four structures, all already computed by the release machinery today:

1. a map of every artifact the reactor produces to its producing member — including sub-module artifacts, which is what lets a consumer of the OWL extension find tinkar-core;
2. the set of members with changes to release (detection plus cascade);
3. the version to use for an artifact that is **not** releasing — its last released version, sourced from the previous cycle record (`releases/release-<cycle>.yaml`), the same baseline release notes build from;
4. the version to use for an artifact that **is** releasing — the release plan’s value.

**Scope of the first increment: dependency binding only.** The release cycle keeps writing member versions, so a tagged tree remains self-describing, and the settled post-bump semantics — references settle at released values — stay untouched.

**Deferred, explicitly:** computed member versions (writing no versions into POMs at all). It would structurally remove the in-flight rollback cost tracked on IKE-Network/ike-issues#1010, but it changes what a tagged tree says about itself, and is its own future settlement.

**Graph hygiene:** the depends-on derivation keeps pin-edges distinct from build-edges; komet-bom pins rocks-kb’s version while rocks-kb imports komet-bom, and conflating the edge kinds would read that as a cycle.

## [#what-this-subsumes](#what-this-subsumes)What this subsumes

- The bystander backward-pinning increment left open on the reactor-pass design (a releasing member referencing an out-of-set member’s released version) is the "unchanged → released" rule applied at model time — absorbed here.
- The accepted `bom-import-from-reactor` model-problem warnings and the missing sorter edges are two faces of one root. With the extension supplying versions, the accepted-warning count becomes a ratchet candidate: a design cost repaid.

## [#decision-log](#decision-log)Decision log

| Date | Decision |
| --- | --- |
| 2026-08-11 | Reactor-pass release model settled: one cycle from the workspace root, the reactor as the coherence mechanism (IKE-Network/ike-issues#997). |
| 2026-08-14 | Release cycle pins every member’s commit into the tagged manifest, so a release tag describes a buildable working set (IKE-Network/ike-issues#1017). |
| 2026-08-15 | Reactor-ordering blind spot diagnosed; declaration-order tie-breaking validated empirically, then rejected as brittle (IKE-Network/ike-issues#1018). |
| 2026-08-16 | This design settled: the extension computes intra-set dependency binding by the build/release rule above (IKE-Network/ike-issues#1019). |
