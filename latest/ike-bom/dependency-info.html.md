---
date_published: 2026-10-08
date_modified: 2026-10-08
canonical_url: https://ike.network/ike-platform/ike-bom/dependency-info.html
---

# Maven Coordinates

## Apache Maven

```
<dependency>
  <groupId>network.ike.platform</groupId>
  <artifactId>ike-bom</artifactId>
  <version>200</version>
  <type>pom</type>
</dependency>
```

## Apache Ivy

```
<dependency org="network.ike.platform" name="ike-bom" rev="200">
  <artifact name="ike-bom" type="pom" />
</dependency>
```

## Groovy Grape

```
@Grapes(
@Grab(group='network.ike.platform', module='ike-bom', version='200')
)
```

## Gradle/Grails

```
implementation 'network.ike.platform:ike-bom:200'
```

## Scala SBT

```
libraryDependencies += "network.ike.platform" % "ike-bom" % "200"
```

## Leiningen

```
[network.ike.platform/ike-bom "200"]
```
