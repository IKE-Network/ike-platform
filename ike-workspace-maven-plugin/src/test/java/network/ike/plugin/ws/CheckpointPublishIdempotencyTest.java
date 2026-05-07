package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the idempotency guard in {@code ws:checkpoint-publish}
 * (ike-issues#294).
 *
 * <p>The guard exits cleanly when {@code checkpoints/checkpoint-<name>.yaml}
 * already exists, instead of re-running the tag-and-write pipeline
 * (which would either duplicate-tag or rewrite the file with a fresh
 * timestamp). The check fires early, before any of the per-subproject
 * git work, so the test only needs the existing file plus a minimal
 * workspace.
 */
class CheckpointPublishIdempotencyTest {

    @TempDir
    Path tempDir;

    @Test
    void rerun_with_existing_checkpoint_file_short_circuits() throws Exception {
        scaffoldWorkspace();

        // Pre-create the checkpoint file as if a prior successful run
        // had written it. The guard's predicate is simply
        // Files.isRegularFile(path).
        Path checkpointDir = tempDir.resolve("checkpoints");
        Files.createDirectories(checkpointDir);
        Path checkpoint = checkpointDir.resolve("checkpoint-pre-existing.yaml");
        String original = """
                checkpoint: pre-existing
                generated: 2026-05-07T00:00:00Z
                """;
        Files.writeString(checkpoint, original, StandardCharsets.UTF_8);

        WsCheckpointDraftMojo mojo = TestLog.createMojo(WsCheckpointPublishMojo.class);
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        setField(mojo, "name", "pre-existing");
        setField(mojo, "publish", true);

        // The guard must short-circuit BEFORE the inner tag/commit
        // machinery, so this should complete without throwing even
        // though the workspace has no git repos and no subprojects.
        mojo.execute();

        // File must be untouched — the guard exits early; no rewrite.
        assertThat(Files.readString(checkpoint, StandardCharsets.UTF_8))
                .as("existing checkpoint file should be preserved")
                .isEqualTo(original);
    }

    private void scaffoldWorkspace() throws Exception {
        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.1"
                generated: "2026-05-07"

                defaults:
                  branch: main

                subprojects:
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <groupId>local.test</groupId>
                    <artifactId>idempotency-test-ws</artifactId>
                    <version>1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """, StandardCharsets.UTF_8);
    }

    private static void setField(Object target, String fieldName, Object value,
                                 Class<?> clazz) throws Exception {
        Field f = clazz.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void setField(Object target, String fieldName, Object value)
            throws Exception {
        // walk class hierarchy so we can set fields declared in any
        // class up the inheritance chain without specifying which one
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
