package dev.raindancer118.extendedreplay.paper.snapshot;

import dev.raindancer118.extendedreplay.storage.snapshot.ArenaSnapshotFile;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * Creates and applies arena snapshots without stalling the server: block access happens
 * on the main thread in per-tick budgets, compression and disk I/O run async.
 */
public final class SnapshotService {

    /** Blocks scanned/applied per tick. ~40k block reads cost well under 10 ms. */
    private static final int BLOCKS_PER_TICK = 40_000;

    private final Plugin plugin;
    private final Path directory;
    private volatile boolean jobRunning;

    public SnapshotService(Plugin plugin, Path directory) {
        this.plugin = plugin;
        this.directory = directory;
    }

    public boolean isJobRunning() {
        return jobRunning;
    }

    private Path fileOf(String name) {
        String safe = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        return directory.resolve(safe + ".erpa");
    }

    public boolean exists(String name) {
        return Files.exists(fileOf(name));
    }

    public List<String> list() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".erpa"))
                    .map(n -> n.substring(0, n.length() - 5))
                    .sorted()
                    .toList();
        }
    }

    public void delete(String name) throws IOException {
        Files.deleteIfExists(fileOf(name));
    }

    /** Header info + sha256 for /erp snapshot info|verify. Call async — reads the file. */
    public String describe(String name) throws IOException {
        Path file = fileOf(name);
        ArenaSnapshotFile snapshot = ArenaSnapshotFile.read(file);
        return String.format(Locale.ROOT,
                "%s · Welt %s · %d×%d×%d = %,d Blöcke · Palette %d · %d KB · sha256 %s",
                name, snapshot.worldName(), snapshot.sizeX(), snapshot.sizeY(),
                snapshot.sizeZ(), snapshot.blockCount(), snapshot.palette().size(),
                Files.size(file) / 1024, ArenaSnapshotFile.sha256(file).substring(0, 16) + "…");
    }

    /**
     * Scans the cuboid region in per-tick budgets and writes the snapshot async.
     * The returned future completes with the sha256 of the written file.
     */
    public CompletableFuture<String> create(String name, World world,
                                            int x1, int y1, int z1, int x2, int y2, int z2,
                                            Player progressViewer) {
        CompletableFuture<String> future = new CompletableFuture<>();
        if (jobRunning) {
            future.completeExceptionally(new IllegalStateException("Ein Snapshot-Job läuft bereits."));
            return future;
        }
        jobRunning = true;

        int minX = Math.min(x1, x2);
        int minY = Math.max(world.getMinHeight(), Math.min(y1, y2));
        int minZ = Math.min(z1, z2);
        int maxX = Math.max(x1, x2);
        int maxY = Math.min(world.getMaxHeight() - 1, Math.max(y1, y2));
        int maxZ = Math.max(z1, z2);
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;

        ArenaSnapshotFile.Builder builder;
        try {
            builder = new ArenaSnapshotFile.Builder(world.getName(),
                    minX, minY, minZ, sizeX, sizeY, sizeZ);
        } catch (IllegalArgumentException e) {
            jobRunning = false;
            future.completeExceptionally(e);
            return future;
        }

        long total = (long) sizeX * sizeY * sizeZ;
        BossBar bar = BossBar.bossBar(Component.text("Snapshot: scanne…"), 0f,
                BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        if (progressViewer != null) {
            progressViewer.showBossBar(bar);
        }

        // linear cursor over x + z*sizeX + y*sizeX*sizeZ
        final long[] cursor = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            long processed = 0;
            while (cursor[0] < total && processed < BLOCKS_PER_TICK) {
                long index = cursor[0];
                int x = (int) (index % sizeX);
                int z = (int) ((index / sizeX) % sizeZ);
                int y = (int) (index / ((long) sizeX * sizeZ));
                builder.set(x, y, z, world.getBlockAt(minX + x, minY + y, minZ + z)
                        .getBlockData().getAsString());
                cursor[0]++;
                processed++;
            }
            bar.progress((float) ((double) cursor[0] / total));
            if (cursor[0] >= total) {
                task.cancel();
                if (progressViewer != null) {
                    bar.name(Component.text("Snapshot: komprimiere…"));
                }
                ArenaSnapshotFile snapshot = builder.build();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        Path file = fileOf(name);
                        snapshot.write(file);
                        String sha = ArenaSnapshotFile.sha256(file);
                        future.complete(sha);
                    } catch (IOException e) {
                        plugin.getLogger().log(Level.WARNING, "Snapshot write failed", e);
                        future.completeExceptionally(e);
                    } finally {
                        jobRunning = false;
                        if (progressViewer != null) {
                            Bukkit.getScheduler().runTask(plugin, () ->
                                    progressViewer.hideBossBar(bar));
                        }
                    }
                });
            }
        }, 1L, 1L);
        return future;
    }

    /**
     * Applies a snapshot into the target world in per-tick budgets. Air blocks are only
     * written when the target block is not already air (void playback worlds).
     * Completes with the number of blocks written.
     */
    public CompletableFuture<Long> apply(String name, World target, Player progressViewer) {
        CompletableFuture<Long> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ArenaSnapshotFile snapshot;
            try {
                snapshot = ArenaSnapshotFile.read(fileOf(name));
            } catch (IOException e) {
                future.completeExceptionally(e);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () ->
                    applyOnMain(snapshot, target, progressViewer, future));
        });
        return future;
    }

    private void applyOnMain(ArenaSnapshotFile snapshot, World target, Player progressViewer,
                             CompletableFuture<Long> future) {
        long total = snapshot.blockCount();
        int sizeX = snapshot.sizeX();
        int sizeZ = snapshot.sizeZ();
        BossBar bar = BossBar.bossBar(Component.text("Snapshot anwenden…"), 0f,
                BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        if (progressViewer != null) {
            progressViewer.showBossBar(bar);
        }
        // pre-parse palette once; remember which entries are air
        BlockData[] palette = new BlockData[snapshot.palette().size()];
        boolean[] paletteAir = new boolean[palette.length];
        for (int i = 0; i < palette.length; i++) {
            String entry = snapshot.palette().get(i);
            try {
                palette[i] = Bukkit.createBlockData(entry);
                paletteAir[i] = palette[i].getMaterial().isAir();
            } catch (IllegalArgumentException e) {
                palette[i] = null; // unknown on this version — skip those blocks
            }
        }
        final long[] cursor = {0};
        final long[] written = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            long processed = 0;
            while (cursor[0] < total && processed < BLOCKS_PER_TICK) {
                long index = cursor[0];
                int x = (int) (index % sizeX);
                int z = (int) ((index / sizeX) % sizeZ);
                int y = (int) (index / ((long) sizeX * sizeZ));
                int paletteIndex = snapshot.paletteIndexAt(x, y, z);
                BlockData data = palette[paletteIndex];
                if (data != null) {
                    var block = target.getBlockAt(snapshot.minX() + x,
                            snapshot.minY() + y, snapshot.minZ() + z);
                    if (!paletteAir[paletteIndex] || !block.getType().isAir()) {
                        block.setBlockData(data, false);
                        written[0]++;
                    }
                }
                cursor[0]++;
                processed++;
            }
            bar.progress((float) ((double) cursor[0] / total));
            if (cursor[0] >= total) {
                task.cancel();
                if (progressViewer != null) {
                    progressViewer.hideBossBar(bar);
                }
                future.complete(written[0]);
            }
        }, 1L, 1L);
    }
}
