package dev.raindancer118.extendedreplay.paper.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

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
        PLAY_PAUSE, TIMELINE, EVENTS, FOLLOW, CAMERA, ROUTES, INSPECT, SPEED, EXIT
    }

    /** Actions of the REPLAY server's lobby hotbar (no active playback/mirror). */
    public enum LobbyAction {
        BROWSE_SESSIONS, LIVE_MIRROR, PLAY_LAST, HELP
    }

    private final NamespacedKey key;
    private final NamespacedKey lobbyKey;

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
        inventory.setItem(0, taggedItem(Material.LIME_DYE, "▶ Play / Pause", key, Action.PLAY_PAUSE.name()));
        inventory.setItem(1, taggedItem(Material.CLOCK, "🕐 Wiedergabe-Steuerung", key, Action.TIMELINE.name()));
        inventory.setItem(2, taggedItem(Material.BOOK, "📅 Event-Browser", key, Action.EVENTS.name()));
        inventory.setItem(3, taggedItem(Material.PLAYER_HEAD, "👤 Spieler folgen", key, Action.FOLLOW.name()));
        inventory.setItem(4, taggedItem(Material.ENDER_EYE, "🎥 Freecam", key, Action.CAMERA.name()));
        inventory.setItem(5, taggedItem(Material.RED_CARPET, "🗺 Routen", key, Action.ROUTES.name()));
        inventory.setItem(6, taggedItem(Material.CHEST, "🎒 Inspektion", key, Action.INSPECT.name()));
        inventory.setItem(7, taggedItem(Material.SUGAR, "⚡ Geschwindigkeit", key, Action.SPEED.name()));
        inventory.setItem(8, taggedItem(Material.BARRIER, "✖ Verlassen", key, Action.EXIT.name()));
    }

    public void remove(Player viewer) {
        viewer.getInventory().clear();
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
                lobbyKey, LobbyAction.BROWSE_SESSIONS.name()));
        inventory.setItem(2, taggedItem(Material.ENDER_EYE, "📡 Live-Mirror",
                lobbyKey, LobbyAction.LIVE_MIRROR.name()));
        inventory.setItem(4, taggedItem(Material.CLOCK, "⏱ Letzte Session abspielen",
                lobbyKey, LobbyAction.PLAY_LAST.name()));
        inventory.setItem(8, taggedItem(Material.BOOK, "❓ Hilfe",
                lobbyKey, LobbyAction.HELP.name()));
    }

    private ItemStack taggedItem(Material material, String label, NamespacedKey tagKey, String tagValue) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(label, NamedTextColor.AQUA));
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
