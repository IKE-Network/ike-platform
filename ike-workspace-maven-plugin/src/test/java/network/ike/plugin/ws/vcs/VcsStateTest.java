package network.ike.plugin.ws.vcs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VcsStateTest {

    @Test
    void readFrom_missingFile_returnsEmpty(@TempDir Path dir) {
        assertThat(VcsState.readFrom(dir)).isEmpty();
    }

    @Test
    void readFrom_missingIkeDir_returnsEmpty(@TempDir Path dir) {
        // No .ike/ directory at all
        assertThat(VcsState.readFrom(dir)).isEmpty();
    }

    @Test
    void readFrom_emptyFile_returnsEmpty(@TempDir Path dir) throws IOException {
        Path ikeDir = dir.resolve(".ike");
        Files.createDirectories(ikeDir);
        Files.writeString(ikeDir.resolve("vcs-state"), "", StandardCharsets.UTF_8);
        assertThat(VcsState.readFrom(dir)).isEmpty();
    }

    @Test
    void readFrom_missingSha_returnsEmpty(@TempDir Path dir) throws IOException {
        Path ikeDir = dir.resolve(".ike");
        Files.createDirectories(ikeDir);
        Files.writeString(ikeDir.resolve("vcs-state"),
                "timestamp=2026-03-31T14:00:00Z\nmachine=test\nbranch=main\naction=commit\n",
                StandardCharsets.UTF_8);
        assertThat(VcsState.readFrom(dir)).isEmpty();
    }

    @Test
    void roundTrip(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve(".ike"));

        VcsState original = new VcsState(
                "2026-03-31T14:22:00Z",
                "mac-studio",
                "feature/x",
                "a1b2c3d4",
                VcsState.Action.COMMIT
        );

        VcsState.writeTo(dir, original);

        Optional<VcsState> read = VcsState.readFrom(dir);
        assertThat(read).isPresent();
        assertThat(read.get().timestamp()).isEqualTo("2026-03-31T14:22:00Z");
        assertThat(read.get().machine()).isEqualTo("mac-studio");
        assertThat(read.get().branch()).isEqualTo("feature/x");
        assertThat(read.get().sha()).isEqualTo("a1b2c3d4");
        assertThat(read.get().action()).isEqualTo(VcsState.Action.COMMIT);
    }

    @Test
    void writeTo_createsIkeDirectory(@TempDir Path dir) throws IOException {
        VcsState state = new VcsState(
                "2026-03-31T14:22:00Z", "test", "main", "abcd1234", VcsState.Action.PUSH);

        VcsState.writeTo(dir, state);

        assertThat(dir.resolve(".ike")).isDirectory();
        assertThat(dir.resolve(".ike/vcs-state")).isRegularFile();
    }

    @Test
    void writeTo_propertiesFormat(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve(".ike"));

        VcsState state = new VcsState(
                "2026-03-31T14:22:00Z", "mac-studio", "main", "a1b2c3d4", VcsState.Action.COMMIT);

        VcsState.writeTo(dir, state);

        String content = Files.readString(dir.resolve(".ike/vcs-state"),
                StandardCharsets.UTF_8);
        assertThat(content).contains("timestamp=2026-03-31T14:22:00Z");
        assertThat(content).contains("machine=mac-studio");
        assertThat(content).contains("branch=main");
        assertThat(content).contains("sha=a1b2c3d4");
        assertThat(content).contains("action=commit");
    }

    @Test
    void create_setsTimestampAndMachine() {
        VcsState state = VcsState.create("main", "abcd1234", VcsState.Action.PUSH);

        assertThat(state.branch()).isEqualTo("main");
        assertThat(state.sha()).isEqualTo("abcd1234");
        assertThat(state.action()).isEqualTo(VcsState.Action.PUSH);
        assertThat(state.timestamp()).isNotEmpty();
        assertThat(state.machine()).isNotEmpty();
    }

    @Test
    void isIkeManaged_withIkeDir(@TempDir Path dir) throws IOException {
        assertThat(VcsState.isIkeManaged(dir)).isFalse();

        Files.createDirectories(dir.resolve(".ike"));
        assertThat(VcsState.isIkeManaged(dir)).isTrue();
    }

    @Test
    void readFrom_allActions(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve(".ike"));

        for (VcsState.Action action : VcsState.Action.values()) {
            VcsState state = new VcsState(
                    "2026-03-31T14:22:00Z", "test", "main", "12345678", action);
            VcsState.writeTo(dir, state);

            Optional<VcsState> read = VcsState.readFrom(dir);
            assertThat(read).isPresent();
            assertThat(read.get().action()).isEqualTo(action);
        }
    }
}
