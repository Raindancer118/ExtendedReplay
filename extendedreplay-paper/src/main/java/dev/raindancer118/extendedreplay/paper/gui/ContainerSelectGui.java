package dev.raindancer118.extendedreplay.paper.gui;

import dev.raindancer118.extendedreplay.core.model.ContainerSnapshot;
import dev.raindancer118.extendedreplay.paper.replay.PlaybackSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;

/**
 * Paginated chest GUI listing every container recorded in a session, sorted by container
 * id ({@code world:x,y,z}). Clicking one opens a live {@link InventoryInspectGui} view of
 * its content at the current playback time. Mirrors {@link SessionBrowserGui}'s layout
 * and dispatch style: raw-slot lookups into the page-sliced list, nav/refresh sit on
 * fixed slots that {@link GuiListener} checks directly.
 */
public final class ContainerSelectGui implements InventoryHolder {

    public static final int PAGE_SIZE = 45;

    private final List<String> containerIds;
    private final int page;
    private Inventory inventory;

    private ContainerSelectGui(List<String> containerIds, int page) {
        this.containerIds = containerIds;
        this.page = page;
    }

    public static void open(Player viewer, PlaybackSession session, int page) {
        List<String> sorted = session.allContainers().keySet().stream().sorted().toList();
        int maxPage = Math.max(0, (sorted.size() - 1) / PAGE_SIZE);
        ContainerSelectGui gui = new ContainerSelectGui(sorted, Math.max(0, Math.min(page, maxPage)));
        viewer.openInventory(gui.build(session));
    }

    private Inventory build(PlaybackSession session) {
        int maxPage = Math.max(0, (containerIds.size() - 1) / PAGE_SIZE);
        inventory = Bukkit.createInventory(this, 54,
                Component.text("📦 Container – Seite " + (page + 1) + "/" + (maxPage + 1)));
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < containerIds.size(); i++) {
            inventory.setItem(i, containerItem(containerIds.get(from + i), session));
        }
        if (page > 0) {
            inventory.setItem(45, navItem(Material.ARROW, "« Vorherige Seite"));
        }
        inventory.setItem(49, navItem(Material.BARRIER, "Schließen"));
        if (from + PAGE_SIZE < containerIds.size()) {
            inventory.setItem(53, navItem(Material.ARROW, "Nächste Seite »"));
        }
        return inventory;
    }

    private ItemStack containerItem(String containerId, PlaybackSession session) {
        NavigableMap<Integer, ContainerSnapshot> byTick = session.allContainers().get(containerId);
        ContainerSnapshot latest = byTick != null && !byTick.isEmpty()
                ? byTick.lastEntry().getValue() : null;
        Material material = latest != null ? Material.matchMaterial(latest.containerType()) : null;
        if (material == null) {
            material = Material.CHEST;
        }
        String[] parts = parseContainerId(containerId);
        String world = parts[0];
        String coords = parts[1];
        String typeLabel = latest != null ? latest.containerType() : "Container";
        int snapshotCount = byTick != null ? byTick.size() : 0;
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(typeLabel + " @ " + coords, NamedTextColor.AQUA));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Welt: " + world, NamedTextColor.GRAY));
            lore.add(Component.text("Snapshots: " + snapshotCount, NamedTextColor.GRAY));
            lore.add(Component.text("Klick: Inhalt zur aktuellen Replay-Zeit (live)",
                    NamedTextColor.YELLOW));
            meta.lore(lore);
        });
        return item;
    }

    /** Splits {@code world:x,y,z} into {@code [world, "x / y / z"]}. */
    private static String[] parseContainerId(String containerId) {
        int colon = containerId.indexOf(':');
        if (colon < 0) {
            return new String[]{containerId, containerId};
        }
        String world = containerId.substring(0, colon);
        String coords = containerId.substring(colon + 1).replace(",", " / ");
        return new String[]{world, coords};
    }

    private static ItemStack navItem(Material material, String label) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> meta.displayName(Component.text(label)));
        return item;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public int page() {
        return page;
    }

    public List<String> containerIds() {
        return containerIds;
    }

    /** The container id behind a raw slot click, or null for nav/empty slots. */
    public String containerIdAt(int slot) {
        if (slot < 0 || slot >= PAGE_SIZE) {
            return null;
        }
        int index = page * PAGE_SIZE + slot;
        return index < containerIds.size() ? containerIds.get(index) : null;
    }
}
