package dev.raindancer118.extendedreplay.paper.producer;

import dev.raindancer118.extendedreplay.api.ReplayBounds;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mutable state of one recording session on the producer. Thread-safe: the tick counter
 * advances on the main thread, lookups happen from commands and the pipeline.
 */
public final class ActiveSession {

    private final UUID sessionId;
    private final String name;
    private final String externalKey;
    private final String worldName;
    private final ReplayBounds bounds;
    private final long startedAtMillis;
    private final AtomicInteger tick = new AtomicInteger();
    private final Map<UUID, Integer> playerIndexes = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> equipmentVersions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> entityIndexes = new ConcurrentHashMap<>();
    private final AtomicInteger nextPlayerIndex = new AtomicInteger();
    private final AtomicInteger nextEquipmentVersion = new AtomicInteger();
    private final AtomicInteger nextEntityIndex = new AtomicInteger();
    private volatile boolean active = true;

    public ActiveSession(UUID sessionId, String name, String externalKey, String worldName,
                         ReplayBounds bounds) {
        this.sessionId = sessionId;
        this.name = name;
        this.externalKey = externalKey;
        this.worldName = worldName;
        this.bounds = bounds;
        this.startedAtMillis = System.currentTimeMillis();
    }

    public UUID sessionId() {
        return sessionId;
    }

    public String name() {
        return name;
    }

    public String externalKey() {
        return externalKey;
    }

    public String worldName() {
        return worldName;
    }

    /** May be null: whole world. */
    public ReplayBounds bounds() {
        return bounds;
    }

    public long startedAtMillis() {
        return startedAtMillis;
    }

    public int currentTick() {
        return tick.get();
    }

    public int advanceTick() {
        return tick.incrementAndGet();
    }

    public boolean isActive() {
        return active;
    }

    public void deactivate() {
        active = false;
    }

    /** Returns the stable per-session index of the player, or -1 if not registered. */
    public int playerIndex(UUID playerId) {
        Integer index = playerIndexes.get(playerId);
        return index != null ? index : -1;
    }

    /** Registers the player if unknown; returns true when this call created the index. */
    public boolean registerPlayer(UUID playerId) {
        return playerIndexes.putIfAbsent(playerId, nextPlayerIndex.getAndIncrement()) == null;
    }

    public boolean isTracked(UUID playerId) {
        return playerIndexes.containsKey(playerId);
    }

    public Map<UUID, Integer> trackedPlayers() {
        return playerIndexes;
    }

    public int currentEquipmentVersion(UUID playerId) {
        return equipmentVersions.getOrDefault(playerId, 0);
    }

    public int bumpEquipmentVersion(UUID playerId) {
        int version = nextEquipmentVersion.incrementAndGet();
        equipmentVersions.put(playerId, version);
        return version;
    }

    /** Returns the stable per-session id of the entity, or -1 if not tracked. */
    public int entityIndex(UUID entityId) {
        Integer index = entityIndexes.get(entityId);
        return index != null ? index : -1;
    }

    /** Tracks the entity if unknown; returns true when this call created the id. */
    public boolean registerEntity(UUID entityId) {
        return entityIndexes.putIfAbsent(entityId, nextEntityIndex.getAndIncrement()) == null;
    }

    public boolean isTrackedEntity(UUID entityId) {
        return entityIndexes.containsKey(entityId);
    }

    /** Stops tracking; returns the id it had, or -1. */
    public int forgetEntity(UUID entityId) {
        Integer index = entityIndexes.remove(entityId);
        return index != null ? index : -1;
    }

    public boolean inBounds(String world, double x, double y, double z) {
        if (!worldName.equals(world)) {
            return false;
        }
        return bounds == null || bounds.contains(x, y, z);
    }
}
