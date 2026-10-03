---
date_published: 2026-10-02
date_modified: 2026-10-02
canonical_url: https://ike.network/ike-platform/release-notes.html
---

# Release Notes

## ike-lease-plugin v3

### Fixes

- LeaseWatcher.standDown races its own follow-up sweep and closes an already-disposed project ([#1009](https://github.com/IKE-Network/ike-issues/issues/1009))

## ike-platform v150

### Fixes

- depends-on derivation trusts drifted checkouts — re-derive from main-content POMs strips feature-branch edges ([#968](https://github.com/IKE-Network/ike-issues/issues/968))

## ike-tooling v243

### Fixes

- generated BOM lacks Central-required licenses/developers/scm — PomChecker blocks the first swapped upload (platform 148) ([#967](https://github.com/IKE-Network/ike-issues/issues/967))

## ike-tooling v242

### Fixes

- async Central deploy bypasses GeneratedBomSwap — ike-bom 145/146 shipped as stubs despite #853 ([#966](https://github.com/IKE-Network/ike-issues/issues/966))

## ike-platform v145

### Fixes

- ws: depends-on re-derivation silently discards manual workspace.yaml edits ([#964](https://github.com/IKE-Network/ike-issues/issues/964))
- ws: depends-on re-derivation emits a manifest that its own goals reject (komet ⇄ komet-claude-plugin cycle) ([#962](https://github.com/IKE-Network/ike-issues/issues/962))

### Internal

- ws: depends-on derivation cannot see plugin-staged artifact references — no bundle edges derived, no cascade reach for artifactItem pins ([#965](https://github.com/IKE-Network/ike-issues/issues/965))
- ike-platform: pdf-theme hardcoded to ike-default in asciidoctor pluginManagement — make it a property ([#655](https://github.com/IKE-Network/ike-issues/issues/655))

## ike-tooling v241

### Enhancements

- ws: add non-build 'bundle' relationship for repository-resolved plugin artifacts ([#963](https://github.com/IKE-Network/ike-issues/issues/963))

### Internal

- ike:site-publish should fail when landing-page registration cannot run ([#928](https://github.com/IKE-Network/ike-issues/issues/928))
- Published ike-bom is an empty stub: generate-bom output never attached — Central versions 72-132 manage zero dependencies ([#853](https://github.com/IKE-Network/ike-issues/issues/853))

## ike-tooling v221

### Fixes

- release-publish: unauthenticated GitHub API rate-limits the last repo of a cascade (403 on milestones) ([#572](https://github.com/IKE-Network/ike-issues/issues/572))
- ike:release-cascade skips downstream repos with stale upstream version-property drift ([#456](https://github.com/IKE-Network/ike-issues/issues/456))

## ike-platform v108

### Fixes

- ws:remove is main-only — make it branch-scoped (mirror ws:add); feature-only subprojects are currently un-removable ([#575](https://github.com/IKE-Network/ike-issues/issues/575))
- Feature-branch version qualification missing: ws:add doesn't apply it; scaffold-publish doesn't self-heal it ([#574](https://github.com/IKE-Network/ike-issues/issues/574))
- ws:switch ignores target-branch membership — feature-only subprojects stranded on main (should stash + park + restore) ([#573](https://github.com/IKE-Network/ike-issues/issues/573))
- ws:scaffold-publish re-adds `!.idea/misc.xml` whitelist, overriding deliberate untracking ([#571](https://github.com/IKE-Network/ike-issues/issues/571))

### Enhancements

- Harden ws:feature-finish-*-publish against mid-run crashes (front-load version strip; advertise resumability) ([#667](https://github.com/IKE-Network/ike-issues/issues/667))

### Internal

- Test harness: FaultableExec + WorkspaceStateAssert + verifyReactor() seam (failure-injection slice of #296) ([#691](https://github.com/IKE-Network/ike-issues/issues/691))
- Epic: ws-plugin correctness & release hygiene (ike-platform v108 / ike-tooling v221) ([#690](https://github.com/IKE-Network/ike-issues/issues/690))
- ws:checkpoint-publish should gate on a reactor compile — don't cut checkpoints that won't build ([#689](https://github.com/IKE-Network/ike-issues/issues/689))
- Installer/ReactorTest builds rebuild STALE subprojects when a pin advances — hardcoded rm -rf cleanup omits newer subprojects + scaffold-init skips dirty clones ([#685](https://github.com/IKE-Network/ike-issues/issues/685))

## ike-docs v67

### Internal

- ike-docs: factor topic-ingestion infrastructure into ike-doc-ingest library module ([#550](https://github.com/IKE-Network/ike-issues/issues/550))

## ike-docs v66

### Internal

- ike-docs: add ike-network-example deployment tier (sentry-orange) to LintSiteMojo ([#546](https://github.com/IKE-Network/ike-issues/issues/546))

## ike-platform v95

### Internal

- ike-platform doc-pipeline: render-pdf executions silently skipped under Maven 4 plugin-merge ordering ([#529](https://github.com/IKE-Network/ike-issues/issues/529))

## ike-base-parent v13

### Internal

- ike-base-parent v13: restructure <build><plugins> for proper active/managed separation ([#523](https://github.com/IKE-Network/ike-issues/issues/523))

## ike-tooling v209

### Internal

- Landing page left-nav and ike-base-parent README drift from FOUNDATION set; hand-maintained surfaces missing ike-java-support and ike-version-management-extension ([#520](https://github.com/IKE-Network/ike-issues/issues/520))
- Unify visual theme across Maven site, JaCoCo, and Javadoc (currently three different themes per project) ([#518](https://github.com/IKE-Network/ike-issues/issues/518))

## ike-tooling v208

### Internal

- Configure maven-javadoc-plugin <links> for cross-module references across foundation apidocs ([#517](https://github.com/IKE-Network/ike-issues/issues/517))

## ike-base-parent v10

### Internal

- Release ike-base-parent v10 to propagate ike-java-support v1→v2 canonical pin ([#519](https://github.com/IKE-Network/ike-issues/issues/519))

## ike-tooling v207

### Internal

- Clean up stale release-cascade.yaml content (drop unread version-property data; update X.version comments) ([#516](https://github.com/IKE-Network/ike-issues/issues/516))
- Publish Javadoc on ike-tooling and ike-java-support Maven sites ([#513](https://github.com/IKE-Network/ike-issues/issues/513))

## ike-java-support v2

### Internal

- ike-java-support is missing src/main/cascade/release-cascade.yaml ([#515](https://github.com/IKE-Network/ike-issues/issues/515))

## ike-tooling v206

### Internal

- Landing page polish: Kroki dependency diagram + complete site/README for new foundation members ([#511](https://github.com/IKE-Network/ike-issues/issues/511))
- LandingPageRegistrationReconciler.detect probes a URL that always 404s ([#508](https://github.com/IKE-Network/ike-issues/issues/508))

## ike-tooling v198

### Internal

- Async Maven Central deploy with sentinel-file status tracking ([#484](https://github.com/IKE-Network/ike-issues/issues/484))
