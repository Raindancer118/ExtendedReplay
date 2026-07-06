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
 */
public final class HotbarUI {

    public enum Action {
        PLAY_PAUSE, TIMELINE, EVENTS, FOLLOW, CAMERA, ROUTES, INSPECT, SPEED, EXIT
    }

    private final NamespacedKey key;

    public HotbarUI(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "erp-hotbar-action");
    }

    public NamespacedKey key() {
        return key;
    }

    public void give(Player viewer) {
        var inventory = viewer.getInventory();
        inventory.clear();
        inventory.setItem(0, item(Material.LIME_DYE, "▶ Play / Pause", Action.PLAY_PAUSE));
        inventory.setItem(1, item(Material.CLOCK, "🕐 Timeline", Action.TIMELINE));
        inventory.setItem(2, item(Material.BOOK, "📅 Event-Browser", Action.EVENTS));
        inventory.setItem(3, item(Material.PLAYER_HEAD, "👤 Spieler folgen", Action.FOLLOW));
        inventory.setItem(4, item(Material.ENDER_EYE, "🎥 Freecam", Action.CAMERA));
        inventory.setItem(5, item(Material.RED_CARPET, "🗺 Routen", Action.ROUTES));
        inventory.setItem(6, item(Material.CHEST, "🎒 Inspektion", Action.INSPECT));
        inventory.setItem(7, item(Material.SUGAR, "⚡ Geschwindigkeit", Action.SPEED));
        inventory.setItem(8, item(Material.BARRIER, "✖ Verlassen", Action.EXIT));
    }

    public void remove(Player viewer) {
        viewer.getInventory().clear();
    }

    private ItemStack item(Material material, String label, Action action) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(label, NamedTextColor.AQUA));
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, action.name());
        });
        return item;
    }

    /** The action bound to the item, or null. */
    public Action actionOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String value = item.getItemMeta().getPersistentDataContainer()
                .get(key, PersistentDataType.STRING);
        if (value == null) {
            return null;
        }
        try {
            return Action.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
