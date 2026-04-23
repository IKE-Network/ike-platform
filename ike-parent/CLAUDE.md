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
the plugin management matrix (including the `ike-doc-maven-plugin`
external-extensions declaration from `ike-docs`), and the AsciiDoc
documentation pipeline.

- **Artifact**: `network.ike.platform:ike-parent`
- **Packaging**: POM

## Key Conventions

- Dependency versions are managed inline in `<dependencyManagement>`
- The `ike-doc-maven-plugin` declaration uses a **literal** version
  (not a property) because Maven resolves `<extensions>true</extensions>`
  plugins before property interpolation
- `ike-maven-plugin`, `ike-workspace-maven-plugin`, and
  `ike-doc-maven-plugin` all co-release on their respective repo
  cadences; this POM pins each at a literal version
- All modules in this reactor share the unified `ike-platform` version
- Projects inheriting ike-parent get managed versions automatically

## Build

```bash
mvn install
```
