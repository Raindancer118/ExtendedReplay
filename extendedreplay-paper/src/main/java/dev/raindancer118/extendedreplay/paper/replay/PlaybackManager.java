package dev.raindancer118.extendedreplay.paper.replay;

import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;
import dev.raindancer118.extendedreplay.paper.config.ReplayConfig;
import dev.raindancer118.extendedreplay.paper.replay.render.ArmorStandRenderer;
import dev.raindancer118.extendedreplay.paper.replay.render.MannequinRenderer;
import dev.raindancer118.extendedreplay.paper.replay.render.ReplayActorRenderer;
import dev.raindancer118.extendedreplay.storage.meta.EventRecord;
import dev.raindancer118.extendedreplay.storage.meta.SessionRecord;
import net.kyori.adventure.bossbar.BossBar;
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
    private final ReplayHud hud = new ReplayHud();
    private BukkitTask tickTask;
    private int worldCounter;
    private int hudTickCounter;
    private final boolean replayLobbyMode;

    /** How often (in ticks) the HUD is refreshed — cheap enough per-viewer but avoids
     * pushing a boss bar packet every single tick. */
    private static final int HUD_UPDATE_INTERVAL_TICKS = 10;

    public PlaybackManager(Plugin plugin, ReplayConfig config, ReplayServerManager replayServer,
                           dev.raindancer118.extendedreplay.paper.gui.HotbarUI hotbar,
                           dev.raindancer118.extendedreplay.paper.snapshot.SnapshotService snapshots,
                           boolean replayLobbyMode) {
        this.plugin = plugin;
        this.config = config;
        this.replayServer = replayServer;
        this.hotbar = hotbar;
        this.snapshots = snapshots;
        this.replayLobbyMode = replayLobbyMode;
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
        hudTickCounter++;
        boolean refreshHud = hudTickCounter % HUD_UPDATE_INTERVAL_TICKS == 0;
        for (PlaybackSession session : sessionsById.values()) {
            session.advance();
            followTick(session);
            if (refreshHud) {
                hudTick(session);
            }
        }
    }

    private void hudTick(PlaybackSession session) {
        for (UUID viewerId : session.viewers()) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                hud.update(viewer, session);
            }
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

    /** Ticks a boss bar stays visible at 100% before being removed. */
    private static final long PROGRESS_BAR_LINGER_TICKS = 40L; // 2s

    /**
     * Opens a playback session: packets load on an async thread, world creation, actor
     * spawn and viewer teleport happen back on the main thread. A boss bar shows the
     * viewer visible loading progress throughout (session load 0-60%, world creation 65%,
     * snapshot apply 75%, state rebuild 85%, then 100% "Ready" before it is removed).
     */
    public CompletableFuture<PlaybackSession> open(UUID sessionId, Player viewer) {
        PlaybackSession existing = sessionsById.get(sessionId);
        if (existing != null) {
            attachViewer(existing, viewer);
            return CompletableFuture.completedFuture(existing);
        }
        CompletableFuture<PlaybackSession> future = new CompletableFuture<>();
        UUID viewerId = viewer.getUniqueId();
        // the console/command-block cannot see boss bars — but this method only ever
        // receives a real logged-in Player (GUI clicks / player commands), never console
        BossBar progressBar = BossBar.bossBar(Component.text("Lade Session… 0%"), 0f,
                BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        viewer.showBossBar(progressBar);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<ReplayPacket> packets;
            String name;
            Long worldSeed;
            String worldEnvironment;
            String snapshotName;
            try {
                packets = replayServer.storage().loadSession(sessionId, percent ->
                        updateProgressBar(progressBar, percent * 60 / 100, "Lade Session… " + percent + "%"));
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
                    updateProgressBar(progressBar, 65, "Erstelle Welt…");
                    String worldName = config.playbackWorldPrefix() + "_" + (worldCounter++);
                    World world = finalWorldSeed != null
                            ? PlaybackWorlds.getOrCreate(worldName, finalWorldSeed, finalWorldEnvironment)
                            : PlaybackWorlds.getOrCreate(worldName);
                    if (world == null) {
                        throw new IllegalStateException(
                                "Playback-Welt konnte nicht erstellt werden: " + worldName);
                    }
                    PlaybackSession session = new PlaybackSession(sessionId, finalName, world,
                            createRenderer(), packets,
                            config.keyframeIntervalSeconds() * 20);
                    sessionsById.put(sessionId, session);

                    Runnable finish = () -> {
                        updateProgressBar(progressBar, 85, "Baue Zustand auf…");
                        session.seek(0);
                        attachViewer(session, viewer);
                        updateProgressBar(progressBar, 100, "✔ Bereit", BossBar.Color.GREEN);
                        scheduleProgressBarRemoval(viewerId, progressBar);
                        future.complete(session);
                    };
                    // arena base state first, if the session references a local snapshot
                    if (finalSnapshotName != null && snapshots.exists(finalSnapshotName)) {
                        updateProgressBar(progressBar, 75, "Wende Snapshot an…");
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
        // the message consumers show error.getMessage() to the viewer — the full trace
        // belongs in the server log, otherwise NPEs surface as an unhelpful "null"
        future.whenComplete((session, error) -> {
            if (error != null) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Opening playback session " + sessionId + " failed", error);
                removeProgressBarNow(viewerId, progressBar);
            }
        });
        return future;
    }

    /**
     * Updates a viewer's loading boss bar. Safe to call from any thread — the actual
     * mutation always runs on the main thread via the scheduler, since Adventure boss bars
     * push their state to viewers as soon as it changes.
     */
    private void updateProgressBar(BossBar bar, int percent, String text) {
        updateProgressBar(bar, percent, text, null);
    }

    private void updateProgressBar(BossBar bar, int percent, String text, BossBar.Color color) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            bar.name(Component.text(text));
            bar.progress(Math.max(0f, Math.min(1f, percent / 100f)));
            if (color != null) {
                bar.color(color);
            }
        });
    }

    /** Removes the boss bar after a short linger at 100%, so "Ready" is actually seen. */
    private void scheduleProgressBarRemoval(UUID viewerId, BossBar bar) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(viewerId);
            if (player != null) {
                player.hideBossBar(bar);
            }
        }, PROGRESS_BAR_LINGER_TICKS);
    }

    /** Removes the boss bar immediately (error path) — safe from any thread. */
    private void removeProgressBarNow(UUID viewerId, BossBar bar) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(viewerId);
            if (player != null) {
                player.hideBossBar(bar);
            }
        });
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
        // async: the target chunks of a freshly created seeded world may not exist yet
        viewer.teleportAsync(start != null ? start.clone().add(0, 5, 0)
                : session.world().getSpawnLocation());
        viewer.sendMessage(Component.text("Playback geöffnet: ").append(session.statusLine()));
        hud.show(viewer, session);
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
        hud.hide(viewer);
        session.removeViewer(viewer.getUniqueId());

        org.bukkit.inventory.ItemStack[] saved = savedInventories.remove(viewer.getUniqueId());
        GameMode previous = savedGameModes.remove(viewer.getUniqueId());
        if (replayLobbyMode) {
            // dedicated replay server: viewers always return to the lobby hotbar instead of
            // whatever was saved — stays correct even across restarts that lose these maps
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

    /**
     * Jumps a viewer to the previous ({@code direction < 0}) or next ({@code direction > 0})
     * indexed event relative to {@code session.currentTick()}. Events are loaded async from
     * storage (all categories, "SESSION" start/end markers included as valid jump targets);
     * the seek itself and the viewer message happen back on the main thread.
     */
    public void jumpToAdjacentEvent(Player viewer, PlaybackSession session, int direction) {
        UUID sessionId = session.sessionId();
        int currentTick = session.currentTick();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<EventRecord> events;
            try {
                events = replayServer.storage().listEvents(sessionId, null, 10_000);
            } catch (SQLException e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Loading events for jump failed (session " + sessionId + ")", e);
                Bukkit.getScheduler().runTask(plugin, () -> viewer.sendMessage(
                        Component.text("Events konnten nicht geladen werden.")));
                return;
            }
            EventRecord target = direction < 0
                    ? nearestEventBefore(events, currentTick - 5)
                    : nearestEventAfter(events, currentTick + 5);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (target == null) {
                    viewer.sendMessage(Component.text(direction < 0
                            ? "Kein früheres Event." : "Kein späteres Event."));
                    return;
                }
                session.seek(target.tick());
                String arrow = direction < 0 ? "⏮" : "⏭";
                viewer.sendMessage(Component.text(arrow + " " + target.eventType() + " @ "
                        + PlaybackSession.formatTicks(target.tick())));
            });
        });
    }

    /** Largest event with a tick strictly below {@code beforeTick}, or null. */
    private EventRecord nearestEventBefore(List<EventRecord> events, int beforeTick) {
        EventRecord best = null;
        for (EventRecord event : events) {
            if (event.tick() < beforeTick && (best == null || event.tick() > best.tick())) {
                best = event;
            }
        }
        return best;
    }

    /** Smallest event with a tick strictly above {@code afterTick}, or null. */
    private EventRecord nearestEventAfter(List<EventRecord> events, int afterTick) {
        EventRecord best = null;
        for (EventRecord event : events) {
            if (event.tick() > afterTick && (best == null || event.tick() < best.tick())) {
                best = event;
            }
        }
        return best;
    }
}
