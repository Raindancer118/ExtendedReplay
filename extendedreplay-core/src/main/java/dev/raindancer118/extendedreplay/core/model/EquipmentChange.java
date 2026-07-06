package dev.raindancer118.extendedreplay.core.model;

/**
 * Visible equipment of a player at a point in time. Recorded only when it changes;
 * {@link PlayerFrame}s reference it through {@code equipmentVersion}.
 *
 * <p>Item slots contain platform-serialized ItemStack bytes (Paper's
 * {@code ItemStack.serializeAsBytes()}) or null for empty. Serialization happens off
 * the hot path (equipment changes are rare compared to movement).</p>
 */
public record EquipmentChange(
        int tick,
        int playerIndex,
        int version,
        byte[] mainHand,
        byte[] offHand,
        byte[] helmet,
        byte[] chestplate,
        byte[] leggings,
        byte[] boots) {
}
