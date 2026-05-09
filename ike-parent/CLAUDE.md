# IKE Parent — Claude Standards

## Initial Setup — ALWAYS DO THIS FIRST

Run `mvn validate` before any other work. This unpacks current build
standards into `.claude/standards/`. Do not proceed without this step.

If `mvn validate` fails because `ike-build-standards` is not in the
local repository, install it first:

```bash
mvn install -f ../ike-build-standards/pom.xml
```

After validate completes, read and follow these files in `.claude/standards/`:

- MAVEN.md — Maven 4 build standards (always read)
- IKE-MAVEN.md — IKE-specific Maven conventions (always read)

## Module Overview

Root parent POM for all projects inheriting IKE build conventions.
Declares centralized dependency versions in `<dependencyManagement>`,
the plugin management matrix (including upstream IKE plugins from
`ike-tooling` and `ike-docs` at `${...}` property versions), and the
AsciiDoc documentation pipeline.

- **Artifact**: `network.ike.platform:ike-parent`
- **Packaging**: POM

## Key Conventions

- Dependency versions are managed inline in `<dependencyManagement>`.
- Upstream IKE plugins resolve via property indirection:
  `ike-maven-plugin` at `${ike-tooling.version}`,
  `ike-doc-maven-plugin` at `${ike-docs.version}`. Both are regular
  managed plugins — no `<extensions>true</extensions>`, no custom
  packaging contributed to the build extension realm.
- `ike-workspace-maven-plugin` is a sibling module in this reactor
  and uses `${project.version}`.
- All modules in this reactor share the unified `ike-platform`
  version.
- Projects inheriting `ike-parent` get managed versions
  automatically.

## History

Earlier revisions of this POM declared `ike-maven-plugin` and
`ike-doc-maven-plugin` with `<extensions>true</extensions>` at
literal versions, because Maven resolves extension plugins at
project-load time, before property interpolation. That
declaration registered the custom `<packaging>ike-doc</packaging>`
type for documentation modules. The custom packaging was retired
in `IKE-Network/ike-issues#321` in favor of a classifier-canonical
doc shape (`<classifier>adoc</classifier><type>zip</type>`); both
`<extensions>true</extensions>` declarations were dropped in the
same migration. See
`ike-parent/src/site/asciidoc/index.adoc` for the full design
rationale.

## Build

```bash
mvn install
```
