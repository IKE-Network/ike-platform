---
date_published: 2026-05-12
date_modified: 2026-05-12
canonical_url: https://ike.network/ike-platform/ike-parent/dependency-management.html
---

# Project Dependency Management

## [compile](#compile)

The following is a list of compile dependencies in the DependencyManagement of this project. These dependencies can be included in the submodules to compile and run the submodule:

| GroupId | ArtifactId | Version | Classifier | Type | License |
| --- | --- | --- | --- | --- | --- |
| network.ike.docs | [docbook-xsl](https://github.com/IKE-Network/ike-docbook-xsl)[1] | 26 | - | jar | [MIT License (DocBook XSL Stylesheets)](https://github.com/docbook/xslt10-stylesheets/blob/master/xsl/COPYING)[2], [Apache License 2.0 (IKE Customization Layer)](https://www.apache.org/licenses/LICENSE-2.0)[3] |
| network.ike.docs | [ike-doc-resources](https://github.com/IKE-Network/ike-docs)[4] | 26 | - | jar | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| network.ike.docs | [koncept-asciidoc-extension](https://github.com/IKE-Network/ike-docs)[4] | 26 | - | jar | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| network.ike.docs | [minimal-fonts](https://github.com/IKE-Network/ike-minimal-fonts)[6] | 26 | - | zip | [SIL Open Font License 1.1](https://scripts.sil.org/OFL)[7] |
| network.ike.tooling | [ike-build-standards](https://ike.network/ike-tooling/ike-build-standards/)[8] | 168 | asciidoctorconfig | zip | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| network.ike.tooling | [ike-build-standards](https://ike.network/ike-tooling/ike-build-standards/)[8] | 168 | built-with | zip | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| network.ike.tooling | [ike-build-standards](https://ike.network/ike-tooling/ike-build-standards/)[8] | 168 | claude | zip | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| network.ike.tooling | [ike-build-standards](https://ike.network/ike-tooling/ike-build-standards/)[8] | 168 | config | zip | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| network.ike.tooling | [ike-build-standards](https://ike.network/ike-tooling/ike-build-standards/)[8] | 168 | docs | zip | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| network.ike.tooling | [ike-build-standards](https://ike.network/ike-tooling/ike-build-standards/)[8] | 168 | scaffold | zip | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| network.ike.tooling | [ike-build-standards](https://ike.network/ike-tooling/ike-build-standards/)[8] | 168 | site-theme | zip | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| org.asciidoctor | [asciidoctorj](https://github.com/asciidoctor/asciidoctorj)[9] | 3.0.1 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[10] |
| org.asciidoctor | [asciidoctorj-diagram](https://github.com/asciidoctor/asciidoctorj-diagram)[11] | 2.3.1 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[10] |
| org.asciidoctor | [asciidoctorj-pdf](https://github.com/asciidoctor/asciidoctorj-pdf)[12] | 2.3.23 | - | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[10] |

## [test](#test)

The following is a list of test dependencies in the DependencyManagement of this project. These dependencies can be included in the submodules to compile and run unit tests for the submodule:

| GroupId | ArtifactId | Version | Type | License |
| --- | --- | --- | --- | --- |
| org.assertj | [assertj-core](https://assertj.github.io/doc/#assertj-core)[13] | 3.27.3 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| org.junit.jupiter | [junit-jupiter](https://junit.org/)[14] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[15] |
| org.mockito | [mockito-core](https://github.com/mockito/mockito)[16] | 5.14.2 | jar | [MIT](https://opensource.org/licenses/MIT)[17] |
| org.mockito | [mockito-junit-jupiter](https://github.com/mockito/mockito)[16] | 5.14.2 | jar | [MIT](https://opensource.org/licenses/MIT)[17] |
