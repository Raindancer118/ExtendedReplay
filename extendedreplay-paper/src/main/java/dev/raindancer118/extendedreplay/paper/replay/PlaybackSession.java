package dev.raindancer118.extendedreplay.paper.replay;

import dev.raindancer118.extendedreplay.core.model.ContainerSnapshot;
import dev.raindancer118.extendedreplay.core.model.EquipmentChange;
import dev.raindancer118.extendedreplay.core.model.InventorySnapshot;
import dev.raindancer118.extendedreplay.core.model.PlayerFrame;
import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;
import dev.raindancer118.extendedreplay.paper.replay.render.ReplayActorRenderer;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One playback of a stored session: a timeline of packets indexed by tick, applied into
 * a playback world through the renderer, driven by {@link #advance()} once per server
 * tick. Supports speed, pause, seeking in both directions and state inspection at the
 * current playback time.
 *
 * <p>Main thread only (world + entity access).</p>
 */
public final class PlaybackSession {

    private final UUID sessionId;
    private final String sessionName;
    private final World world;
    private final ReplayActorRenderer renderer;
    private final WorldStateApplier blockApplier;

    private final NavigableMap<Integer, List<ReplayPacket>> timeline = new TreeMap<>();
    private final Map<Integer, PlayerProfileData> profiles = new HashMap<>();
    private final Map<Integer, EquipmentChange> equipmentByVersion = new HashMap<>();
    private final Map<Integer, NavigableMap<Integer, InventorySnapshot>> inventories = new HashMap<>();
    private final Map<String, NavigableMap<Integer, ContainerSnapshot>> containers = new HashMap<>();
    private final Map<Integer, PlayerFrame> lastFrames = new HashMap<>();
    private final Map<Integer, Integer> appliedEquipmentVersion = new HashMap<>();
    private final int lastTick;

    private final List<UUID> viewers = new ArrayList<>();

    private double cursor;
    private int appliedUpTo = -1;
    private double speed = 1.0;
    private boolean paused = true;
    private Integer followedPlayerIndex;

    public PlaybackSession(UUID sessionId, String sessionName, World world,
                           ReplayActorRenderer renderer, List<ReplayPacket> packets) {
        this.sessionId = sessionId;
        this.sessionName = sessionName;
        this.world = world;
        this.renderer = renderer;
        this.blockApplier = new WorldStateApplier(world);

        int maxTick = 0;
        for (ReplayPacket packet : packets) {
            if (packet instanceof ReplayPacket.PlayerProfile p) {
                profiles.put(p.profile().playerIndex(), p.profile());
            }
            if (packet instanceof ReplayPacket.EquipmentChangePacket p) {
                equipmentByVersion.put(p.change().version(), p.change());
            }
            if (packet instanceof ReplayPacket.InventorySnapshotPacket p) {
                inventories.computeIfAbsent(p.snapshot().playerIndex(), k -> new TreeMap<>())
                        .put(p.snapshot().tick(), p.snapshot());
            }
            if (packet instanceof ReplayPacket.ContainerSnapshotPacket p) {
                containers.computeIfAbsent(p.snapshot().containerId(), k -> new TreeMap<>())
                        .put(p.snapshot().tick(), p.snapshot());
            }
            int tick = Math.max(packet.tick(), 0);
            maxTick = Math.max(maxTick, tick);
            timeline.computeIfAbsent(tick, k -> new ArrayList<>()).add(packet);
        }
        this.lastTick = maxTick;
    }

    // --- info ---

    public UUID sessionId() {
        return sessionId;
    }

    public String sessionName() {
        return sessionName;
    }

    public World world() {
        return world;
    }

    public ReplayActorRenderer renderer() {
        return renderer;
    }

    public int currentTick() {
        return (int) Math.floor(cursor);
    }

    public int lastTickOfSession() {
        return lastTick;
    }

    public double speed() {
        return speed;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isFinished() {
        return currentTick() >= lastTick;
    }

    public Map<Integer, PlayerProfileData> profiles() {
        return profiles;
    }

    public List<UUID> viewers() {
        return viewers;
    }

    public void addViewer(UUID playerId) {
        if (!viewers.contains(playerId)) {
            viewers.add(playerId);
        }
    }

    public void removeViewer(UUID playerId) {
        viewers.remove(playerId);
    }

    // --- controls ---

    public void play() {
        paused = false;
    }

    public void pause() {
        paused = true;
    }

    public void setSpeed(double speed) {
        this.speed = Math.max(0.05, Math.min(16.0, speed));
    }

    public void follow(Integer playerIndex) {
        this.followedPlayerIndex = playerIndex;
    }

    public Integer followedPlayer() {
        return followedPlayerIndex;
    }

    /**
     * Seeks to a tick. Backward seeks restore all modified blocks and rebuild state from
     * the beginning — correct, and bounded by the number of recorded block changes.
     */
    public void seek(int targetTick) {
        int target = Math.max(0, Math.min(targetTick, lastTick));
        if (target < appliedUpTo) {
            blockApplier.restoreAll();
            renderer.despawnAll();
            lastFrames.clear();
            appliedEquipmentVersion.clear();
            appliedUpTo = -1;
        }
        applyRange(appliedUpTo + 1, target);
        cursor = target;
        appliedUpTo = target;
        refreshActors();
    }

    /** Called once per server tick by the playback manager. */
    public void advance() {
        if (paused || isFinished()) {
            return;
        }
        cursor = Math.min(cursor + speed, lastTick);
        int target = (int) Math.floor(cursor);
        if (target > appliedUpTo) {
            applyRange(appliedUpTo + 1, target);
            appliedUpTo = target;
        }
    }

    private void applyRange(int fromTick, int toTick) {
        if (fromTick > toTick) {
            return;
        }
        boolean fastForward = (toTick - fromTick) > 5;
        for (List<ReplayPacket> packets : timeline.subMap(fromTick, true, toTick, true).values()) {
            for (ReplayPacket packet : packets) {
                applyPacket(packet, fastForward);
            }
        }
        if (fastForward) {
            refreshActors();
        }
    }

    /**
     * @param fastForward during seeks only the final state matters: block changes are
     *                    applied, frames only remembered (renderer update happens once
     *                    at the end via {@link #refreshActors()})
     */
    private void applyPacket(ReplayPacket packet, boolean fastForward) {
        switch (packet) {
            case ReplayPacket.PlayerFramePacket p -> {
                PlayerFrame frame = p.frame();
                lastFrames.put(frame.playerIndex(), frame);
                if (!fastForward) {
                    ensureSpawned(frame);
                    renderer.applyFrame(frame.playerIndex(), world, frame);
                    applyEquipmentVersion(frame.playerIndex(), frame.equipmentVersion());
                }
            }
            case ReplayPacket.BlockChangePacket p -> blockApplier.apply(p.change());
            case ReplayPacket.PlayerQuit p -> {
                if (!fastForward) {
                    renderer.despawnActor(p.playerIndex());
                }
                lastFrames.remove(p.playerIndex());
            }
            default -> {
                // profiles/equipment/inventories/containers are pre-indexed; events are
                // browsed through the database, not re-emitted during playback
            }
        }
    }

    private void ensureSpawned(PlayerFrame frame) {
        if (renderer.isSpawned(frame.playerIndex())) {
            return;
        }
        PlayerProfileData profile = profiles.get(frame.playerIndex());
        if (profile == null) {
            profile = new PlayerProfileData(frame.playerIndex(), UUID.randomUUID(),
                    "Player-" + frame.playerIndex(), null, null);
        }
        renderer.spawnActor(world, frame.playerIndex(), profile,
                new Location(world, frame.x(), frame.y(), frame.z()));
    }

    /** Syncs all actors to their last known frame (after seeks). */
    private void refreshActors() {
        for (Map.Entry<Integer, PlayerFrame> entry : lastFrames.entrySet()) {
            PlayerFrame frame = entry.getValue();
            ensureSpawned(frame);
            renderer.applyFrame(entry.getKey(), world, frame);
            applyEquipmentVersion(entry.getKey(), frame.equipmentVersion());
        }
    }

    private void applyEquipmentVersion(int playerIndex, int version) {
        Integer applied = appliedEquipmentVersion.get(playerIndex);
        if (applied != null && applied == version) {
            return;
        }
        EquipmentChange equipment = equipmentByVersion.get(version);
        if (equipment != null) {
            renderer.applyEquipment(playerIndex, equipment);
            appliedEquipmentVersion.put(playerIndex, version);
        }
    }

    // --- inspection ---

    /** Inventory of the player as it was at the current playback tick. */
    public InventorySnapshot inventoryAt(int playerIndex, int tick) {
        NavigableMap<Integer, InventorySnapshot> byTick = inventories.get(playerIndex);
        if (byTick == null) {
            return null;
        }
        Map.Entry<Integer, InventorySnapshot> entry = byTick.floorEntry(tick);
        return entry != null ? entry.getValue() : null;
    }

    /** Container content as it was at the current playback tick. */
    public ContainerSnapshot containerAt(String containerId, int tick) {
        NavigableMap<Integer, ContainerSnapshot> byTick = containers.get(containerId);
        if (byTick == null) {
            return null;
        }
        Map.Entry<Integer, ContainerSnapshot> entry = byTick.floorEntry(tick);
        return entry != null ? entry.getValue() : null;
    }

    public Map<String, NavigableMap<Integer, ContainerSnapshot>> allContainers() {
        return containers;
    }

    /** Index of the recorded player with the given name, or null. */
    public Integer playerIndexByName(String name) {
        for (PlayerProfileData profile : profiles.values()) {
            if (profile.name().equalsIgnoreCase(name)) {
                return profile.playerIndex();
            }
        }
        return null;
    }

    /** Where the actor currently stands, falling back to the last frame position. */
    public Location actorLocation(int playerIndex) {
        Location live = renderer.actorLocation(playerIndex);
        if (live != null) {
            return live;
        }
        PlayerFrame frame = lastFrames.get(playerIndex);
        return frame != null
                ? new Location(world, frame.x(), frame.y(), frame.z()) : null;
    }

    public Component statusLine() {
        return Component.text(String.format(java.util.Locale.ROOT,
                "▶ %s  %s / %s  (%.2fx%s)", sessionName,
                formatTicks(currentTick()), formatTicks(lastTick), speed,
                paused ? ", paused" : ""));
    }

    public static String formatTicks(int ticks) {
        int totalSeconds = ticks / 20;
        return String.format(java.util.Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    /** Restores the world and removes all actors. */
    public void close() {
        renderer.despawnAll();
        blockApplier.restoreAll();
        viewers.clear();
    }
}
