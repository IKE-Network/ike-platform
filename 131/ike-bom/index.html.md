---
date_published: 2026-07-02
date_modified: 2026-07-02
canonical_url: https://ike.network/ike-platform/ike-bom/index.html
---

# IKE Bill of Materials

The `ike-bom` artifact is a Maven Bill of Materials that pins the versions of every IKE-published artifact. External projects import it once and inherit a coherent set of versions for `ike-parent`, `ike-workspace-maven-plugin`, the `ike-tooling` family, and the `ike-docs` family.

| Coordinate | Value |
| --- | --- |
| Group ID | `network.ike.platform` |
| Artifact ID | `ike-bom` |
| Packaging | `pom` |

## [#when-to-use-it](#when-to-use-it)When to use it

If your project consumes more than one IKE artifact and you want a single coordinated upgrade vector, import the BOM:

```
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>network.ike.platform</groupId>
            <artifactId>ike-bom</artifactId>
            <version>21</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

After importing, declare IKE dependencies **without** a version — the BOM resolves them:

```
<dependencies>
    <dependency>
        <groupId>network.ike.tooling</groupId>
        <artifactId>ike-workspace-model</artifactId>
        <!-- version inherited from ike-bom -->
    </dependency>
</dependencies>
```

Bumping all IKE artifact versions then becomes a single edit to the BOM coordinate.

## [#bom-vs-ike-parent](#bom-vs-ike-parent)BOM vs. ike-parent

Two ways to consume IKE versions; pick the one that matches how your project relates to IKE:

| Mechanism | When to use | What you get |
| --- | --- | --- |
| `<parent>ike-parent</parent>` | You’re an IKE-aligned project that wants build conventions **and** dependency versions. | Java 25 compiler, GPG/BC signer, AsciiDoc pipeline, JaCoCo, surefire/failsafe/javadoc configs, **plus** dependency versions. |
| `<scope>import</scope>` of `ike-bom` | You’re an external project that wants only the dependency versions, not the build conventions. | Just the version pins. Your build conventions stay your own. |

External consumers (downstream of the IKE platform but not part of it) typically import the BOM. Projects inside the IKE platform inherit `ike-parent`.

## [#whats-pinned](#whats-pinned)What’s pinned

The BOM pins versions for:

- **IKE Tooling** — `ike-maven-plugin`, `ike-workspace-model`, `ike-build-standards` (claude/docs/config/asciidoctorconfig/scaffold classifiers), `ike-maven-plugin-support`.
- **IKE Docs** — `ike-doc-maven-plugin`, `koncept-asciidoc-extension`, `ike-doc-resources`, `minimal-fonts`, `docbook-xsl`, `semantic-linebreak`.
- **IKE Platform** — `ike-parent`, `ike-workspace-maven-plugin`.

The complete inventory is generated from the BOM’s `<dependencyManagement>` during the build; see the [Dependency Info](dependency-info.html)[1] report for the current concrete version table.

## [#generation](#generation)Generation

The BOM is auto-generated from the `<dependencyManagement>` of the parent reactor by `ike:generate-bom` (from the `ike-maven-plugin`). This means the BOM and the parent POM cannot drift apart — every dependency version managed for internal use is also pinned for external consumers.

## [#source](#source)Source

- GitHub: [ike-platform/ike-bom](https://github.com/IKE-Network/ike-platform/tree/main/ike-bom)[2]
- Issues: [IKE-Network/ike-issues](https://github.com/IKE-Network/ike-issues)[3]
