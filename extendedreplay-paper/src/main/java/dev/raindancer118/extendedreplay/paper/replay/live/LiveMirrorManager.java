package dev.raindancer118.extendedreplay.paper.replay.live;

import dev.raindancer118.extendedreplay.core.model.PlayerFrame;
import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;
import dev.raindancer118.extendedreplay.paper.config.ReplayConfig;
import dev.raindancer118.extendedreplay.paper.gui.HotbarUI;
import dev.raindancer118.extendedreplay.paper.replay.PlaybackWorlds;
import dev.raindancer118.extendedreplay.paper.replay.ReplayServerManager;
import dev.raindancer118.extendedreplay.paper.replay.WorldStateApplier;
import dev.raindancer118.extendedreplay.paper.replay.render.ArmorStandRenderer;
import dev.raindancer118.extendedreplay.paper.replay.render.EntityActorRenderer;
import dev.raindancer118.extendedreplay.paper.replay.render.MannequinRenderer;
import dev.raindancer118.extendedreplay.paper.replay.render.ReplayActorRenderer;
import dev.raindancer118.extendedreplay.paper.snapshot.SnapshotService;
import dev.raindancer118.extendedreplay.storage.meta.SessionRecord;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * True streaming live mirror: packets of the currently running producer session are
 * buffered as they arrive (storage thread) and applied into a mirror world on the main
 * thread once they are older than the configured live delay.
 *
 * <p>One mirror at a time. Moderators join read-only (adventure + flight); the world is
 * restored and actors are removed when the last viewer leaves or the session ends.</p>
 */
public final class LiveMirrorManager {

    private record Buffered(long receivedAtMillis, ReplayPacket packet) {
    }

    private final Plugin plugin;
    private final ReplayConfig config;
    private final ReplayServerManager replayServer;
    private final SnapshotService snapshots;
    private final HotbarUI hotbar;
    private final UUID listenerKey = UUID.randomUUID();

    private final ConcurrentLinkedQueue<Buffered> buffer = new ConcurrentLinkedQueue<>();
    private final Map<Integer, PlayerProfileData> profiles = new HashMap<>();
    private final Map<UUID, GameMode> savedGameModes = new HashMap<>();
    private final Map<UUID, org.bukkit.inventory.ItemStack[]> savedInventories = new HashMap<>();
    private final java.util.Set<UUID> viewers = new java.util.HashSet<>();

    private UUID mirroredSession;
    private World mirrorWorld;
    private ReplayActorRenderer renderer;
    private EntityActorRenderer entityRenderer;
    private WorldStateApplier blockApplier;
    private BukkitTask tickTask;
    private volatile boolean sessionEnded;
    private final boolean replayLobbyMode;

    public LiveMirrorManager(Plugin plugin, ReplayConfig config,
                             ReplayServerManager replayServer, SnapshotService snapshots,
                             HotbarUI hotbar, boolean replayLobbyMode) {
        this.plugin = plugin;
        this.config = config;
        this.replayServer = replayServer;
        this.snapshots = snapshots;
        this.hotbar = hotbar;
        this.replayLobbyMode = replayLobbyMode;
    }

    public boolean isActive() {
        return mirroredSession != null;
    }

    public Optional<UUID> mirroredSession() {
        return Optional.ofNullable(mirroredSession);
    }

    public boolean isViewer(Player player) {
        return viewers.contains(player.getUniqueId());
    }

    /** Attaches a moderator; starts the mirror if none is running. Main thread. */
    public void join(Player moderator, UUID liveSessionId) {
        if (mirroredSession != null && !mirroredSession.equals(liveSessionId)) {
            moderator.sendMessage(Component.text(
                    "Es läuft bereits ein Live-Mirror einer anderen Session."));
            return;
        }
        if (mirroredSession == null) {
            start(liveSessionId);
        }
        savedGameModes.put(moderator.getUniqueId(), moderator.getGameMode());
        savedInventories.put(moderator.getUniqueId(), moderator.getInventory().getContents());
        viewers.add(moderator.getUniqueId());
        moderator.setGameMode(GameMode.ADVENTURE);
        moderator.setAllowFlight(true);
        moderator.setFlying(true);
        hotbar.give(moderator);
        moderator.teleport(anchorLocation());
        moderator.sendMessage(Component.text("Live-Mirror aktiv (Verzögerung "
                + config.liveDelaySeconds() + "s). Verlassen: /erp close"));
    }

    private void start(UUID liveSessionId) {
        mirroredSession = liveSessionId;
        sessionEnded = false;

        // The live-mirror world name is reused across sessions, so a stale world generated
        // for a different (or no) seed must be recreated rather than just reused.
        Long worldSeed = null;
        String worldEnvironment = null;
        try {
            SessionRecord sessionRecord = replayServer.storage().getSession(liveSessionId).orElse(null);
            if (sessionRecord != null) {
                worldSeed = sessionRecord.worldSeed();
                worldEnvironment = sessionRecord.worldEnvironment();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "Could not read session metadata for live mirror seed: " + e.getMessage());
        }
        mirrorWorld = PlaybackWorlds.recreateIfSeedDiffers(
                config.playbackWorldPrefix() + "_live", worldSeed, worldEnvironment);
        renderer = createRenderer();
        entityRenderer = new EntityActorRenderer();
        blockApplier = new WorldStateApplier(mirrorWorld);
        buffer.clear();
        profiles.clear();

        // arena base: apply a local snapshot if the session references one we have
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                replayServer.storage().getSession(liveSessionId).ifPresent(record -> {
                    if (record.snapshotName() != null && snapshots.exists(record.snapshotName())) {
                        Bukkit.getScheduler().runTask(plugin, () ->
                                snapshots.apply(record.snapshotName(), mirrorWorld, null));
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Live mirror snapshot lookup failed: " + e.getMessage());
            }
        });

        replayServer.addLiveListener(listenerKey, packet -> {
            // storage thread: only buffer
            buffer.add(new Buffered(System.currentTimeMillis(), packet));
        });
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::applyDue, 1L, 1L);
        plugin.getLogger().info("Live mirror started for session " + liveSessionId);
    }

    /** Main-thread tick: applies all packets older than the live delay. */
    private void applyDue() {
        long threshold = System.currentTimeMillis() - config.liveDelaySeconds() * 1000L;
        Buffered next;
        while ((next = buffer.peek()) != null && next.receivedAtMillis() <= threshold) {
            buffer.poll();
            apply(next.packet());
        }
        if (sessionEnded && buffer.isEmpty()) {
            for (UUID viewerId : java.util.Set.copyOf(viewers)) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) {
                    viewer.sendMessage(Component.text(
                            "Live-Session beendet — Mirror wird geschlossen. "
                                    + "Wiedergabe: /erp play " + mirroredSession));
                }
            }
            stop();
        }
    }

    private void apply(ReplayPacket packet) {
        if (mirroredSession == null) {
            return;
        }
        switch (packet) {
            case ReplayPacket.PlayerProfile p when p.sessionId().equals(mirroredSession) ->
                    profiles.put(p.profile().playerIndex(), p.profile());
            case ReplayPacket.PlayerFramePacket p when p.sessionId().equals(mirroredSession) -> {
                PlayerFrame frame = p.frame();
                if (!renderer.isSpawned(frame.playerIndex())) {
                    PlayerProfileData profile = profiles.getOrDefault(frame.playerIndex(),
                            new PlayerProfileData(frame.playerIndex(), UUID.randomUUID(),
                                    "Player-" + frame.playerIndex(), null, null));
                    renderer.spawnActor(mirrorWorld, frame.playerIndex(), profile,
                            new Location(mirrorWorld, frame.x(), frame.y(), frame.z()));
                }
                renderer.applyFrame(frame.playerIndex(), mirrorWorld, frame);
            }
            case ReplayPacket.EquipmentChangePacket p when p.sessionId().equals(mirroredSession) ->
                    renderer.applyEquipment(p.change().playerIndex(), p.change());
            case ReplayPacket.BlockChangePacket p when p.sessionId().equals(mirroredSession) ->
                    blockApplier.apply(p.change());
            case ReplayPacket.PlayerQuit p when p.sessionId().equals(mirroredSession) ->
                    renderer.despawnActor(p.playerIndex());
            case ReplayPacket.EntitySpawn p when p.sessionId().equals(mirroredSession) ->
                    entityRenderer.spawn(mirrorWorld, p.entityId(), p.entityType(),
                            new Location(mirrorWorld, p.x(), p.y(), p.z(), p.yaw(), p.pitch()),
                            p.metadata());
            case ReplayPacket.EntityFramePacket p when p.sessionId().equals(mirroredSession) ->
                    entityRenderer.applyFrame(p.frame());
            case ReplayPacket.EntityDespawn p when p.sessionId().equals(mirroredSession) ->
                    entityRenderer.despawn(p.entityId());
            case ReplayPacket.SessionEnd p when p.sessionId().equals(mirroredSession) ->
                    sessionEnded = true;
            default -> {
                // events land in the DB and are visible through the event browser
            }
        }
    }

    private Location anchorLocation() {
        if (renderer != null) {
            for (Integer index : profiles.keySet()) {
                Location location = renderer.actorLocation(index);
                if (location != null) {
                    return location.clone().add(0, 5, 0);
                }
            }
        }
        return mirrorWorld.getSpawnLocation();
    }

    /** Detaches one viewer; stops the mirror when nobody watches anymore. Main thread. */
    public void leave(Player moderator) {
        if (!viewers.remove(moderator.getUniqueId())) {
            return;
        }
        restoreViewer(moderator);
        if (viewers.isEmpty()) {
            stop();
        }
    }

    private void restoreViewer(Player viewer) {
        org.bukkit.inventory.ItemStack[] saved = savedInventories.remove(viewer.getUniqueId());
        GameMode previous = savedGameModes.remove(viewer.getUniqueId());
        if (replayLobbyMode) {
            // dedicated replay server: back to the lobby hotbar, not whatever was saved
            viewer.setGameMode(GameMode.ADVENTURE);
            viewer.setAllowFlight(true);
            viewer.setFlying(true);
            hotbar.giveLobby(viewer);
        } else {
            if (saved != null) {
                viewer.getInventory().setContents(saved);
            }
            if (previous != null) {
                viewer.setGameMode(previous);
            }
        }
        viewer.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
    }

    /** Stops the mirror, restores the world, releases the listener. Main thread. */
    public void stop() {
        replayServer.removeLiveListener(listenerKey);
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (UUID viewerId : java.util.Set.copyOf(viewers)) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                restoreViewer(viewer);
            }
        }
        viewers.clear();
        if (renderer != null) {
            renderer.despawnAll();
        }
        if (entityRenderer != null) {
            entityRenderer.despawnAll();
            entityRenderer = null;
        }
        if (blockApplier != null) {
            blockApplier.restoreAll();
        }
        buffer.clear();
        profiles.clear();
        mirroredSession = null;
        sessionEnded = false;
    }

    private ReplayActorRenderer createRenderer() {
        if ("MANNEQUIN".equalsIgnoreCase(config.preferredRenderer())
                && MannequinRenderer.isSupported()) {
            MannequinRenderer r = new MannequinRenderer();
            r.setNameTagVisible(config.showNametags());
            return r;
        }
        ArmorStandRenderer r = new ArmorStandRenderer();
        r.setNameTagVisible(config.showNametags());
        return r;
    }
}
