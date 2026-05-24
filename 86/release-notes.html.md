---
date_published: 2026-05-23
date_modified: 2026-05-23
canonical_url: https://ike.network/ike-platform/release-notes.html
---

# Release Notes

## [ike-java-support v2](#ike-java-support-v2)

### [Internal](#internal)

- ike-java-support is missing src/main/cascade/release-cascade.yaml ([#515](https://github.com/IKE-Network/ike-issues/issues/515)[1])

## [ike-tooling v198](#ike-tooling-v198)

### [Internal](#internal_2)

- Async Maven Central deploy with sentinel-file status tracking ([#484](https://github.com/IKE-Network/ike-issues/issues/484)[2])

## [ike-tooling v196](#ike-tooling-v196)

### [Internal](#internal_3)

- Nexus-first two-phase deploy with retries in ike:release-publish ([#482](https://github.com/IKE-Network/ike-issues/issues/482)[3])

## [ike-tooling v185](#ike-tooling-v185)

### [Internal](#internal_4)

- Consolidate the AsciiDoc doc-rendering pipeline into ike-doc-maven-plugin ([#437](https://github.com/IKE-Network/ike-issues/issues/437)[4])
- Add Central-required POM metadata (developers, scm); fix stale reactor comment ([#434](https://github.com/IKE-Network/ike-issues/issues/434)[5])
- Re-pin koncept-asciidoc-extension to network.ike.docs groupId ([#432](https://github.com/IKE-Network/ike-issues/issues/432)[6])

## [ike-platform v68](#ike-platform-v68)

### [Internal](#internal_5)

- ws:scaffold-publish report: show parent-cascade from→to and post-run uncommitted state ([#431](https://github.com/IKE-Network/ike-issues/issues/431)[7])

## [ike-tooling v183](#ike-tooling-v183)

### [Internal](#internal_6)

- URL-mode cascade resolver — assemble the release cascade without local sibling checkouts ([#429](https://github.com/IKE-Network/ike-issues/issues/429)[8])
- Fail ike:release-publish on preflight warnings by default; add ike.release.ignoreWarnings ([#428](https://github.com/IKE-Network/ike-issues/issues/428)[9])

## [ike-tooling v182](#ike-tooling-v182)

### [Internal](#internal_7)

- Decentralize the release cascade: per-project manifests, loosely coupled ([#420](https://github.com/IKE-Network/ike-issues/issues/420)[10])
- Complete the ike:-tier release-cascade capability (executor, alignment, terminal marker, POM wiring) ([#419](https://github.com/IKE-Network/ike-issues/issues/419)[11])

## [ike-tooling v180](#ike-tooling-v180)

### [Fixes](#fixes)

- Foundation-drift report mislabels 'ahead' projects as 'behind'; no direction, no explanation ([#412](https://github.com/IKE-Network/ike-issues/issues/412)[12])

### [Internal](#internal_8)

- Developer environment setup guide in ike-build-standards + scaffold-enforced README link ([#410](https://github.com/IKE-Network/ike-issues/issues/410)[13])

## [ike-platform v58](#ike-platform-v58)

### [Fixes](#fixes_2)

- workspace.yaml: ws:checkpoint emits duplicate sha keys instead of replacing ([#387](https://github.com/IKE-Network/ike-issues/issues/387)[14])

### [Enhancements](#enhancements)

- Document checkpoint vs release issue handling: checkpoints report, releases close ([#394](https://github.com/IKE-Network/ike-issues/issues/394)[15])
- Collapse 15 workspace goals into 3 scaffold-* goals via convergence pattern ([#393](https://github.com/IKE-Network/ike-issues/issues/393)[16])

## [ike-tooling v175](#ike-tooling-v175)

### [Fixes](#fixes_3)

- IKE-WORKSPACE.md references archived network.ike.pipeline pluginGroup ([#389](https://github.com/IKE-Network/ike-issues/issues/389)[17])

### [Enhancements](#enhancements_2)

- ike:* site lifecycle convergence: 7 goals → 2 (site-draft / site-publish) ([#398](https://github.com/IKE-Network/ike-issues/issues/398)[18])
- Standards: every standards-change issue must include a Documentation Impact section ([#396](https://github.com/IKE-Network/ike-issues/issues/396)[19])
- Release preflight: verify gh permissions and pending-release label setup for issue-management workflow ([#392](https://github.com/IKE-Network/ike-issues/issues/392)[20])
- Release process should remove pending-release label from resolved issues ([#390](https://github.com/IKE-Network/ike-issues/issues/390)[21])
- Build standards: add commit-message issue-association standard ([#388](https://github.com/IKE-Network/ike-issues/issues/388)[22])

## [ike-pipeline 111](#ike-pipeline-111)

### [Internal](#internal_9)

- ike-pipeline: port to ike-tooling 127 — SubprojectType removal ([#228](https://github.com/IKE-Network/ike-issues/issues/228)[23])

## [ike-tooling v67](#ike-tooling-v67)

### [Internal](#internal_10)

- Publish Maven sites to GitHub Pages at ike.network ([#60](https://github.com/IKE-Network/ike-issues/issues/60)[24])

## [ike-pipeline v51](#ike-pipeline-v51)

### [Enhancements](#enhancements_3)

- ws: goals should produce a cumulative markdown report with optional browser open ([#52](https://github.com/IKE-Network/ike-issues/issues/52)[25])

### [Internal](#internal_11)

- Update architecture documentation for workspace plugin split ([#59](https://github.com/IKE-Network/ike-issues/issues/59)[26])
- Workspace POM generation should derive tooling version from ike-parent ([#58](https://github.com/IKE-Network/ike-issues/issues/58)[27])
- Update ike-pipeline ike-tooling.version to v66 ([#57](https://github.com/IKE-Network/ike-issues/issues/57)[28])
- Add parent version alignment to ws:verify and ws:align ([#56](https://github.com/IKE-Network/ike-issues/issues/56)[29])
- Move ike-workspace-maven-plugin to ike-pipeline reactor ([#55](https://github.com/IKE-Network/ike-issues/issues/55)[30])
- Update ike-pipeline to align with ike-tooling v66 and release v51 ([#53](https://github.com/IKE-Network/ike-issues/issues/53)[31])

## [ike-tooling v66](#ike-tooling-v66)

### [Internal](#internal_12)

- Extract ReleaseSupport and ReleaseNotesSupport to ike-workspace-model ([#54](https://github.com/IKE-Network/ike-issues/issues/54)[32])

## [ike-tooling v64](#ike-tooling-v64)

### [Fixes](#fixes_4)

- Fix release notes 404: generate XHTML for maven-site-plugin ([#39](https://github.com/IKE-Network/ike-issues/issues/39)[33])

### [Enhancements](#enhancements_4)

- Dynamic workspace name in all mojo output headers ([#40](https://github.com/IKE-Network/ike-issues/issues/40)[34])

## [ike-tooling v63](#ike-tooling-v63)

### [Fixes](#fixes_5)

- ws:add: derive version from POM and write to workspace.yaml ([#37](https://github.com/IKE-Network/ike-issues/issues/37)[35])

### [Enhancements](#enhancements_5)

- ws:feature-start: POM fallback when workspace.yaml has no version ([#38](https://github.com/IKE-Network/ike-issues/issues/38)[36])
- Generate full release history on site from all milestones ([#35](https://github.com/IKE-Network/ike-issues/issues/35)[37])

### [Internal](#internal_13)

- Retroactively create milestones for v58-v62 releases ([#36](https://github.com/IKE-Network/ike-issues/issues/36)[38])

## [ike-tooling v62](#ike-tooling-v62)

### [Fixes](#fixes_6)

- ws:feature-start: workspace repo push should be non-fatal when no remote exists ([#33](https://github.com/IKE-Network/ike-issues/issues/33)[39])

### [Enhancements](#enhancements_6)

- Graceful remote handling across all push-capable goals ([#34](https://github.com/IKE-Network/ike-issues/issues/34)[40])

### [Internal](#internal_14)

- Standards: document idempotency as a design principle for workspace goals ([#32](https://github.com/IKE-Network/ike-issues/issues/32)[41])

## [ike-tooling v61](#ike-tooling-v61)

### [Fixes](#fixes_7)

- ws:add: resolve Maven property references in dependency groupId/artifactId ([#31](https://github.com/IKE-Network/ike-issues/issues/31)[42])
- ws:add should reject duplicate component names ([#28](https://github.com/IKE-Network/ike-issues/issues/28)[43])

### [Enhancements](#enhancements_7)

- ws:add: consider shallow clone option for faster workspace setup ([#29](https://github.com/IKE-Network/ike-issues/issues/29)[44])

## [ike-tooling v60](#ike-tooling-v60)

### [Internal](#internal_15)

- PublishedArtifactSet: replace regex POM parsing with javax.xml DOM parser ([#27](https://github.com/IKE-Network/ike-issues/issues/27)[45])

## [ike-tooling v59](#ike-tooling-v59)

### [Fixes](#fixes_8)

- ws:create should use workspace name as POM <name>, not generic default ([#21](https://github.com/IKE-Network/ike-issues/issues/21)[46])

### [Enhancements](#enhancements_8)

- ws:graph should show full transitive dependency tree, not just direct edges ([#24](https://github.com/IKE-Network/ike-issues/issues/24)[47])
- Rename VCS bridge to subproject git state; clarify verify output ([#23](https://github.com/IKE-Network/ike-issues/issues/23)[48])
- ws:add should report detailed dependency artifacts, versions, and alignment status ([#22](https://github.com/IKE-Network/ike-issues/issues/22)[49])

## [ike-tooling v58](#ike-tooling-v58)

### [Fixes](#fixes_9)

- ws:create should warn or fail if workspace directory already exists ([#20](https://github.com/IKE-Network/ike-issues/issues/20)[50])

### [Enhancements](#enhancements_9)

- Use gh CLI for authenticated GitHub API calls instead of raw HttpClient ([#19](https://github.com/IKE-Network/ike-issues/issues/19)[51])
- Integrate release notes into site build ([#18](https://github.com/IKE-Network/ike-issues/issues/18)[52])

## [ike-tooling v57](#ike-tooling-v57)

### [Enhancements](#enhancements_10)

- ws:add: derive depends-on from POM analysis instead of manual -DdependsOn ([#17](https://github.com/IKE-Network/ike-issues/issues/17)[53])
- Implement ws:release-notes goal ([#16](https://github.com/IKE-Network/ike-issues/issues/16)[54])

### [Internal](#internal_16)

- Add issue templates and README to ike-issues ([#15](https://github.com/IKE-Network/ike-issues/issues/15)[55])
- Create IKE-RELEASE.md release standards ([#14](https://github.com/IKE-Network/ike-issues/issues/14)[56])
- Add bootstrap checklist to IKE-WORKSPACE.md prerequisites ([#13](https://github.com/IKE-Network/ike-issues/issues/13)[57])
- ws:create bootstrap: settings.xml requires pluginGroups for ws: prefix ([#12](https://github.com/IKE-Network/ike-issues/issues/12)[58])
