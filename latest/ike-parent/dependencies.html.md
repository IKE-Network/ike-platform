---
date_published: 2026-06-23
date_modified: 2026-06-23
canonical_url: https://ike.network/ike-platform/ike-parent/dependencies.html
---

# Dependencies (SBOM)

Full transitive dependency graph for `ike-parent` 115, generated from [bom.json](bom.json)[1] (CycloneDX 1.6) at build time. Same SBOM source as the SPDX-grouped [licenses.html](licenses.html)[2] and the curated [built-with.html](built-with.html)[3] — three views of the same data.

## [#summary](#summary)Summary

| Total components | 6 |
| --- | --- |
| Distinct license expressions | 1 |

## [#components](#components)Components

Sorted by group, artifact, version. Click [bom.json](bom.json)[1] for the raw machine-readable form (Dependency-Track, Trivy, Snyk, GitHub dep-graph all ingest it directly).

| Group | Artifact | Version | License | Type |
| --- | --- | --- | --- | --- |
| `network.ike` | `ike-base-parent` | `15` | `Apache-2.0` | library |
| `network.ike.tooling` | `ike-build-standards` | `225` | `Apache-2.0` | library |
| `network.ike.tooling` | `ike-build-standards` | `225` | `Apache-2.0` | library |
| `network.ike.tooling` | `ike-build-standards` | `225` | `Apache-2.0` | library |
| `network.ike.tooling` | `ike-build-standards` | `225` | `Apache-2.0` | library |
| `network.ike.tooling` | `ike-build-standards` | `225` | `Apache-2.0` | library |

## [#download](#download)Download

- [Software Bill of Materials (CycloneDX, JSON)](bom.json)[1] — raw machine-readable form. Includes purls, hashes, and dependency-graph edges that this page summarizes.
- [bom.xml](bom.xml)[4] — same content in XML.
- As a Maven artifact: pull `ike-parent:​115` with `<classifier>cyclonedx</classifier><type>json</type>` from Nexus / Maven Central.

## [#see-also](#see-also)See also

- [Licenses (SPDX)](licenses.html)[2] — same components grouped by license expression.
- [Built With](built-with.html)[3] — curated narrative + per-license summary.
- [ike-issues#341](https://github.com/IKE-Network/ike-issues/issues/341)[5] — the issue that introduced this page.
