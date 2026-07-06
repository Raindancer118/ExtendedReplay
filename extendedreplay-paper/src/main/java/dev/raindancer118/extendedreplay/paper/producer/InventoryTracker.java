package dev.raindancer118.extendedreplay.paper.producer;

import dev.raindancer118.extendedreplay.core.model.ContainerSnapshot;
import dev.raindancer118.extendedreplay.core.model.InventorySnapshot;
import dev.raindancer118.extendedreplay.core.model.PlayerFrame;
import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;
import dev.raindancer118.extendedreplay.paper.util.ItemBytes;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dirty-state snapshotting of player inventories and block containers.
 *
 * <p>Bukkit events mark inventories/containers dirty; at end of tick each dirty target
 * is snapshotted once. A content hash suppresses duplicate snapshots caused by event
 * chains (shift-click → pickup → slot change …). Serialization only happens for dirty
 * targets, never in the 20 Hz frame path.</p>
 *
 * <p>All methods run on the main thread.</p>
 */
public final class InventoryTracker {

    /** A dirty block container: world + position + cause. */
    private record DirtyContainer(String world, int x, int y, int z, String cause) {
    }

    private final ProducerManager producer;
    private final Map<UUID, String> dirtyPlayers = new LinkedHashMap<>();
    private final Map<String, DirtyContainer> dirtyContainers = new LinkedHashMap<>();
    private final Map<UUID, Long> lastPlayerHash = new HashMap<>();
    private final Map<String, Long> lastContainerHash = new HashMap<>();

    InventoryTracker(ProducerManager producer) {
        this.producer = producer;
    }

    public void markDirty(UUID playerId, String cause) {
        dirtyPlayers.putIfAbsent(playerId, cause);
    }

    public void markContainerDirty(Block block, String cause) {
        String id = ContainerSnapshot.containerId(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        dirtyContainers.putIfAbsent(id, new DirtyContainer(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), cause));
    }

    /** Snapshot the player's inventory immediately (e.g. death, before drops spawn). */
    public void snapshotNow(Player player, String cause) {
        producer.sessionOf(player).ifPresent(session ->
                snapshotPlayer(session, player, cause, true));
        dirtyPlayers.remove(player.getUniqueId());
    }

    /** End-of-tick flush, called from the capture tick task. */
    void flushEndOfTick() {
        if (!dirtyPlayers.isEmpty()) {
            for (Map.Entry<UUID, String> entry : Map.copyOf(dirtyPlayers).entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    producer.sessionOf(player).ifPresent(session ->
                            snapshotPlayer(session, player, entry.getValue(), false));
                }
            }
            dirtyPlayers.clear();
        }
        if (!dirtyContainers.isEmpty()) {
            for (DirtyContainer dirty : Map.copyOf(dirtyContainers).values()) {
                snapshotContainer(dirty);
            }
            dirtyContainers.clear();
        }
    }

    private void snapshotPlayer(ActiveSession session, Player player, String cause, boolean force) {
        if (!producer.config().captureInventories()) {
            return;
        }
        var inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents(); // 0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand
        byte[][] slots = new byte[contents.length][];
        for (int i = 0; i < contents.length; i++) {
            slots[i] = ItemBytes.serialize(contents[i]);
        }
        byte[] cursor = ItemBytes.serialize(player.getOpenInventory().getCursor());
        long hash = ItemBytes.contentHash(slots, cursor);

        UUID playerId = player.getUniqueId();
        Long previous = lastPlayerHash.get(playerId);
        if (!force && previous != null && previous == hash) {
            return;
        }
        lastPlayerHash.put(playerId, hash);

        producer.offer(new ReplayPacket.InventorySnapshotPacket(session.sessionId(),
                new InventorySnapshot(session.currentTick(),
                        session.playerIndex(playerId),
                        slots, cursor,
                        (byte) inventory.getHeldItemSlot(),
                        PlayerFrame.packHealth(player.getHealth()),
                        (byte) player.getFoodLevel(),
                        player.getLevel(),
                        hash, cause)));
    }

    private void snapshotContainer(DirtyContainer dirty) {
        if (!producer.config().captureContainers()) {
            return;
        }
        var world = Bukkit.getWorld(dirty.world());
        if (world == null) {
            return;
        }
        var session = producer.sessionForWorld(dirty.world()).orElse(null);
        if (session == null
                || !session.inBounds(dirty.world(), dirty.x(), dirty.y(), dirty.z())) {
            return;
        }
        Block block = world.getBlockAt(dirty.x(), dirty.y(), dirty.z());
        if (!(block.getState() instanceof Container container)) {
            return; // container destroyed since being marked; block change covers it
        }
        Inventory inventory = container.getInventory();
        ItemStack[] contents = inventory.getContents();
        byte[][] slots = new byte[contents.length][];
        for (int i = 0; i < contents.length; i++) {
            slots[i] = ItemBytes.serialize(contents[i]);
        }
        long hash = ItemBytes.contentHash(slots, null);

        String id = ContainerSnapshot.containerId(dirty.world(), dirty.x(), dirty.y(), dirty.z());
        Long previous = lastContainerHash.get(id);
        if (previous != null && previous == hash) {
            return;
        }
        lastContainerHash.put(id, hash);

        producer.offer(new ReplayPacket.ContainerSnapshotPacket(session.sessionId(),
                new ContainerSnapshot(session.currentTick(), id, dirty.world(),
                        dirty.x(), dirty.y(), dirty.z(),
                        block.getType().name(), slots, -1, hash, dirty.cause())));
    }

    public void forget(UUID playerId) {
        dirtyPlayers.remove(playerId);
        lastPlayerHash.remove(playerId);
    }
}
