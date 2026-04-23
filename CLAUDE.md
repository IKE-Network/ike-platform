# IKE Platform — Claude Standards

## Initial Setup — ALWAYS DO THIS FIRST

Run `mvn validate` before any other work. This unpacks the current
build standards into `.claude/standards/` for each module via
`ike-build-standards` (from `network.ike.tooling`, unpacked through
`maven-dependency-plugin`). Do not proceed without this step.

If `mvn validate` fails because `ike-build-standards` is not in the
local repository, either fetch it from Nexus or install it from the
`ike-tooling` workspace:

```bash
# Via Nexus (default):
mvn dependency:resolve -Dartifact=network.ike.tooling:ike-build-standards:${ike-tooling.version}:zip

# Or locally from the ike-tooling checkout:
mvn install -pl ike-build-standards -f ../../pipeline-ws/ike-tooling/pom.xml
```

After validate completes, read and follow these files in `.claude/standards/`:

- MAVEN.md — Maven 4 build standards (always read)
- IKE-MAVEN.md — IKE-specific Maven conventions (always read)

Read these additional files when working on Java code:

- JAVA.md — Java 25 standards
- IKE-JAVA.md — IKE-specific Java patterns

Do not read other files in that directory unless specifically relevant
to the task you are performing.

## Project Overview

This is **IKE Platform** — a Maven 4 reactor that hosts the
IKE Community's parent POM, its BOM, and the workspace-orchestration
Maven plugin. It is the hub that ties `ike-tooling` and `ike-docs`
together into a single set of build conventions that downstream
projects (e.g., `ike-lab-documents`, `doc-example`, `example-project`)
inherit.

Split from the archived `ike-pipeline` repo to resolve a fundamental
Maven `<extensions>true</extensions>` reactor-load cycle where the
old `ike-pipeline` declared a sibling module with `extensions=true`
that Maven attempted to resolve before its own reactor had built it.
See `dev-ike-repo-split-architecture` in `ike-lab-documents/topics/`
and `IKE-Network/ike-issues#216`.

### Module Structure

Subprojects build in this order:

| Module | Purpose | Packaging |
|---|---|---|
| `ike-parent` | The parent POM — dependency and plugin management, profiles | POM |
| `ike-workspace-maven-plugin` | `ws:*` goals for multi-repo workspaces | maven-plugin |
| `ike-bom` | Auto-generated BOM for external consumers (`<scope>import</scope>`) | POM |

### The extensions=true Story

`ike-parent`'s `<pluginManagement>` declares
`network.ike.docs:ike-doc-maven-plugin` with
`<extensions>true</extensions>` at a **literal** version
(`<version>1</version>`, not `${ike-docs.version}`). Maven resolves
extension plugins at project-load time, before property interpolation,
so property references there cause unresolved-version build failures.

Because `ike-doc-maven-plugin` now lives in `ike-docs` (a separate
repo), its JAR is already released to Nexus by the time `ike-platform`
builds. No intra-reactor cycle.

### Release Cascade Position

```
ike-tooling → ike-docs → [ike-platform] → { doc-example, example-project } → ike-example-ws
```

`ike-platform` must release after `ike-docs` (so the doc plugin JAR
is available on Nexus) and after `ike-tooling` (whose plugins this
POM declares at literal versions). Downstream example projects and
the workspace aggregator consume `ike-parent` and/or `ike-bom`.

### Dependencies on Other Repos

- `network.ike.tooling:ike-maven-plugin` — release orchestration,
  BOM generation, site deploy. Declared at literal
  `<version>125</version>` in `ike-parent`'s `<pluginManagement>`.
- `network.ike.tooling:ike-build-standards` — versioned Claude
  instruction files and build-config ZIPs.
- `network.ike.docs:ike-doc-maven-plugin` — the `ike-doc` custom
  packaging handler (extensions=true). Literal version `1`.
- `network.ike.docs:*` — managed in ike-parent's dependencyManagement
  at `${ike-docs.version}`.

## Key Build Commands

```bash
# Full reactor:
mvn clean install

# Only the workspace plugin:
mvn install -pl ike-workspace-maven-plugin -am

# Only the BOM:
mvn install -pl ike-bom -am

# Skip tests during fast iteration:
mvn install -DskipTests
```

## Project-Specific Context

- Group ID: `network.ike.platform`
- Model version: `4.1.0` for all POMs
- Java version: 25 (ike-workspace-maven-plugin)
- Version strategy: single-segment integer (starts at 1). Not semver.
- All subprojects are versionless — root version is the single source
  of truth.

## `.mvn/jvm.config` constraints

Maven's `.mvn/jvm.config` is parsed as raw JVM arguments — one token
per line, NO comment syntax. A `#` at column 0 is passed to the JVM
as if it were a main-class name, and IntelliJ will show:

```
Error: Could not find or load main class #
Caused by: java.lang.ClassNotFoundException: #
```

Do NOT add `#`-prefixed comments to `.mvn/jvm.config`. The current
file contains exactly one argument:

- `--sun-misc-unsafe-memory-access=allow` — suppresses the JFFI
  `sun.misc.Unsafe` deprecation warnings emitted by
  JRuby/AsciidoctorJ on Java 24+.

Also do NOT set `-Denv.PATH` or PATH-related options here or in
`MAVEN_OPTS`: PATH entries containing spaces (e.g. JetBrains
Toolbox) cause the JVM launcher to bail with the same
"Could not find or load main class" error for an unrelated reason.

## Workspace Tooling

`ike-workspace-maven-plugin` (prefix `ws:`) is built in this repo.
Use `ws:*` goals from workspace aggregators (e.g., `ike-example-ws`)
to orchestrate cross-repo releases and feature branching. Never
invoke raw `git` for workspace-wide operations — always use the `ws:`
goals.

`ike-maven-plugin` (prefix `ike:`) is consumed from `ike-tooling` —
`ike:prepare-release`, `ike:release-status`, etc., drive the release
of *this* repo.

`ike-doc-maven-plugin` (prefix `idoc:`) is consumed from `ike-docs`
via the extensions=true declaration in `ike-parent`.
