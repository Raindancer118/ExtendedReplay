package dev.raindancer118.extendedreplay.paper.replay.render;

import dev.raindancer118.extendedreplay.core.model.EntityFrame;
import dev.raindancer118.extendedreplay.core.protocol.PacketIO;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Renders recorded non-player entities in a playback/mirror world: real entities of the
 * recorded type, frozen (no AI, no gravity, invulnerable) and moved by recorded frames.
 *
 * <p>Main thread only.</p>
 */
public final class EntityActorRenderer {

    private final Map<Integer, Entity> actors = new HashMap<>();

    /** Spawns the actor for a recorded entity id. Idempotent; unspawnable types are skipped. */
    public void spawn(World world, int entityId, String entityType, Location location,
                      Map<String, String> metadata) {
        if (actors.containsKey(entityId)) {
            return;
        }
        NamespacedKey key = NamespacedKey.fromString(entityType);
        if (key == null) {
            return;
        }
        EntityType type = Registry.ENTITY_TYPE.get(key);
        if (type == null || type == EntityType.PLAYER) {
            return;
        }
        Entity entity;
        try {
            if (type == EntityType.ITEM) {
                Material material = metadata.containsKey("item")
                        ? Registry.MATERIAL.get(NamespacedKey.fromString(metadata.get("item")))
                        : null;
                if (material == null || material.isAir()) {
                    return;
                }
                int amount = parseAmount(metadata.get("amount"));
                Item item = world.dropItem(location, new ItemStack(material, amount));
                item.setPickupDelay(Integer.MAX_VALUE);
                item.setUnlimitedLifetime(true);
                item.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                entity = item;
            } else if (type.isSpawnable()) {
                entity = world.spawnEntity(location, type);
            } else {
                return;
            }
        } catch (Exception e) {
            return; // type not spawnable in this context — cosmetic, skip silently
        }
        entity.setPersistent(false);
        entity.setSilent(true);
        entity.setInvulnerable(true);
        entity.setGravity(false);
        if (entity instanceof LivingEntity living) {
            living.setAI(false);
            living.setCollidable(false);
            living.setRemoveWhenFarAway(false);
        }
        if (metadata.containsKey("name")) {
            entity.customName(Component.text(metadata.get("name")));
            entity.setCustomNameVisible(true);
        }
        actors.put(entityId, entity);
    }

    /** Applies one recorded movement frame. */
    public void applyFrame(EntityFrame frame) {
        Entity entity = actors.get(frame.entityId());
        if (entity == null || !entity.isValid()) {
            return;
        }
        Location target = new Location(entity.getWorld(), frame.x(), frame.y(), frame.z(),
                PacketIO.byteToAngle(frame.yaw()), PacketIO.byteToAngle(frame.pitch()));
        entity.teleport(target);
    }

    public boolean isSpawned(int entityId) {
        return actors.containsKey(entityId);
    }

    public void despawn(int entityId) {
        Entity entity = actors.remove(entityId);
        if (entity != null) {
            entity.remove();
        }
    }

    public void despawnAll() {
        for (Entity entity : actors.values()) {
            entity.remove();
        }
        actors.clear();
    }

    private static int parseAmount(String amount) {
        try {
            return amount != null ? Math.max(1, Integer.parseInt(amount)) : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
