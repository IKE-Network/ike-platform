---
date_published: 2026-05-16
date_modified: 2026-05-16
canonical_url: https://ike.network/ike-platform/release-notes.html
---

# Release Notes

## [ike-tooling v183](#ike-tooling-v183)

### [Internal](#internal)

- URL-mode cascade resolver — assemble the release cascade without local sibling checkouts ([#429](https://github.com/IKE-Network/ike-issues/issues/429)[1])
- Fail ike:release-publish on preflight warnings by default; add ike.release.ignoreWarnings ([#428](https://github.com/IKE-Network/ike-issues/issues/428)[2])

## [ike-tooling v182](#ike-tooling-v182)

### [Internal](#internal_2)

- Decentralize the release cascade: per-project manifests, loosely coupled ([#420](https://github.com/IKE-Network/ike-issues/issues/420)[3])
- Complete the ike:-tier release-cascade capability (executor, alignment, terminal marker, POM wiring) ([#419](https://github.com/IKE-Network/ike-issues/issues/419)[4])

## [ike-tooling v180](#ike-tooling-v180)

### [Fixes](#fixes)

- Foundation-drift report mislabels 'ahead' projects as 'behind'; no direction, no explanation ([#412](https://github.com/IKE-Network/ike-issues/issues/412)[5])

### [Internal](#internal_3)

- Developer environment setup guide in ike-build-standards + scaffold-enforced README link ([#410](https://github.com/IKE-Network/ike-issues/issues/410)[6])

## [ike-platform v58](#ike-platform-v58)

### [Fixes](#fixes_2)

- workspace.yaml: ws:checkpoint emits duplicate sha keys instead of replacing ([#387](https://github.com/IKE-Network/ike-issues/issues/387)[7])

### [Enhancements](#enhancements)

- Document checkpoint vs release issue handling: checkpoints report, releases close ([#394](https://github.com/IKE-Network/ike-issues/issues/394)[8])
- Collapse 15 workspace goals into 3 scaffold-* goals via convergence pattern ([#393](https://github.com/IKE-Network/ike-issues/issues/393)[9])

## [ike-tooling v175](#ike-tooling-v175)

### [Fixes](#fixes_3)

- IKE-WORKSPACE.md references archived network.ike.pipeline pluginGroup ([#389](https://github.com/IKE-Network/ike-issues/issues/389)[10])

### [Enhancements](#enhancements_2)

- ike:* site lifecycle convergence: 7 goals → 2 (site-draft / site-publish) ([#398](https://github.com/IKE-Network/ike-issues/issues/398)[11])
- Standards: every standards-change issue must include a Documentation Impact section ([#396](https://github.com/IKE-Network/ike-issues/issues/396)[12])
- Release preflight: verify gh permissions and pending-release label setup for issue-management workflow ([#392](https://github.com/IKE-Network/ike-issues/issues/392)[13])
- Release process should remove pending-release label from resolved issues ([#390](https://github.com/IKE-Network/ike-issues/issues/390)[14])
- Build standards: add commit-message issue-association standard ([#388](https://github.com/IKE-Network/ike-issues/issues/388)[15])

## [ike-pipeline 111](#ike-pipeline-111)

### [Internal](#internal_4)

- ike-pipeline: port to ike-tooling 127 — SubprojectType removal ([#228](https://github.com/IKE-Network/ike-issues/issues/228)[16])

## [ike-tooling v67](#ike-tooling-v67)

### [Internal](#internal_5)

- Publish Maven sites to GitHub Pages at ike.network ([#60](https://github.com/IKE-Network/ike-issues/issues/60)[17])

## [ike-pipeline v51](#ike-pipeline-v51)

### [Enhancements](#enhancements_3)

- ws: goals should produce a cumulative markdown report with optional browser open ([#52](https://github.com/IKE-Network/ike-issues/issues/52)[18])

### [Internal](#internal_6)

- Update architecture documentation for workspace plugin split ([#59](https://github.com/IKE-Network/ike-issues/issues/59)[19])
- Workspace POM generation should derive tooling version from ike-parent ([#58](https://github.com/IKE-Network/ike-issues/issues/58)[20])
- Update ike-pipeline ike-tooling.version to v66 ([#57](https://github.com/IKE-Network/ike-issues/issues/57)[21])
- Add parent version alignment to ws:verify and ws:align ([#56](https://github.com/IKE-Network/ike-issues/issues/56)[22])
- Move ike-workspace-maven-plugin to ike-pipeline reactor ([#55](https://github.com/IKE-Network/ike-issues/issues/55)[23])
- Update ike-pipeline to align with ike-tooling v66 and release v51 ([#53](https://github.com/IKE-Network/ike-issues/issues/53)[24])

## [ike-tooling v66](#ike-tooling-v66)

### [Internal](#internal_7)

- Extract ReleaseSupport and ReleaseNotesSupport to ike-workspace-model ([#54](https://github.com/IKE-Network/ike-issues/issues/54)[25])

## [ike-tooling v64](#ike-tooling-v64)

### [Fixes](#fixes_4)

- Fix release notes 404: generate XHTML for maven-site-plugin ([#39](https://github.com/IKE-Network/ike-issues/issues/39)[26])

### [Enhancements](#enhancements_4)

- Dynamic workspace name in all mojo output headers ([#40](https://github.com/IKE-Network/ike-issues/issues/40)[27])

## [ike-tooling v63](#ike-tooling-v63)

### [Fixes](#fixes_5)

- ws:add: derive version from POM and write to workspace.yaml ([#37](https://github.com/IKE-Network/ike-issues/issues/37)[28])

### [Enhancements](#enhancements_5)

- ws:feature-start: POM fallback when workspace.yaml has no version ([#38](https://github.com/IKE-Network/ike-issues/issues/38)[29])
- Generate full release history on site from all milestones ([#35](https://github.com/IKE-Network/ike-issues/issues/35)[30])

### [Internal](#internal_8)

- Retroactively create milestones for v58-v62 releases ([#36](https://github.com/IKE-Network/ike-issues/issues/36)[31])

## [ike-tooling v62](#ike-tooling-v62)

### [Fixes](#fixes_6)

- ws:feature-start: workspace repo push should be non-fatal when no remote exists ([#33](https://github.com/IKE-Network/ike-issues/issues/33)[32])

### [Enhancements](#enhancements_6)

- Graceful remote handling across all push-capable goals ([#34](https://github.com/IKE-Network/ike-issues/issues/34)[33])

### [Internal](#internal_9)

- Standards: document idempotency as a design principle for workspace goals ([#32](https://github.com/IKE-Network/ike-issues/issues/32)[34])

## [ike-tooling v61](#ike-tooling-v61)

### [Fixes](#fixes_7)

- ws:add: resolve Maven property references in dependency groupId/artifactId ([#31](https://github.com/IKE-Network/ike-issues/issues/31)[35])
- ws:add should reject duplicate component names ([#28](https://github.com/IKE-Network/ike-issues/issues/28)[36])

### [Enhancements](#enhancements_7)

- ws:add: consider shallow clone option for faster workspace setup ([#29](https://github.com/IKE-Network/ike-issues/issues/29)[37])

## [ike-tooling v60](#ike-tooling-v60)

### [Internal](#internal_10)

- PublishedArtifactSet: replace regex POM parsing with javax.xml DOM parser ([#27](https://github.com/IKE-Network/ike-issues/issues/27)[38])

## [ike-tooling v59](#ike-tooling-v59)

### [Fixes](#fixes_8)

- ws:create should use workspace name as POM <name>, not generic default ([#21](https://github.com/IKE-Network/ike-issues/issues/21)[39])

### [Enhancements](#enhancements_8)

- ws:graph should show full transitive dependency tree, not just direct edges ([#24](https://github.com/IKE-Network/ike-issues/issues/24)[40])
- Rename VCS bridge to subproject git state; clarify verify output ([#23](https://github.com/IKE-Network/ike-issues/issues/23)[41])
- ws:add should report detailed dependency artifacts, versions, and alignment status ([#22](https://github.com/IKE-Network/ike-issues/issues/22)[42])

## [ike-tooling v58](#ike-tooling-v58)

### [Fixes](#fixes_9)

- ws:create should warn or fail if workspace directory already exists ([#20](https://github.com/IKE-Network/ike-issues/issues/20)[43])

### [Enhancements](#enhancements_9)

- Use gh CLI for authenticated GitHub API calls instead of raw HttpClient ([#19](https://github.com/IKE-Network/ike-issues/issues/19)[44])
- Integrate release notes into site build ([#18](https://github.com/IKE-Network/ike-issues/issues/18)[45])

## [ike-tooling v57](#ike-tooling-v57)

### [Enhancements](#enhancements_10)

- ws:add: derive depends-on from POM analysis instead of manual -DdependsOn ([#17](https://github.com/IKE-Network/ike-issues/issues/17)[46])
- Implement ws:release-notes goal ([#16](https://github.com/IKE-Network/ike-issues/issues/16)[47])

### [Internal](#internal_11)

- Add issue templates and README to ike-issues ([#15](https://github.com/IKE-Network/ike-issues/issues/15)[48])
- Create IKE-RELEASE.md release standards ([#14](https://github.com/IKE-Network/ike-issues/issues/14)[49])
- Add bootstrap checklist to IKE-WORKSPACE.md prerequisites ([#13](https://github.com/IKE-Network/ike-issues/issues/13)[50])
- ws:create bootstrap: settings.xml requires pluginGroups for ws: prefix ([#12](https://github.com/IKE-Network/ike-issues/issues/12)[51])
