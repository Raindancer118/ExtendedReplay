package dev.raindancer118.extendedreplay.paper.gui;

import dev.raindancer118.extendedreplay.paper.ExtendedReplayPlugin;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Puts freshly joining viewers on a dedicated REPLAY server straight into the lobby:
 * adventure mode, free flight and the lobby hotbar. Only registered for role REPLAY —
 * on STANDALONE the server is actually played/recorded on and joining players must keep
 * their normal survival state.
 */
public final class ReplayLobbyListener implements Listener {

    private final ExtendedReplayPlugin plugin;

    public ReplayLobbyListener(ExtendedReplayPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("extendedreplay.viewer")) {
            return;
        }
        // a fast rejoin while still attached to a playback/mirror keeps that session's hotbar
        if (plugin.playback() != null && plugin.playback().sessionOf(player).isPresent()) {
            return;
        }
        if (plugin.liveMirror() != null && plugin.liveMirror().isViewer(player)) {
            return;
        }
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true);
        player.setFlying(true);
        plugin.hotbar().giveLobby(player);
    }
}
