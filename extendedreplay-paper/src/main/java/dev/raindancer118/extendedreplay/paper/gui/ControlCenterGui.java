package dev.raindancer118.extendedreplay.paper.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * The Replay Control Center: the main entry point opened by {@code /erp} (no args) and
 * {@code /erp menu}. Rows out to every other GUI depending on the server's role — a pure
 * REPLAY server hides the recording button, a pure PRODUCER server hides everything
 * playback-related. Mirrors {@link RecordControlGui}'s structure: PDC-tagged buttons on a
 * glass-pane background, click dispatch left entirely to {@link GuiListener}.
 */
public final class ControlCenterGui implements InventoryHolder {

    private static final int SIZE = 27;

    public enum Action {
        SESSIONS, FAVORITES, LIVE, LAST, RECORD, JOBS, STORAGE, HELP
    }

    private final NamespacedKey key;
    private Inventory inventory;

    private ControlCenterGui(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "erp-control-center-action");
    }

    /**
     * Builds and opens the panel.
     *
     * @param canPlayback whether this server can play back sessions ({@code plugin.playback() != null})
     * @param canRecord   whether this server can record ({@code plugin.role().records()} and a producer exists)
     */
    public static void open(Player viewer, Plugin plugin, boolean canPlayback, boolean canRecord) {
        ControlCenterGui gui = new ControlCenterGui(plugin);
        viewer.openInventory(gui.build(canPlayback, canRecord));
    }

    private Inventory build(boolean canPlayback, boolean canRecord) {
        inventory = Bukkit.createInventory(this, SIZE, Component.text("🎬 ExtendedReplay"));
        fill(Material.GRAY_STAINED_GLASS_PANE);

        if (canPlayback) {
            inventory.setItem(10, button(Material.CHEST_MINECART,
                    Component.text("📼 Sessions", NamedTextColor.AQUA),
                    List.of(Component.text("Alle gespeicherten Sessions durchsuchen.", NamedTextColor.GRAY)),
                    Action.SESSIONS));
            inventory.setItem(11, button(Material.NETHER_STAR,
                    Component.text("★ Favoriten", NamedTextColor.YELLOW),
                    List.of(Component.text("Nur favorisierte Sessions.", NamedTextColor.GRAY)),
                    Action.FAVORITES));
            inventory.setItem(12, button(Material.ENDER_EYE,
                    Component.text("📡 Live beitreten", NamedTextColor.RED),
                    List.of(Component.text("Der aktuell laufenden Aufnahme zuschauen.", NamedTextColor.GRAY)),
                    Action.LIVE));
            inventory.setItem(13, button(Material.CLOCK,
                    Component.text("⏱ Letzte Session abspielen", NamedTextColor.AQUA),
                    List.of(Component.text("Öffnet die zuletzt beendete Session.", NamedTextColor.GRAY)),
                    Action.LAST));
        }
        if (canRecord) {
            inventory.setItem(14, button(Material.REDSTONE,
                    Component.text("⏺ Aufnahme-Steuerung", NamedTextColor.RED),
                    List.of(Component.text("Radius, Start/Stop der Aufnahme.", NamedTextColor.GRAY)),
                    Action.RECORD));
        }
        inventory.setItem(15, button(Material.HOPPER,
                Component.text("⚙ Jobs", NamedTextColor.AQUA),
                List.of(Component.text("Laufende & abgeschlossene Hintergrund-Jobs.", NamedTextColor.GRAY)),
                Action.JOBS));
        if (canPlayback) {
            inventory.setItem(16, button(Material.BOOKSHELF,
                    Component.text("💾 Speicher-Info", NamedTextColor.AQUA),
                    List.of(Component.text("Belegter Speicher & Session-Anzahl.", NamedTextColor.GRAY)),
                    Action.STORAGE));
        }
        inventory.setItem(22, button(Material.BOOK,
                Component.text("❓ Hilfe", NamedTextColor.GREEN),
                List.of(Component.text("Kurzüberblick über die Funktionen.", NamedTextColor.GRAY)),
                Action.HELP));

        return inventory;
    }

    private void fill(Material material) {
        ItemStack pane = new ItemStack(material);
        pane.editMeta(meta -> meta.displayName(Component.text(" ")));
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, pane);
        }
    }

    private ItemStack button(Material material, Component name, List<Component> lore, Action action) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name);
            meta.lore(lore);
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, action.name());
        });
        return item;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** The action bound to the clicked item, or null (filler/empty slots). */
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
