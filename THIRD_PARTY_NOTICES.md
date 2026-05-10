# Third-Party Notices — IKE Platform

Three layers of attribution ship with each release:

1. **Software Bill of Materials (CycloneDX, machine-readable):**
   - https://ike.network/ike-platform/bom.json
   - https://ike.network/ike-platform/bom.xml
   - Full transitive dependency graph, SPDX-normalized licenses, artifact hashes.
   - Also reachable as a Maven artifact with `<classifier>cyclonedx</classifier>`.

2. **Maven Site dependency report (HTML, human-browseable):**
   - https://ike.network/ike-platform/dependencies.html
   - https://ike.network/ike-platform/licenses.html
   - https://ike.network/ike-platform/dependency-management.html (BOM-managed entries)

3. **Curated Third-Party Notices (this document):**
   - **Current release:** https://ike.network/ike-platform/THIRD_PARTY_NOTICES.html
   - **Versioned:** https://ike.network/ike-platform/&lt;version&gt;/THIRD_PARTY_NOTICES.html
   - **Latest:** https://ike.network/ike-platform/latest/THIRD_PARTY_NOTICES.html
   - The source AsciiDoc lives at [`src/site/asciidoc/THIRD_PARTY_NOTICES.adoc`](src/site/asciidoc/THIRD_PARTY_NOTICES.adoc).

## What's covered

The curated document acknowledges third-party open-source software
that consumers of `ike-parent` / `ike-bom` pick up indirectly: the
Java language and runtime, BOM-managed dependency families (Jackson,
SLF4J/Logback, Commons, Guava), test frameworks, signing
infrastructure (Bouncy Castle), and the AsciiDoc/Site components
reachable through the `ike-parent` consumer chain.

For corresponding notices in the rest of the IKE platform see:

- [ike-tooling](https://ike.network/ike-tooling/THIRD_PARTY_NOTICES.html) — Maven build infrastructure, plugin core, signing.
- [ike-docs](https://ike.network/ike-docs/THIRD_PARTY_NOTICES.html) — AsciiDoc rendering chain, fonts, DocBook, frontend assets.

Issues or omissions: file at https://github.com/IKE-Network/ike-issues.
