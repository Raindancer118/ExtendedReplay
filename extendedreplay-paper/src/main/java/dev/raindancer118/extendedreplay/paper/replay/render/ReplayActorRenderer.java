package dev.raindancer118.extendedreplay.paper.replay.render;

import dev.raindancer118.extendedreplay.core.model.EquipmentChange;
import dev.raindancer118.extendedreplay.core.model.PlayerFrame;
import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Renders recorded players as in-world actors on the replay server. Implementations:
 * native {@link MannequinRenderer} (preferred) and {@link ArmorStandRenderer} (fallback).
 *
 * <p>All methods must be called on the main thread (region thread on Folia).</p>
 */
public interface ReplayActorRenderer {

    /** Spawns the actor for a recorded player. Idempotent per player index. */
    void spawnActor(World world, int playerIndex, PlayerProfileData profile, Location location);

    void despawnActor(int playerIndex);

    void despawnAll();

    /** Applies position, rotation and pose of one 20 Hz frame. */
    void applyFrame(int playerIndex, World world, PlayerFrame frame);

    /** Applies serialized equipment. */
    void applyEquipment(int playerIndex, EquipmentChange equipment);

    void setNameTagVisible(boolean visible);

    boolean isSpawned(int playerIndex);

    /** Current location of the actor, or null when not spawned. */
    Location actorLocation(int playerIndex);
}
