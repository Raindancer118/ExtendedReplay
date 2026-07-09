package dev.raindancer118.extendedreplay.paper.producer;

import dev.raindancer118.extendedreplay.core.model.EntityFrame;
import dev.raindancer118.extendedreplay.core.protocol.PacketIO;
import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures non-player entities of a recorded world: spawn/despawn packets plus 20 Hz
 * movement frames for entities that actually moved. Frames are cosmetic (droppable);
 * spawns/despawns are critical.
 *
 * <p>Main thread only, driven by {@link ProducerManager#captureTick} and the
 * add/remove-from-world listeners.</p>
 */
public final class EntityTracker {

    /** Hard per-tick frame budget so huge entity counts can never eat the tick. */
    private static final int MAX_FRAMES_PER_TICK = 500;
    private static final double MOVE_EPSILON_SQUARED = 0.0001; // 0.01 blocks

    private record LastPose(double x, double y, double z, byte yaw, byte pitch) {
    }

    private final ProducerManager producer;
    /** session id -> (entity index -> last emitted pose) */
    private final Map<UUID, Map<Integer, LastPose>> lastPoses = new ConcurrentHashMap<>();

    public EntityTracker(ProducerManager producer) {
        this.producer = producer;
    }

    /** Registers all entities already living in the session world. */
    public void sessionStarted(ActiveSession session, World world) {
        if (!producer.config().captureEntities()) {
            return;
        }
        for (Entity entity : world.getEntities()) {
            track(session, entity);
        }
    }

    public void sessionEnded(UUID sessionId) {
        lastPoses.remove(sessionId);
    }

    /** Called when an entity appears in a recorded world (spawn or chunk load). */
    public void track(ActiveSession session, Entity entity) {
        if (!producer.config().captureEntities()
                || entity instanceof Player
                || !session.inBounds(entity.getWorld().getName(),
                entity.getLocation().getX(), entity.getLocation().getY(),
                entity.getLocation().getZ())) {
            return;
        }
        if (!session.registerEntity(entity.getUniqueId())) {
            return;
        }
        Location loc = entity.getLocation();
        producer.offer(new ReplayPacket.EntitySpawn(session.sessionId(),
                session.currentTick(), session.entityIndex(entity.getUniqueId()),
                entity.getType().getKey().asString(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(),
                spawnMetadata(entity)));
    }

    /** Called when an entity leaves a recorded world (death, despawn, chunk unload). */
    public void untrack(ActiveSession session, Entity entity) {
        int index = session.forgetEntity(entity.getUniqueId());
        if (index < 0) {
            return;
        }
        Map<Integer, LastPose> poses = lastPoses.get(session.sessionId());
        if (poses != null) {
            poses.remove(index);
        }
        producer.offer(new ReplayPacket.EntityDespawn(session.sessionId(),
                session.currentTick(), index));
    }

    /** 20 Hz sampling: one frame per tracked entity that moved since its last frame. */
    public void sampleTick(ActiveSession session, World world, int tick) {
        if (!producer.config().captureEntities()) {
            return;
        }
        Map<Integer, LastPose> poses = lastPoses.computeIfAbsent(session.sessionId(),
                k -> new HashMap<>());
        int frames = 0;
        for (Entity entity : world.getEntities()) {
            if (frames >= MAX_FRAMES_PER_TICK) {
                break;
            }
            if (entity instanceof Player) {
                continue;
            }
            int index = session.entityIndex(entity.getUniqueId());
            if (index < 0) {
                // appeared without an add-to-world event (defensive fallback)
                track(session, entity);
                index = session.entityIndex(entity.getUniqueId());
                if (index < 0) {
                    continue;
                }
            }
            Location loc = entity.getLocation();
            byte yaw = PacketIO.angleToByte(loc.getYaw());
            byte pitch = PacketIO.angleToByte(loc.getPitch());
            LastPose last = poses.get(index);
            if (last != null && yaw == last.yaw() && pitch == last.pitch()
                    && distanceSquared(last, loc) < MOVE_EPSILON_SQUARED) {
                continue;
            }
            poses.put(index, new LastPose(loc.getX(), loc.getY(), loc.getZ(), yaw, pitch));
            producer.offer(new ReplayPacket.EntityFramePacket(session.sessionId(),
                    new EntityFrame(tick, index, loc.getX(), loc.getY(), loc.getZ(),
                            yaw, pitch)));
            frames++;
        }
    }

    private static double distanceSquared(LastPose last, Location loc) {
        double dx = loc.getX() - last.x();
        double dy = loc.getY() - last.y();
        double dz = loc.getZ() - last.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static Map<String, String> spawnMetadata(Entity entity) {
        Map<String, String> metadata = new HashMap<>();
        if (entity.customName() != null) {
            metadata.put("name",
                    PlainTextComponentSerializer.plainText().serialize(entity.customName()));
        }
        if (entity instanceof Item item) {
            metadata.put("item", item.getItemStack().getType().getKey().asString());
            metadata.put("amount", Integer.toString(item.getItemStack().getAmount()));
        }
        return metadata.isEmpty() ? Map.of() : metadata;
    }
}
