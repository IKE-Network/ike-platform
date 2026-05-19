# IKE Platform

[![Maven Central](https://img.shields.io/maven-central/v/network.ike.platform/ike-platform)](https://central.sonatype.com/artifact/network.ike.platform/ike-platform)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Documentation](https://img.shields.io/badge/docs-ike.network%2Fike--platform-blue)](https://ike.network/ike-platform/)
[![IKE Network](https://img.shields.io/badge/IKE-Network-green)](https://ike.network/)

Parent POM, BOM, and workspace-orchestration plugin for the IKE
Network build pipeline.

## Modules

| Module | Artifact | Purpose |
|---|---|---|
| `ike-parent` | `network.ike.platform:ike-parent` | Parent POM — dependency and plugin management, profiles |
| `ike-workspace-maven-plugin` | `network.ike.platform:ike-workspace-maven-plugin` | `ws:*` goals for multi-repo workspace orchestration |
| `ike-bom` | `network.ike.platform:ike-bom` | Auto-generated BOM for external `<scope>import</scope>` consumers |

## Build

```bash
mvn clean install
```

## Usage

Downstream projects inherit from `ike-parent` (check Nexus or the
[GitHub releases](https://github.com/IKE-Network/ike-platform/releases)
for the current version — replace `54` below):

```xml
<parent>
    <groupId>network.ike.platform</groupId>
    <artifactId>ike-parent</artifactId>
    <version>54</version>
</parent>
```

Inheriting projects MUST also declare their own
`<distributionManagement><site>` URL — the inherited template
resolves to the in-reactor location and is wrong for external
consumers. See [`ike-parent/CLAUDE.md`](ike-parent/CLAUDE.md) and
[`IKE-Network/ike-issues#383`](https://github.com/IKE-Network/ike-issues/issues/383)
for the override pattern. A build enforcer fails projects that
miss this.

Consumers who do not want to inherit `ike-parent`'s build conventions
can still align dependency versions by importing the BOM:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>network.ike.platform</groupId>
            <artifactId>ike-bom</artifactId>
            <version>54</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## Release Cascade

```
ike-tooling → ike-docs → [ike-platform] → { doc-example, example-project, ike-example-its } → ike-example-ws
```

The foundation cascade (`ike-tooling → ike-docs → ike-platform`) is
orchestrated by `ike-maven-plugin:release-cascade`, which assembles
the order from each repo's own `src/main/cascade/release-cascade.yaml`
(IKE-Network/ike-issues#420). See
[`cutting-a-release.adoc`](https://ike.network/ike-platform/cutting-a-release.html)
for the full procedure.

Release order is structurally upstream-first: `ike-parent`'s
`<pluginManagement>` declares `ike-maven-plugin` at
`${ike-tooling.version}` and `ike-doc-maven-plugin` at
`${ike-docs.version}`, both of which must be resolvable from Nexus
when downstream reactors load.

Earlier revisions of these docs cited extension-realm timing
(`<extensions>true</extensions>` plugins resolving at project-load
time, before property interpolation) as the reason for literal-
version pinning. That constraint was eliminated in
[`IKE-Network/ike-issues#321`](https://github.com/IKE-Network/ike-issues/issues/321):
both upstream plugins retired their custom-packaging registrations
and `ike-parent` dropped both `<extensions>true</extensions>`
declarations. The cascade ordering is unchanged; the literal-version
pinning is gone. See
[`ike-parent/src/site/asciidoc/index.adoc`](ike-parent/src/site/asciidoc/index.adoc)
for the full design rationale.

## Doc as Code + LLM-Friendly

`ike-platform` is the IKE Network's parent-POM tier and follows
the doc-as-code philosophy: build conventions, documentation
standards, and AI-assistant guidance live as versioned Markdown
files in
[`ike-build-standards`](https://github.com/IKE-Network/ike-tooling/tree/main/ike-build-standards#readme)
and are unpacked into every project that inherits `ike-parent`
(into `.claude/standards/` at `validate` phase). When a developer —
or Claude itself — opens an IKE project, the agent reads those
standards and applies them automatically; contributors don't have
to memorize the conventions.

The standards most directly relevant to `ike-platform` are
[`IKE-MAVEN.md`](https://github.com/IKE-Network/ike-tooling/blob/main/ike-build-standards/src/main/standards/IKE-MAVEN.md)
(IKE-specific Maven conventions),
[`IKE-RELEASE.md`](https://github.com/IKE-Network/ike-tooling/blob/main/ike-build-standards/src/main/standards/IKE-RELEASE.md)
(cascade and recovery procedures), and
[`IKE-CLASSIFIERS.md`](https://github.com/IKE-Network/ike-tooling/blob/main/ike-build-standards/src/main/standards/IKE-CLASSIFIERS.md)
(artifact classifier conventions). See the
[full inventory](https://github.com/IKE-Network/ike-tooling/tree/main/ike-build-standards#readme).

## Links

- **Documentation:** [`https://ike.network/ike-platform/`](https://ike.network/ike-platform/)
  - [`ike-parent`](https://ike.network/ike-platform/ike-parent/) — parent POM rationale + reference
  - [`ike-workspace-maven-plugin`](https://ike.network/ike-platform/ike-workspace-maven-plugin/) — `ws:*` goal reference
  - [`ike-bom`](https://ike.network/ike-platform/ike-bom/) — BOM coordinates
- **Build standards:** [`ike-build-standards`](https://ike.network/ike-tooling/ike-build-standards/)
- **Issues:** [`IKE-Network/ike-issues`](https://github.com/IKE-Network/ike-issues) (cross-project tracker)
- **Source:** [`IKE-Network/ike-platform`](https://github.com/IKE-Network/ike-platform)

## History

Split from the archived `ike-pipeline` repo to resolve a Maven
extension-plugin reactor-load cycle. See
[`IKE-Network/ike-issues#216`](https://github.com/IKE-Network/ike-issues/issues/216)
and `dev-ike-repo-split-architecture` in `ike-lab-documents/topics/`.
