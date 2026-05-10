---
date_published: 2026-05-09
date_modified: 2026-05-09
canonical_url: https://ike.network/ike-platform/ike-workspace-maven-plugin/dependencies.html
---

# Project Dependencies

## [compile](#compile)

The following is a list of compile dependencies for this project. These dependencies are required to compile and run the application:

| GroupId | ArtifactId | Version | Type | Licenses |
| --- | --- | --- | --- | --- |
| network.ike.tooling | [ike-maven-plugin-support](https://ike.network/ike-tooling/ike-maven-plugin-support/)[1] | 147 | jar | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| network.ike.tooling | [ike-workspace-model](https://ike.network/ike-tooling/ike-workspace-model/)[3] | 147 | jar | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.openrewrite | [rewrite-xml](https://github.com/openrewrite/rewrite)[4] | 8.79.2 | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |

## [test](#test)

The following is a list of test dependencies for this project. These dependencies are only required to compile and run unit tests for the application:

| GroupId | ArtifactId | Version | Type | Licenses |
| --- | --- | --- | --- | --- |
| org.assertj | [assertj-core](https://assertj.github.io/doc/#assertj-core)[6] | 3.27.3 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.junit.jupiter | [junit-jupiter](https://junit.org/)[7] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[8] |

## [provided](#provided)

The following is a list of provided dependencies for this project. These dependencies are required to compile the application, but should be provided by default when using the library:

| GroupId | ArtifactId | Version | Classifier | Type | Licenses |
| --- | --- | --- | --- | --- | --- |
| network.ike.tooling | [ike-build-standards](https://ike.network/ike-tooling/ike-build-standards/)[9] | 147 | claude | zip | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| network.ike.tooling | [ike-build-standards](https://ike.network/ike-tooling/ike-build-standards/)[9] | 147 | site-theme | zip | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-api-core](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-core/)[10] | 4.0.0-rc-5 | - | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-api-di](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-di/)[11] | 4.0.0-rc-5 | - | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-api-model](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-model/)[12] | 4.0.0-rc-5 | - | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-api-plugin](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-plugin/)[13] | 4.0.0-rc-5 | - | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |

# Project Transitive Dependencies

The following is a list of transitive dependencies for this project. Transitive dependencies are the dependencies of the project dependencies.

## [compile](#compile_2)

The following is a list of compile dependencies for this project. These dependencies are required to compile and run the application:

| GroupId | ArtifactId | Version | Type | Licenses |
| --- | --- | --- | --- | --- |
| com.fasterxml.jackson.core | [jackson-annotations](https://github.com/FasterXML/jackson)[14] | 2.21 | jar | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| com.fasterxml.jackson.core | [jackson-core](https://github.com/FasterXML/jackson-core)[15] | 2.21.2 | jar | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| com.fasterxml.jackson.core | [jackson-databind](https://github.com/FasterXML/jackson)[14] | 2.21.2 | jar | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| com.fasterxml.jackson.dataformat | [jackson-dataformat-smile](https://github.com/FasterXML/jackson-dataformats-binary)[16] | 2.21.2 | jar | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| com.fasterxml.jackson.datatype | [jackson-datatype-jsr310](https://github.com/FasterXML/jackson-modules-java8/jackson-datatype-jsr310)[17] | 2.21.2 | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| com.fasterxml.jackson.module | [jackson-module-parameter-names](https://github.com/FasterXML/jackson-modules-java8/jackson-module-parameter-names)[18] | 2.21.2 | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| com.fasterxml.woodstox | [woodstox-core](https://github.com/FasterXML/woodstox)[19] | 7.1.1 | jar | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| org.apache.maven | [maven-api-annotations](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-annotations/)[20] | 4.0.0-rc-5 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-api-metadata](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-metadata/)[21] | 4.0.0-rc-5 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-api-settings](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-settings/)[22] | 4.0.0-rc-5 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-api-toolchain](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-toolchain/)[23] | 4.0.0-rc-5 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-api-xml](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-xml/)[24] | 4.0.0-rc-5 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-support](https://maven.apache.org/ref/4.0.0-rc-5/maven-impl-modules/maven-support/)[25] | 4.0.0-rc-5 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.maven | [maven-xml](https://maven.apache.org/ref/4.0.0-rc-5/maven-impl-modules/maven-xml/)[26] | 4.0.0-rc-5 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.codehaus.woodstox | [stax2-api](http://github.com/FasterXML/stax2-api)[27] | 4.2.2 | jar | [The BSD 2-Clause License](http://www.opensource.org/licenses/bsd-license.php)[28] |
| org.jetbrains | [annotations](https://github.com/JetBrains/java-annotations)[29] | 26.1.0 | jar | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.jspecify | [jspecify](http://jspecify.org/)[30] | 1.0.0 | jar | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| org.openrewrite | [rewrite-core](https://github.com/openrewrite/rewrite)[4] | 8.79.2 | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| org.openrewrite.tools | [jgit](https://github.com/openrewrite/jgit)[31] | 1.4.1 | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| org.yaml | [snakeyaml](https://bitbucket.org/snakeyaml/snakeyaml)[32] | 2.2 | jar | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |

## [runtime](#runtime)

The following is a list of runtime dependencies for this project. These dependencies are required to run the application:

| GroupId | ArtifactId | Version | Type | Licenses |
| --- | --- | --- | --- | --- |
| com.github.ben-manes.caffeine | [caffeine](https://github.com/ben-manes/caffeine)[33] | 2.9.3 | jar | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| com.google.errorprone | [error_prone_annotations](https://errorprone.info/error_prone_annotations)[34] | 2.10.0 | jar | [Apache 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| com.googlecode.javaewah | [JavaEWAH](https://github.com/lemire/javaewah)[35] | 1.1.13 | jar | [Apache 2](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| com.univocity | [univocity-parsers](http://github.com/univocity/univocity-parsers)[36] | 2.9.1 | jar | [Apache 2](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| io.github.classgraph | [classgraph](https://github.com/classgraph/classgraph)[37] | 4.8.184 | jar | [The MIT License (MIT)](http://opensource.org/licenses/MIT)[38] |
| io.micrometer | [micrometer-core](https://github.com/micrometer-metrics/micrometer)[39] | 1.9.17 | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| io.moderne | [jsonrpc](https://github.com/moderneinc/jsonrpc)[40] | 1.0.5 | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| io.quarkus.gizmo | [gizmo](http://www.jboss.org/gizmo)[41] | 1.0.11.Final | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| net.java.dev.jna | [jna](https://github.com/java-native-access/jna)[42] | 5.18.1 | jar | [LGPL-2.1-or-later](https://www.gnu.org/licenses/old-licenses/lgpl-2.1)[43][Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| net.java.dev.jna | [jna-platform](https://github.com/java-native-access/jna)[42] | 5.18.1 | jar | [LGPL-2.1-or-later](https://www.gnu.org/licenses/old-licenses/lgpl-2.1)[43][Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.antlr | [antlr4-runtime](https://www.antlr.org/antlr4-runtime/)[44] | 4.13.2 | jar | [BSD-3-Clause](https://www.antlr.org/license.html)[45] |
| org.apache.commons | [commons-lang3](https://commons.apache.org/proper/commons-lang/)[46] | 3.20.0 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apache.commons | [commons-text](https://commons.apache.org/proper/commons-text)[47] | 1.15.0 | jar | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.checkerframework | [checker-qual](https://checkerframework.org)[48] | 3.19.0 | jar | [The MIT License](http://opensource.org/licenses/MIT)[38] |
| org.hdrhistogram | [HdrHistogram](http://hdrhistogram.github.io/HdrHistogram/)[49] | 2.1.12 | jar | [Public Domain, per Creative Commons CC0](http://creativecommons.org/publicdomain/zero/1.0/)[50][BSD-2-Clause](https://opensource.org/licenses/BSD-2-Clause)[51] |
| org.jboss | [jandex](http://www.jboss.org/jandex)[52] | 2.4.2.Final | jar | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| org.latencyutils | [LatencyUtils](http://latencyutils.github.io/LatencyUtils/)[53] | 2.0.3 | jar | [Public Domain, per Creative Commons CC0](http://creativecommons.org/publicdomain/zero/1.0/)[50] |
| org.objenesis | [objenesis](https://objenesis.org/objenesis)[54] | 3.5 | jar | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| org.openrewrite.tools | [java-object-diff](https://github.com/openrewrite/java-object-diff)[55] | 1.0.1 | jar | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| org.ow2.asm | [asm](http://asm.ow2.io/)[56] | 9.3 | jar | [BSD-3-Clause](https://asm.ow2.io/license.html)[57] |
| org.ow2.asm | [asm-analysis](http://asm.ow2.io/)[56] | 9.3 | jar | [BSD-3-Clause](https://asm.ow2.io/license.html)[57] |
| org.ow2.asm | [asm-tree](http://asm.ow2.io/)[56] | 9.3 | jar | [BSD-3-Clause](https://asm.ow2.io/license.html)[57] |
| org.ow2.asm | [asm-util](http://asm.ow2.io/)[56] | 9.3 | jar | [BSD-3-Clause](https://asm.ow2.io/license.html)[57] |

## [test](#test_2)

The following is a list of test dependencies for this project. These dependencies are only required to compile and run unit tests for the application:

| GroupId | ArtifactId | Version | Type | Licenses |
| --- | --- | --- | --- | --- |
| net.bytebuddy | [byte-buddy](https://bytebuddy.net/byte-buddy)[58] | 1.15.11 | jar | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
| org.apiguardian | [apiguardian-api](https://github.com/apiguardian-team/apiguardian)[59] | 1.1.2 | jar | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
| org.junit.jupiter | [junit-jupiter-api](https://junit.org/)[7] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[8] |
| org.junit.jupiter | [junit-jupiter-engine](https://junit.org/)[7] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[8] |
| org.junit.jupiter | [junit-jupiter-params](https://junit.org/)[7] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[8] |
| org.junit.platform | [junit-platform-commons](https://junit.org/)[7] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[8] |
| org.junit.platform | [junit-platform-engine](https://junit.org/)[7] | 6.0.0 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[8] |
| org.opentest4j | [opentest4j](https://github.com/ota4j-team/opentest4j)[60] | 1.3.0 | jar | [The Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |

# Project Dependency Graph

## [Dependency Tree](#dependency-tree)

- network.ike.platform:ike-workspace-maven-plugin:maven-plugin:30 ** 
  
  | IKE Workspace Maven Plugin |
  | --- |
  | **Description: **Cross-platform Maven plugin for multi-repository workspace management: initialization, verification, feature branching, release orchestration, and checkpoint creation. **URL: **[https://ike.network/ike-platform/ike-workspace-maven-plugin/](https://ike.network/ike-platform/ike-workspace-maven-plugin/)[61] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
  
    - network.ike.tooling:ike-workspace-model:jar:147 (compile) ** 
      
      | IKE Workspace Model |
      | --- |
      | **Description: **Typed model for workspace.yaml manifests with graph algorithms (topological sort, cascade analysis, cycle detection) and version manipulation utilities. Foundation for ike-maven-plugin workspace goals. **URL: **[https://ike.network/ike-tooling/ike-workspace-model/](https://ike.network/ike-tooling/ike-workspace-model/)[3] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
      
          - org.yaml:snakeyaml:jar:2.2 (compile) ** 
            
            | SnakeYAML |
            | --- |
            | **Description: **YAML 1.1 parser and emitter for Java **URL: **[https://bitbucket.org/snakeyaml/snakeyaml](https://bitbucket.org/snakeyaml/snakeyaml)[32] **Project Licenses: **[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
          - org.apache.maven:maven-support:jar:4.0.0-rc-5 (compile) ** 
            
            | Maven 4 Model Support |
            | --- |
            | **Description: **Provides the Maven 4 Model Support **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/maven-impl-modules/maven-support/](https://maven.apache.org/ref/4.0.0-rc-5/maven-impl-modules/maven-support/)[25] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
            
                  - org.apache.maven:maven-api-metadata:jar:4.0.0-rc-5 (compile) ** 
                    
                    | Maven 4 API :: Repository Metadata |
                    | --- |
                    | **Description: **Maven 4 API - Immutable Repository Metadata model. **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-metadata/](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-metadata/)[21] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
                  - org.apache.maven:maven-xml:jar:4.0.0-rc-5 (compile) ** 
                    
                    | Maven 4 XML Implementation |
                    | --- |
                    | **Description: **Provides the implementation classes for the Maven XML **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/maven-impl-modules/maven-xml/](https://maven.apache.org/ref/4.0.0-rc-5/maven-impl-modules/maven-xml/)[26] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
                    
                            - com.fasterxml.woodstox:woodstox-core:jar:7.1.1 (compile) ** 
                              
                              | Woodstox |
                              | --- |
                              | **Description: **Woodstox is a high-performance XML processor that implements Stax (JSR-173), SAX2 and Stax2 APIs **URL: **[https://github.com/FasterXML/woodstox](https://github.com/FasterXML/woodstox)[19] **Project Licenses: **[The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
                            - org.codehaus.woodstox:stax2-api:jar:4.2.2 (compile) ** 
                              
                              | Stax2 API |
                              | --- |
                              | **Description: **Stax2 API is an extension to basic Stax 1.0 API that adds significant new functionality, such as full-featured bi-direction validation interface and high-performance Typed Access API. **URL: **[http://github.com/FasterXML/stax2-api](http://github.com/FasterXML/stax2-api)[27] **Project Licenses: **[The BSD 2-Clause License](http://www.opensource.org/licenses/bsd-license.php)[28] |
    - network.ike.tooling:ike-maven-plugin-support:jar:147 (compile) ** 
      
      | IKE Maven Plugin Support |
      | --- |
      | **Description: **Shared library for IKE Maven plugins: goal identifier interface, base Mojo with per-goal report writing, self-healing gitignore, and interactive parameter resolution helpers. **URL: **[https://ike.network/ike-tooling/ike-maven-plugin-support/](https://ike.network/ike-tooling/ike-maven-plugin-support/)[1] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - network.ike.tooling:ike-build-standards:zip:claude:147 (provided) ** 
      
      | IKE Build Standards |
      | --- |
      | **Description: **Versioned Claude instruction files for IKE projects. Modular standards (Maven, Java, IKE-specific) distributed as a classified Maven artifact. **URL: **[https://ike.network/ike-tooling/ike-build-standards/](https://ike.network/ike-tooling/ike-build-standards/)[9] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - org.apache.maven:maven-api-core:jar:4.0.0-rc-5 (provided) ** 
      
      | Maven 4 API :: Core |
      | --- |
      | **Description: **Maven 4 API - Maven Core API **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-core/](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-core/)[10] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
      
          - org.apache.maven:maven-api-annotations:jar:4.0.0-rc-5 (compile) ** 
            
            | Maven 4 API :: Meta annotations |
            | --- |
            | **Description: **Maven 4 API - Java meta annotations. **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-annotations/](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-annotations/)[20] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
          - org.apache.maven:maven-api-settings:jar:4.0.0-rc-5 (compile) ** 
            
            | Maven 4 API :: Settings |
            | --- |
            | **Description: **Maven 4 API - Immutable Settings model. **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-settings/](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-settings/)[22] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
          - org.apache.maven:maven-api-toolchain:jar:4.0.0-rc-5 (compile) ** 
            
            | Maven 4 API :: Toolchain |
            | --- |
            | **Description: **Maven 4 API - Immutable Toolchain model. **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-toolchain/](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-toolchain/)[23] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
          - org.apache.maven:maven-api-xml:jar:4.0.0-rc-5 (compile) ** 
            
            | Maven 4 API :: XML |
            | --- |
            | **Description: **Maven 4 API - Immutable XML. **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-xml/](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-xml/)[24] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - org.apache.maven:maven-api-di:jar:4.0.0-rc-5 (provided) ** 
      
      | Maven 4 API :: Dependency Injection |
      | --- |
      | **Description: **Maven 4 API - Dependency Injection **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-di/](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-di/)[11] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - org.apache.maven:maven-api-plugin:jar:4.0.0-rc-5 (provided) ** 
      
      | Maven 4 API :: Plugin |
      | --- |
      | **Description: **Maven 4 API - Immutable Plugin model. **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-plugin/](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-plugin/)[13] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - org.apache.maven:maven-api-model:jar:4.0.0-rc-5 (provided) ** 
      
      | Maven 4 API :: Model |
      | --- |
      | **Description: **Maven 4 API - Immutable Model for Maven POM (Project Object Model). **URL: **[https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-model/](https://maven.apache.org/ref/4.0.0-rc-5/api/maven-api-model/)[12] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - org.openrewrite:rewrite-xml:jar:8.79.2 (compile) ** 
      
      | rewrite-xml |
      | --- |
      | **Description: **Eliminate tech-debt. Automatically. **URL: **[https://github.com/openrewrite/rewrite](https://github.com/openrewrite/rewrite)[4] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
      
          - org.openrewrite:rewrite-core:jar:8.79.2 (compile) ** 
            
            | rewrite-core |
            | --- |
            | **Description: **Eliminate tech-debt. Automatically. **URL: **[https://github.com/openrewrite/rewrite](https://github.com/openrewrite/rewrite)[4] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
            
                  - org.openrewrite.tools:jgit:jar:1.4.1 (compile) ** 
                    
                    | jgit |
                    | --- |
                    | **Description: **Fork of jgit to maintain Java 8 compatibility **URL: **[https://github.com/openrewrite/jgit](https://github.com/openrewrite/jgit)[31] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
                    
                            - com.googlecode.javaewah:JavaEWAH:jar:1.1.13 (runtime) ** 
                              
                              | JavaEWAH |
                              | --- |
                              | **Description: **The bit array data structure is implemented in Java as the BitSet class. Unfortunately, this fails to scale without compression. JavaEWAH is a word-aligned compressed variant of the Java bitset class. It uses a 64-bit run-length encoding (RLE) compression scheme. The goal of word-aligned compression is not to achieve the best compression, but rather to improve query processing time. Hence, we try to save CPU cycles, maybe at the expense of storage. However, the EWAH scheme we implemented is always more efficient storage-wise than an uncompressed bitmap (implemented in Java as the BitSet class). Unlike some alternatives, javaewah does not rely on a patented scheme. **URL: **[https://github.com/lemire/javaewah](https://github.com/lemire/javaewah)[35] **Project Licenses: **[Apache 2](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
                  - com.fasterxml.jackson.core:jackson-core:jar:2.21.2 (compile) ** 
                    
                    | Jackson-core |
                    | --- |
                    | **Description: **Core Jackson processing abstractions (aka Streaming API), implementation for JSON **URL: **[https://github.com/FasterXML/jackson-core](https://github.com/FasterXML/jackson-core)[15] **Project Licenses: **[The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
                  - com.fasterxml.jackson.core:jackson-databind:jar:2.21.2 (compile) ** 
                    
                    | jackson-databind |
                    | --- |
                    | **Description: **General data-binding functionality for Jackson: works on core streaming API **URL: **[https://github.com/FasterXML/jackson](https://github.com/FasterXML/jackson)[14] **Project Licenses: **[The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
                  - com.fasterxml.jackson.dataformat:jackson-dataformat-smile:jar:2.21.2 (compile) ** 
                    
                    | Jackson dataformat: Smile |
                    | --- |
                    | **Description: **Support for reading and writing Smile ("binary JSON") encoded data using Jackson abstractions (streaming API, data binding, tree model) **URL: **[https://github.com/FasterXML/jackson-dataformats-binary](https://github.com/FasterXML/jackson-dataformats-binary)[16] **Project Licenses: **[The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
                  - com.fasterxml.jackson.module:jackson-module-parameter-names:jar:2.21.2 (compile) ** 
                    
                    | Jackson-module-parameter-names |
                    | --- |
                    | **Description: **Add-on module for Jackson (https://github.com/FasterXML/jackson) to support introspection of method/constructor parameter names, without having to add explicit property name annotation. **URL: **[https://github.com/FasterXML/jackson-modules-java8/jackson-module-parameter-names](https://github.com/FasterXML/jackson-modules-java8/jackson-module-parameter-names)[18] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
                  - com.fasterxml.jackson.datatype:jackson-datatype-jsr310:jar:2.21.2 (compile) ** 
                    
                    | Jackson datatype: JSR310 |
                    | --- |
                    | **Description: **Add-on module to support JSR-310 (Java 8 Date & Time API) data types. **URL: **[https://github.com/FasterXML/jackson-modules-java8/jackson-datatype-jsr310](https://github.com/FasterXML/jackson-modules-java8/jackson-datatype-jsr310)[17] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
                  - org.jspecify:jspecify:jar:1.0.0 (compile) ** 
                    
                    | JSpecify annotations |
                    | --- |
                    | **Description: **An artifact of well-named and well-specified annotations to power static analysis checks **URL: **[http://jspecify.org/](http://jspecify.org/)[30] **Project Licenses: **[The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
                  - org.openrewrite.tools:java-object-diff:jar:1.0.1 (runtime) ** 
                    
                    | java-object-diff |
                    | --- |
                    | **Description: **Fork of object differ with the logging removed **URL: **[https://github.com/openrewrite/java-object-diff](https://github.com/openrewrite/java-object-diff)[55] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
                  - io.quarkus.gizmo:gizmo:jar:1.0.11.Final (runtime) ** 
                    
                    | Gizmo |
                    | --- |
                    | **Description: **A bytecode generation library. **URL: **[http://www.jboss.org/gizmo](http://www.jboss.org/gizmo)[41] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
                    
                            - org.ow2.asm:asm:jar:9.3 (runtime) ** 
                              
                              | asm |
                              | --- |
                              | **Description: **ASM, a very small and fast Java bytecode manipulation framework **URL: **[http://asm.ow2.io/](http://asm.ow2.io/)[56] **Project Licenses: **[BSD-3-Clause](https://asm.ow2.io/license.html)[57] |
                            - org.ow2.asm:asm-util:jar:9.3 (runtime) ** 
                              
                              | asm-util |
                              | --- |
                              | **Description: **Utilities for ASM, a very small and fast Java bytecode manipulation framework **URL: **[http://asm.ow2.io/](http://asm.ow2.io/)[56] **Project Licenses: **[BSD-3-Clause](https://asm.ow2.io/license.html)[57] |
                              
                                        - org.ow2.asm:asm-tree:jar:9.3 (runtime) ** 
                                          
                                          | asm-tree |
                                          | --- |
                                          | **Description: **Tree API of ASM, a very small and fast Java bytecode manipulation framework **URL: **[http://asm.ow2.io/](http://asm.ow2.io/)[56] **Project Licenses: **[BSD-3-Clause](https://asm.ow2.io/license.html)[57] |
                                        - org.ow2.asm:asm-analysis:jar:9.3 (runtime) ** 
                                          
                                          | asm-analysis |
                                          | --- |
                                          | **Description: **Static code analysis API of ASM, a very small and fast Java bytecode manipulation framework **URL: **[http://asm.ow2.io/](http://asm.ow2.io/)[56] **Project Licenses: **[BSD-3-Clause](https://asm.ow2.io/license.html)[57] |
                            - org.jboss:jandex:jar:2.4.2.Final (runtime) ** 
                              
                              | Java Annotation Indexer |
                              | --- |
                              | **Description: **Parent POM for JBoss projects. Provides default project build configuration. **URL: **[http://www.jboss.org/jandex](http://www.jboss.org/jandex)[52] **Project Licenses: **[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
                  - net.java.dev.jna:jna-platform:jar:5.18.1 (runtime) ** 
                    
                    | Java Native Access Platform |
                    | --- |
                    | **Description: **Java Native Access Platform **URL: **[https://github.com/java-native-access/jna](https://github.com/java-native-access/jna)[42] **Project Licenses: **[LGPL-2.1-or-later](https://www.gnu.org/licenses/old-licenses/lgpl-2.1)[43], [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
                    
                            - net.java.dev.jna:jna:jar:5.18.1 (runtime) ** 
                              
                              | Java Native Access |
                              | --- |
                              | **Description: **Java Native Access **URL: **[https://github.com/java-native-access/jna](https://github.com/java-native-access/jna)[42] **Project Licenses: **[LGPL-2.1-or-later](https://www.gnu.org/licenses/old-licenses/lgpl-2.1)[43], [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
                  - com.univocity:univocity-parsers:jar:2.9.1 (runtime) ** 
                    
                    | univocity-parsers |
                    | --- |
                    | **Description: **univocity's open source parsers for processing different text formats using a consistent API **URL: **[http://github.com/univocity/univocity-parsers](http://github.com/univocity/univocity-parsers)[36] **Project Licenses: **[Apache 2](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
                  - io.github.classgraph:classgraph:jar:4.8.184 (runtime) ** 
                    
                    | ClassGraph |
                    | --- |
                    | **Description: **The uber-fast, ultra-lightweight classpath and module scanner for JVM languages. **URL: **[https://github.com/classgraph/classgraph](https://github.com/classgraph/classgraph)[37] **Project Licenses: **[The MIT License (MIT)](http://opensource.org/licenses/MIT)[38] |
                  - io.moderne:jsonrpc:jar:1.0.5 (runtime) ** 
                    
                    | jsonrpc |
                    | --- |
                    | **Description: **JSON-RPC 2.0 client and server library **URL: **[https://github.com/moderneinc/jsonrpc](https://github.com/moderneinc/jsonrpc)[40] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
                  - org.objenesis:objenesis:jar:3.5 (runtime) ** 
                    
                    | Objenesis |
                    | --- |
                    | **Description: **A library for instantiating Java objects **URL: **[https://objenesis.org/objenesis](https://objenesis.org/objenesis)[54] **Project Licenses: **[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
          - org.jetbrains:annotations:jar:26.1.0 (compile) ** 
            
            | JetBrains Java Annotations |
            | --- |
            | **Description: **A set of annotations used for code inspection support and code documentation. **URL: **[https://github.com/JetBrains/java-annotations](https://github.com/JetBrains/java-annotations)[29] **Project Licenses: **[The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
          - com.fasterxml.jackson.core:jackson-annotations:jar:2.21 (compile) ** 
            
            | Jackson-annotations |
            | --- |
            | **Description: **Core annotations used for value types, used by Jackson data binding package. **URL: **[https://github.com/FasterXML/jackson](https://github.com/FasterXML/jackson)[14] **Project Licenses: **[The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
          - org.antlr:antlr4-runtime:jar:4.13.2 (runtime) ** 
            
            | ANTLR 4 Runtime |
            | --- |
            | **Description: **The ANTLR 4 Runtime **URL: **[https://www.antlr.org/antlr4-runtime/](https://www.antlr.org/antlr4-runtime/)[44] **Project Licenses: **[BSD-3-Clause](https://www.antlr.org/license.html)[45] |
          - com.github.ben-manes.caffeine:caffeine:jar:2.9.3 (runtime) ** 
            
            | Caffeine cache |
            | --- |
            | **Description: **A high performance caching library **URL: **[https://github.com/ben-manes/caffeine](https://github.com/ben-manes/caffeine)[33] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
            
                  - org.checkerframework:checker-qual:jar:3.19.0 (runtime) ** 
                    
                    | Checker Qual |
                    | --- |
                    | **Description: **checker-qual contains annotations (type qualifiers) that a programmer writes to specify Java code for type-checking by the Checker Framework. **URL: **[https://checkerframework.org](https://checkerframework.org)[48] **Project Licenses: **[The MIT License](http://opensource.org/licenses/MIT)[38] |
                  - com.google.errorprone:error_prone_annotations:jar:2.10.0 (runtime) ** 
                    
                    | error-prone annotations |
                    | --- |
                    | **Description: **Error Prone is a static analysis tool for Java that catches common programming mistakes at compile-time. **URL: **[https://errorprone.info/error_prone_annotations](https://errorprone.info/error_prone_annotations)[34] **Project Licenses: **[Apache 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
          - io.micrometer:micrometer-core:jar:1.9.17 (runtime) ** 
            
            | micrometer-core |
            | --- |
            | **Description: **Core module of Micrometer containing instrumentation API and implementation **URL: **[https://github.com/micrometer-metrics/micrometer](https://github.com/micrometer-metrics/micrometer)[39] **Project Licenses: **[The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
            
                  - org.hdrhistogram:HdrHistogram:jar:2.1.12 (runtime) ** 
                    
                    | HdrHistogram |
                    | --- |
                    | **Description: **HdrHistogram supports the recording and analyzing sampled data value counts across a configurable integer value range with configurable value precision within the range. Value precision is expressed as the number of significant digits in the value recording, and provides control over value quantization behavior across the value range and the subsequent value resolution at any given level. **URL: **[http://hdrhistogram.github.io/HdrHistogram/](http://hdrhistogram.github.io/HdrHistogram/)[49] **Project Licenses: **[Public Domain, per Creative Commons CC0](http://creativecommons.org/publicdomain/zero/1.0/)[50], [BSD-2-Clause](https://opensource.org/licenses/BSD-2-Clause)[51] |
                  - org.latencyutils:LatencyUtils:jar:2.0.3 (runtime) ** 
                    
                    | LatencyUtils |
                    | --- |
                    | **Description: **LatencyUtils is a package that provides latency recording and reporting utilities. **URL: **[http://latencyutils.github.io/LatencyUtils/](http://latencyutils.github.io/LatencyUtils/)[53] **Project Licenses: **[Public Domain, per Creative Commons CC0](http://creativecommons.org/publicdomain/zero/1.0/)[50] |
          - org.apache.commons:commons-text:jar:1.15.0 (runtime) ** 
            
            | Apache Commons Text |
            | --- |
            | **Description: **Apache Commons Text is a set of utility functions and reusable components for processing and manipulating text in a Java environment. **URL: **[https://commons.apache.org/proper/commons-text](https://commons.apache.org/proper/commons-text)[47] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
            
                  - org.apache.commons:commons-lang3:jar:3.20.0 (runtime) ** 
                    
                    | Apache Commons Lang |
                    | --- |
                    | **Description: **Apache Commons Lang, a package of Java utility classes for the classes that are in java.lang's hierarchy, or are considered to be so standard as to justify existence in java.lang. The code is tested using the latest revision of the JDK for supported LTS releases: 8, 11, 17, 21 and 25 currently. See https://github.com/apache/commons-lang/blob/master/.github/workflows/maven.yml Please ensure your build environment is up-to-date and kindly report any build issues. **URL: **[https://commons.apache.org/proper/commons-lang/](https://commons.apache.org/proper/commons-lang/)[46] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - org.junit.jupiter:junit-jupiter:jar:6.0.0 (test) ** 
      
      | JUnit Jupiter (Aggregator) |
      | --- |
      | **Description: **Module "junit-jupiter" of JUnit **URL: **[https://junit.org/](https://junit.org/)[7] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[8] |
      
          - org.junit.jupiter:junit-jupiter-api:jar:6.0.0 (test) ** 
            
            | JUnit Jupiter API |
            | --- |
            | **Description: **Module "junit-jupiter-api" of JUnit **URL: **[https://junit.org/](https://junit.org/)[7] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[8] |
            
                  - org.opentest4j:opentest4j:jar:1.3.0 (test) ** 
                    
                    | org.opentest4j:opentest4j |
                    | --- |
                    | **Description: **Open Test Alliance for the JVM **URL: **[https://github.com/ota4j-team/opentest4j](https://github.com/ota4j-team/opentest4j)[60] **Project Licenses: **[The Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
                  - org.junit.platform:junit-platform-commons:jar:6.0.0 (test) ** 
                    
                    | JUnit Platform Commons |
                    | --- |
                    | **Description: **Module "junit-platform-commons" of JUnit **URL: **[https://junit.org/](https://junit.org/)[7] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[8] |
                  - org.apiguardian:apiguardian-api:jar:1.1.2 (test) ** 
                    
                    | org.apiguardian:apiguardian-api |
                    | --- |
                    | **Description: **@API Guardian **URL: **[https://github.com/apiguardian-team/apiguardian](https://github.com/apiguardian-team/apiguardian)[59] **Project Licenses: **[The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[5] |
          - org.junit.jupiter:junit-jupiter-params:jar:6.0.0 (test) ** 
            
            | JUnit Jupiter Params |
            | --- |
            | **Description: **Module "junit-jupiter-params" of JUnit **URL: **[https://junit.org/](https://junit.org/)[7] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[8] |
          - org.junit.jupiter:junit-jupiter-engine:jar:6.0.0 (test) ** 
            
            | JUnit Jupiter Engine |
            | --- |
            | **Description: **Module "junit-jupiter-engine" of JUnit **URL: **[https://junit.org/](https://junit.org/)[7] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[8] |
            
                  - org.junit.platform:junit-platform-engine:jar:6.0.0 (test) ** 
                    
                    | JUnit Platform Engine API |
                    | --- |
                    | **Description: **Module "junit-platform-engine" of JUnit **URL: **[https://junit.org/](https://junit.org/)[7] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[8] |
    - org.assertj:assertj-core:jar:3.27.3 (test) ** 
      
      | AssertJ Core |
      | --- |
      | **Description: **Rich and fluent assertions for testing in Java **URL: **[https://assertj.github.io/doc/#assertj-core](https://assertj.github.io/doc/#assertj-core)[6] **Project Licenses: **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
      
          - net.bytebuddy:byte-buddy:jar:1.15.11 (test) ** 
            
            | Byte Buddy (without dependencies) |
            | --- |
            | **Description: **Byte Buddy is a Java library for creating Java classes at run time. This artifact is a build of Byte Buddy with all ASM dependencies repackaged into its own name space. **URL: **[https://bytebuddy.net/byte-buddy](https://bytebuddy.net/byte-buddy)[58] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |
    - network.ike.tooling:ike-build-standards:zip:site-theme:147 (provided) ** 
      
      | IKE Build Standards |
      | --- |
      | **Description: **Versioned Claude instruction files for IKE projects. Modular standards (Maven, Java, IKE-specific) distributed as a classified Maven artifact. **URL: **[https://ike.network/ike-tooling/ike-build-standards/](https://ike.network/ike-tooling/ike-build-standards/)[9] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[2] |

# Licenses

**The Apache License, Version 2.0: **JSpecify annotations, Woodstox, org.apiguardian:apiguardian-api, org.opentest4j:opentest4j

**Apache 2.0: **error-prone annotations

**The BSD 2-Clause License: **Stax2 API

**BSD-3-Clause: **ANTLR 4 Runtime, asm, asm-analysis, asm-tree, asm-util

**BSD-2-Clause: **HdrHistogram

**Eclipse Public License v2.0: **JUnit Jupiter (Aggregator), JUnit Jupiter API, JUnit Jupiter Engine, JUnit Jupiter Params, JUnit Platform Commons, JUnit Platform Engine API

**Apache 2: **JavaEWAH, univocity-parsers

**The MIT License (MIT): **ClassGraph

**Public Domain, per Creative Commons CC0: **HdrHistogram, LatencyUtils

**LGPL-2.1-or-later: **Java Native Access, Java Native Access Platform

**The MIT License: **Checker Qual

**Apache License, Version 2.0: **Byte Buddy (without dependencies), Caffeine cache, IKE Build Standards, IKE Maven Plugin Support, IKE Workspace Maven Plugin, IKE Workspace Model, Java Annotation Indexer, Objenesis, SnakeYAML

**Apache-2.0: **Apache Commons Lang, Apache Commons Text, AssertJ Core, Java Native Access, Java Native Access Platform, Maven 4 API :: Core, Maven 4 API :: Dependency Injection, Maven 4 API :: Meta annotations, Maven 4 API :: Model, Maven 4 API :: Plugin, Maven 4 API :: Repository Metadata, Maven 4 API :: Settings, Maven 4 API :: Toolchain, Maven 4 API :: XML, Maven 4 Model Support, Maven 4 XML Implementation

**The Apache Software License, Version 2.0: **Gizmo, Jackson dataformat: Smile, Jackson datatype: JSR310, Jackson-annotations, Jackson-core, Jackson-module-parameter-names, JetBrains Java Annotations, jackson-databind, java-object-diff, jgit, jsonrpc, micrometer-core, rewrite-core, rewrite-xml

# Dependency File Details

| Total | Size | Entries | Classes | Packages | Java Version | Debug Information |
| --- | --- | --- | --- | --- | --- | --- |
| jackson-annotations-2.21.jar | 82.1 kB | 89 | 76 | 2 | 1.8 | Yes |
| jackson-core-2.21.2.jar | 595.5 kB | 286 | - | - | - | - |
|    • Root | - | 250 | 213 | 16 | 1.8 | Yes |
|    • Versioned | - | 2 | 1 | 1 | 9 | No |
|    • Versioned | - | 12 | 3 | 1 | 11 | Yes |
|    • Versioned | - | 11 | 2 | 1 | 17 | Yes |
|    • Versioned | - | 11 | 2 | 1 | 21 | Yes |
| jackson-databind-2.21.2.jar | 1.7 MB | 852 | - | - | - | - |
|    • Root | - | 850 | 812 | 23 | 1.8 | Yes |
|    • Versioned | - | 2 | 1 | 1 | 9 | No |
| jackson-dataformat-smile-2.21.2.jar | 97.4 kB | 41 | - | - | - | - |
|    • Root | - | 39 | 20 | 3 | 1.8 | Yes |
|    • Versioned | - | 2 | 1 | 1 | 9 | No |
| jackson-datatype-jsr310-2.21.2.jar | 136.6 kB | 88 | - | - | - | - |
|    • Root | - | 86 | 64 | 6 | 1.8 | Yes |
|    • Versioned | - | 2 | 1 | 1 | 9 | No |
| jackson-module-parameter-names-2.21.2.jar | 10.2 kB | 23 | - | - | - | - |
|    • Root | - | 21 | 4 | 1 | 1.8 | Yes |
|    • Versioned | - | 2 | 1 | 1 | 9 | No |
| woodstox-core-7.1.1.jar | 1.6 MB | 1091 | 942 | 78 | 1.8 | Yes |
| caffeine-2.9.3.jar | 912.1 kB | 703 | 692 | 4 | 1.8 | Yes |
| error_prone_annotations-2.10.0.jar | 16 kB | 37 | 25 | 2 | 1.7 | Yes |
| JavaEWAH-1.1.13.jar | 166.9 kB | 120 | 106 | 5 | 1.8 | Yes |
| univocity-parsers-2.9.1.jar | 447 kB | 299 | 273 | 16 | 1.6 | Yes |
| classgraph-4.8.184.jar | 587 kB | 284 | - | - | - | - |
|    • Root | - | 282 | 253 | 13 | 1.8 | Yes |
|    • Versioned | - | 2 | 1 | 1 | 9 | No |
| micrometer-core-1.9.17.jar | 663.6 kB | 423 | 367 | 39 | 1.8 | Yes |
| jsonrpc-1.0.5.jar | 30.2 kB | 30 | 21 | 4 | 1.8 | Yes |
| gizmo-1.0.11.Final.jar | 101.3 kB | 77 | 66 | 1 | 1.8 | Yes |
| byte-buddy-1.15.11.jar | 8.5 MB | 5890 | - | - | - | - |
|    • Root | - | 2950 | 2897 | 38 | 1.5 | Yes |
|    • Versioned | - | 2940 | 2898 | 39 | 1.8 | Yes |
| jna-5.18.1.jar | 2 MB | 191 | 124 | 4 | 1.8 | Yes |
| jna-platform-5.18.1.jar | 1.4 MB | 1336 | 1288 | 15 | 1.8 | Yes |
| ike-build-standards-147-claude.zip | 81 kB | - | - | - | - | - |
| ike-build-standards-147-site-theme.zip | 3.4 kB | - | - | - | - | - |
| ike-maven-plugin-support-147.jar | 37.9 kB | 31 | 19 | 2 | 25 | Yes |
| ike-workspace-model-147.jar | 108.6 kB | 57 | 46 | 2 | 25 | Yes |
| antlr4-runtime-4.13.2.jar | 326.3 kB | 232 | 215 | 7 | 1.8 | Yes |
| commons-lang3-3.20.0.jar | 713.9 kB | 454 | - | - | - | - |
|    • Root | - | 452 | 421 | 18 | 1.8 | Yes |
|    • Versioned | - | 2 | 1 | 1 | 9 | No |
| commons-text-1.15.0.jar | 264.9 kB | 191 | - | - | - | - |
|    • Root | - | 189 | 168 | 8 | 1.8 | Yes |
|    • Versioned | - | 2 | 1 | 1 | 9 | No |
| maven-api-annotations-4.0.0-rc-5.jar | 13.1 kB | 27 | 12 | 1 | 17 | Yes |
| maven-api-core-4.0.0-rc-5.jar | 218.3 kB | 257 | 237 | 7 | 17 | Yes |
| maven-api-di-4.0.0-rc-5.jar | 16.4 kB | 32 | 13 | 2 | 17 | Yes |
| maven-api-metadata-4.0.0-rc-5.jar | 41.8 kB | 45 | 30 | 1 | 17 | Yes |
| maven-api-model-4.0.0-rc-5.jar | 222.2 kB | 128 | 113 | 1 | 17 | Yes |
| maven-api-plugin-4.0.0-rc-5.jar | 82.1 kB | 77 | 60 | 2 | 17 | Yes |
| maven-api-settings-4.0.0-rc-5.jar | 84.7 kB | 67 | 52 | 1 | 17 | Yes |
| maven-api-toolchain-4.0.0-rc-5.jar | 41.5 kB | 45 | 30 | 1 | 17 | Yes |
| maven-api-xml-4.0.0-rc-5.jar | 36.5 kB | 42 | 27 | 1 | 17 | Yes |
| maven-support-4.0.0-rc-5.jar | 299.4 kB | 81 | 55 | 6 | 17 | Yes |
| maven-xml-4.0.0-rc-5.jar | 51.8 kB | 47 | 30 | 1 | 17 | Yes |
| apiguardian-api-1.1.2.jar | 6.8 kB | 9 | 3 | 2 | 1.6 | Yes |
| assertj-core-3.27.3.jar | 1.4 MB | 881 | - | - | - | - |
|    • Root | - | 877 | 838 | 27 | 1.8 | Yes |
|    • Versioned | - | 4 | 1 | 1 | 9 | No |
| checker-qual-3.19.0.jar | 222.1 kB | 424 | 356 | 30 | 1.8 | Yes |
| stax2-api-4.2.2.jar | 195.9 kB | 146 | 125 | 12 | 1.6 | Yes |
| HdrHistogram-2.1.12.jar | 173.8 kB | 106 | 96 | 2 | 1.7 | Yes |
| jandex-2.4.2.Final.jar | 230.8 kB | 125 | 115 | 1 | 1.6 | Yes |
| annotations-26.1.0.jar | 31.1 kB | 72 | - | - | - | - |
|    • Root | - | 70 | 60 | 2 | 1.8 | Yes |
|    • Versioned | - | 2 | 1 | 1 | 9 | No |
| jspecify-1.0.0.jar | 3.8 kB | 14 | - | - | - | - |
|    • Root | - | 10 | 4 | 1 | 1.8 | No |
|    • Versioned | - | 4 | 1 | 1 | 9 | No |
| junit-jupiter-6.0.0.jar | 6.4 kB | 5 | 1 | 1 | 17 | No |
| junit-jupiter-api-6.0.0.jar | 249.9 kB | 224 | 208 | 9 | 17 | Yes |
| junit-jupiter-engine-6.0.0.jar | 353.7 kB | 188 | 171 | 9 | 17 | Yes |
| junit-jupiter-params-6.0.0.jar | 293.7 kB | 215 | 194 | 9 | 17 | Yes |
| junit-platform-commons-6.0.0.jar | 171.1 kB | 103 | 87 | 10 | 17 | Yes |
| junit-platform-engine-6.0.0.jar | 277.6 kB | 193 | 175 | 9 | 17 | Yes |
| LatencyUtils-2.0.3.jar | 29.8 kB | 31 | 22 | 1 | 1.6 | Yes |
| objenesis-3.5.jar | 49.5 kB | 58 | 43 | 10 | 1.8 | Yes |
| rewrite-core-8.79.2.jar | 1 MB | 586 | 548 | 26 | 1.8 | Yes |
| rewrite-xml-8.79.2.jar | 437.7 kB | 244 | 221 | 11 | 1.8 | Yes |
| java-object-diff-1.0.1.jar | 163.5 kB | 172 | 152 | 15 | 1.8 | Yes |
| jgit-1.4.1.jar | 2.8 MB | 1589 | 1525 | 48 | 1.8 | Yes |
| opentest4j-1.3.0.jar | 14.3 kB | 15 | 9 | 2 | 1.6 | Yes |
| asm-9.3.jar | 122.2 kB | 44 | 38 | 3 | 1.5 | Yes |
| asm-analysis-9.3.jar | 34.3 kB | 22 | 15 | 2 | 1.5 | Yes |
| asm-tree-9.3.jar | 52.7 kB | 45 | 39 | 2 | 1.5 | Yes |
| asm-util-9.3.jar | 85.7 kB | 32 | 26 | 2 | 1.5 | Yes |
| snakeyaml-2.2.jar | 334.4 kB | 278 | - | - | - | - |
|    • Root | - | 270 | 229 | 23 | 1.7 | Yes |
|    • Versioned | - | 8 | 3 | 2 | 9 | Yes |
| 62 | 30.4 MB | 19494 | 15071 | 600 | 25 | 58 |
| compile: 23 | compile: 9.7 MB | compile: 5841 | compile: 5144 | compile: 268 | 25 | compile: 22 |
| runtime: 23 | runtime: 8.8 MB | runtime: 5436 | runtime: 4921 | runtime: 204 | runtime: 23 |
| provided: 6 | provided: 623.3 kB | provided: 494 | provided: 423 | provided: 12 | provided: 4 |
| test: 10 | test: 11.2 MB | test: 7723 | test: 4583 | test: 116 | 17 | test: 9 |
