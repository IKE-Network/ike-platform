# IKE Platform

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Documentation](https://img.shields.io/badge/docs-ike.network%2Fike--platform-blue)](https://ike.network/ike-platform/)
[![IKE Network](https://img.shields.io/badge/IKE-Network-green)](https://ike.network/)

Parent POM, BOM, and workspace-orchestration plugin for the IKE
Community build pipeline.

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

Downstream projects inherit from `ike-parent`:

```xml
<parent>
    <groupId>network.ike.platform</groupId>
    <artifactId>ike-parent</artifactId>
    <version>1</version>
</parent>
```

Consumers who do not want to inherit `ike-parent`'s build conventions
can still align dependency versions by importing the BOM:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>network.ike.platform</groupId>
            <artifactId>ike-bom</artifactId>
            <version>1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## Release Cascade

```
ike-tooling → ike-docs → [ike-platform] → { doc-example, example-project } → ike-example-ws
```

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

## Links

- **Documentation:** [`https://ike.network/ike-platform/`](https://ike.network/ike-platform/)
  - [`ike-parent`](https://ike.network/ike-platform/ike-parent/) — parent POM rationale + reference
  - [`ike-workspace-maven-plugin`](https://ike.network/ike-platform/ike-workspace-maven-plugin/) — `ws:*` goal reference
  - [`ike-bom`](https://ike.network/ike-platform/ike-bom/) — BOM coordinates
- **Issues:** [`IKE-Network/ike-issues`](https://github.com/IKE-Network/ike-issues) (cross-project tracker)
- **Source:** [`IKE-Network/ike-platform`](https://github.com/IKE-Network/ike-platform)

## History

Split from the archived `ike-pipeline` repo to resolve a Maven
extension-plugin reactor-load cycle. See
[`IKE-Network/ike-issues#216`](https://github.com/IKE-Network/ike-issues/issues/216)
and `dev-ike-repo-split-architecture` in `ike-lab-documents/topics/`.
