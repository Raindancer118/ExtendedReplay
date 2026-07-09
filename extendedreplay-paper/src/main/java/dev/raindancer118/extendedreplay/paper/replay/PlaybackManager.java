package dev.raindancer118.extendedreplay.paper.replay;

import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;
import dev.raindancer118.extendedreplay.paper.config.ReplayConfig;
import dev.raindancer118.extendedreplay.paper.replay.render.ArmorStandRenderer;
import dev.raindancer118.extendedreplay.paper.replay.render.MannequinRenderer;
import dev.raindancer118.extendedreplay.paper.replay.render.ReplayActorRenderer;
import dev.raindancer118.extendedreplay.storage.meta.SessionRecord;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Owns all playback sessions on the replay server: opening (async load, main-thread
 * spawn), the per-tick advance task, viewer teleports/follow and closing.
 */
public final class PlaybackManager {

    private final Plugin plugin;
    private final ReplayConfig config;
    private final ReplayServerManager replayServer;
    private final dev.raindancer118.extendedreplay.paper.gui.HotbarUI hotbar;
    private final dev.raindancer118.extendedreplay.paper.snapshot.SnapshotService snapshots;
    private final Map<UUID, PlaybackSession> sessionsByViewer = new HashMap<>();
    private final Map<UUID, PlaybackSession> sessionsById = new HashMap<>();
    private final Map<UUID, org.bukkit.inventory.ItemStack[]> savedInventories = new HashMap<>();
    private final Map<UUID, GameMode> savedGameModes = new HashMap<>();
    private final Map<UUID, Map<String, Location>> savedCameras = new HashMap<>();
    private BukkitTask tickTask;
    private int worldCounter;

    public PlaybackManager(Plugin plugin, ReplayConfig config, ReplayServerManager replayServer,
                           dev.raindancer118.extendedreplay.paper.gui.HotbarUI hotbar,
                           dev.raindancer118.extendedreplay.paper.snapshot.SnapshotService snapshots) {
        this.plugin = plugin;
        this.config = config;
        this.replayServer = replayServer;
        this.hotbar = hotbar;
        this.snapshots = snapshots;
    }

    /** Per-viewer named camera positions (session-scoped, in memory). */
    public Map<String, Location> camerasOf(Player viewer) {
        return savedCameras.computeIfAbsent(viewer.getUniqueId(), k -> new HashMap<>());
    }

    public void start() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        for (PlaybackSession session : List.copyOf(sessionsById.values())) {
            session.close();
        }
        sessionsById.clear();
        sessionsByViewer.clear();
    }

    private void tick() {
        for (PlaybackSession session : sessionsById.values()) {
            session.advance();
            followTick(session);
        }
    }

    private void followTick(PlaybackSession session) {
        Integer followed = session.followedPlayer();
        if (followed == null) {
            return;
        }
        Location target = session.actorLocation(followed);
        if (target == null) {
            return;
        }
        for (UUID viewerId : session.viewers()) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !viewer.getWorld().equals(session.world())) {
                continue;
            }
            if (session.isPovMode()) {
                // first-person approximation: eye position + recorded view direction
                var frame = session.lastFrame(followed);
                Location eye = target.clone().add(0, 1.62, 0);
                if (frame != null) {
                    eye.setYaw(dev.raindancer118.extendedreplay.core.protocol.PacketIO
                            .byteToAngle(frame.yaw()));
                    eye.setPitch(dev.raindancer118.extendedreplay.core.protocol.PacketIO
                            .byteToAngle(frame.pitch()));
                }
                viewer.teleport(eye);
            } else if (viewer.getLocation().distanceSquared(target) > 9) {
                viewer.teleport(target.clone().add(0, 3, 0));
            }
        }
    }

    /**
     * Opens a playback session: packets load on an async thread, world creation, actor
     * spawn and viewer teleport happen back on the main thread.
     */
    public CompletableFuture<PlaybackSession> open(UUID sessionId, Player viewer) {
        PlaybackSession existing = sessionsById.get(sessionId);
        if (existing != null) {
            attachViewer(existing, viewer);
            return CompletableFuture.completedFuture(existing);
        }
        CompletableFuture<PlaybackSession> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<ReplayPacket> packets;
            String name;
            Long worldSeed;
            String worldEnvironment;
            String snapshotName;
            try {
                packets = replayServer.storage().loadSession(sessionId);
                SessionRecord sessionRecord = replayServer.storage().getSession(sessionId).orElse(null);
                name = sessionRecord != null ? sessionRecord.name() : sessionId.toString();
                worldSeed = sessionRecord != null ? sessionRecord.worldSeed() : null;
                worldEnvironment = sessionRecord != null ? sessionRecord.worldEnvironment() : null;
                snapshotName = sessionRecord != null ? sessionRecord.snapshotName() : null;
            } catch (IOException | SQLException e) {
                future.completeExceptionally(e);
                return;
            }
            if (packets.isEmpty()) {
                future.completeExceptionally(
                        new IllegalArgumentException("Session has no data: " + sessionId));
                return;
            }
            String finalName = name;
            Long finalWorldSeed = worldSeed;
            String finalWorldEnvironment = worldEnvironment;
            String finalSnapshotName = snapshotName;
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    String worldName = config.playbackWorldPrefix() + "_" + (worldCounter++);
                    World world = finalWorldSeed != null
                            ? PlaybackWorlds.getOrCreate(worldName, finalWorldSeed, finalWorldEnvironment)
                            : PlaybackWorlds.getOrCreate(worldName);
                    PlaybackSession session = new PlaybackSession(sessionId, finalName, world,
                            createRenderer(), packets,
                            config.keyframeIntervalSeconds() * 20);
                    sessionsById.put(sessionId, session);

                    Runnable finish = () -> {
                        session.seek(0);
                        attachViewer(session, viewer);
                        future.complete(session);
                    };
                    // arena base state first, if the session references a local snapshot
                    if (finalSnapshotName != null && snapshots.exists(finalSnapshotName)) {
                        viewer.sendMessage(net.kyori.adventure.text.Component.text(
                                "Wende Arena-Snapshot '" + finalSnapshotName + "' an…"));
                        snapshots.apply(finalSnapshotName, world, viewer)
                                .whenComplete((written, error) ->
                                        Bukkit.getScheduler().runTask(plugin, finish));
                    } else {
                        finish.run();
                    }
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        });
        return future;
    }

    private ReplayActorRenderer createRenderer() {
        if ("MANNEQUIN".equalsIgnoreCase(config.preferredRenderer())
                && MannequinRenderer.isSupported()) {
            MannequinRenderer renderer = new MannequinRenderer();
            renderer.setNameTagVisible(config.showNametags());
            return renderer;
        }
        ArmorStandRenderer renderer = new ArmorStandRenderer();
        renderer.setNameTagVisible(config.showNametags());
        return renderer;
    }

    private void attachViewer(PlaybackSession session, Player viewer) {
        // one playback per viewer: leaving a previous one first
        detachViewer(viewer);
        session.addViewer(viewer.getUniqueId());
        sessionsByViewer.put(viewer.getUniqueId(), session);

        savedInventories.put(viewer.getUniqueId(), viewer.getInventory().getContents());
        savedGameModes.put(viewer.getUniqueId(), viewer.getGameMode());
        // adventure + flight instead of spectator so the hotbar UI stays usable
        viewer.setGameMode(GameMode.ADVENTURE);
        viewer.setAllowFlight(true);
        viewer.setFlying(true);
        hotbar.give(viewer);

        Location start = firstActorLocation(session);
        viewer.teleport(start != null ? start.clone().add(0, 5, 0)
                : session.world().getSpawnLocation());
        viewer.sendMessage(Component.text("Playback geöffnet: ").append(session.statusLine()));
    }

    private Location firstActorLocation(PlaybackSession session) {
        for (Integer index : session.profiles().keySet()) {
            Location location = session.actorLocation(index);
            if (location != null) {
                return location;
            }
        }
        return null;
    }

    /** Removes the viewer; closes the session when its last viewer left. */
    public void detachViewer(Player viewer) {
        PlaybackSession session = sessionsByViewer.remove(viewer.getUniqueId());
        if (session == null) {
            return;
        }
        session.removeViewer(viewer.getUniqueId());

        org.bukkit.inventory.ItemStack[] saved = savedInventories.remove(viewer.getUniqueId());
        if (saved != null) {
            viewer.getInventory().setContents(saved);
        }
        GameMode previous = savedGameModes.remove(viewer.getUniqueId());
        if (previous != null) {
            viewer.setGameMode(previous);
        }
        World mainWorld = Bukkit.getWorlds().get(0);
        viewer.teleport(mainWorld.getSpawnLocation());

        if (session.viewers().isEmpty()) {
            session.close();
            sessionsById.remove(session.sessionId());
        }
    }

    public Optional<PlaybackSession> sessionOf(Player viewer) {
        return Optional.ofNullable(sessionsByViewer.get(viewer.getUniqueId()));
    }

    public Optional<PlaybackSession> byId(UUID sessionId) {
        return Optional.ofNullable(sessionsById.get(sessionId));
    }

    public int activeSessionCount() {
        return sessionsById.size();
    }
}
