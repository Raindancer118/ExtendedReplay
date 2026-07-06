package dev.raindancer118.extendedreplay.core.model;

/**
 * Full player inventory at a point in time, recorded event-based when the inventory
 * becomes dirty and its content hash actually changed.
 *
 * <p>{@code slots} holds serialized ItemStack bytes indexed like
 * {@code PlayerInventory#getContents()} (0–8 hotbar, 9–35 main, 36–39 armor, 40 offhand);
 * null entries are empty slots. {@code cursor} is the item on the cursor during an open
 * inventory view, if any.</p>
 */
public record InventorySnapshot(
        int tick,
        int playerIndex,
        byte[][] slots,
        byte[] cursor,        // may be null
        byte selectedSlot,
        short health,         // health * 10 fixed point
        byte food,
        int xpLevel,
        long contentHash,
        String cause) {
}
