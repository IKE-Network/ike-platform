---
date_published: 2026-05-10
date_modified: 2026-05-10
canonical_url: https://ike.network/ike-platform/release-notes.html
---

# Release Notes

## [ike-pipeline 111](#ike-pipeline-111)

### [Internal](#internal)

- ike-pipeline: port to ike-tooling 127 — SubprojectType removal ([#228](https://github.com/IKE-Network/ike-issues/issues/228)[1])

## [ike-tooling v67](#ike-tooling-v67)

### [Internal](#internal_2)

- Publish Maven sites to GitHub Pages at ike.network ([#60](https://github.com/IKE-Network/ike-issues/issues/60)[2])

## [ike-pipeline v51](#ike-pipeline-v51)

### [Enhancements](#enhancements)

- ws: goals should produce a cumulative markdown report with optional browser open ([#52](https://github.com/IKE-Network/ike-issues/issues/52)[3])

### [Internal](#internal_3)

- Update architecture documentation for workspace plugin split ([#59](https://github.com/IKE-Network/ike-issues/issues/59)[4])
- Workspace POM generation should derive tooling version from ike-parent ([#58](https://github.com/IKE-Network/ike-issues/issues/58)[5])
- Update ike-pipeline ike-tooling.version to v66 ([#57](https://github.com/IKE-Network/ike-issues/issues/57)[6])
- Add parent version alignment to ws:verify and ws:align ([#56](https://github.com/IKE-Network/ike-issues/issues/56)[7])
- Move ike-workspace-maven-plugin to ike-pipeline reactor ([#55](https://github.com/IKE-Network/ike-issues/issues/55)[8])
- Update ike-pipeline to align with ike-tooling v66 and release v51 ([#53](https://github.com/IKE-Network/ike-issues/issues/53)[9])

## [ike-tooling v66](#ike-tooling-v66)

### [Internal](#internal_4)

- Extract ReleaseSupport and ReleaseNotesSupport to ike-workspace-model ([#54](https://github.com/IKE-Network/ike-issues/issues/54)[10])

## [ike-tooling v64](#ike-tooling-v64)

### [Fixes](#fixes)

- Fix release notes 404: generate XHTML for maven-site-plugin ([#39](https://github.com/IKE-Network/ike-issues/issues/39)[11])

### [Enhancements](#enhancements_2)

- Dynamic workspace name in all mojo output headers ([#40](https://github.com/IKE-Network/ike-issues/issues/40)[12])

## [ike-tooling v63](#ike-tooling-v63)

### [Fixes](#fixes_2)

- ws:add: derive version from POM and write to workspace.yaml ([#37](https://github.com/IKE-Network/ike-issues/issues/37)[13])

### [Enhancements](#enhancements_3)

- ws:feature-start: POM fallback when workspace.yaml has no version ([#38](https://github.com/IKE-Network/ike-issues/issues/38)[14])
- Generate full release history on site from all milestones ([#35](https://github.com/IKE-Network/ike-issues/issues/35)[15])

### [Internal](#internal_5)

- Retroactively create milestones for v58-v62 releases ([#36](https://github.com/IKE-Network/ike-issues/issues/36)[16])

## [ike-tooling v62](#ike-tooling-v62)

### [Fixes](#fixes_3)

- ws:feature-start: workspace repo push should be non-fatal when no remote exists ([#33](https://github.com/IKE-Network/ike-issues/issues/33)[17])

### [Enhancements](#enhancements_4)

- Graceful remote handling across all push-capable goals ([#34](https://github.com/IKE-Network/ike-issues/issues/34)[18])

### [Internal](#internal_6)

- Standards: document idempotency as a design principle for workspace goals ([#32](https://github.com/IKE-Network/ike-issues/issues/32)[19])

## [ike-tooling v61](#ike-tooling-v61)

### [Fixes](#fixes_4)

- ws:add: resolve Maven property references in dependency groupId/artifactId ([#31](https://github.com/IKE-Network/ike-issues/issues/31)[20])
- ws:add should reject duplicate component names ([#28](https://github.com/IKE-Network/ike-issues/issues/28)[21])

### [Enhancements](#enhancements_5)

- ws:add: consider shallow clone option for faster workspace setup ([#29](https://github.com/IKE-Network/ike-issues/issues/29)[22])

## [ike-tooling v60](#ike-tooling-v60)

### [Internal](#internal_7)

- PublishedArtifactSet: replace regex POM parsing with javax.xml DOM parser ([#27](https://github.com/IKE-Network/ike-issues/issues/27)[23])

## [ike-tooling v59](#ike-tooling-v59)

### [Fixes](#fixes_5)

- ws:create should use workspace name as POM <name>, not generic default ([#21](https://github.com/IKE-Network/ike-issues/issues/21)[24])

### [Enhancements](#enhancements_6)

- ws:graph should show full transitive dependency tree, not just direct edges ([#24](https://github.com/IKE-Network/ike-issues/issues/24)[25])
- Rename VCS bridge to subproject git state; clarify verify output ([#23](https://github.com/IKE-Network/ike-issues/issues/23)[26])
- ws:add should report detailed dependency artifacts, versions, and alignment status ([#22](https://github.com/IKE-Network/ike-issues/issues/22)[27])

## [ike-tooling v58](#ike-tooling-v58)

### [Fixes](#fixes_6)

- ws:create should warn or fail if workspace directory already exists ([#20](https://github.com/IKE-Network/ike-issues/issues/20)[28])

### [Enhancements](#enhancements_7)

- Use gh CLI for authenticated GitHub API calls instead of raw HttpClient ([#19](https://github.com/IKE-Network/ike-issues/issues/19)[29])
- Integrate release notes into site build ([#18](https://github.com/IKE-Network/ike-issues/issues/18)[30])

## [ike-tooling v57](#ike-tooling-v57)

### [Enhancements](#enhancements_8)

- ws:add: derive depends-on from POM analysis instead of manual -DdependsOn ([#17](https://github.com/IKE-Network/ike-issues/issues/17)[31])
- Implement ws:release-notes goal ([#16](https://github.com/IKE-Network/ike-issues/issues/16)[32])

### [Internal](#internal_8)

- Add issue templates and README to ike-issues ([#15](https://github.com/IKE-Network/ike-issues/issues/15)[33])
- Create IKE-RELEASE.md release standards ([#14](https://github.com/IKE-Network/ike-issues/issues/14)[34])
- Add bootstrap checklist to IKE-WORKSPACE.md prerequisites ([#13](https://github.com/IKE-Network/ike-issues/issues/13)[35])
- ws:create bootstrap: settings.xml requires pluginGroups for ws: prefix ([#12](https://github.com/IKE-Network/ike-issues/issues/12)[36])
