package dev.raindancer118.extendedreplay.paper.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moderator hotbar while inside a playback session. Items are tagged with a PDC key so
 * the listener can dispatch clicks; the original inventory is not touched because
 * viewers are in spectator/creative replay context and get their items back on exit.
 *
 * <p>Also provides the REPLAY server's lobby hotbar (see {@link #giveLobby(Player)}),
 * tagged with a separate PDC key so both hotbars can coexist without action collisions.</p>
 */
public final class HotbarUI {

    public enum Action {
        PREV_EVENT, REWIND, PLAY_PAUSE, FAST_FORWARD, NEXT_EVENT, SPEED, PLAYERS, CAMERA, MENU
    }

    /** Actions of the REPLAY server's lobby hotbar (no active playback/mirror). */
    public enum LobbyAction {
        BROWSE_SESSIONS, LIVE_MIRROR, PLAY_LAST, HELP, CONTROL_CENTER
    }

    /** Snapshot of the dynamic hotbar state (play/pause + speed) last rendered to a
     * viewer — used to skip redundant {@code setItem} calls in {@link #refresh}. */
    private record RenderedState(boolean paused, double speed) {
    }

    private final NamespacedKey key;
    private final NamespacedKey lobbyKey;
    private final Map<UUID, RenderedState> renderedStates = new HashMap<>();

    public HotbarUI(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "erp-hotbar-action");
        this.lobbyKey = new NamespacedKey(plugin, "erp-lobby-action");
    }

    public NamespacedKey key() {
        return key;
    }

    public void give(Player viewer) {
        var inventory = viewer.getInventory();
        inventory.clear();
        inventory.setItem(0, taggedItem(Material.SPECTRAL_ARROW, "⏮ Vorheriges Event",
                List.of(), key, Action.PREV_EVENT.name()));
        inventory.setItem(1, taggedItem(Material.ARROW, "« Zurückspulen (10s / Shift: 60s)",
                List.of(), key, Action.REWIND.name()));
        inventory.setItem(2, playPauseItem(true));
        inventory.setItem(3, taggedItem(Material.ARROW, "Vorspulen (10s / Shift: 60s) »",
                List.of(), key, Action.FAST_FORWARD.name()));
        inventory.setItem(4, taggedItem(Material.SPECTRAL_ARROW, "⏭ Nächstes Event",
                List.of(), key, Action.NEXT_EVENT.name()));
        inventory.setItem(5, speedItem(1.0));
        inventory.setItem(6, taggedItem(Material.PLAYER_HEAD, "👥 Spieler",
                List.of("Folgen · POV · Teleport · Inventar"), key, Action.PLAYERS.name()));
        inventory.setItem(7, taggedItem(Material.ENDER_EYE, "🎥 Freecam",
                List.of(), key, Action.CAMERA.name()));
        inventory.setItem(8, taggedItem(Material.COMPASS, "☰ Menü",
                List.of("Klick: Steuerung öffnen", "Shift-Klick: Replay verlassen"),
                key, Action.MENU.name()));
        // default state matches what was just rendered (paused, 1x) so the first refresh()
        // call right after attaching a viewer is a cheap no-op
        renderedStates.put(viewer.getUniqueId(), new RenderedState(true, 1.0));
    }

    public void remove(Player viewer) {
        viewer.getInventory().clear();
        renderedStates.remove(viewer.getUniqueId());
    }

    /**
     * Drops a viewer's cached render state without touching their inventory — used when a
     * viewer's real inventory is restored directly (non-lobby detach) instead of being
     * replaced by {@link #give} or {@link #giveLobby}, so the stale entry doesn't linger
     * forever (never keyed by {@link Player}, but still worth not leaking indefinitely).
     */
    public void forgetState(UUID playerId) {
        renderedStates.remove(playerId);
    }

    /**
     * Updates only the dynamic play/pause (slot 2) and speed (slot 5) items in-place, and
     * only if the viewer's last-rendered state actually changed — cheap enough to call from
     * every hotbar-affecting action and from the periodic HUD tick.
     */
    public void refresh(Player viewer, boolean paused, double speed) {
        RenderedState next = new RenderedState(paused, speed);
        RenderedState previous = renderedStates.put(viewer.getUniqueId(), next);
        if (next.equals(previous)) {
            return;
        }
        viewer.getInventory().setItem(2, playPauseItem(paused));
        viewer.getInventory().setItem(5, speedItem(speed));
    }

    private ItemStack playPauseItem(boolean paused) {
        return taggedItem(paused ? Material.LIME_DYE : Material.ORANGE_DYE,
                paused ? "▶ Wiedergabe" : "⏸ Pause", List.of(), key, Action.PLAY_PAUSE.name());
    }

    private ItemStack speedItem(double speed) {
        ItemStack item = taggedItem(Material.SUGAR, "⚡ Geschwindigkeit: " + formatSpeed(speed) + "x",
                List.of("Klick: Geschwindigkeit wählen"), key, Action.SPEED.name());
        int amount = (int) Math.max(1, Math.min(64, Math.round(speed)));
        item.setAmount(amount);
        return item;
    }

    private static String formatSpeed(double speed) {
        return speed == Math.floor(speed) ? String.valueOf((int) speed) : String.valueOf(speed);
    }

    /**
     * Gives the REPLAY server's lobby hotbar: session browser, live-mirror join, replay
     * of the last finished session, and a short help text. Only slots 0/2/4/8 are used;
     * the rest stays empty.
     */
    public void giveLobby(Player viewer) {
        var inventory = viewer.getInventory();
        inventory.clear();
        inventory.setItem(0, taggedItem(Material.CHEST_MINECART, "📼 Session-Browser",
                List.of(), lobbyKey, LobbyAction.BROWSE_SESSIONS.name()));
        inventory.setItem(2, taggedItem(Material.ENDER_EYE, "📡 Live-Mirror",
                List.of(), lobbyKey, LobbyAction.LIVE_MIRROR.name()));
        inventory.setItem(4, taggedItem(Material.CLOCK, "⏱ Letzte Session abspielen",
                List.of(), lobbyKey, LobbyAction.PLAY_LAST.name()));
        inventory.setItem(6, taggedItem(Material.COMPASS, "🎬 Control Center",
                List.of(), lobbyKey, LobbyAction.CONTROL_CENTER.name()));
        inventory.setItem(8, taggedItem(Material.BOOK, "❓ Hilfe",
                List.of(), lobbyKey, LobbyAction.HELP.name()));
        // no playback active in the lobby — drop any stale render-state entry
        renderedStates.remove(viewer.getUniqueId());
    }

    private ItemStack taggedItem(Material material, String label, List<String> lore,
                                 NamespacedKey tagKey, String tagValue) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(label, NamedTextColor.AQUA));
            if (!lore.isEmpty()) {
                meta.lore(lore.stream()
                        .map(line -> (Component) Component.text(line, NamedTextColor.GRAY))
                        .toList());
            }
            meta.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, tagValue);
        });
        return item;
    }

    /** The action bound to the item, or null. */
    public Action actionOf(ItemStack item) {
        String value = tagValue(item, key);
        if (value == null) {
            return null;
        }
        try {
            return Action.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The lobby action bound to the item, or null. */
    public LobbyAction lobbyActionOf(ItemStack item) {
        String value = tagValue(item, lobbyKey);
        if (value == null) {
            return null;
        }
        try {
            return LobbyAction.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True for any item tagged by this hotbar (playback or lobby) — used to keep both
     * hotbars from being moved out of their slots or dropped. */
    public boolean isTagged(ItemStack item) {
        return tagValue(item, key) != null || tagValue(item, lobbyKey) != null;
    }

    private String tagValue(ItemStack item, NamespacedKey tagKey) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(tagKey, PersistentDataType.STRING);
    }
}
