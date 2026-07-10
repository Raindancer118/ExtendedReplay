package dev.raindancer118.extendedreplay.paper.gui;

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
 * Generic yes/no confirmation dialog: a description item plus confirm/cancel buttons.
 * Callers pass plain {@link Runnable}s; {@code GuiListener} runs them on the main thread
 * after closing the inventory. Not wired into any flow yet — a future step (session
 * deletion) will use it.
 */
public final class ConfirmGui implements InventoryHolder {

    private static final int CONFIRM_SLOT = 11;
    private static final int INFO_SLOT = 13;
    private static final int CANCEL_SLOT = 15;

    private final Runnable onConfirm;
    private final Runnable onCancel;
    private Inventory inventory;

    private ConfirmGui(Runnable onConfirm, Runnable onCancel) {
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    /**
     * Builds and opens the confirmation dialog.
     *
     * @param onConfirm run on the main thread if the viewer confirms; may be null
     * @param onCancel  run on the main thread if the viewer explicitly cancels; may be
     *                  null. Closing the inventory without clicking anything does not
     *                  trigger this — only an explicit cancel click does.
     */
    public static void open(Player viewer, Component title, List<Component> description,
                            Runnable onConfirm, Runnable onCancel) {
        ConfirmGui gui = new ConfirmGui(onConfirm, onCancel);
        viewer.openInventory(gui.build(title, description));
    }

    private Inventory build(Component title, List<Component> description) {
        inventory = Bukkit.createInventory(this, 27, title);
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        pane.editMeta(meta -> meta.displayName(Component.text(" ")));
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, pane);
        }

        ItemStack confirm = new ItemStack(Material.GREEN_CONCRETE);
        confirm.editMeta(meta -> meta.displayName(Component.text("✔ Bestätigen", NamedTextColor.GREEN)));
        inventory.setItem(CONFIRM_SLOT, confirm);

        ItemStack info = new ItemStack(Material.PAPER);
        info.editMeta(meta -> {
            meta.displayName(Component.text("ℹ Details", NamedTextColor.AQUA));
            meta.lore(description);
        });
        inventory.setItem(INFO_SLOT, info);

        ItemStack cancel = new ItemStack(Material.RED_CONCRETE);
        cancel.editMeta(meta -> meta.displayName(Component.text("✘ Abbrechen", NamedTextColor.RED)));
        inventory.setItem(CANCEL_SLOT, cancel);

        return inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public boolean isConfirmSlot(int slot) {
        return slot == CONFIRM_SLOT;
    }

    public boolean isCancelSlot(int slot) {
        return slot == CANCEL_SLOT;
    }

    public void runConfirm() {
        if (onConfirm != null) {
            onConfirm.run();
        }
    }

    public void runCancel() {
        if (onCancel != null) {
            onCancel.run();
        }
    }
}
