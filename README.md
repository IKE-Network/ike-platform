# IKE Platform

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

Release order is enforced by the literal version pins in `ike-parent`'s
`<pluginManagement>`: `ike-maven-plugin` and `ike-doc-maven-plugin` are
pinned to specific released versions (not `${...}` properties), because
Maven resolves `<extensions>true</extensions>` plugins before property
interpolation.

## History

Split from the archived `ike-pipeline` repo to resolve a Maven
extension-plugin reactor-load cycle. See
[`IKE-Network/ike-issues#216`](https://github.com/IKE-Network/ike-issues/issues/216)
and `dev-ike-repo-split-architecture` in `ike-lab-documents/topics/`.
