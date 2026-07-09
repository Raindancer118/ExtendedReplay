package dev.raindancer118.extendedreplay.paper.gui;

import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Lists every recorded player as a real-skin head; clicking one makes the replay
 * camera follow that player. A barrier item at the end stops following. Built fresh
 * per open from a data snapshot ({@link PlayerProfileData} values), never from live
 * entities — safe to keep around for the lifetime of the open inventory.
 */
public final class PlayerSelectGui implements InventoryHolder {

    private final List<PlayerProfileData> players;
    private Inventory inventory;

    private PlayerSelectGui(List<PlayerProfileData> players) {
        this.players = players;
    }

    /** Builds and opens the follow-selection GUI for the viewer. */
    public static void open(Player viewer, Map<Integer, PlayerProfileData> profiles,
                            Integer currentFollow) {
        List<PlayerProfileData> sorted = profiles.values().stream()
                .sorted(Comparator.comparing(PlayerProfileData::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        PlayerSelectGui gui = new PlayerSelectGui(sorted);
        viewer.openInventory(gui.build(currentFollow));
    }

    private Inventory build(Integer currentFollow) {
        int rows = Math.max(1, Math.min(6, (players.size() + 1 + 8) / 9));
        inventory = Bukkit.createInventory(this, rows * 9,
                Component.text("👤 Spieler folgen"));
        int slot = 0;
        for (PlayerProfileData profile : players) {
            if (slot >= inventory.getSize() - 1) {
                break; // extreme case: more recorded players than fit on one page
            }
            boolean active = currentFollow != null && currentFollow == profile.playerIndex();
            ItemStack head = Heads.playerHead(profile);
            head.editMeta(meta -> {
                meta.displayName(Component.text((active ? "» " : "") + profile.name(),
                        active ? NamedTextColor.YELLOW : NamedTextColor.AQUA));
                meta.setEnchantmentGlintOverride(active);
                meta.lore(List.of(Component.text("Klicken, um dieser Person zu folgen.",
                        NamedTextColor.GRAY)));
            });
            inventory.setItem(slot, head);
            slot++;
        }
        ItemStack stop = new ItemStack(Material.BARRIER);
        stop.editMeta(meta -> {
            meta.displayName(Component.text("✖ Folgen beenden", NamedTextColor.RED));
            meta.lore(List.of(Component.text("Kamera folgt niemandem mehr.", NamedTextColor.GRAY)));
        });
        inventory.setItem(inventory.getSize() - 1, stop);
        return inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** The recorded player index behind a slot, or null (out of range / stop slot). */
    public Integer playerIndexAt(int slot) {
        return slot >= 0 && slot < players.size() ? players.get(slot).playerIndex() : null;
    }

    public boolean isStopSlot(int slot) {
        return inventory != null && slot == inventory.getSize() - 1;
    }
}
