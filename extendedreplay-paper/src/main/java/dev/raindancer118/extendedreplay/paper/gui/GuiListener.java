package dev.raindancer118.extendedreplay.paper.gui;

import dev.raindancer118.extendedreplay.paper.replay.PlaybackManager;
import dev.raindancer118.extendedreplay.paper.replay.PlaybackSession;
import dev.raindancer118.extendedreplay.storage.ReplayStorage;
import dev.raindancer118.extendedreplay.storage.meta.EventRecord;
import dev.raindancer118.extendedreplay.storage.meta.SessionRecord;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Dispatches clicks in replay GUIs and hotbar item usage. Only active on servers that
 * play back (REPLAY/STANDALONE).
 */
public final class GuiListener implements Listener {

    private final dev.raindancer118.extendedreplay.paper.ExtendedReplayPlugin plugin;
    private final PlaybackManager playback;
    private final ReplayStorage storage;
    private final HotbarUI hotbar;
    private final dev.raindancer118.extendedreplay.paper.replay.route.RouteManager routes;
    private static final double[] SPEED_STEPS = PlaybackControlGui.SPEED_STEPS;

    public GuiListener(dev.raindancer118.extendedreplay.paper.ExtendedReplayPlugin plugin,
                       PlaybackManager playback, ReplayStorage storage, HotbarUI hotbar,
                       dev.raindancer118.extendedreplay.paper.replay.route.RouteManager routes) {
        this.plugin = plugin;
        this.playback = playback;
        this.storage = storage;
        this.hotbar = hotbar;
        this.routes = routes;
    }

    // --- chest GUIs ---

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
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

    // --- hotbar ---

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
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
                    case EXIT -> live.leave(player);
                    case CAMERA -> toggleFreecam(player);
                    default -> player.sendMessage(Component.text(
                            "Im Live-Mirror verfügbar: Freecam (Auge), Verlassen (Barriere)."));
                }
            }
            return;
        }
        switch (action) {
            case PLAY_PAUSE -> {
                if (session.isPaused()) {
                    session.play();
                    player.sendMessage(Component.text("▶ Wiedergabe"));
                } else {
                    session.pause();
                    player.sendMessage(Component.text("⏸ Pausiert"));
                }
            }
            case TIMELINE -> openPlaybackControl(player, session);
            case EVENTS -> openEventBrowser(player, session);
            case FOLLOW -> PlayerSelectGui.open(player, session.profiles(), session.followedPlayer());
            case CAMERA -> toggleFreecam(player);
            case ROUTES -> {
                if (routes.markerCount() > 0) {
                    routes.clear(player);
                } else {
                    routes.render(player, session, java.util.List.of(), 0,
                            session.lastTickOfSession());
                }
            }
            case INSPECT -> player.sendMessage(Component.text(
                    "Nutze /erp inventory <spieler> oder /erp container <x> <y> <z>"));
            case SPEED -> cycleSpeed(player, session);
            case EXIT -> playback.detachViewer(player);
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

    /** Opens the fancy playback control panel (play/pause, skip, speed, follow, …). */
    public void openPlaybackControl(Player player, PlaybackSession session) {
        PlaybackControlGui.open(player, plugin, session);
    }

    // --- session browser GUI ---

    /**
     * Loads every stored session (async DB read) and opens the browser on the main
     * thread. Used both for the initial open and the in-GUI refresh button.
     */
    public void openSessionBrowser(Player player) {
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
            Bukkit.getScheduler().runTask(plugin, () -> SessionBrowserGui.open(player, sessions, 0));
        });
    }

    private void onSessionBrowserClick(InventoryClickEvent event, SessionBrowserGui gui) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == 45 && gui.page() > 0) {
            SessionBrowserGui.open(player, gui.sessions(), gui.page() - 1);
            return;
        }
        if (slot == 53) {
            SessionBrowserGui.open(player, gui.sessions(), gui.page() + 1);
            return;
        }
        if (slot == 47) {
            openSessionBrowser(player);
            return;
        }
        if (slot == 49) {
            player.closeInventory();
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
        if (!record.isFinished()) {
            player.closeInventory();
            var live = plugin.liveMirror();
            if (live == null) {
                player.sendMessage(Component.text("Live-Mirror ist auf diesem Server nicht verfügbar."));
                return;
            }
            live.join(player, record.sessionId());
            return;
        }
        player.closeInventory();
        player.sendMessage(Component.text("Lade Session " + record.name() + "…"));
        playback.open(record.sessionId(), player).whenComplete((session, error) -> {
            if (error != null) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                        Component.text("Konnte Session nicht öffnen: " + error.getMessage())));
            }
        });
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
            player.sendMessage(Component.text("Folgen beendet."));
            openPlaybackControl(player, session);
            return;
        }
        Integer index = gui.playerIndexAt(slot);
        if (index == null) {
            return;
        }
        session.follow(index);
        String name = session.profiles().get(index) != null
                ? session.profiles().get(index).name() : ("#" + index);
        player.sendMessage(Component.text("Folge " + name));
        openPlaybackControl(player, session);
    }

    private void toggleFreecam(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(GameMode.ADVENTURE);
            player.setAllowFlight(true);
            player.setFlying(true);
            hotbar.give(player);
            player.sendMessage(Component.text("Freecam aus — Hotbar wieder aktiv."));
        } else {
            player.setGameMode(GameMode.SPECTATOR);
            player.sendMessage(Component.text(
                    "Freecam an (Spectator). Nochmal nutzen: /erp freecam"));
        }
    }

    private void cycleSpeed(Player player, PlaybackSession session) {
        double current = session.speed();
        int index = 0;
        for (int i = 0; i < SPEED_STEPS.length; i++) {
            if (Math.abs(SPEED_STEPS[i] - current) < 0.01) {
                index = (i + 1) % SPEED_STEPS.length;
                break;
            }
        }
        session.setSpeed(SPEED_STEPS[index]);
        player.sendMessage(Component.text("Geschwindigkeit: " + SPEED_STEPS[index] + "x"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        playback.detachViewer(event.getPlayer());
        var live = plugin.liveMirror();
        if (live != null) {
            live.leave(event.getPlayer());
        }
    }
}
