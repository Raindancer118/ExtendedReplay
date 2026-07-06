package dev.raindancer118.extendedreplay.core.model;

/**
 * A single block delta. {@code blockData} is the serialized BlockData string
 * (e.g. {@code minecraft:chest[facing=north]}); compact enough at block-change rates
 * and self-describing across versions.
 */
public record BlockChange(
        int tick,
        String worldName,
        int x,
        int y,
        int z,
        String blockData,
        int actorPlayerIndex,  // -1 when not player-caused
        String cause) {
}
