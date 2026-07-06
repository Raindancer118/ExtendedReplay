package dev.raindancer118.extendedreplay.core.model;

/** Movement sample of a non-player entity (item, projectile, mob). Cosmetic: droppable. */
public record EntityFrame(
        int tick,
        int entityId,
        double x,
        double y,
        double z,
        byte yaw,
        byte pitch) {
}
