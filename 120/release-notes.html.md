---
date_published: 2026-06-26
date_modified: 2026-06-26
canonical_url: https://ike.network/ike-platform/release-notes.html
---

# Release Notes

## [ike-tooling v221](#ike-tooling-v221)

### [Fixes](#fixes)

- ike:release-cascade skips downstream repos with stale upstream version-property drift ([#456](https://github.com/IKE-Network/ike-issues/issues/456)[1])

## [ike-platform v108](#ike-platform-v108)

### [Fixes](#fixes_2)

- ws:remove is main-only — make it branch-scoped (mirror ws:add); feature-only subprojects are currently un-removable ([#575](https://github.com/IKE-Network/ike-issues/issues/575)[2])
- Feature-branch version qualification missing: ws:add doesn't apply it; scaffold-publish doesn't self-heal it ([#574](https://github.com/IKE-Network/ike-issues/issues/574)[3])
- ws:switch ignores target-branch membership — feature-only subprojects stranded on main (should stash + park + restore) ([#573](https://github.com/IKE-Network/ike-issues/issues/573)[4])
- ws:scaffold-publish re-adds `!.idea/misc.xml` whitelist, overriding deliberate untracking ([#571](https://github.com/IKE-Network/ike-issues/issues/571)[5])

## [ike-platform v95](#ike-platform-v95)

### [Internal](#internal)

- ike-platform doc-pipeline: render-pdf executions silently skipped under Maven 4 plugin-merge ordering ([#529](https://github.com/IKE-Network/ike-issues/issues/529)[6])

## [ike-base-parent v13](#ike-base-parent-v13)

### [Internal](#internal_2)

- ike-base-parent v13: restructure <build><plugins> for proper active/managed separation ([#523](https://github.com/IKE-Network/ike-issues/issues/523)[7])

## [ike-tooling v209](#ike-tooling-v209)

### [Internal](#internal_3)

- Unify visual theme across Maven site, JaCoCo, and Javadoc (currently three different themes per project) ([#518](https://github.com/IKE-Network/ike-issues/issues/518)[8])

## [ike-tooling v208](#ike-tooling-v208)

### [Internal](#internal_4)

- Configure maven-javadoc-plugin <links> for cross-module references across foundation apidocs ([#517](https://github.com/IKE-Network/ike-issues/issues/517)[9])

## [ike-base-parent v10](#ike-base-parent-v10)

### [Internal](#internal_5)

- Release ike-base-parent v10 to propagate ike-java-support v1→v2 canonical pin ([#519](https://github.com/IKE-Network/ike-issues/issues/519)[10])

## [ike-tooling v207](#ike-tooling-v207)

### [Internal](#internal_6)

- Clean up stale release-cascade.yaml content (drop unread version-property data; update X.version comments) ([#516](https://github.com/IKE-Network/ike-issues/issues/516)[11])
- Publish Javadoc on ike-tooling and ike-java-support Maven sites ([#513](https://github.com/IKE-Network/ike-issues/issues/513)[12])

## [ike-java-support v2](#ike-java-support-v2)

### [Internal](#internal_7)

- ike-java-support is missing src/main/cascade/release-cascade.yaml ([#515](https://github.com/IKE-Network/ike-issues/issues/515)[13])

## [ike-tooling v206](#ike-tooling-v206)

### [Internal](#internal_8)

- Landing page polish: Kroki dependency diagram + complete site/README for new foundation members ([#511](https://github.com/IKE-Network/ike-issues/issues/511)[14])

## [ike-tooling v198](#ike-tooling-v198)

### [Internal](#internal_9)

- Async Maven Central deploy with sentinel-file status tracking ([#484](https://github.com/IKE-Network/ike-issues/issues/484)[15])

## [ike-tooling v196](#ike-tooling-v196)

### [Internal](#internal_10)

- Nexus-first two-phase deploy with retries in ike:release-publish ([#482](https://github.com/IKE-Network/ike-issues/issues/482)[16])
