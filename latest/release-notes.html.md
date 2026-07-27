---
date_published: 2026-07-26
date_modified: 2026-07-26
canonical_url: https://ike.network/ike-platform/release-notes.html
---

# Release Notes

## [ike-tooling v221](#ike-tooling-v221)

### [Fixes](#fixes)

- release-publish: unauthenticated GitHub API rate-limits the last repo of a cascade (403 on milestones) ([#572](https://github.com/IKE-Network/ike-issues/issues/572)[1])
- ike:release-cascade skips downstream repos with stale upstream version-property drift ([#456](https://github.com/IKE-Network/ike-issues/issues/456)[2])

## [ike-platform v108](#ike-platform-v108)

### [Fixes](#fixes_2)

- ws:remove is main-only — make it branch-scoped (mirror ws:add); feature-only subprojects are currently un-removable ([#575](https://github.com/IKE-Network/ike-issues/issues/575)[3])
- Feature-branch version qualification missing: ws:add doesn't apply it; scaffold-publish doesn't self-heal it ([#574](https://github.com/IKE-Network/ike-issues/issues/574)[4])
- ws:switch ignores target-branch membership — feature-only subprojects stranded on main (should stash + park + restore) ([#573](https://github.com/IKE-Network/ike-issues/issues/573)[5])
- ws:scaffold-publish re-adds `!.idea/misc.xml` whitelist, overriding deliberate untracking ([#571](https://github.com/IKE-Network/ike-issues/issues/571)[6])

### [Enhancements](#enhancements)

- Harden ws:feature-finish-*-publish against mid-run crashes (front-load version strip; advertise resumability) ([#667](https://github.com/IKE-Network/ike-issues/issues/667)[7])

### [Internal](#internal)

- Test harness: FaultableExec + WorkspaceStateAssert + verifyReactor() seam (failure-injection slice of #296) ([#691](https://github.com/IKE-Network/ike-issues/issues/691)[8])
- ws:checkpoint-publish should gate on a reactor compile — don't cut checkpoints that won't build ([#689](https://github.com/IKE-Network/ike-issues/issues/689)[9])
- Installer/ReactorTest builds rebuild STALE subprojects when a pin advances — hardcoded rm -rf cleanup omits newer subprojects + scaffold-init skips dirty clones ([#685](https://github.com/IKE-Network/ike-issues/issues/685)[10])

## [ike-docs v67](#ike-docs-v67)

### [Internal](#internal_2)

- ike-docs: factor topic-ingestion infrastructure into ike-doc-ingest library module ([#550](https://github.com/IKE-Network/ike-issues/issues/550)[11])

## [ike-docs v66](#ike-docs-v66)

### [Internal](#internal_3)

- ike-docs: add ike-network-example deployment tier (sentry-orange) to LintSiteMojo ([#546](https://github.com/IKE-Network/ike-issues/issues/546)[12])

## [ike-platform v95](#ike-platform-v95)

### [Internal](#internal_4)

- ike-platform doc-pipeline: render-pdf executions silently skipped under Maven 4 plugin-merge ordering ([#529](https://github.com/IKE-Network/ike-issues/issues/529)[13])

## [ike-base-parent v13](#ike-base-parent-v13)

### [Internal](#internal_5)

- ike-base-parent v13: restructure <build><plugins> for proper active/managed separation ([#523](https://github.com/IKE-Network/ike-issues/issues/523)[14])

## [ike-tooling v209](#ike-tooling-v209)

### [Internal](#internal_6)

- Landing page left-nav and ike-base-parent README drift from FOUNDATION set; hand-maintained surfaces missing ike-java-support and ike-version-management-extension ([#520](https://github.com/IKE-Network/ike-issues/issues/520)[15])
- Unify visual theme across Maven site, JaCoCo, and Javadoc (currently three different themes per project) ([#518](https://github.com/IKE-Network/ike-issues/issues/518)[16])

## [ike-tooling v208](#ike-tooling-v208)

### [Internal](#internal_7)

- Configure maven-javadoc-plugin <links> for cross-module references across foundation apidocs ([#517](https://github.com/IKE-Network/ike-issues/issues/517)[17])

## [ike-base-parent v10](#ike-base-parent-v10)

### [Internal](#internal_8)

- Release ike-base-parent v10 to propagate ike-java-support v1→v2 canonical pin ([#519](https://github.com/IKE-Network/ike-issues/issues/519)[18])

## [ike-tooling v207](#ike-tooling-v207)

### [Internal](#internal_9)

- Clean up stale release-cascade.yaml content (drop unread version-property data; update X.version comments) ([#516](https://github.com/IKE-Network/ike-issues/issues/516)[19])
- Publish Javadoc on ike-tooling and ike-java-support Maven sites ([#513](https://github.com/IKE-Network/ike-issues/issues/513)[20])

## [ike-java-support v2](#ike-java-support-v2)

### [Internal](#internal_10)

- ike-java-support is missing src/main/cascade/release-cascade.yaml ([#515](https://github.com/IKE-Network/ike-issues/issues/515)[21])

## [ike-tooling v206](#ike-tooling-v206)

### [Internal](#internal_11)

- Landing page polish: Kroki dependency diagram + complete site/README for new foundation members ([#511](https://github.com/IKE-Network/ike-issues/issues/511)[22])
- LandingPageRegistrationReconciler.detect probes a URL that always 404s ([#508](https://github.com/IKE-Network/ike-issues/issues/508)[23])

## [ike-tooling v198](#ike-tooling-v198)

### [Internal](#internal_12)

- Async Maven Central deploy with sentinel-file status tracking ([#484](https://github.com/IKE-Network/ike-issues/issues/484)[24])

## [ike-tooling v196](#ike-tooling-v196)

### [Internal](#internal_13)

- Nexus-first two-phase deploy with retries in ike:release-publish ([#482](https://github.com/IKE-Network/ike-issues/issues/482)[25])
