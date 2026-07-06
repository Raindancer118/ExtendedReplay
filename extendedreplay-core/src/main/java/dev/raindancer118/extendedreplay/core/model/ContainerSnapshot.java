package dev.raindancer118.extendedreplay.core.model;

/**
 * Full content of a block container (chest, barrel, supply drop…) at a point in time.
 * The container id is stable: {@code world + ":" + x + "," + y + "," + z}, so the same
 * block always maps to the same logical container across the session.
 */
public record ContainerSnapshot(
        int tick,
        String containerId,
        String worldName,
        int x,
        int y,
        int z,
        String containerType,
        byte[][] slots,
        int lastInteractionPlayerIndex,  // -1 when unknown
        long contentHash,
        String cause) {

    public static String containerId(String worldName, int x, int y, int z) {
        return worldName + ":" + x + "," + y + "," + z;
    }
}
