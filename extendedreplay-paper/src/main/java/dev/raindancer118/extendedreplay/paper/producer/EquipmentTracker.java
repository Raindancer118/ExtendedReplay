package dev.raindancer118.extendedreplay.paper.producer;

import dev.raindancer118.extendedreplay.core.model.EquipmentChange;
import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;
import dev.raindancer118.extendedreplay.paper.util.ItemBytes;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects visible equipment changes cheaply and records full serialized equipment only
 * when something actually changed.
 *
 * <p>The periodic check computes a fingerprint from material + amount + damage-ish
 * identity (no NBT serialization). Only on fingerprint change the six slots are
 * serialized and an {@link EquipmentChange} is enqueued.</p>
 */
public final class EquipmentTracker {

    private final ProducerManager producer;
    private final Map<UUID, Long> fingerprints = new ConcurrentHashMap<>();

    EquipmentTracker(ProducerManager producer) {
        this.producer = producer;
    }

    /** Called from the main-thread tick loop every 10 ticks. */
    void checkWorld(ActiveSession session, World world, int tick) {
        for (Player player : world.getPlayers()) {
            if (!session.isTracked(player.getUniqueId())) {
                continue;
            }
            long fingerprint = fingerprint(player);
            Long previous = fingerprints.put(player.getUniqueId(), fingerprint);
            if (previous == null || previous != fingerprint) {
                capture(session, player, tick);
            }
        }
    }

    /** Forces a capture, e.g. on player registration. Main thread only. */
    public void captureNow(ActiveSession session, Player player) {
        fingerprints.put(player.getUniqueId(), fingerprint(player));
        capture(session, player, session.currentTick());
    }

    private void capture(ActiveSession session, Player player, int tick) {
        EntityEquipment equipment = player.getEquipment();
        int version = session.bumpEquipmentVersion(player.getUniqueId());
        producer.offer(new ReplayPacket.EquipmentChangePacket(session.sessionId(),
                new EquipmentChange(tick,
                        session.playerIndex(player.getUniqueId()),
                        version,
                        ItemBytes.serialize(equipment.getItemInMainHand()),
                        ItemBytes.serialize(equipment.getItemInOffHand()),
                        ItemBytes.serialize(equipment.getHelmet()),
                        ItemBytes.serialize(equipment.getChestplate()),
                        ItemBytes.serialize(equipment.getLeggings()),
                        ItemBytes.serialize(equipment.getBoots()))));
    }

    private static long fingerprint(Player player) {
        EntityEquipment equipment = player.getEquipment();
        long hash = 17;
        hash = hash * 31 + slotId(equipment.getItemInMainHand());
        hash = hash * 31 + slotId(equipment.getItemInOffHand());
        hash = hash * 31 + slotId(equipment.getHelmet());
        hash = hash * 31 + slotId(equipment.getChestplate());
        hash = hash * 31 + slotId(equipment.getLeggings());
        hash = hash * 31 + slotId(equipment.getBoots());
        return hash;
    }

    private static long slotId(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return 0;
        }
        // cheap identity: type + amount + meta presence; NBT-only changes are caught by
        // the inventory dirty events which force a capture through markEquipmentDirty
        return ((long) item.getType().ordinal() << 16)
                ^ ((long) item.getAmount() << 8)
                ^ (item.hasItemMeta() ? 1 : 0);
    }

    /** Invalidates the fingerprint so the next periodic check re-captures. */
    public void markEquipmentDirty(UUID playerId) {
        fingerprints.remove(playerId);
    }

    public void forget(UUID playerId) {
        fingerprints.remove(playerId);
    }
}
