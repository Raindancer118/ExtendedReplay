package dev.raindancer118.extendedreplay.storage.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArenaSnapshotFileTest {

    @TempDir
    Path tempDir;

    private ArenaSnapshotFile sample() {
        var builder = new ArenaSnapshotFile.Builder("arena", -10, 60, -10, 20, 5, 20);
        for (int y = 0; y < 5; y++) {
            for (int z = 0; z < 20; z++) {
                for (int x = 0; x < 20; x++) {
                    String data = y == 0 ? "minecraft:stone"
                            : (x + z) % 7 == 0 ? "minecraft:oak_planks" : "minecraft:air";
                    builder.set(x, y, z, data);
                }
            }
        }
        builder.set(5, 2, 5, "minecraft:chest[facing=north]");
        return builder.build();
    }

    @Test
    void roundtripPreservesEverything() throws IOException {
        Path file = tempDir.resolve("arena.erpa");
        sample().write(file);

        ArenaSnapshotFile read = ArenaSnapshotFile.read(file);
        assertThat(read.worldName()).isEqualTo("arena");
        assertThat(read.minX()).isEqualTo(-10);
        assertThat(read.minY()).isEqualTo(60);
        assertThat(read.minZ()).isEqualTo(-10);
        assertThat(read.sizeX()).isEqualTo(20);
        assertThat(read.blockCount()).isEqualTo(20L * 5 * 20);
        assertThat(read.blockData(0, 0, 0)).isEqualTo("minecraft:stone");
        assertThat(read.blockData(5, 2, 5)).isEqualTo("minecraft:chest[facing=north]");
        assertThat(read.blockData(1, 3, 2)).isEqualTo("minecraft:air");
        assertThat(read.palette()).hasSize(4);
    }

    @Test
    void sha256IsStable() throws IOException {
        Path file = tempDir.resolve("arena.erpa");
        sample().write(file);
        String first = ArenaSnapshotFile.sha256(file);
        assertThat(first).hasSize(64).isEqualTo(ArenaSnapshotFile.sha256(file));
    }

    @Test
    void corruptFileIsRejected() throws IOException {
        Path file = tempDir.resolve("arena.erpa");
        sample().write(file);
        byte[] bytes = Files.readAllBytes(file);
        bytes[0] = 0x00;
        Files.write(file, bytes);
        assertThatThrownBy(() -> ArenaSnapshotFile.read(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("bad magic");
    }

    @Test
    void oversizedRegionIsRejected() {
        assertThatThrownBy(() -> new ArenaSnapshotFile.Builder("w", 0, 0, 0, 10_000, 10_000, 10_000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
