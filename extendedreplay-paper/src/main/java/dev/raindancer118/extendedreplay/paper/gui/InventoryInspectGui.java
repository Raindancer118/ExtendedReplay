package dev.raindancer118.extendedreplay.paper.gui;

import dev.raindancer118.extendedreplay.core.model.ContainerSnapshot;
import dev.raindancer118.extendedreplay.core.model.InventorySnapshot;
import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import dev.raindancer118.extendedreplay.paper.replay.PlaybackSession;
import dev.raindancer118.extendedreplay.paper.util.ItemBytes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Read-only view of a recorded inventory or container at a replay timestamp.
 *
 * <p>Player inventory layout: rows 1–3 = main (9–35), row 4 = hotbar (0–8),
 * row 5 = armor + offhand, row 6 = info.</p>
 */
public final class InventoryInspectGui implements InventoryHolder {

    private Inventory inventory;
    private final Component title;
    private final ItemStack[] slots;

    private InventoryInspectGui(Component title, ItemStack[] slots) {
        this.title = title;
        this.slots = slots;
    }

    public static void openPlayerInventory(Player viewer, String playerName,
                                           InventorySnapshot snapshot, PlayerProfileData profile) {
        ItemStack[] gui = new ItemStack[54];
        // main inventory 9..35 → rows 1-3
        for (int i = 9; i <= 35 && i < snapshot.slots().length; i++) {
            gui[i - 9] = ItemBytes.deserialize(snapshot.slots()[i]);
        }
        // hotbar 0..8 → row 4
        for (int i = 0; i <= 8 && i < snapshot.slots().length; i++) {
            gui[27 + i] = ItemBytes.deserialize(snapshot.slots()[i]);
        }
        // armor 36..39 + offhand 40 → row 5
        for (int i = 36; i <= 40 && i < snapshot.slots().length; i++) {
            gui[36 + (i - 36)] = ItemBytes.deserialize(snapshot.slots()[i]);
        }
        gui[44] = ItemBytes.deserialize(snapshot.cursor());
        // real skin of the inspected player, if one was captured
        gui[45] = profile != null ? Heads.playerHead(profile) : null;
        gui[49] = infoItem(List.of(
                "Spieler: " + playerName,
                "Zeit: " + PlaybackSession.formatTicks(snapshot.tick()),
                "Grund: " + snapshot.cause(),
                "Leben: " + (snapshot.health() / 10.0),
                "Hunger: " + snapshot.food(),
                "XP-Level: " + snapshot.xpLevel()));
        InventoryInspectGui holder = new InventoryInspectGui(
                Component.text("🎒 " + playerName + " @ "
                        + PlaybackSession.formatTicks(snapshot.tick())), gui);
        viewer.openInventory(holder.getInventory());
    }

    public static void openContainer(Player viewer, ContainerSnapshot snapshot) {
        int size = Math.min(54, ((snapshot.slots().length + 8) / 9) * 9 + 9);
        ItemStack[] gui = new ItemStack[size];
        for (int i = 0; i < snapshot.slots().length && i < size - 9; i++) {
            gui[i] = ItemBytes.deserialize(snapshot.slots()[i]);
        }
        gui[size - 5] = infoItem(List.of(
                "Container: " + snapshot.containerType(),
                "Position: " + snapshot.x() + " / " + snapshot.y() + " / " + snapshot.z(),
                "Zeit: " + PlaybackSession.formatTicks(snapshot.tick()),
                "Grund: " + snapshot.cause()));
        InventoryInspectGui holder = new InventoryInspectGui(
                Component.text("📦 " + snapshot.containerType() + " @ "
                        + PlaybackSession.formatTicks(snapshot.tick())), gui);
        viewer.openInventory(holder.getInventory());
    }

    private static ItemStack infoItem(List<String> lines) {
        ItemStack item = new ItemStack(Material.OAK_SIGN);
        item.editMeta(meta -> {
            meta.displayName(Component.text("ℹ Snapshot-Info", NamedTextColor.AQUA));
            meta.lore(lines.stream()
                    .map(line -> (Component) Component.text(line, NamedTextColor.GRAY))
                    .toList());
        });
        return item;
    }

    @Override
    public Inventory getInventory() {
        if (inventory == null) {
            inventory = Bukkit.createInventory(this, slots.length, title);
            inventory.setContents(slots);
        }
        return inventory;
    }
}
