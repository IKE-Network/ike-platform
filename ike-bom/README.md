# ike-bom

**Documentation:** https://ike.network/ike-platform/ike-bom/

Maven Bill of Materials pinning the versions of every IKE-published
artifact. External projects import it once and inherit a coherent set
of versions for `ike-parent`, `ike-workspace-maven-plugin`, the
`ike-tooling` family, and the `ike-docs` family.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>network.ike.platform</groupId>
            <artifactId>ike-bom</artifactId>
            <version>22</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Use the BOM if you want only the version pins. Inherit `ike-parent`
if you want the build conventions too. See the
[BOM vs. ike-parent decision guide](https://ike.network/ike-platform/ike-bom/)
for full guidance.
