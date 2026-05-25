---
date_published: 2026-05-24
date_modified: 2026-05-24
canonical_url: https://ike.network/ike-platform/release-notes.html
---

# Release Notes

## [ike-tooling v209](#ike-tooling-v209)

### [Internal](#internal)

- Unify visual theme across Maven site, JaCoCo, and Javadoc (currently three different themes per project) ([#518](https://github.com/IKE-Network/ike-issues/issues/518)[1])

## [ike-tooling v208](#ike-tooling-v208)

### [Internal](#internal_2)

- Configure maven-javadoc-plugin <links> for cross-module references across foundation apidocs ([#517](https://github.com/IKE-Network/ike-issues/issues/517)[2])

## [ike-base-parent v10](#ike-base-parent-v10)

### [Internal](#internal_3)

- Release ike-base-parent v10 to propagate ike-java-support v1→v2 canonical pin ([#519](https://github.com/IKE-Network/ike-issues/issues/519)[3])

## [ike-tooling v207](#ike-tooling-v207)

### [Internal](#internal_4)

- Clean up stale release-cascade.yaml content (drop unread version-property data; update X.version comments) ([#516](https://github.com/IKE-Network/ike-issues/issues/516)[4])
- Publish Javadoc on ike-tooling and ike-java-support Maven sites ([#513](https://github.com/IKE-Network/ike-issues/issues/513)[5])

## [ike-java-support v2](#ike-java-support-v2)

### [Internal](#internal_5)

- ike-java-support is missing src/main/cascade/release-cascade.yaml ([#515](https://github.com/IKE-Network/ike-issues/issues/515)[6])

## [ike-tooling v206](#ike-tooling-v206)

### [Internal](#internal_6)

- Landing page polish: Kroki dependency diagram + complete site/README for new foundation members ([#511](https://github.com/IKE-Network/ike-issues/issues/511)[7])

## [ike-tooling v198](#ike-tooling-v198)

### [Internal](#internal_7)

- Async Maven Central deploy with sentinel-file status tracking ([#484](https://github.com/IKE-Network/ike-issues/issues/484)[8])

## [ike-tooling v196](#ike-tooling-v196)

### [Internal](#internal_8)

- Nexus-first two-phase deploy with retries in ike:release-publish ([#482](https://github.com/IKE-Network/ike-issues/issues/482)[9])

## [ike-tooling v185](#ike-tooling-v185)

### [Internal](#internal_9)

- Consolidate the AsciiDoc doc-rendering pipeline into ike-doc-maven-plugin ([#437](https://github.com/IKE-Network/ike-issues/issues/437)[10])
- Add Central-required POM metadata (developers, scm); fix stale reactor comment ([#434](https://github.com/IKE-Network/ike-issues/issues/434)[11])
- Re-pin koncept-asciidoc-extension to network.ike.docs groupId ([#432](https://github.com/IKE-Network/ike-issues/issues/432)[12])

## [ike-platform v68](#ike-platform-v68)

### [Internal](#internal_10)

- ws:scaffold-publish report: show parent-cascade from→to and post-run uncommitted state ([#431](https://github.com/IKE-Network/ike-issues/issues/431)[13])

## [ike-tooling v183](#ike-tooling-v183)

### [Internal](#internal_11)

- URL-mode cascade resolver — assemble the release cascade without local sibling checkouts ([#429](https://github.com/IKE-Network/ike-issues/issues/429)[14])
- Fail ike:release-publish on preflight warnings by default; add ike.release.ignoreWarnings ([#428](https://github.com/IKE-Network/ike-issues/issues/428)[15])

## [ike-tooling v182](#ike-tooling-v182)

### [Internal](#internal_12)

- Decentralize the release cascade: per-project manifests, loosely coupled ([#420](https://github.com/IKE-Network/ike-issues/issues/420)[16])
- Complete the ike:-tier release-cascade capability (executor, alignment, terminal marker, POM wiring) ([#419](https://github.com/IKE-Network/ike-issues/issues/419)[17])

## [ike-tooling v180](#ike-tooling-v180)

### [Fixes](#fixes)

- Foundation-drift report mislabels 'ahead' projects as 'behind'; no direction, no explanation ([#412](https://github.com/IKE-Network/ike-issues/issues/412)[18])

### [Internal](#internal_13)

- Developer environment setup guide in ike-build-standards + scaffold-enforced README link ([#410](https://github.com/IKE-Network/ike-issues/issues/410)[19])

## [ike-platform v58](#ike-platform-v58)

### [Fixes](#fixes_2)

- workspace.yaml: ws:checkpoint emits duplicate sha keys instead of replacing ([#387](https://github.com/IKE-Network/ike-issues/issues/387)[20])

### [Enhancements](#enhancements)

- Document checkpoint vs release issue handling: checkpoints report, releases close ([#394](https://github.com/IKE-Network/ike-issues/issues/394)[21])
- Collapse 15 workspace goals into 3 scaffold-* goals via convergence pattern ([#393](https://github.com/IKE-Network/ike-issues/issues/393)[22])

## [ike-tooling v175](#ike-tooling-v175)

### [Fixes](#fixes_3)

- IKE-WORKSPACE.md references archived network.ike.pipeline pluginGroup ([#389](https://github.com/IKE-Network/ike-issues/issues/389)[23])

### [Enhancements](#enhancements_2)

- ike:* site lifecycle convergence: 7 goals → 2 (site-draft / site-publish) ([#398](https://github.com/IKE-Network/ike-issues/issues/398)[24])
- Standards: every standards-change issue must include a Documentation Impact section ([#396](https://github.com/IKE-Network/ike-issues/issues/396)[25])
- Release preflight: verify gh permissions and pending-release label setup for issue-management workflow ([#392](https://github.com/IKE-Network/ike-issues/issues/392)[26])
- Release process should remove pending-release label from resolved issues ([#390](https://github.com/IKE-Network/ike-issues/issues/390)[27])
- Build standards: add commit-message issue-association standard ([#388](https://github.com/IKE-Network/ike-issues/issues/388)[28])

## [ike-pipeline 111](#ike-pipeline-111)

### [Internal](#internal_14)

- ike-pipeline: port to ike-tooling 127 — SubprojectType removal ([#228](https://github.com/IKE-Network/ike-issues/issues/228)[29])

## [ike-tooling v67](#ike-tooling-v67)

### [Internal](#internal_15)

- Publish Maven sites to GitHub Pages at ike.network ([#60](https://github.com/IKE-Network/ike-issues/issues/60)[30])

## [ike-pipeline v51](#ike-pipeline-v51)

### [Enhancements](#enhancements_3)

- ws: goals should produce a cumulative markdown report with optional browser open ([#52](https://github.com/IKE-Network/ike-issues/issues/52)[31])

### [Internal](#internal_16)

- Update architecture documentation for workspace plugin split ([#59](https://github.com/IKE-Network/ike-issues/issues/59)[32])
- Workspace POM generation should derive tooling version from ike-parent ([#58](https://github.com/IKE-Network/ike-issues/issues/58)[33])
- Update ike-pipeline ike-tooling.version to v66 ([#57](https://github.com/IKE-Network/ike-issues/issues/57)[34])
- Add parent version alignment to ws:verify and ws:align ([#56](https://github.com/IKE-Network/ike-issues/issues/56)[35])
- Move ike-workspace-maven-plugin to ike-pipeline reactor ([#55](https://github.com/IKE-Network/ike-issues/issues/55)[36])
- Update ike-pipeline to align with ike-tooling v66 and release v51 ([#53](https://github.com/IKE-Network/ike-issues/issues/53)[37])

## [ike-tooling v66](#ike-tooling-v66)

### [Internal](#internal_17)

- Extract ReleaseSupport and ReleaseNotesSupport to ike-workspace-model ([#54](https://github.com/IKE-Network/ike-issues/issues/54)[38])

## [ike-tooling v64](#ike-tooling-v64)

### [Fixes](#fixes_4)

- Fix release notes 404: generate XHTML for maven-site-plugin ([#39](https://github.com/IKE-Network/ike-issues/issues/39)[39])

### [Enhancements](#enhancements_4)

- Dynamic workspace name in all mojo output headers ([#40](https://github.com/IKE-Network/ike-issues/issues/40)[40])

## [ike-tooling v63](#ike-tooling-v63)

### [Fixes](#fixes_5)

- ws:add: derive version from POM and write to workspace.yaml ([#37](https://github.com/IKE-Network/ike-issues/issues/37)[41])

### [Enhancements](#enhancements_5)

- ws:feature-start: POM fallback when workspace.yaml has no version ([#38](https://github.com/IKE-Network/ike-issues/issues/38)[42])
- Generate full release history on site from all milestones ([#35](https://github.com/IKE-Network/ike-issues/issues/35)[43])

### [Internal](#internal_18)

- Retroactively create milestones for v58-v62 releases ([#36](https://github.com/IKE-Network/ike-issues/issues/36)[44])

## [ike-tooling v62](#ike-tooling-v62)

### [Fixes](#fixes_6)

- ws:feature-start: workspace repo push should be non-fatal when no remote exists ([#33](https://github.com/IKE-Network/ike-issues/issues/33)[45])

### [Enhancements](#enhancements_6)

- Graceful remote handling across all push-capable goals ([#34](https://github.com/IKE-Network/ike-issues/issues/34)[46])

### [Internal](#internal_19)

- Standards: document idempotency as a design principle for workspace goals ([#32](https://github.com/IKE-Network/ike-issues/issues/32)[47])

## [ike-tooling v61](#ike-tooling-v61)

### [Fixes](#fixes_7)

- ws:add: resolve Maven property references in dependency groupId/artifactId ([#31](https://github.com/IKE-Network/ike-issues/issues/31)[48])
- ws:add should reject duplicate component names ([#28](https://github.com/IKE-Network/ike-issues/issues/28)[49])

### [Enhancements](#enhancements_7)

- ws:add: consider shallow clone option for faster workspace setup ([#29](https://github.com/IKE-Network/ike-issues/issues/29)[50])

## [ike-tooling v60](#ike-tooling-v60)

### [Internal](#internal_20)

- PublishedArtifactSet: replace regex POM parsing with javax.xml DOM parser ([#27](https://github.com/IKE-Network/ike-issues/issues/27)[51])
