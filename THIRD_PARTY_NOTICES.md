# Third-Party Notices — IKE ike-platform

This file is a discoverability stub for the legal-style filename
that license scanners look for at the repo root. The canonical,
rendered, friendly-named version is at:

- **Current release:** https://ike.network/ike-platform/built-with.html
- **Versioned:** https://ike.network/ike-platform/&lt;version&gt;/built-with.html
- **Latest:** https://ike.network/ike-platform/latest/built-with.html

Three layers of attribution ship with each release:

1. **Software Bill of Materials (CycloneDX, machine-readable):**
   - https://ike.network/ike-platform/bom.json
   - https://ike.network/ike-platform/bom.xml
   - Full transitive dependency graph, SPDX-normalized licenses, artifact hashes.
   - Also reachable as a Maven artifact with `<classifier>cyclonedx</classifier>`.

2. **Licenses (SPDX, HTML):**
   - https://ike.network/ike-platform/licenses.html
   - SPDX-grouped, deduplicated view of declared dependencies, rendered from `bom.json`.

3. **Built With** (this stub's canonical home):
   - Curated companion covering components mechanical reports can't see —
     Maven Site skin, external services, fonts inside artifacts, frontend
     assets in rendered HTML.
   - Source content lives at [`src/main/built-with/supplement.yaml`](src/main/built-with/supplement.yaml)
     at the reactor root. The rendered HTML is generated per-module by
     `ike:built-with` (ike-issues#336).

Issues or omissions: file at https://github.com/IKE-Network/ike-issues.
