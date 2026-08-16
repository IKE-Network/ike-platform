---
date_published: 2026-08-15
date_modified: 2026-08-15
canonical_url: https://ike.network/ike-platform/ike-parent/index.html
---

# IKE Parent POM

`ike-parent` is the standard parent POM for IKE Network projects. Inheriting it provides three things through a single `<parent>` declaration: build conventions, dependency version management, and the AsciiDoc documentation pipeline.

| Coordinate | Value |
| --- | --- |
| Group ID | `network.ike.platform` |
| Artifact ID | `ike-parent` |
| Packaging | `pom` |

## [#usage](#usage)Usage

```
<parent>
    <groupId>network.ike.platform</groupId>
    <artifactId>ike-parent</artifactId>
    <version>21</version>
</parent>
```

After inheriting, declare IKE dependencies without versions — `ike-parent’s `<dependencyManagement>` resolves them:

```
<dependency>
    <groupId>network.ike.docs</groupId>
    <artifactId>koncept-asciidoc-extension</artifactId>
    <!-- version inherited -->
</dependency>
```

## [#what-you-get](#what-you-get)What you get

### [#build-conventions](#build-conventions)Build conventions

- **Java 25 compiler** with `--enable-preview` opt-in via property.
- **ASM 9.9** in surefire/failsafe/compiler plugin realms — the release that supports classfile major 69 (Java 25).
- **Surefire/failsafe** with JPMS `--add-opens` for tests that need reflective access.
- **JaCoCo** for coverage. Skip with `-Dike.skip.jacoco=true`.
- **GPG signing** via Bouncy Castle (BC) — required for parallel-safe signing during release. Native `gpg` is **not** a substitute.
- **Javadoc strictness profiles** — lenient by default; opt into strict (`fail-on-warnings`) per-project.
- **Reproducible-build timestamp** (`project.build.outputTimestamp`) stamped during release for byte-identical artifacts across machines.

### [#dependency-version-management](#dependency-version-management)Dependency version management

Inline `<dependencyManagement>` for the IKE ecosystem:

- **IKE Tooling** — `ike-maven-plugin`, `ike-workspace-model`, `ike-build-standards` (multiple classifiers), `ike-maven-plugin-support`.
- **IKE Docs** — `ike-doc-maven-plugin`, `koncept-asciidoc-extension`, `ike-doc-resources`, `minimal-fonts`, `docbook-xsl`, `semantic-linebreak`.
- **AsciiDoc toolchain** — `asciidoctor-maven-plugin`, `asciidoctorj`, `asciidoctorj-pdf`, `asciidoctorj-diagram`, `jruby`.
- **Test dependencies** — JUnit 5, AssertJ, Mockito.

### [#asciidoc-documentation-pipeline](#asciidoc-documentation-pipeline)AsciiDoc documentation pipeline

When a module has `src/docs/asciidoc/` (or `src/site/asciidoc/`), the AsciiDoc pipeline activates automatically:

- HTML rendering via `asciidoctor-maven-plugin`.
- Optional PDF rendering via Prawn or FOP backends.
- Kroki diagram rendering for PlantUML, Graphviz, etc.
- Site integration so authored `.adoc` files render as part of `mvn site` alongside the auto-generated maven-site-plugin pages.

### [#upstream-ike-plugins](#upstream-ike-plugins)Upstream IKE plugins

Two upstream plugins are declared in `<pluginManagement>` for inheriting projects to invoke as needed:

- `network.ike.tooling:ike-maven-plugin` at `248` — `ike:*` goals (`release-publish`, `prepare-release`, `inject-breadcrumb`, etc.).
- `network.ike.docs:ike-doc-maven-plugin` at `104` — `idoc:*` render goals (AsciiDoc, multi-renderer PDF wrappers, etc.).

Both are **regular plugins** — no `<extensions>true</extensions>`, no custom packaging contributed to the build extension realm. Their `<version>` interpolates from `${…​}` properties like any other managed plugin, and ordinary version-property tooling (`ws:align-publish`, `versions:set-property`) maintains them across releases.

Earlier revisions of `ike-parent` declared both plugins with `<extensions>true</extensions>` to register custom packaging types (`<packaging>ike-doc</packaging>` for documentation modules) into the build extension realm. That declaration came with a structural constraint: Maven resolves extension plugin JARs at project-load time, **before** the Model Builder runs property interpolation, which forced the `<version>` element to be a literal string rather than a `${property}` reference. Across the cross-repo cascade (`ike-tooling` → `ike-docs` → `ike-platform` → consumers), the literal pin became toxic — every consumer POM had to carry an unmaintainable literal version of every upstream extension plugin.

The constraint was eliminated in [ike-issues#321](https://github.com/IKE-Network/ike-issues/issues/321)[1]. The custom `<packaging>ike-doc</packaging>` was retired in favor of a classifier-canonical doc shape (`<classifier>adoc</classifier><type>zip</type>`), which removed the structural reason for `<extensions>true</extensions>` to exist on either upstream plugin. With the extension realm gone, both plugins are now ordinary managed plugins.

See [Design rationale](#design-rationale) below for the full discussion. The classifier-canonical migration also resolved [#220](https://github.com/IKE-Network/ike-issues/issues/220)[2] (asciidoc.zip-as-primary), [#236](https://github.com/IKE-Network/ike-issues/issues/236)[3] (extensions=true literals silently lag), and [#320](https://github.com/IKE-Network/ike-issues/issues/320)[4] (stale components.xml in ike-maven-plugin).

## [#ike-parent-vs-ike-bom](#ike-parent-vs-ike-bom)ike-parent vs. ike-bom

External consumers have two ways to align with IKE versions:

| Mechanism | When to use | What you get |
| --- | --- | --- |
| `<parent>ike-parent</parent>` | You’re an IKE-aligned project; you want build conventions **and** versions. | Compiler config, signing, AsciiDoc pipeline, **plus** dependency version pins. |
| `<scope>import</scope>` of `ike-bom` | You’re an external project; you want only the version pins, not the build conventions. | Just the version pins. Your build conventions stay your own. |

If you want both build conventions and version alignment, inherit `ike-parent`. If you want only the versions, see [ike-bom](../ike-bom/index.html)[5].

## [#design-rationale](#design-rationale)Design rationale

This section explains why `ike-parent` no longer registers a custom `<packaging>ike-doc</packaging>` type, why neither upstream plugin needs `<extensions>true</extensions>`, and why doc artifacts are now classifier-canonical. The reasoning lives here (rather than only in the tracking issue) so it survives after the issue closes and so anyone picking up the project can understand the design without spelunking through GitHub history.

### [#three-module-shapes-after-the-migration](#three-module-shapes-after-the-migration)Three module shapes after the migration

The doc-pipeline activates path-conditionally on `<file><exists>src/docs/asciidoc</exists></file>` — **not** on `<packaging>` — so all three module shapes coexist under one mechanism:

| Module type | `<packaging>` | Primary artifact | `adoc` classifier |
| --- | --- | --- | --- |
| Doc-only (assembly, brief, topics) | `pom` | none | yes |
| Hybrid (Java plus reference docs) | `jar` | jar | yes |
| Pure code (no `src/docs/asciidoc/`) | `jar` | jar | no |

The `adoc` classifier is the canonical **source** payload. Renderer outputs (Prince/FOP/XEP/AH/WeasyPrint PDFs, HTML) attach as additional classifiers exactly as before. Consumers depend on whichever shape they need.

### [#why-no-extensionstrue-extensions](#why-no-extensionstrue-extensions)Why no `<extensions>true</extensions>`

A plugin with `<extensions>true</extensions>` is loaded into the build extension realm at **project-load time** — during Maven’s “Scanning for projects” phase, before the Model Builder runs property interpolation. This forces the plugin’s `<version>` to be a literal string in the POM; a `${property}` reference produces unresolved-version failures.

For a single-repo reactor, the constraint is manageable. For our cross-repo cascade (`ike-tooling` → `ike-docs` → `ike-platform` → consumers), it became toxic: every consumer POM had to carry the literal version of every upstream extension plugin, the literal had to be kept in sync manually across repos, and routine version-property tooling (`ws:align-publish`, `versions:set-property`) was structurally unable to maintain it. The pain surfaced as [ike-issues#236](https://github.com/IKE-Network/ike-issues/issues/236)[3] — pre-flight release checks repeatedly catching stale literals after upstream releases that the alignment tooling could not see.

### [#why-no-packagingike-doc-packaging](#why-no-packagingike-doc-packaging)Why no `<packaging>ike-doc</packaging>`

The historical reason for `<extensions>true</extensions>` here was to register a custom `<packaging>ike-doc</packaging>` type for documentation modules — a packaging that produces a `.zip` of AsciiDoc sources as the primary artifact, with a lean lifecycle that skips compile/test phases.

Inspection revealed two structural problems with that frame:

1. **The custom packaging produced a primary artifact nobody used.** Across 43 `<packaging>ike-doc</packaging>` modules in `ike-lab-documents`, **zero** dependencies referenced `<type>ike-doc</type>`. Consumers exclusively used a parallel `<classifier>asciidoc</classifier><type>zip</type>` attachment (built by `maven-assembly-plugin` from the same source directory). Same content, two coordinates, redundant.
2. **Custom packaging cannot serve hybrid modules.** `<packaging>` is single-valued: a Java module that ships reference docs cannot be both `jar` and `ike-doc`. Hybrid modules are the norm in any non-trivial project. Every comparable docs-as-code project in the JVM ecosystem (Hibernate, Eclipse Collections, Vert.x, Quarkus, OptaPlanner, Apache projects) handles this via classifier attachments for exactly this reason. No project in the surveyed set invents a custom packaging type for documentation.

The architectural decision was therefore to retire `<packaging>ike-doc</packaging>` in favor of a classifier-canonical shape: `<classifier>adoc</classifier><type>zip</type>` attached to either a `<packaging>pom</packaging>` (doc-only) or `<packaging>jar</packaging>` (hybrid) primary. With no custom packaging type to register, neither upstream plugin needs `<extensions>true</extensions>`. With the extension realm gone, literal-version pinning is gone with it.

### [#the-artifact-uniformity-argument-for-staying-maven](#the-artifact-uniformity-argument-for-staying-maven)The artifact-uniformity argument for staying Maven-canonical

A natural objection: most large JVM projects (Spring, many Apache projects, Quarkus) publish docs as static sites to gh-pages or CDN-hosted destinations rather than as Maven artifacts. Why not follow that pattern?

Examined point-by-point, those rationales do not transfer. Most of the apparent reasons (continuous publication, search-driven discovery, contributor friction, CDN economics) have Maven-snapshot-plus-post-deploy-rsync solutions and so do not argue against staying inside the Maven artifact ecosystem. What genuinely remains as a non-Maven argument — Antora as a turnkey product — does not apply here, because the IKE rendering pipeline is already built (`ike-parent’s render profiles, Prince/FOP/XEP chains, knowledge.design rsync deploy, the asciidoctor toolchain). The “Antora is turnkey” argument compares a not-yet-built pipeline to a turnkey product; for IKE, both alternatives are already built, and the comparison does not apply.

For our constraints — small team, cross-repo doc dependencies via topic libraries, an already-built rendering pipeline, and a standing commitment to artifact uniformity — staying inside the Maven artifact ecosystem is the rigorous choice. Every shipped artifact, code or documentation, goes through one pipeline: signed (Bouncy Castle GPG, parallel-safe), versioned, deployed to Nexus, mirrorable, and reproducibly buildable years later. We do not want one class of output operating under different rules than another. The single-pipeline discipline is the standard we are protecting.

The classifier-canonical shape **is** fully Maven-canonical — every artifact deploys to Nexus, signed and versioned, just like every other output of the project. The choice between custom packaging and classifier is not a Maven-vs-non-Maven choice; both stay inside the Maven ecosystem. Custom packaging was buying an aesthetic type-system marker; classifier-canonical buys a single mechanism that handles doc-only and hybrid uniformly.

### [#tracking](#tracking)Tracking

- [ike-issues#321](https://github.com/IKE-Network/ike-issues/issues/321)[1] — primary tracking issue (umbrella); subsumes #220, #236, #320.
- [ike-issues#216](https://github.com/IKE-Network/ike-issues/issues/216)[6] — repo split that established the cross-repo boundary the extension realm was working around.
- Design note: `dev-classifier-canonical-doc-shape` in `ike-lab-documents/topics/` (full Socratic discovery captured for posterity).

## [#source](#source)Source

- GitHub: [ike-platform/ike-parent](https://github.com/IKE-Network/ike-platform/tree/main/ike-parent)[7]
- Issues: [IKE-Network/ike-issues](https://github.com/IKE-Network/ike-issues)[8]
