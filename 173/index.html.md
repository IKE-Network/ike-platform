---
date_published: 2026-08-19
date_modified: 2026-08-19
canonical_url: https://ike.network/ike-platform/index.html
---

# IKE Platform

[https://central.sonatype.com/artifact/network.ike.platform/ike-platform](https://central.sonatype.com/artifact/network.ike.platform/ike-platform)[1]

The IKE Platform reactor hosts the IKE Network’s parent POM, its BOM, and the workspace-orchestration Maven plugin. It is the hub that ties `ike-tooling` (Maven plugins) and `ike-docs` (AsciiDoc toolchain) together into a single set of build conventions that downstream projects inherit.

| Coordinate | Value |
| --- | --- |
| Group ID | `network.ike.platform` |
| Packaging | POM (reactor) |
| Java version | 25 |

## [#modules](#modules)Modules

| Module | Description |
| --- | --- |
| [IKE Parent](ike-parent/index.html)[2] | The standard parent POM for IKE Network projects. Inheriting it provides Java 25 build conventions, GPG signing via Bouncy Castle, JaCoCo, the AsciiDoc documentation pipeline, dependency version management for the IKE ecosystem, and the `extensions=true` declarations for `ike-maven-plugin` and `ike-doc-maven-plugin`. |
| [IKE Bill of Materials](ike-bom/index.html)[3] | Maven BOM that pins the versions of every IKE-published artifact. External projects import it once and inherit a coherent set of versions for `ike-parent`, `ike-workspace-maven-plugin`, the `ike-tooling` family, and the `ike-docs` family. Use the BOM if you want only the version pins; inherit `ike-parent` if you want the build conventions too. |
| [IKE Workspace Maven Plugin](ike-workspace-maven-plugin/index.html)[4] | The `ws:*` plugin — 50 goals that coordinate cross-repository operations across an IKE workspace. Where bare `git` only sees one repo at a time, `ws:*` goals fan out across every checked-out subproject in topological order. |

## [#quick-start](#quick-start)Quick Start

### [#build-the-reactor](#build-the-reactor)Build the reactor

```
# Full reactor build:
mvn clean install

# Just the workspace plugin:
mvn install -pl ike-workspace-maven-plugin -am
```

### [#use-the-parent-in-your-project](#use-the-parent-in-your-project)Use the parent in your project

```
<parent>
    <groupId>network.ike.platform</groupId>
    <artifactId>ike-parent</artifactId>
    <version>22</version>
</parent>
```

After inheriting, declare IKE dependencies without versions — `ike-parent’s `<dependencyManagement>` resolves them.

### [#use-the-bom-without-inheriting-ike-parent](#use-the-bom-without-inheriting-ike-parent)Use the BOM (without inheriting `ike-parent`)

```
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>network.ike.platform</groupId>
            <artifactId>ike-bom</artifactId>
            <version>22</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### [#common-workspace-operations](#common-workspace-operations)Common workspace operations

```
# Snapshot of workspace state (manifest + graph + git status + cascade)
mvn ws:overview

# Daily-driver: pull, refresh-main, push across the workspace
mvn ws:sync

# Coordinated feature branch across all workspace subprojects
mvn ws:feature-start-publish -Dfeature=my-feature
mvn ws:feature-finish-squash-publish -Dfeature=my-feature

# Coordinated multi-repo release
mvn ws:release-draft       # preview
mvn ws:release-publish     # execute
```

For the full reference, see the [ws:* Goal Reference](ike-workspace-maven-plugin/ws-goals.html)[5] and [Workspace Lifecycle](ike-workspace-maven-plugin/workspace-lifecycle.html)[6] narrative.

For first-time setup of a workspace, see [Workspace Getting Started](workspace-getting-started.html)[7].

## [#release-cascade](#release-cascade)Release Cascade

```
ike-tooling -> ike-docs -> [ike-platform] -> { downstream consumers }
```

`ike-platform` releases after `ike-tooling` (whose `ike-maven-plugin` this POM declares at `250` in `ike-parent’s `<pluginManagement>`) and after `ike-docs` (whose `ike-doc-maven-plugin` is declared at `106`). The upstream plugins must be on Nexus before `ike-platform` can build — ordinary `<pluginManagement>` resolution, like any other managed plugin.

Earlier revisions of this page cited Maven extension-realm timing (`<extensions>true</extensions>` plugins resolving at project-load time, before property interpolation) as the reason for literal- version pinning. That constraint was eliminated in [ike-issues#321](https://github.com/IKE-Network/ike-issues/issues/321)[8]: both upstream plugins retired their custom-packaging registrations, and `ike-parent` dropped both `<extensions>true</extensions>` declarations along with their literal-version requirements. The cascade ordering is unchanged; the literal-version pinning and the extension realm are both gone. See [ike-parent module page](ike-parent/index.html)[2] for the full design rationale.

## [#history](#history)History

Split from the archived `ike-pipeline` repo to resolve a Maven extension-plugin reactor-load cycle: the old monolith declared a sibling module with `extensions=true` that Maven attempted to resolve before its own reactor had built it. See [ike-issues#216](https://github.com/IKE-Network/ike-issues/issues/216)[9] for the full diagnosis. The follow-on cleanup that retired the extension-plugin pattern entirely is tracked in [ike-issues#321](https://github.com/IKE-Network/ike-issues/issues/321)[8].

## [#the-ike-foundation](#the-ike-foundation)The IKE foundation

`ike-platform` is one of four foundation projects published to Maven Central. Together they form the parent-inheritance forest that every IKE project builds on:

- [ike-base-parent](https://ike.network/ike-base-parent/)[10] — Tier 0 foundation parent POM; shared publishing metadata and signing config.
- [ike-tooling](https://ike.network/ike-tooling/)[11] — Maven plugins for release orchestration and workspace management.
- [ike-docs](https://ike.network/ike-docs/)[12] — the AsciiDoc documentation pipeline and `idoc:*` plugin.
- [ike-platform](https://ike.network/ike-platform/)[13] — the consumer-facing `ike-parent`, the BOM, and the `ws:*` plugin.

## [#resources](#resources)Resources

| Resource | URL |
| --- | --- |
| GitHub | [https://github.com/IKE-Network/ike-platform](https://github.com/IKE-Network/ike-platform)[14] |
| Issues | [https://github.com/IKE-Network/ike-issues](https://github.com/IKE-Network/ike-issues)[15] |
| Nexus Artifacts | [https://nexus.tinkar.org](https://nexus.tinkar.org)[16] |
| IKE Network | [https://ike.network](https://ike.network)[17] |
