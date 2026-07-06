package dev.raindancer118.extendedreplay.paper.replay;

import dev.raindancer118.extendedreplay.core.model.BlockChange;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies recorded block deltas into a playback/mirror world and remembers the original
 * block states so everything can be restored (backward seeks, session close).
 *
 * <p>Main thread only.</p>
 */
public final class WorldStateApplier {

    private record BlockPos(int x, int y, int z) {
    }

    private final World world;
    private final Map<BlockPos, BlockData> originals = new LinkedHashMap<>();

    public WorldStateApplier(World world) {
        this.world = world;
    }

    public World world() {
        return world;
    }

    public void apply(BlockChange change) {
        applyRaw(change.x(), change.y(), change.z(), change.blockData());
    }

    public void applyRaw(int x, int y, int z, String blockData) {
        BlockData data;
        try {
            data = Bukkit.createBlockData(blockData);
        } catch (IllegalArgumentException e) {
            return; // unknown block data on this version — skip rather than crash playback
        }
        Block block = world.getBlockAt(x, y, z);
        BlockPos pos = new BlockPos(x, y, z);
        originals.putIfAbsent(pos, block.getBlockData());
        block.setBlockData(data, false);
    }

    /** Restores all modified blocks to their pre-playback state. */
    public void restoreAll() {
        for (Map.Entry<BlockPos, BlockData> entry : originals.entrySet()) {
            world.getBlockAt(entry.getKey().x(), entry.getKey().y(), entry.getKey().z())
                    .setBlockData(entry.getValue(), false);
        }
        originals.clear();
    }

    public int modifiedBlocks() {
        return originals.size();
    }
}
