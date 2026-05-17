---
date_published: 2026-05-16
date_modified: 2026-05-16
canonical_url: https://ike.network/ike-platform/ike-parent/built-with.html
---

# Built With

Open-source software that `ike-parent` 69 depends on, links against, ships within, or invokes at runtime.

Three layers of attribution ship with each release:

- [Software Bill of Materials (CycloneDX, JSON)](bom.json)[1] — full transitive dependency graph with SPDX-normalized licenses and artifact hashes. Ingestible by Dependency-Track, Trivy, Snyk, GitHub’s dependency graph.
- [Licenses (SPDX)](licenses.html)[2] — human-readable SPDX-grouped view of declared dependencies, generated from `bom.json` (#335).
- This page — curated companion covering what mechanical reports can’t see (Maven Site skin, external services, fonts inside artifacts, frontend assets in rendered HTML).

## [#curated-narrative](#curated-narrative)Curated narrative

Components covered by the project-wide supplement at `src/main/built-with/supplement.yaml`. These are the components that don’t appear in `bom.json` because they aren’t Maven artifacts (external services, fonts inside classifier ZIPs, runtime binaries, frontend assets).

### [#java-language-and-runtime](#java-language-and-runtime)Java language and runtime

| Component | License | Role |
| --- | --- | --- |
| [OpenJDK 25](https://openjdk.org/)[3] | `GPL-2.0-only WITH Classpath-exception-2.0` | The Java SDK and runtime targeted by IKE consumers. Build uses --enable-preview for opt-in language preview features. The Classpath Exception is what makes OpenJDK usable from non-GPL consumers without infecting their licensing. |

### [#maven-build-infrastructure](#maven-build-infrastructure)Maven build infrastructure

| Component | License | Role |
| --- | --- | --- |
| [Apache Maven core](https://maven.apache.org/)[4] | `Apache-2.0` | Build orchestration. ike-parent is a Maven 4 (modelVersion 4.1.0) POM consumed by every project that wants IKE conventions. |
| [Maven Site Plugin](https://maven.apache.org/plugins/maven-site-plugin/)[5] | `Apache-2.0` | Site rendering and report generation. |
| [Maven Project Info Reports Plugin](https://maven.apache.org/plugins/maven-project-info-reports-plugin/)[6] | `Apache-2.0` | Produces dependencies.html, plugins.html, dependency-info.html and the rest of the standard reports under Project Reports. |
| [Maven Dependency Plugin](https://maven.apache.org/plugins/maven-dependency-plugin/)[7] | `Apache-2.0` | Used by ike-parent to unpack the site-theme, asciidoctorconfig, and font classified artifacts at build phases. |
| [Maven Compiler / Surefire / Failsafe / Jar / Resources / Install / Deploy / Clean Plugins](https://maven.apache.org/plugins/)[8] | `Apache-2.0` | Standard Maven lifecycle plugins, version-pinned in ike-parent pluginManagement so consumers inherit consistent versions. |
| [Maven Source / Javadoc / GPG Plugins](https://maven.apache.org/plugins/)[8] | `Apache-2.0` | Source jar, javadoc, and (with the BC signer) signing during release. |
| [CycloneDX Maven Plugin](https://github.com/CycloneDX/cyclonedx-maven-plugin)[9] | `Apache-2.0` | Generates the SBOM (bom.json + bom.xml) at package phase per ike-issues#333. |
| [wagon-ssh-external](https://maven.apache.org/wagon/wagon-providers/wagon-ssh-external/)[10] | `Apache-2.0` | Provides the scpexe:// protocol for site:deploy to the internal mirror at ike.komet.sh. Registered as a Maven build extension (.mvn/extensions.xml). Distributed to consumer projects via the ike-build-standards scaffold (ike-issues#338). |

### [#cryptographic-signing](#cryptographic-signing)Cryptographic signing

| Component | License | Role |
| --- | --- | --- |
| [Bouncy Castle (bcprov-jdk18on, bcpg-jdk18on)](https://www.bouncycastle.org/)[11] | `MIT` | OpenPGP signing engine activated via <signer>bc</signer> on maven-gpg-plugin. Required because the native gpg signer serializes through gpg-agent and breaks parallel reactor builds. Bouncy Castle distributes under an MIT-X11 adaptation; SPDX classifies as MIT. |

### [#java-annotation-processing-and-bytecode](#java-annotation-processing-and-bytecode)Java annotation processing and bytecode

| Component | License | Role |
| --- | --- | --- |
| [ASM 9.x](https://asm.ow2.io/)[12] | `BSD-3-Clause` | Bytecode manipulation library. Pinned at the version compatible with the --release 25 compiler target so annotation processors on the build classpath don’t fail on newer class file versions. |
| [Project Lombok (consumer-optional)](https://www.lombok.org/)[13] | `MIT` | Annotation processor for boilerplate reduction. ike-bom manages the version; consumers opt in. |

### [#logging-json-and-runtime-utilities-bom-managed](#logging-json-and-runtime-utilities-bom-managed)Logging, JSON, and runtime utilities (BOM-managed)

| Component | License | Role |
| --- | --- | --- |
| [SLF4J API](https://www.slf4j.org/)[14] | `MIT` | Logging facade. |
| [Logback Classic / Core](https://logback.qos.ch/)[15] | `EPL-1.0 OR LGPL-2.1-or-later` | Default SLF4J implementation. |
| [Jackson (core, databind, annotations, jsr310, yaml)](https://github.com/FasterXML/jackson)[16] | `Apache-2.0` | JSON / YAML serialization stack. |
| [SnakeYAML](https://www.snakeyaml.org/)[17] | `Apache-2.0` | Underlying YAML parser used by Jackson YAML datatype. |
| [Apache Commons Lang / Commons IO](https://commons.apache.org/)[18] | `Apache-2.0` | Standard utility libraries surfaced through the BOM. |
| [Guava](https://github.com/google/guava)[19] | `Apache-2.0` | Collections and concurrency utilities, BOM-managed. |

### [#test-frameworks-bom-managed](#test-frameworks-bom-managed)Test frameworks (BOM-managed)

| Component | License | Role |
| --- | --- | --- |
| [JUnit 5 (Jupiter API, Engine, Platform)](https://junit.org/junit5/)[20] | `EPL-2.0` | Unit and integration test framework. |
| [AssertJ Core](https://assertj.github.io/)[21] | `Apache-2.0` | Fluent assertion library. |
| [Mockito Core, Mockito JUnit Jupiter](https://site.mockito.org/)[22] | `MIT` | Mocking framework for unit tests. |
| [Testcontainers](https://www.testcontainers.org/)[23] | `MIT` | Container-orchestrated integration tests, used by IKE projects that need PostgreSQL, Selenium, etc. BOM-managed for consumers that opt in. |

### [#asciidoc-and-maven-site-consumed-via-ike-parent](#asciidoc-and-maven-site-consumed-via-ike-parent)AsciiDoc and Maven Site (consumed via ike-parent)

| Component | License | Role |
| --- | --- | --- |
| [AsciidoctorJ](https://asciidoctor.org/)[24] | `MIT` | ike-parent activates the AsciiDoc pipeline through profiles when a consumer module has src/docs/asciidoc/ or src/site/asciidoc/. The toolchain itself lives in ike-docs (see that reactor’s built-with for full detail). |
| [AsciidoctorJ PDF / Diagram](https://github.com/asciidoctor/)[25] | `MIT` | PDF rendering and diagram support for AsciiDoc. |
| [JRuby](https://www.jruby.org/)[26] | `EPL-2.0 OR GPL-2.0-only OR LGPL-2.1-only` | Embedded Ruby runtime that Asciidoctor itself runs on. Pulled in transitively by AsciidoctorJ. The OR is consumer’s choice — for downstream users including IKE, EPL-2.0 is the most redistribution-friendly of the three. |
| [Sentry Maven Skin](https://github.com/sentrysoftware/maven-skins)[27] | `Apache-2.0` | The Maven Site skin every published page uses. Provides the navigation chrome, breadcrumbs, and template structure. The IKE Forest theme overlays Sentry’s defaults via the site-theme classifier produced by ike-build-standards (#318). |
| [Prism](https://prismjs.com/)[28] | `MIT` | Syntax highlighter for code blocks in rendered HTML. |
| [Font Awesome (free tier)](https://fontawesome.com/)[29] | `CC-BY-4.0 AND OFL-1.1 AND MIT` | Icon set used in rendered HTML. Different files under different licenses — icons under CC-BY-4.0; fonts under OFL-1.1; JS under MIT. |

## [#mechanical-inventory](#mechanical-inventory)Mechanical inventory

Direct dependencies of this module, grouped by SPDX expression. Generated from `bom.json` at build time.

| SPDX Expression | Components |
| --- | --- |
| `Apache-2.0` | 5 |
| **Total** | **5** |

For full per-component detail (group, artifact, version, hashes, transitive deps), see [bom.json](bom.json)[1] or [licenses.html](licenses.html)[2].

## [#related](#related)Related

- [site index](index.html)[30]
- [ike-issues#336](https://github.com/IKE-Network/ike-issues/issues/336)[31] — the issue that introduced this page (rename of the legacy "Third-Party Notices" to friendlier "Built With").
