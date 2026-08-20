---
date_published: 2026-08-19
date_modified: 2026-08-19
canonical_url: https://ike.network/ike-platform/release-notes.html
---

# Release Notes

## [ike-lease-plugin v3](#ike-lease-plugin-v3)

### [Fixes](#fixes)

- LeaseWatcher.standDown races its own follow-up sweep and closes an already-disposed project ([#1009](https://github.com/IKE-Network/ike-issues/issues/1009)[1])

## [ike-platform v150](#ike-platform-v150)

### [Fixes](#fixes_2)

- depends-on derivation trusts drifted checkouts — re-derive from main-content POMs strips feature-branch edges ([#968](https://github.com/IKE-Network/ike-issues/issues/968)[2])

## [ike-tooling v243](#ike-tooling-v243)

### [Fixes](#fixes_3)

- generated BOM lacks Central-required licenses/developers/scm — PomChecker blocks the first swapped upload (platform 148) ([#967](https://github.com/IKE-Network/ike-issues/issues/967)[3])

## [ike-tooling v242](#ike-tooling-v242)

### [Fixes](#fixes_4)

- async Central deploy bypasses GeneratedBomSwap — ike-bom 145/146 shipped as stubs despite #853 ([#966](https://github.com/IKE-Network/ike-issues/issues/966)[4])

## [ike-platform v145](#ike-platform-v145)

### [Fixes](#fixes_5)

- ws: depends-on re-derivation silently discards manual workspace.yaml edits ([#964](https://github.com/IKE-Network/ike-issues/issues/964)[5])
- ws: depends-on re-derivation emits a manifest that its own goals reject (komet ⇄ komet-claude-plugin cycle) ([#962](https://github.com/IKE-Network/ike-issues/issues/962)[6])

### [Internal](#internal)

- ws: depends-on derivation cannot see plugin-staged artifact references — no bundle edges derived, no cascade reach for artifactItem pins ([#965](https://github.com/IKE-Network/ike-issues/issues/965)[7])
- ike-platform: pdf-theme hardcoded to ike-default in asciidoctor pluginManagement — make it a property ([#655](https://github.com/IKE-Network/ike-issues/issues/655)[8])

## [ike-tooling v241](#ike-tooling-v241)

### [Enhancements](#enhancements)

- ws: add non-build 'bundle' relationship for repository-resolved plugin artifacts ([#963](https://github.com/IKE-Network/ike-issues/issues/963)[9])

### [Internal](#internal_2)

- ike:site-publish should fail when landing-page registration cannot run ([#928](https://github.com/IKE-Network/ike-issues/issues/928)[10])
- Published ike-bom is an empty stub: generate-bom output never attached — Central versions 72-132 manage zero dependencies ([#853](https://github.com/IKE-Network/ike-issues/issues/853)[11])

## [ike-tooling v221](#ike-tooling-v221)

### [Fixes](#fixes_6)

- release-publish: unauthenticated GitHub API rate-limits the last repo of a cascade (403 on milestones) ([#572](https://github.com/IKE-Network/ike-issues/issues/572)[12])
- ike:release-cascade skips downstream repos with stale upstream version-property drift ([#456](https://github.com/IKE-Network/ike-issues/issues/456)[13])

## [ike-platform v108](#ike-platform-v108)

### [Fixes](#fixes_7)

- ws:remove is main-only — make it branch-scoped (mirror ws:add); feature-only subprojects are currently un-removable ([#575](https://github.com/IKE-Network/ike-issues/issues/575)[14])
- Feature-branch version qualification missing: ws:add doesn't apply it; scaffold-publish doesn't self-heal it ([#574](https://github.com/IKE-Network/ike-issues/issues/574)[15])
- ws:switch ignores target-branch membership — feature-only subprojects stranded on main (should stash + park + restore) ([#573](https://github.com/IKE-Network/ike-issues/issues/573)[16])
- ws:scaffold-publish re-adds `!.idea/misc.xml` whitelist, overriding deliberate untracking ([#571](https://github.com/IKE-Network/ike-issues/issues/571)[17])

### [Enhancements](#enhancements_2)

- Harden ws:feature-finish-*-publish against mid-run crashes (front-load version strip; advertise resumability) ([#667](https://github.com/IKE-Network/ike-issues/issues/667)[18])

### [Internal](#internal_3)

- Test harness: FaultableExec + WorkspaceStateAssert + verifyReactor() seam (failure-injection slice of #296) ([#691](https://github.com/IKE-Network/ike-issues/issues/691)[19])
- Epic: ws-plugin correctness & release hygiene (ike-platform v108 / ike-tooling v221) ([#690](https://github.com/IKE-Network/ike-issues/issues/690)[20])
- ws:checkpoint-publish should gate on a reactor compile — don't cut checkpoints that won't build ([#689](https://github.com/IKE-Network/ike-issues/issues/689)[21])
- Installer/ReactorTest builds rebuild STALE subprojects when a pin advances — hardcoded rm -rf cleanup omits newer subprojects + scaffold-init skips dirty clones ([#685](https://github.com/IKE-Network/ike-issues/issues/685)[22])

## [ike-docs v67](#ike-docs-v67)

### [Internal](#internal_4)

- ike-docs: factor topic-ingestion infrastructure into ike-doc-ingest library module ([#550](https://github.com/IKE-Network/ike-issues/issues/550)[23])

## [ike-docs v66](#ike-docs-v66)

### [Internal](#internal_5)

- ike-docs: add ike-network-example deployment tier (sentry-orange) to LintSiteMojo ([#546](https://github.com/IKE-Network/ike-issues/issues/546)[24])

## [ike-platform v95](#ike-platform-v95)

### [Internal](#internal_6)

- ike-platform doc-pipeline: render-pdf executions silently skipped under Maven 4 plugin-merge ordering ([#529](https://github.com/IKE-Network/ike-issues/issues/529)[25])

## [ike-base-parent v13](#ike-base-parent-v13)

### [Internal](#internal_7)

- ike-base-parent v13: restructure <build><plugins> for proper active/managed separation ([#523](https://github.com/IKE-Network/ike-issues/issues/523)[26])

## [ike-tooling v209](#ike-tooling-v209)

### [Internal](#internal_8)

- Landing page left-nav and ike-base-parent README drift from FOUNDATION set; hand-maintained surfaces missing ike-java-support and ike-version-management-extension ([#520](https://github.com/IKE-Network/ike-issues/issues/520)[27])
- Unify visual theme across Maven site, JaCoCo, and Javadoc (currently three different themes per project) ([#518](https://github.com/IKE-Network/ike-issues/issues/518)[28])

## [ike-tooling v208](#ike-tooling-v208)

### [Internal](#internal_9)

- Configure maven-javadoc-plugin <links> for cross-module references across foundation apidocs ([#517](https://github.com/IKE-Network/ike-issues/issues/517)[29])

## [ike-base-parent v10](#ike-base-parent-v10)

### [Internal](#internal_10)

- Release ike-base-parent v10 to propagate ike-java-support v1→v2 canonical pin ([#519](https://github.com/IKE-Network/ike-issues/issues/519)[30])

## [ike-tooling v207](#ike-tooling-v207)

### [Internal](#internal_11)

- Clean up stale release-cascade.yaml content (drop unread version-property data; update X.version comments) ([#516](https://github.com/IKE-Network/ike-issues/issues/516)[31])
- Publish Javadoc on ike-tooling and ike-java-support Maven sites ([#513](https://github.com/IKE-Network/ike-issues/issues/513)[32])

## [ike-java-support v2](#ike-java-support-v2)

### [Internal](#internal_12)

- ike-java-support is missing src/main/cascade/release-cascade.yaml ([#515](https://github.com/IKE-Network/ike-issues/issues/515)[33])

## [ike-tooling v206](#ike-tooling-v206)

### [Internal](#internal_13)

- Landing page polish: Kroki dependency diagram + complete site/README for new foundation members ([#511](https://github.com/IKE-Network/ike-issues/issues/511)[34])
- LandingPageRegistrationReconciler.detect probes a URL that always 404s ([#508](https://github.com/IKE-Network/ike-issues/issues/508)[35])

## [ike-tooling v198](#ike-tooling-v198)

### [Internal](#internal_14)

- Async Maven Central deploy with sentinel-file status tracking ([#484](https://github.com/IKE-Network/ike-issues/issues/484)[36])

## [ike-tooling v196](#ike-tooling-v196)

### [Internal](#internal_15)

- Nexus-first two-phase deploy with retries in ike:release-publish ([#482](https://github.com/IKE-Network/ike-issues/issues/482)[37])
