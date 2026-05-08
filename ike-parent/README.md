# ike-parent

**Documentation:** https://ike.network/ike-platform/ike-parent/

Standard parent POM for IKE Community projects. Inheriting it
provides Java 25 build conventions, GPG signing via Bouncy Castle,
JaCoCo, the AsciiDoc documentation pipeline, dependency version
management for the IKE ecosystem, and `extensions=true` declarations
for `ike-maven-plugin` and `ike-doc-maven-plugin`.

```xml
<parent>
    <groupId>network.ike.platform</groupId>
    <artifactId>ike-parent</artifactId>
    <version>22</version>
</parent>
```

See the [full module documentation](https://ike.network/ike-platform/ike-parent/)
for the complete list of managed dependencies and build conventions.
