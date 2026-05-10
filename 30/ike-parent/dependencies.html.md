---
date_published: 2026-05-09
date_modified: 2026-05-09
canonical_url: https://ike.network/ike-platform/ike-parent/dependencies.html
---

# Project Dependencies

## [provided](#provided)

The following is a list of provided dependencies for this project. These dependencies are required to compile the application, but should be provided by default when using the library:

| GroupId | ArtifactId | Version | Classifier | Type | Licenses |
| --- | --- | --- | --- | --- | --- |
| network.ike.tooling | [ike-build-standards](https://ike.network/ike-tooling/ike-build-standards/)[1] | 147 | claude | zip | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| network.ike.tooling | [ike-build-standards](https://ike.network/ike-tooling/ike-build-standards/)[1] | 147 | config | zip | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| network.ike.tooling | [ike-build-standards](https://ike.network/ike-tooling/ike-build-standards/)[1] | 147 | site-theme | zip | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |

# Project Transitive Dependencies

No transitive dependencies are required for this project.

# Project Dependency Graph

## [Dependency Tree](#dependency-tree)

- network.ike.platform:ike-parent:pom:30 ** 
  
  | IKE Parent |
  | --- |
  | **Description: **Standard parent POM for IKE Network projects. Inheriting this POM provides build conventions (Java 25 compiler, test harness, GPG signing, AsciiDoc documentation pipeline) from the ike-platform reactor root and centralized dependency version management declared inline. Declares ike-doc-maven-plugin (from network.ike.docs) with extensions=true to provide the ike-doc custom packaging type to external doc projects. **URL: **[https://ike.network/ike-platform/ike-parent/](https://ike.network/ike-platform/ike-parent/)[3] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
  
    - network.ike.tooling:ike-build-standards:zip:claude:147 (provided) ** 
      
      | IKE Build Standards |
      | --- |
      | **Description: **Versioned Claude instruction files for IKE projects. Modular standards (Maven, Java, IKE-specific) distributed as a classified Maven artifact. **URL: **[https://ike.network/ike-tooling/ike-build-standards/](https://ike.network/ike-tooling/ike-build-standards/)[1] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - network.ike.tooling:ike-build-standards:zip:config:147 (provided) ** 
      
      | IKE Build Standards |
      | --- |
      | **Description: **Versioned Claude instruction files for IKE projects. Modular standards (Maven, Java, IKE-specific) distributed as a classified Maven artifact. **URL: **[https://ike.network/ike-tooling/ike-build-standards/](https://ike.network/ike-tooling/ike-build-standards/)[1] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - network.ike.tooling:ike-build-standards:zip:site-theme:147 (provided) ** 
      
      | IKE Build Standards |
      | --- |
      | **Description: **Versioned Claude instruction files for IKE projects. Modular standards (Maven, Java, IKE-specific) distributed as a classified Maven artifact. **URL: **[https://ike.network/ike-tooling/ike-build-standards/](https://ike.network/ike-tooling/ike-build-standards/)[1] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |

# Licenses

**Apache License, Version 2.0: **IKE Build Standards, IKE Parent

# Dependency File Details

| Total | Size | Entries | Classes | Packages | Java Version | Debug Information |
| --- | --- | --- | --- | --- | --- | --- |
| ike-build-standards-147-claude.zip | 81 kB | - | - | - | - | - |
| ike-build-standards-147-config.zip | 1.2 kB | - | - | - | - | - |
| ike-build-standards-147-site-theme.zip | 3.4 kB | - | - | - | - | - |
| 3 | 85.6 kB | - | - | - | - | - |
| provided: 3 | provided: 85.6 kB | - | - | - | - | - |
