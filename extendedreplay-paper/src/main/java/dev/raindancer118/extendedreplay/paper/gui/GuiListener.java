package dev.raindancer118.extendedreplay.paper.gui;

import dev.raindancer118.extendedreplay.paper.replay.PlaybackManager;
import dev.raindancer118.extendedreplay.paper.replay.PlaybackSession;
import dev.raindancer118.extendedreplay.storage.ReplayStorage;
import dev.raindancer118.extendedreplay.storage.meta.EventRecord;
import net.kyori.adventure.text.Component;
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
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Dispatches clicks in replay GUIs and hotbar item usage. Only active on servers that
 * play back (REPLAY/STANDALONE).
 */
public final class GuiListener implements Listener {

    private final Plugin plugin;
    private final PlaybackManager playback;
    private final ReplayStorage storage;
    private final HotbarUI hotbar;
    private final dev.raindancer118.extendedreplay.paper.replay.route.RouteManager routes;
    private static final double[] SPEED_STEPS = {0.25, 0.5, 1.0, 2.0, 4.0, 8.0};

    public GuiListener(Plugin plugin, PlaybackManager playback, ReplayStorage storage,
                       HotbarUI hotbar,
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
            case TIMELINE -> player.sendMessage(session.statusLine());
            case EVENTS -> openEventBrowser(player, session);
            case FOLLOW -> cycleFollow(player, session);
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

    private void cycleFollow(Player player, PlaybackSession session) {
        var indexes = session.profiles().keySet().stream().sorted().toList();
        if (indexes.isEmpty()) {
            return;
        }
        Integer current = session.followedPlayer();
        int position = current == null ? -1 : indexes.indexOf(current);
        if (position + 1 >= indexes.size()) {
            session.follow(null);
            player.sendMessage(Component.text("Folgen beendet."));
        } else {
            Integer next = indexes.get(position + 1);
            session.follow(next);
            String name = session.profiles().get(next) != null
                    ? session.profiles().get(next).name() : ("#" + next);
            player.sendMessage(Component.text("Folge " + name));
        }
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
    }
}
