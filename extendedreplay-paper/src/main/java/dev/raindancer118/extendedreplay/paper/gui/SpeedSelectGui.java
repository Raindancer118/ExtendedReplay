package dev.raindancer118.extendedreplay.paper.gui;

import dev.raindancer118.extendedreplay.paper.replay.PlaybackSession;
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
 * Speed picker chest GUI: ten fixed speed steps (0.1x–64x) plus single-tick step buttons
 * for frame-by-frame inspection while paused. Complements {@link HotbarUI}'s speed slot,
 * which just opens this GUI. A fresh instance/inventory is built on every open so the
 * active-speed marker always reflects the session at open time.
 */
public final class SpeedSelectGui implements InventoryHolder {

    public static final double[] SPEED_STEPS =
            {0.1, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0};

    public enum Action {
        SPEED_0_1(0.1), SPEED_0_25(0.25), SPEED_0_5(0.5), SPEED_1(1.0), SPEED_2(2.0),
        SPEED_4(4.0), SPEED_8(8.0), SPEED_16(16.0), SPEED_32(32.0), SPEED_64(64.0),
        TICK_BACK(null), TICK_FORWARD(null), CLOSE(null);

        private final Double speedValue;

        Action(Double speedValue) {
            this.speedValue = speedValue;
        }

        /** The playback speed this button applies, or null if it isn't a speed button. */
        public Double speedValue() {
            return speedValue;
        }
    }

    private static final Action[] SPEED_ACTIONS = {Action.SPEED_0_1, Action.SPEED_0_25,
            Action.SPEED_0_5, Action.SPEED_1, Action.SPEED_2, Action.SPEED_4, Action.SPEED_8,
            Action.SPEED_16, Action.SPEED_32, Action.SPEED_64};
    private static final int[] SPEED_SLOTS = {1, 2, 3, 4, 5, 10, 11, 12, 13, 14};

    private final NamespacedKey key;
    private Inventory inventory;

    private SpeedSelectGui(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "erp-speed-gui-action");
    }

    /** Builds and opens the speed picker for the viewer's current playback speed. */
    public static void open(Player viewer, Plugin plugin, PlaybackSession session) {
        SpeedSelectGui gui = new SpeedSelectGui(plugin);
        viewer.openInventory(gui.build(session));
    }

    private Inventory build(PlaybackSession session) {
        inventory = Bukkit.createInventory(this, 27, Component.text("⚡ Geschwindigkeit"));
        fill(Material.GRAY_STAINED_GLASS_PANE);

        double speed = session.speed();
        for (int i = 0; i < SPEED_ACTIONS.length; i++) {
            Action action = SPEED_ACTIONS[i];
            boolean active = Math.abs(action.speedValue() - speed) < 0.001;
            inventory.setItem(SPEED_SLOTS[i], button(Material.SUGAR,
                    Component.text((active ? "» " : "") + formatSpeed(action.speedValue())
                            + "x" + (active ? " «" : ""),
                            active ? NamedTextColor.YELLOW : NamedTextColor.WHITE),
                    List.of(Component.text("Setzt die Wiedergabegeschwindigkeit.", NamedTextColor.GRAY)),
                    action, active));
        }

        inventory.setItem(20, button(Material.REPEATER,
                Component.text("« -1 Tick", NamedTextColor.AQUA),
                List.of(Component.text("Pausiert und springt einen Tick zurück.", NamedTextColor.GRAY)),
                Action.TICK_BACK, false));
        inventory.setItem(24, button(Material.COMPARATOR,
                Component.text("+1 Tick »", NamedTextColor.AQUA),
                List.of(Component.text("Pausiert und springt einen Tick vor.", NamedTextColor.GRAY)),
                Action.TICK_FORWARD, false));
        inventory.setItem(22, button(Material.BARRIER,
                Component.text("✖ Schließen", NamedTextColor.RED),
                List.of(Component.text("Schließt dieses Menü.", NamedTextColor.GRAY)),
                Action.CLOSE, false));

        return inventory;
    }

    private void fill(Material material) {
        ItemStack pane = new ItemStack(material);
        pane.editMeta(meta -> meta.displayName(Component.text(" ")));
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, pane);
        }
    }

    private ItemStack button(Material material, Component name, List<Component> lore,
                             Action action, boolean glint) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name);
            meta.lore(lore);
            meta.setEnchantmentGlintOverride(glint);
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, action.name());
        });
        return item;
    }

    private static String formatSpeed(double speed) {
        return speed == Math.floor(speed) ? String.valueOf((int) speed) : String.valueOf(speed);
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
