package dev.raindancer118.extendedreplay.paper.replay.route;

import dev.raindancer118.extendedreplay.core.model.PlayerFrame;
import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;
import dev.raindancer118.extendedreplay.paper.config.ReplayConfig;
import dev.raindancer118.extendedreplay.paper.replay.PlaybackSession;
import dev.raindancer118.extendedreplay.storage.ReplayStorage;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Replay-server-only analysis: renders player movement paths into the playback world as
 * colored carpet/glass/concrete overlays or particles.
 *
 * <p>Frame reading and route simplification run async; block placement happens on the
 * main thread with a strict per-tick budget. Original blocks are remembered and restored
 * by {@link #clear}.</p>
 */
public final class RouteManager {

    public enum Mode {
        CARPET, GLASS, CONCRETE, PARTICLES;

        public static Mode parse(String value) {
            try {
                return valueOf(value.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return CARPET;
            }
        }
    }

    private static final Material[] CARPET_COLORS = {
            Material.RED_CARPET, Material.BLUE_CARPET, Material.LIME_CARPET,
            Material.YELLOW_CARPET, Material.MAGENTA_CARPET, Material.CYAN_CARPET,
            Material.ORANGE_CARPET, Material.WHITE_CARPET};
    private static final Material[] GLASS_COLORS = {
            Material.RED_STAINED_GLASS, Material.BLUE_STAINED_GLASS, Material.LIME_STAINED_GLASS,
            Material.YELLOW_STAINED_GLASS, Material.MAGENTA_STAINED_GLASS,
            Material.CYAN_STAINED_GLASS, Material.ORANGE_STAINED_GLASS, Material.WHITE_STAINED_GLASS};
    private static final Material[] CONCRETE_COLORS = {
            Material.RED_CONCRETE, Material.BLUE_CONCRETE, Material.LIME_CONCRETE,
            Material.YELLOW_CONCRETE, Material.MAGENTA_CONCRETE, Material.CYAN_CONCRETE,
            Material.ORANGE_CONCRETE, Material.WHITE_CONCRETE};

    private record BlockPos(int x, int y, int z) {
    }

    private final Plugin plugin;
    private final ReplayConfig config;
    private final ReplayStorage storage;

    private final Map<BlockPos, BlockData> originals = new LinkedHashMap<>();
    private Mode mode = Mode.CARPET;
    private volatile boolean jobRunning;
    private volatile boolean cancelRequested;

    public RouteManager(Plugin plugin, ReplayConfig config, ReplayStorage storage) {
        this.plugin = plugin;
        this.config = config;
        this.storage = storage;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    public boolean isJobRunning() {
        return jobRunning;
    }

    public void cancel() {
        cancelRequested = true;
    }

    /**
     * Renders routes for the given player indexes (empty = all players) of the playback
     * session's recording between the tick bounds.
     */
    public void render(Player moderator, PlaybackSession playback, List<Integer> playerIndexes,
                       int startTick, int endTick) {
        if (jobRunning) {
            moderator.sendMessage(Component.text("Es läuft bereits ein Route-Job."));
            return;
        }
        jobRunning = true;
        cancelRequested = false;
        UUID sessionId = playback.sessionId();
        World world = playback.world();
        BossBar bar = BossBar.bossBar(Component.text("Route: lade Frames…"), 0f,
                BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        moderator.showBossBar(bar);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<Integer, List<BlockPos>> routes = computeRoutes(sessionId, playerIndexes,
                        startTick, endTick);
                if (cancelRequested) {
                    finishJob(moderator, bar, "Route-Job abgebrochen.");
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () ->
                        applyRoutes(moderator, bar, world, routes));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Route job failed", e);
                finishJob(moderator, bar, "Route-Job fehlgeschlagen: " + e.getMessage());
            }
        });
    }

    /** Async: reads frames from storage and simplifies them to deduplicated block positions. */
    private Map<Integer, List<BlockPos>> computeRoutes(UUID sessionId, List<Integer> playerIndexes,
                                                       int startTick, int endTick) throws Exception {
        Map<Integer, List<BlockPos>> routes = new LinkedHashMap<>();
        Map<Integer, BlockPos> lastKept = new LinkedHashMap<>();
        storage.readSession(sessionId, packet -> {
            if (!(packet instanceof ReplayPacket.PlayerFramePacket p)) {
                return;
            }
            PlayerFrame frame = p.frame();
            if (frame.tick() < startTick || frame.tick() > endTick) {
                return;
            }
            if (!playerIndexes.isEmpty() && !playerIndexes.contains(frame.playerIndex())) {
                return;
            }
            BlockPos pos = new BlockPos((int) Math.floor(frame.x()),
                    (int) Math.floor(frame.y()), (int) Math.floor(frame.z()));
            BlockPos last = lastKept.get(frame.playerIndex());
            if (pos.equals(last)) {
                return; // simplification: one marker per entered block
            }
            lastKept.put(frame.playerIndex(), pos);
            routes.computeIfAbsent(frame.playerIndex(), k -> new ArrayList<>()).add(pos);
        });
        return routes;
    }

    /** Main thread: applies markers with a per-tick block budget. */
    private void applyRoutes(Player moderator, BossBar bar, World world,
                             Map<Integer, List<BlockPos>> routes) {
        List<Map.Entry<Integer, BlockPos>> queue = new ArrayList<>();
        for (Map.Entry<Integer, List<BlockPos>> entry : routes.entrySet()) {
            for (BlockPos pos : entry.getValue()) {
                queue.add(Map.entry(entry.getKey(), pos));
            }
        }
        if (queue.isEmpty()) {
            finishJob(moderator, bar, "Keine Frames im gewählten Bereich.");
            return;
        }
        int budget = Math.max(1, config.maxBlockChangesPerTick());
        int total = queue.size();

        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (cancelRequested) {
                task.cancel();
                finishJob(moderator, bar, "Route-Job abgebrochen (" + originals.size()
                        + " Marker gesetzt).");
                return;
            }
            int placed = 0;
            while (!queue.isEmpty() && placed < budget) {
                Map.Entry<Integer, BlockPos> entry = queue.remove(queue.size() - 1);
                placeMarker(world, entry.getValue(), entry.getKey());
                placed++;
            }
            bar.progress(1f - (float) queue.size() / total);
            bar.name(Component.text("Route: " + (total - queue.size()) + "/" + total));
            if (queue.isEmpty()) {
                task.cancel();
                finishJob(moderator, bar, "Route gerendert: " + total + " Marker. "
                        + "/erp route clear zum Entfernen.");
            }
        }, 1L, 1L);
    }

    private void placeMarker(World world, BlockPos pos, int playerIndex) {
        if (mode == Mode.PARTICLES) {
            world.spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER,
                    pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5, 3, 0.1, 0.1, 0.1, 0);
            return;
        }
        Material[] palette = switch (mode) {
            case GLASS -> GLASS_COLORS;
            case CONCRETE -> CONCRETE_COLORS;
            default -> CARPET_COLORS;
        };
        Material material = palette[Math.floorMod(playerIndex, palette.length)];
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (mode == Mode.CARPET && !block.getType().isAir()) {
            // carpets need support; place them one above solid ground
            block = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
        }
        BlockPos actual = new BlockPos(block.getX(), block.getY(), block.getZ());
        originals.putIfAbsent(actual, block.getBlockData());
        block.setType(material, false);
    }

    private void finishJob(Player moderator, BossBar bar, String message) {
        jobRunning = false;
        Bukkit.getScheduler().runTask(plugin, () -> {
            moderator.hideBossBar(bar);
            moderator.sendMessage(Component.text(message));
        });
    }

    // --- heatmaps ---

    /** Intensity gradient, cold → hot. */
    private static final Material[] HEAT_GRADIENT = {
            Material.WHITE_CONCRETE, Material.YELLOW_CONCRETE, Material.ORANGE_CONCRETE,
            Material.RED_CONCRETE, Material.MAGENTA_CONCRETE};

    /**
     * Renders a movement heatmap: frame density per block column, drawn as color-graded
     * concrete at the position players actually moved through.
     */
    public void renderMovementHeatmap(Player moderator, PlaybackSession playback) {
        if (jobRunning) {
            moderator.sendMessage(Component.text("Es läuft bereits ein Render-Job."));
            return;
        }
        jobRunning = true;
        cancelRequested = false;
        UUID sessionId = playback.sessionId();
        World world = playback.world();
        BossBar bar = BossBar.bossBar(Component.text("Heatmap: lese Frames…"), 0f,
                BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        moderator.showBossBar(bar);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<BlockPos, Integer> counts = new LinkedHashMap<>();
                storage.readSession(sessionId, packet -> {
                    if (packet instanceof ReplayPacket.PlayerFramePacket p) {
                        PlayerFrame frame = p.frame();
                        BlockPos pos = new BlockPos((int) Math.floor(frame.x()),
                                (int) Math.floor(frame.y()), (int) Math.floor(frame.z()));
                        counts.merge(pos, 1, Integer::sum);
                    }
                });
                Bukkit.getScheduler().runTask(plugin, () ->
                        applyHeatmap(moderator, bar, world, counts));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Heatmap job failed", e);
                finishJob(moderator, bar, "Heatmap fehlgeschlagen: " + e.getMessage());
            }
        });
    }

    /**
     * Renders an event heatmap (kills/deaths/loot/…): one graded marker per event
     * location bin, hotter where more events happened.
     */
    public void renderEventHeatmap(Player moderator, PlaybackSession playback, String category) {
        if (jobRunning) {
            moderator.sendMessage(Component.text("Es läuft bereits ein Render-Job."));
            return;
        }
        jobRunning = true;
        cancelRequested = false;
        World world = playback.world();
        BossBar bar = BossBar.bossBar(Component.text("Heatmap: lese Events…"), 0f,
                BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        moderator.showBossBar(bar);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<BlockPos, Integer> counts = new LinkedHashMap<>();
                for (var event : storage.listEvents(playback.sessionId(), category, 100_000)) {
                    if (!event.hasLocation()) {
                        continue;
                    }
                    BlockPos pos = new BlockPos((int) Math.floor(event.x()),
                            (int) Math.floor(event.y()), (int) Math.floor(event.z()));
                    counts.merge(pos, 1, Integer::sum);
                }
                Bukkit.getScheduler().runTask(plugin, () ->
                        applyHeatmap(moderator, bar, world, counts));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Heatmap job failed", e);
                finishJob(moderator, bar, "Heatmap fehlgeschlagen: " + e.getMessage());
            }
        });
    }

    /** Main thread: budgeted placement of intensity-graded markers. */
    private void applyHeatmap(Player moderator, BossBar bar, World world,
                              Map<BlockPos, Integer> counts) {
        if (counts.isEmpty()) {
            finishJob(moderator, bar, "Keine Daten für die Heatmap.");
            return;
        }
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        List<Map.Entry<BlockPos, Integer>> queue = new ArrayList<>(counts.entrySet());
        int budget = Math.max(1, config.maxBlockChangesPerTick());
        int total = queue.size();

        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (cancelRequested) {
                task.cancel();
                finishJob(moderator, bar, "Heatmap-Job abgebrochen.");
                return;
            }
            int placed = 0;
            while (!queue.isEmpty() && placed < budget) {
                Map.Entry<BlockPos, Integer> entry = queue.remove(queue.size() - 1);
                double normalized = Math.log1p(entry.getValue()) / Math.log1p(max);
                int grade = Math.min(HEAT_GRADIENT.length - 1,
                        (int) Math.floor(normalized * HEAT_GRADIENT.length));
                Block block = world.getBlockAt(entry.getKey().x(), entry.getKey().y(),
                        entry.getKey().z());
                BlockPos actual = new BlockPos(block.getX(), block.getY(), block.getZ());
                originals.putIfAbsent(actual, block.getBlockData());
                block.setType(HEAT_GRADIENT[grade], false);
                placed++;
            }
            bar.progress(1f - (float) queue.size() / total);
            bar.name(Component.text("Heatmap: " + (total - queue.size()) + "/" + total));
            if (queue.isEmpty()) {
                task.cancel();
                finishJob(moderator, bar, "Heatmap gerendert: " + total
                        + " Zellen (max. Intensität " + max + "). /erp route clear zum Entfernen.");
            }
        }, 1L, 1L);
    }

    /** Restores every block the route renderer changed. Budgeted. */
    public void clear(Player moderator) {
        List<Map.Entry<BlockPos, BlockData>> entries = new ArrayList<>(originals.entrySet());
        originals.clear();
        if (entries.isEmpty()) {
            moderator.sendMessage(Component.text("Keine Route-Marker vorhanden."));
            return;
        }
        int budget = Math.max(1, config.maxBlockChangesPerTick());
        World world = moderator.getWorld();
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int restored = 0;
            while (!entries.isEmpty() && restored < budget) {
                Map.Entry<BlockPos, BlockData> entry = entries.remove(entries.size() - 1);
                world.getBlockAt(entry.getKey().x(), entry.getKey().y(), entry.getKey().z())
                        .setBlockData(entry.getValue(), false);
                restored++;
            }
            if (entries.isEmpty()) {
                task.cancel();
                moderator.sendMessage(Component.text("Route-Marker entfernt."));
            }
        }, 1L, 1L);
    }

    public int markerCount() {
        return originals.size();
    }
}
