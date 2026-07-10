package dev.raindancer118.extendedreplay.paper.gui;

import dev.raindancer118.extendedreplay.api.ReplaySessionEndReason;
import dev.raindancer118.extendedreplay.paper.job.Job;
import dev.raindancer118.extendedreplay.paper.job.JobManager;
import dev.raindancer118.extendedreplay.paper.job.VerifyJob;
import dev.raindancer118.extendedreplay.paper.producer.ActiveSession;
import dev.raindancer118.extendedreplay.paper.producer.ProducerManager;
import dev.raindancer118.extendedreplay.paper.producer.RecordingStarter;
import dev.raindancer118.extendedreplay.paper.replay.PlaybackManager;
import dev.raindancer118.extendedreplay.paper.replay.PlaybackSession;
import dev.raindancer118.extendedreplay.storage.ReplayStorage;
import dev.raindancer118.extendedreplay.storage.meta.EventRecord;
import dev.raindancer118.extendedreplay.storage.meta.SessionRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Dispatches clicks in replay/record GUIs and hotbar item usage. Constructed once per
 * server; which dependencies are non-null depends on the configured
 * {@link dev.raindancer118.extendedreplay.paper.config.ServerRole} — a pure REPLAY server
 * has no {@code producer}/{@code recordingStarter}, a pure PRODUCER server has no
 * {@code playback}/{@code storage}/{@code hotbar}/{@code routes}; STANDALONE has all of
 * them. Every handler that touches a role-specific dependency guards it with a null check.
 */
public final class GuiListener implements Listener {

    private final dev.raindancer118.extendedreplay.paper.ExtendedReplayPlugin plugin;
    private final PlaybackManager playback;
    private final ReplayStorage storage;
    private final HotbarUI hotbar;
    private final dev.raindancer118.extendedreplay.paper.replay.route.RouteManager routes;
    private final ProducerManager producer;
    private final RecordingStarter recordingStarter;
    private final JobManager jobs;

    /**
     * @param playback         null on a pure PRODUCER server (no local playback)
     * @param storage          null on a pure PRODUCER server
     * @param hotbar           null on a pure PRODUCER server (only playback/lobby use it)
     * @param routes           null on a pure PRODUCER server
     * @param producer         null on a pure REPLAY server (no recording here)
     * @param recordingStarter null on a pure REPLAY server
     * @param jobs             never null — instantiated for every non-DISABLED role
     */
    public GuiListener(dev.raindancer118.extendedreplay.paper.ExtendedReplayPlugin plugin,
                       PlaybackManager playback, ReplayStorage storage, HotbarUI hotbar,
                       dev.raindancer118.extendedreplay.paper.replay.route.RouteManager routes,
                       ProducerManager producer, RecordingStarter recordingStarter, JobManager jobs) {
        this.plugin = plugin;
        this.playback = playback;
        this.storage = storage;
        this.hotbar = hotbar;
        this.routes = routes;
        this.producer = producer;
        this.recordingStarter = recordingStarter;
        this.jobs = jobs;
    }

    // --- chest GUIs ---

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // hotbar items (playback and lobby) may never be moved, shift-clicked away or
        // swapped into another slot/inventory — checked before any GUI-specific dispatch.
        // hotbar is null on a pure PRODUCER server, which never gives one out.
        if (hotbar != null && event.getWhoClicked() instanceof Player
                && (hotbar.isTagged(event.getCurrentItem()) || hotbar.isTagged(event.getCursor()))) {
            event.setCancelled(true);
            return;
        }
        if (event.getInventory().getHolder() instanceof InventoryInspectGui) {
            event.setCancelled(true);
            return;
        }
        if (event.getInventory().getHolder() instanceof PlaybackControlGui gui) {
            event.setCancelled(true);
            onPlaybackControlClick(event, gui);
            return;
        }
        if (event.getInventory().getHolder() instanceof PlayerSelectGui gui) {
            event.setCancelled(true);
            onPlayerSelectClick(event, gui);
            return;
        }
        if (event.getInventory().getHolder() instanceof SessionBrowserGui gui) {
            event.setCancelled(true);
            onSessionBrowserClick(event, gui);
            return;
        }
        if (event.getInventory().getHolder() instanceof ContainerSelectGui gui) {
            event.setCancelled(true);
            onContainerSelectClick(event, gui);
            return;
        }
        if (event.getInventory().getHolder() instanceof SpeedSelectGui gui) {
            event.setCancelled(true);
            onSpeedSelectClick(event, gui);
            return;
        }
        if (event.getInventory().getHolder() instanceof ConfirmGui gui) {
            event.setCancelled(true);
            onConfirmClick(event, gui);
            return;
        }
        if (event.getInventory().getHolder() instanceof RecordControlGui gui) {
            event.setCancelled(true);
            onRecordControlClick(event, gui);
            return;
        }
        if (event.getInventory().getHolder() instanceof JobsGui gui) {
            event.setCancelled(true);
            onJobsClick(event, gui);
            return;
        }
        if (event.getInventory().getHolder() instanceof ControlCenterGui gui) {
            event.setCancelled(true);
            onControlCenterClick(event, gui);
            return;
        }
        if (event.getInventory().getHolder() instanceof SessionDetailsGui gui) {
            event.setCancelled(true);
            onSessionDetailsClick(event, gui);
            return;
        }
        if (!(event.getInventory().getHolder() instanceof EventBrowserGui gui)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == 45 && gui.page() > 0) {
            EventBrowserGui.open(player, gui.events(), gui.playerNames(), gui.page() - 1);
            return;
        }
        if (slot == 53) {
            EventBrowserGui.open(player, gui.events(), gui.playerNames(), gui.page() + 1);
            return;
        }
        if (slot == 49) {
            player.closeInventory();
            return;
        }
        EventRecord record = gui.eventAt(slot);
        if (record == null) {
            return;
        }
        PlaybackSession session = playback.sessionOf(player).orElse(null);
        if (session == null) {
            player.sendMessage(Component.text("Keine aktive Playback-Session."));
            return;
        }
        if (event.isShiftClick()) {
            session.seek(Math.max(0, record.tick() - 200)); // 10s before
            player.closeInventory();
            player.sendMessage(Component.text("Gesprungen zu 10s vor "
                    + record.eventType() + "."));
        } else if (event.isRightClick() && record.hasLocation()) {
            player.closeInventory();
            player.teleport(new Location(session.world(), record.x(), record.y() + 2,
                    record.z()));
            player.sendMessage(Component.text("Zum Event-Ort teleportiert."));
        } else {
            session.seek(record.tick());
            player.closeInventory();
            player.sendMessage(Component.text("Gesprungen zu " + record.eventType() + " @ "
                    + PlaybackSession.formatTicks(record.tick())));
        }
    }

    /** Blocks dragging a hotbar item across slots (e.g. splitting a stack out of it). */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (hotbar != null && hotbar.isTagged(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    /** Blocks dropping a hotbar item (Q key) out of the inventory entirely. */
    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (hotbar != null && hotbar.isTagged(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /** Blocks swapping a hotbar item into the off-hand (F key). */
    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (hotbar != null
                && (hotbar.isTagged(event.getMainHandItem()) || hotbar.isTagged(event.getOffHandItem()))) {
            event.setCancelled(true);
        }
    }

    // --- hotbar ---

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        // no hotbar (e.g. a pure PRODUCER server never gives one out) — nothing to dispatch
        if (hotbar == null) {
            return;
        }
        HotbarUI.LobbyAction lobbyAction = hotbar.lobbyActionOf(event.getItem());
        if (lobbyAction != null) {
            event.setCancelled(true);
            dispatchLobbyAction(event.getPlayer(), lobbyAction);
            return;
        }
        HotbarUI.Action action = hotbar.actionOf(event.getItem());
        if (action == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        PlaybackSession session = playback.sessionOf(player).orElse(null);
        if (session == null) {
            // live mirror viewers get a reduced action set
            var live = plugin.liveMirror();
            if (live != null && live.isViewer(player)) {
                switch (action) {
                    case CAMERA -> toggleFreecam(player);
                    case MENU -> {
                        if (player.isSneaking()) {
                            live.leave(player);
                        } else {
                            player.sendMessage(Component.text(
                                    "Im Live-Mirror verfügbar: Freecam (Auge), Menü mit Shift-Klick verlassen."));
                        }
                    }
                    default -> player.sendMessage(Component.text(
                            "Im Live-Mirror verfügbar: Freecam (Auge), Menü mit Shift-Klick verlassen."));
                }
            }
            return;
        }
        boolean sneaking = player.isSneaking();
        switch (action) {
            case PREV_EVENT -> playback.jumpToAdjacentEvent(player, session, -1);
            case NEXT_EVENT -> playback.jumpToAdjacentEvent(player, session, 1);
            case REWIND -> {
                int delta = sneaking ? 1200 : 200; // 60s / 10s at 20 ticks/s
                session.seek(Math.max(0, session.currentTick() - delta));
                hotbar.refresh(player, session.isPaused(), session.speed());
            }
            case FAST_FORWARD -> {
                int delta = sneaking ? 1200 : 200;
                session.seek(session.currentTick() + delta);
                hotbar.refresh(player, session.isPaused(), session.speed());
            }
            case PLAY_PAUSE -> {
                if (session.isPaused()) {
                    session.play();
                    player.sendMessage(Component.text("▶ Wiedergabe"));
                } else {
                    session.pause();
                    player.sendMessage(Component.text("⏸ Pausiert"));
                }
                hotbar.refresh(player, session.isPaused(), session.speed());
            }
            case SPEED -> SpeedSelectGui.open(player, plugin, session);
            case PLAYERS -> PlayerSelectGui.open(player, session.profiles(), session.followedPlayer());
            case CAMERA -> toggleFreecam(player);
            case MENU -> {
                if (sneaking) {
                    playback.detachViewer(player);
                } else {
                    openPlaybackControl(player, session);
                }
            }
        }
    }

    public void openEventBrowser(Player player, PlaybackSession session) {
        try {
            var events = storage.listEvents(session.sessionId(), null, 500);
            Map<UUID, String> names = new HashMap<>();
            storage.listPlayers(session.sessionId()).forEach(profile ->
                    names.put(profile.uuid(), profile.name()));
            EventBrowserGui.open(player, events, names, 0);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Event browser failed", e);
            player.sendMessage(Component.text("Events konnten nicht geladen werden."));
        }
    }

    // --- record control GUI (producer side) ---

    /** Opens the recording control panel (auto-snapshot radius, start/stop). */
    public void openRecordControl(Player player) {
        if (producer == null || recordingStarter == null) {
            player.sendMessage(Component.text("Aufnahme ist auf diesem Server nicht verfügbar."));
            return;
        }
        RecordControlGui.open(player, plugin, producer);
    }

    private void onRecordControlClick(InventoryClickEvent event, RecordControlGui gui) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (producer == null || recordingStarter == null) {
            player.closeInventory();
            return;
        }
        RecordControlGui.Action action = gui.actionOf(event.getCurrentItem());
        if (action == null) {
            return;
        }
        boolean shift = event.isShiftClick();
        switch (action) {
            case RADIUS_DOWN -> {
                RecordControlGui.adjustRadius(player.getUniqueId(),
                        producer.config().autoSnapshotRadius(), shift ? -50 : -10);
                RecordControlGui.open(player, plugin, producer);
            }
            case RADIUS_UP -> {
                RecordControlGui.adjustRadius(player.getUniqueId(),
                        producer.config().autoSnapshotRadius(), shift ? 50 : 10);
                RecordControlGui.open(player, plugin, producer);
            }
            case START -> {
                player.closeInventory();
                int radius = RecordControlGui.radiusOf(player.getUniqueId(),
                        producer.config().autoSnapshotRadius());
                String sessionName = "session-" + System.currentTimeMillis() / 1000;
                recordingStarter.start(player, sessionName, player.getWorld(), player.getLocation(),
                        radius, null);
            }
            case STOP -> {
                player.closeInventory();
                List<ActiveSession> active = producer.activeSessions();
                for (ActiveSession session : active) {
                    producer.endSession(session.sessionId(), ReplaySessionEndReason.STOPPED, player);
                }
                player.sendMessage(Component.text(active.isEmpty()
                        ? "Keine aktive Aufnahme."
                        : "Aufnahme gestoppt (" + active.size() + " Session(s)) — Übertragung läuft…"));
            }
            case CLOSE -> player.closeInventory();
        }
    }

    /** Opens the fancy playback control panel (play/pause, skip, speed, follow, …). */
    public void openPlaybackControl(Player player, PlaybackSession session) {
        PlaybackControlGui.open(player, plugin, session);
    }

    // --- lobby hotbar (REPLAY server) ---

    private void dispatchLobbyAction(Player player, HotbarUI.LobbyAction action) {
        switch (action) {
            case BROWSE_SESSIONS -> openSessionBrowser(player);
            case LIVE_MIRROR -> joinLiveMirror(player);
            case PLAY_LAST -> playLastSession(player);
            case HELP -> sendLobbyHelp(player);
            case CONTROL_CENTER -> openControlCenter(player);
        }
    }

    private void joinLiveMirror(Player player) {
        var live = plugin.liveMirror();
        if (live == null) {
            player.sendMessage(Component.text("Live-Mirror ist auf diesem Server nicht verfügbar."));
            return;
        }
        UUID liveId = plugin.replayServer() != null
                ? plugin.replayServer().anyLiveSession().orElse(null) : null;
        if (liveId == null) {
            player.sendMessage(Component.text("Keine laufende Live-Session."));
            return;
        }
        live.join(player, liveId);
    }

    /** Loads the most recently finished session, mirroring /erp play's error handling. */
    private void playLastSession(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            SessionRecord latest;
            try {
                latest = storage.listSessions(50).stream()
                        .filter(SessionRecord::isFinished)
                        .findFirst()
                        .orElse(null);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Loading latest session failed", e);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                        Component.text("Sessions konnten nicht geladen werden.")));
                return;
            }
            if (latest == null) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                        Component.text("Keine beendete Session vorhanden.")));
                return;
            }
            UUID sessionId = latest.sessionId();
            String name = latest.name();
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage(Component.text("Lade Session " + name + "…"));
                playback.open(sessionId, player).whenComplete((session, error) -> {
                    if (error != null) {
                        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                                Component.text("Konnte Session nicht öffnen: " + dev.raindancer118.extendedreplay.paper.util.Errors.describe(error))));
                    }
                });
            });
        });
    }

    private void sendLobbyHelp(Player player) {
        player.sendMessage(Component.text("— ExtendedReplay Lobby —"));
        player.sendMessage(Component.text("📼 Session-Browser · 📡 Live-Mirror beitreten"));
        player.sendMessage(Component.text("⏱ Letzte Session abspielen · /erp help für alle Befehle"));
    }

    // --- session browser GUI ---

    /**
     * Loads every stored session (async DB read) and opens the browser on the main
     * thread. Used both for the initial open and the in-GUI refresh button.
     */
    public void openSessionBrowser(Player player) {
        openSessionBrowser(player, SessionBrowserGui.Filter.ALL);
    }

    /** Same as {@link #openSessionBrowser(Player)}, opened with a specific starting filter. */
    public void openSessionBrowser(Player player, SessionBrowserGui.Filter filter) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<SessionRecord> sessions;
            try {
                sessions = storage.listSessions(500);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Session browser failed", e);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                        Component.text("Sessions konnten nicht geladen werden.")));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> SessionBrowserGui.open(player, sessions, 0, filter));
        });
    }

    private void onSessionBrowserClick(InventoryClickEvent event, SessionBrowserGui gui) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == 45 && gui.page() > 0) {
            SessionBrowserGui.open(player, gui.sessions(), gui.page() - 1, gui.filter());
            return;
        }
        if (slot == 46) {
            SessionBrowserGui.open(player, gui.sessions(), 0, gui.filter().next());
            return;
        }
        if (slot == 47) {
            openSessionBrowser(player, gui.filter());
            return;
        }
        if (slot == 49) {
            player.closeInventory();
            return;
        }
        if (slot == 53) {
            SessionBrowserGui.open(player, gui.sessions(), gui.page() + 1, gui.filter());
            return;
        }
        SessionRecord record = gui.sessionAt(slot);
        if (record == null) {
            return;
        }
        if (!player.hasPermission("extendedreplay.playback")) {
            player.sendMessage(Component.text("Keine Berechtigung zum Laden von Sessions."));
            return;
        }
        player.closeInventory();
        SessionDetailsGui.open(player, plugin, storage, record.sessionId());
    }

    // --- session details GUI ---

    private void onSessionDetailsClick(InventoryClickEvent event, SessionDetailsGui gui) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        SessionDetailsGui.Action action = gui.actionOf(event.getCurrentItem());
        if (action == null) {
            return;
        }
        SessionRecord record = gui.record();
        switch (action) {
            case BACK -> openSessionBrowser(player);
            case PLAY -> {
                player.closeInventory();
                player.sendMessage(Component.text("Lade Session " + record.name() + "…"));
                playback.open(record.sessionId(), player).whenComplete((session, error) -> {
                    if (error != null) {
                        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(Component.text(
                                "Konnte Session nicht öffnen: "
                                        + dev.raindancer118.extendedreplay.paper.util.Errors.describe(error))));
                    }
                });
            }
            case LIVE -> {
                player.closeInventory();
                var live = plugin.liveMirror();
                if (live == null) {
                    player.sendMessage(Component.text("Live-Mirror ist auf diesem Server nicht verfügbar."));
                    return;
                }
                live.join(player, record.sessionId());
            }
            case VERIFY -> {
                UUID playerId = player.getUniqueId();
                Job job = VerifyJob.submit(jobs, storage, record.sessionId(), record.name(), line -> {
                    Player online = Bukkit.getPlayer(playerId);
                    if (online != null) {
                        Bukkit.getScheduler().runTask(plugin, () -> online.sendMessage(Component.text(line)));
                    }
                });
                player.sendMessage(Component.text("Job #" + job.id() + " gestartet: Verifizierung "
                        + record.name()));
            }
            case FAVORITE -> {
                UUID playerId = player.getUniqueId();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        storage.database().setFavorite(record.sessionId(), !record.favorite());
                    } catch (SQLException e) {
                        plugin.getLogger().log(Level.WARNING, "Favorite toggle failed", e);
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player online = Bukkit.getPlayer(playerId);
                        if (online != null) {
                            SessionDetailsGui.open(online, plugin, storage, record.sessionId());
                        }
                    });
                });
            }
            case REINDEX -> {
                if (!player.hasPermission("extendedreplay.storage")) {
                    return;
                }
                player.closeInventory();
                UUID playerId = player.getUniqueId();
                Job job = jobs.submit("Reindex " + record.name(), "Session " + record.sessionId(), j -> {
                    int packets = storage.reindex(record.sessionId());
                    Player online = Bukkit.getPlayer(playerId);
                    if (online != null) {
                        Bukkit.getScheduler().runTask(plugin, () -> online.sendMessage(Component.text(
                                "✔ Reindex fertig: " + packets + " Pakete indiziert.")));
                    }
                });
                player.sendMessage(Component.text("Job #" + job.id() + " gestartet: Reindex " + record.name()));
            }
            case DELETE -> {
                if (!player.hasPermission("extendedreplay.storage")) {
                    return;
                }
                UUID playerId = player.getUniqueId();
                UUID sessionId = record.sessionId();
                String name = record.name();
                ConfirmGui.open(player,
                        Component.text("Session '" + name + "' wirklich löschen?"),
                        List.of(
                                Component.text("Größe: " + SessionBrowserGui.formatBytes(record.sizeBytes()),
                                        NamedTextColor.GRAY),
                                Component.text("Dauer: " + PlaybackSession.formatTicks(record.lastTick()),
                                        NamedTextColor.GRAY),
                                Component.text("Dies kann nicht rückgängig gemacht werden.",
                                        NamedTextColor.RED)),
                        () -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            String result;
                            try {
                                storage.deleteSessionData(sessionId);
                                result = "Session '" + name + "' gelöscht.";
                            } catch (Exception e) {
                                plugin.getLogger().log(Level.WARNING, "Session delete failed", e);
                                result = "Löschen fehlgeschlagen: "
                                        + dev.raindancer118.extendedreplay.paper.util.Errors.describe(e);
                            }
                            String finalResult = result;
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                Player online = Bukkit.getPlayer(playerId);
                                if (online != null) {
                                    online.sendMessage(Component.text(finalResult));
                                    openSessionBrowser(online);
                                }
                            });
                        }),
                        () -> {
                            Player online = Bukkit.getPlayer(playerId);
                            if (online != null) {
                                SessionDetailsGui.open(online, plugin, storage, sessionId);
                            }
                        });
            }
        }
    }

    // --- jobs GUI ---

    public void openJobsGui(Player player) {
        JobsGui.open(player, jobs, 0);
    }

    private void onJobsClick(InventoryClickEvent event, JobsGui gui) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == 45 && gui.page() > 0) {
            JobsGui.open(player, jobs, gui.page() - 1);
            return;
        }
        if (slot == 47) {
            JobsGui.open(player, jobs, gui.page());
            return;
        }
        if (slot == 49) {
            player.closeInventory();
            return;
        }
        if (slot == 53) {
            JobsGui.open(player, jobs, gui.page() + 1);
            return;
        }
        Job job = gui.jobAt(slot);
        if (job == null) {
            return;
        }
        if (job.status() == Job.Status.RUNNING) {
            jobs.cancel(job.id());
            JobsGui.open(player, jobs, gui.page());
        }
    }

    // --- control center GUI ---

    /** Opens the main Replay Control Center, gated per-role like {@code /erp}'s buttons. */
    public void openControlCenter(Player player) {
        boolean canPlayback = playback != null;
        boolean canRecord = plugin.role().records() && producer != null;
        ControlCenterGui.open(player, plugin, canPlayback, canRecord);
    }

    private void onControlCenterClick(InventoryClickEvent event, ControlCenterGui gui) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ControlCenterGui.Action action = gui.actionOf(event.getCurrentItem());
        if (action == null) {
            return;
        }
        switch (action) {
            case SESSIONS -> openSessionBrowser(player, SessionBrowserGui.Filter.ALL);
            case FAVORITES -> openSessionBrowser(player, SessionBrowserGui.Filter.FAVORITES);
            case LIVE -> {
                player.closeInventory();
                joinLiveMirror(player);
            }
            case LAST -> {
                player.closeInventory();
                playLastSession(player);
            }
            case RECORD -> {
                player.closeInventory();
                openRecordControl(player);
            }
            case JOBS -> openJobsGui(player);
            case STORAGE -> storageInfoChat(player);
            case HELP -> {
                player.closeInventory();
                sendControlCenterHelp(player);
            }
        }
    }

    private void storageInfoChat(Player player) {
        player.closeInventory();
        if (storage == null) {
            player.sendMessage(Component.text("Speicher-Info ist auf diesem Server nicht verfügbar."));
            return;
        }
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                long bytes = storage.storageBytes();
                int count = storage.listSessions(1000).size();
                String message = "Speicher: " + (bytes / 1024 / 1024) + " MB · " + count + " Session(s)";
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(playerId);
                    if (online != null) {
                        online.sendMessage(Component.text(message));
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Storage info failed", e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(playerId);
                    if (online != null) {
                        online.sendMessage(Component.text("Speicher-Info konnte nicht geladen werden."));
                    }
                });
            }
        });
    }

    private void sendControlCenterHelp(Player player) {
        player.sendMessage(Component.text("— ExtendedReplay Control Center —"));
        player.sendMessage(Component.text("📼 Sessions · ★ Favoriten · 📡 Live · ⏱ Letzte Session"));
        player.sendMessage(Component.text("⏺ Aufnahme · ⚙ Jobs · 💾 Speicher · /erp help für alle Befehle"));
    }

    // --- playback control GUI ---

    private void onPlaybackControlClick(InventoryClickEvent event, PlaybackControlGui gui) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        PlaybackControlGui.Action action = gui.actionOf(event.getCurrentItem());
        if (action == null) {
            return;
        }
        PlaybackSession session = playback.sessionOf(player).orElse(null);
        if (session == null) {
            player.sendMessage(Component.text("Keine aktive Playback-Session."));
            player.closeInventory();
            return;
        }
        if (action.speedValue() != null) {
            session.setSpeed(action.speedValue());
            player.sendMessage(Component.text("Geschwindigkeit: " + session.speed() + "x"));
            openPlaybackControl(player, session);
            return;
        }
        switch (action) {
            case REWIND_MINUTE -> {
                session.seek(Math.max(0, session.currentTick() - 20 * 60));
                openPlaybackControl(player, session);
            }
            case REWIND_10S -> {
                session.seek(Math.max(0, session.currentTick() - 20 * 10));
                openPlaybackControl(player, session);
            }
            case FORWARD_10S -> {
                session.seek(session.currentTick() + 20 * 10);
                openPlaybackControl(player, session);
            }
            case FORWARD_MINUTE -> {
                session.seek(session.currentTick() + 20 * 60);
                openPlaybackControl(player, session);
            }
            case PLAY_PAUSE -> {
                if (session.isPaused()) {
                    session.play();
                } else {
                    session.pause();
                }
                openPlaybackControl(player, session);
            }
            case JUMP_EVENT -> openEventBrowser(player, session);
            case FOLLOW -> PlayerSelectGui.open(player, session.profiles(), session.followedPlayer());
            case FREECAM -> {
                player.closeInventory();
                toggleFreecam(player);
            }
            case CLOSE -> {
                player.closeInventory();
                playback.detachViewer(player);
                player.sendMessage(Component.text("Playback geschlossen."));
            }
            default -> { }
        }
    }

    // --- follow / player select GUI ---

    private void onPlayerSelectClick(InventoryClickEvent event, PlayerSelectGui gui) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        PlaybackSession session = playback.sessionOf(player).orElse(null);
        if (session == null) {
            player.sendMessage(Component.text("Keine aktive Playback-Session."));
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        if (gui.isStopSlot(slot)) {
            session.follow(null);
            session.pov(null);
            player.sendMessage(Component.text("Folgen/POV beendet."));
            openPlaybackControl(player, session);
            return;
        }
        if (gui.isContainerSlot(slot)) {
            ContainerSelectGui.open(player, session, 0);
            return;
        }
        Integer index = gui.playerIndexAt(slot);
        if (index == null) {
            return;
        }
        var profile = session.profiles().get(index);
        String name = profile != null ? profile.name() : ("#" + index);
        switch (event.getClick()) {
            case LEFT -> {
                session.follow(index);
                player.sendMessage(Component.text("Folge " + name));
                openPlaybackControl(player, session);
            }
            case RIGHT -> {
                session.pov(index);
                player.closeInventory();
                player.sendMessage(Component.text("POV: " + name
                        + " (beenden: erneut Spieler-GUI → Folgen beenden)"));
            }
            case SHIFT_LEFT -> {
                player.closeInventory();
                Location location = session.actorLocation(index);
                if (location == null) {
                    player.sendMessage(Component.text("Kein Standort für " + name + " verfügbar."));
                } else {
                    player.teleport(location.clone().add(0, 2, 0));
                    player.sendMessage(Component.text("Zu " + name + " teleportiert."));
                }
            }
            case SHIFT_RIGHT -> {
                player.closeInventory();
                InventoryInspectGui.openPlayerInventory(player, session, index);
            }
            default -> { }
        }
    }

    // --- container select GUI ---

    private void onContainerSelectClick(InventoryClickEvent event, ContainerSelectGui gui) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == 49) {
            player.closeInventory();
            return;
        }
        PlaybackSession session = playback.sessionOf(player).orElse(null);
        if (slot == 45 && gui.page() > 0) {
            if (session != null) {
                ContainerSelectGui.open(player, session, gui.page() - 1);
            }
            return;
        }
        if (slot == 53) {
            if (session != null) {
                ContainerSelectGui.open(player, session, gui.page() + 1);
            }
            return;
        }
        String containerId = gui.containerIdAt(slot);
        if (containerId == null) {
            return;
        }
        if (session == null) {
            player.sendMessage(Component.text("Keine aktive Playback-Session."));
            player.closeInventory();
            return;
        }
        InventoryInspectGui.openContainer(player, session, containerId);
    }

    // --- speed select GUI ---

    private void onSpeedSelectClick(InventoryClickEvent event, SpeedSelectGui gui) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        PlaybackSession session = playback.sessionOf(player).orElse(null);
        if (session == null) {
            player.sendMessage(Component.text("Keine aktive Playback-Session."));
            player.closeInventory();
            return;
        }
        SpeedSelectGui.Action action = gui.actionOf(event.getCurrentItem());
        if (action == null) {
            return;
        }
        if (action.speedValue() != null) {
            session.setSpeed(action.speedValue());
            player.sendMessage(Component.text("Geschwindigkeit: " + session.speed() + "x"));
            hotbar.refresh(player, session.isPaused(), session.speed());
            SpeedSelectGui.open(player, plugin, session); // reopen so the active marker moves
            return;
        }
        switch (action) {
            case TICK_BACK -> {
                session.pause();
                session.stepTicks(-1);
                player.sendMessage(Component.text("Tick " + session.currentTick()));
                hotbar.refresh(player, session.isPaused(), session.speed());
            }
            case TICK_FORWARD -> {
                session.pause();
                session.stepTicks(1);
                player.sendMessage(Component.text("Tick " + session.currentTick()));
                hotbar.refresh(player, session.isPaused(), session.speed());
            }
            case CLOSE -> player.closeInventory();
            default -> { }
        }
    }

    // --- confirm GUI ---

    private void onConfirmClick(InventoryClickEvent event, ConfirmGui gui) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (gui.isConfirmSlot(slot)) {
            player.closeInventory();
            gui.runConfirm();
        } else if (gui.isCancelSlot(slot)) {
            player.closeInventory();
            gui.runCancel();
        }
    }

    private void toggleFreecam(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(GameMode.ADVENTURE);
            player.setAllowFlight(true);
            player.setFlying(true);
            hotbar.give(player);
            // give() resets slots 2/5 to the (paused, 1x) default — sync them back to the
            // real session state right away instead of waiting for the next HUD tick
            if (playback != null) {
                playback.sessionOf(player).ifPresent(session ->
                        hotbar.refresh(player, session.isPaused(), session.speed()));
            }
            player.sendMessage(Component.text("Freecam aus — Hotbar wieder aktiv."));
        } else {
            player.setGameMode(GameMode.SPECTATOR);
            player.sendMessage(Component.text(
                    "Freecam an (Spectator). Nochmal nutzen: /erp freecam"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (playback != null) {
            playback.detachViewer(event.getPlayer());
        }
        var live = plugin.liveMirror();
        if (live != null) {
            live.leave(event.getPlayer());
        }
        RecordControlGui.forget(event.getPlayer().getUniqueId());
    }
}
