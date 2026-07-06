package dev.raindancer118.extendedreplay.paper.gui;

import dev.raindancer118.extendedreplay.paper.replay.PlaybackSession;
import dev.raindancer118.extendedreplay.storage.meta.EventRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Paginated chest GUI listing session events. Left click jumps to the event, right
 * click teleports the viewer to the event location, shift click jumps 10 seconds
 * before the event.
 */
public final class EventBrowserGui implements InventoryHolder {

    public static final int PAGE_SIZE = 45;

    private final List<EventRecord> events;
    private final Map<UUID, String> playerNames;
    private final int page;
    private Inventory inventory;

    public EventBrowserGui(List<EventRecord> events, Map<UUID, String> playerNames, int page) {
        this.events = events;
        this.playerNames = playerNames;
        this.page = page;
    }

    public static void open(Player viewer, List<EventRecord> events,
                            Map<UUID, String> playerNames, int page) {
        int maxPage = Math.max(0, (events.size() - 1) / PAGE_SIZE);
        EventBrowserGui gui = new EventBrowserGui(events, playerNames,
                Math.max(0, Math.min(page, maxPage)));
        viewer.openInventory(gui.getInventory());
    }

    @Override
    public Inventory getInventory() {
        if (inventory != null) {
            return inventory;
        }
        inventory = org.bukkit.Bukkit.createInventory(this, 54,
                Component.text("Events – Seite " + (page + 1)));
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < events.size(); i++) {
            inventory.setItem(i, eventItem(events.get(from + i)));
        }
        if (page > 0) {
            inventory.setItem(45, navItem(Material.ARROW, "« Vorherige Seite"));
        }
        if (from + PAGE_SIZE < events.size()) {
            inventory.setItem(53, navItem(Material.ARROW, "Nächste Seite »"));
        }
        inventory.setItem(49, navItem(Material.BARRIER, "Schließen"));
        return inventory;
    }

    private ItemStack eventItem(EventRecord event) {
        ItemStack item = new ItemStack(iconFor(event.category()));
        item.editMeta(meta -> {
            meta.displayName(Component.text(event.eventType(), colorFor(event.category())));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Zeit: " + PlaybackSession.formatTicks(event.tick()),
                    NamedTextColor.GRAY));
            lore.add(Component.text("Kategorie: " + event.category(), NamedTextColor.GRAY));
            if (event.actor() != null) {
                lore.add(Component.text("Akteur: "
                        + playerNames.getOrDefault(event.actor(), "?"), NamedTextColor.GRAY));
            }
            if (event.target() != null) {
                lore.add(Component.text("Ziel: "
                        + playerNames.getOrDefault(event.target(), "?"), NamedTextColor.GRAY));
            }
            if (event.hasLocation()) {
                lore.add(Component.text(String.format(java.util.Locale.ROOT,
                        "Ort: %.0f / %.0f / %.0f", event.x(), event.y(), event.z()),
                        NamedTextColor.GRAY));
            }
            event.metadata().forEach((key, value) -> {
                if (value.length() <= 40) {
                    lore.add(Component.text(key + ": " + value, NamedTextColor.DARK_GRAY));
                }
            });
            lore.add(Component.empty());
            lore.add(Component.text("Linksklick: springen", NamedTextColor.YELLOW));
            lore.add(Component.text("Shift-Klick: 10s davor", NamedTextColor.YELLOW));
            if (event.hasLocation()) {
                lore.add(Component.text("Rechtsklick: hin teleportieren", NamedTextColor.YELLOW));
            }
            meta.lore(lore);
        });
        return item;
    }

    private static ItemStack navItem(Material material, String label) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> meta.displayName(Component.text(label)));
        return item;
    }

    static Material iconFor(String category) {
        return switch (category) {
            case "KILL" -> Material.DIAMOND_SWORD;
            case "DEATH" -> Material.SKELETON_SKULL;
            case "COMBAT" -> Material.IRON_SWORD;
            case "LOOT" -> Material.CHEST;
            case "ITEM" -> Material.ITEM_FRAME;
            case "MOVEMENT" -> Material.LEATHER_BOOTS;
            case "WORLD" -> Material.GRASS_BLOCK;
            case "CHAT" -> Material.WRITABLE_BOOK;
            case "SUPPLY" -> Material.ENDER_CHEST;
            case "ADMIN" -> Material.COMMAND_BLOCK;
            case "ANOMALY" -> Material.REDSTONE_TORCH;
            case "BOOKMARK" -> Material.NAME_TAG;
            case "SESSION" -> Material.CLOCK;
            default -> Material.PAPER;
        };
    }

    private static NamedTextColor colorFor(String category) {
        return switch (category) {
            case "KILL" -> NamedTextColor.RED;
            case "DEATH" -> NamedTextColor.DARK_RED;
            case "COMBAT" -> NamedTextColor.GOLD;
            case "LOOT" -> NamedTextColor.YELLOW;
            case "SUPPLY" -> NamedTextColor.LIGHT_PURPLE;
            case "BOOKMARK" -> NamedTextColor.AQUA;
            case "SESSION" -> NamedTextColor.GREEN;
            default -> NamedTextColor.WHITE;
        };
    }

    public int page() {
        return page;
    }

    public List<EventRecord> events() {
        return events;
    }

    public Map<UUID, String> playerNames() {
        return playerNames;
    }

    /** The event behind a raw slot click, or null for nav slots. */
    public EventRecord eventAt(int slot) {
        if (slot < 0 || slot >= PAGE_SIZE) {
            return null;
        }
        int index = page * PAGE_SIZE + slot;
        return index < events.size() ? events.get(index) : null;
    }
}
