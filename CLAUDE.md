# ike-platform

Parent POM (ike-parent), BOM (ike-bom), and workspace plugin (ike-workspace-maven-plugin). Consumes ike-docs and ike-tooling as ordinary managed plugins under property indirection (`${ike-docs.version}`, `${ike-tooling.version}`). Earlier revisions registered the custom `<packaging>ike-doc</packaging>` type via `<extensions>true</extensions>` on `ike-doc-maven-plugin`; that machinery was retired in `IKE-Network/ike-issues#321` in favor of a classifier-canonical doc shape (see `ike-parent/src/site/asciidoc/index.adoc` for the design rationale).

## Build Standards

Files in `.claude/standards/` are build artifacts unpacked from `ike-build-standards`. DO NOT edit or commit them. See the workspace root CLAUDE.md for details.

## Build

```bash
mvn clean verify -DskipTests -T4
```

## Key Facts

- GroupId: `network.ike.platform`
- Version: `1-SNAPSHOT`
- Uses `--enable-preview` (Java 25)
- BOM: imports `dev.ikm.ike:ike-bom` for dependency version management

## Prohibited Patterns

- **Never use `maven-antrun-plugin`** — use a proper Maven goal or `exec-maven-plugin`
- **Never use `build-helper-maven-plugin` for multi-execution property chaining** —
  write a proper Maven goal in `ike-maven-plugin`
- **Never embed shell commands inline in POM** — extract to a named script

See `.claude/standards/` (after `mvn validate`) for full standards.
See `CLAUDE-ike-platform.md` for project-specific notes.
