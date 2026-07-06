package dev.raindancer118.extendedreplay.core.protocol;

import dev.raindancer118.extendedreplay.core.model.BlockChange;
import dev.raindancer118.extendedreplay.core.model.ContainerSnapshot;
import dev.raindancer118.extendedreplay.core.model.EntityFrame;
import dev.raindancer118.extendedreplay.core.model.EquipmentChange;
import dev.raindancer118.extendedreplay.core.model.InventorySnapshot;
import dev.raindancer118.extendedreplay.core.model.PlayerFrame;
import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import dev.raindancer118.extendedreplay.core.model.TimelineEvent;

import java.util.Map;
import java.util.UUID;

/**
 * All packets of the replay protocol. Packets flow producer → replay server and are
 * also the unit stored inside segment files.
 */
public sealed interface ReplayPacket {

    PacketType type();

    /** Session tick this packet belongs to; -1 for session-independent packets. */
    default int tick() {
        return -1;
    }

    /** Critical packets are never dropped under queue pressure. */
    default boolean critical() {
        return true;
    }

    record Handshake(int protocolVersion, String authToken, String serverName) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.HANDSHAKE;
        }
    }

    record HandshakeAck(int protocolVersion, boolean accepted, String message) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.HANDSHAKE_ACK;
        }
    }

    record SessionStart(UUID sessionId, String name, String externalKey, String worldName,
                        long startedAtMillis, int formatVersion,
                        double minX, double minY, double minZ,
                        double maxX, double maxY, double maxZ, boolean hasBounds,
                        Map<String, String> metadata) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.SESSION_START;
        }
    }

    record SessionEnd(UUID sessionId, int tick, String reason) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.SESSION_END;
        }
    }

    record SnapshotReference(UUID sessionId, String snapshotName, String snapshotSha256) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.SNAPSHOT_REFERENCE;
        }
    }

    record PlayerProfile(UUID sessionId, PlayerProfileData profile) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.PLAYER_PROFILE;
        }
    }

    record PlayerFramePacket(UUID sessionId, PlayerFrame frame) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.PLAYER_FRAME;
        }

        @Override
        public int tick() {
            return frame.tick();
        }
    }

    record EquipmentChangePacket(UUID sessionId, EquipmentChange change) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.EQUIPMENT_CHANGE;
        }

        @Override
        public int tick() {
            return change.tick();
        }
    }

    record InventorySnapshotPacket(UUID sessionId, InventorySnapshot snapshot) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.INVENTORY_SNAPSHOT;
        }

        @Override
        public int tick() {
            return snapshot.tick();
        }
    }

    record ContainerSnapshotPacket(UUID sessionId, ContainerSnapshot snapshot) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.CONTAINER_SNAPSHOT;
        }

        @Override
        public int tick() {
            return snapshot.tick();
        }
    }

    record BlockChangePacket(UUID sessionId, BlockChange change) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.BLOCK_CHANGE;
        }

        @Override
        public int tick() {
            return change.tick();
        }
    }

    record EntitySpawn(UUID sessionId, int tick, int entityId, String entityType,
                       double x, double y, double z, float yaw, float pitch,
                       Map<String, String> metadata) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.ENTITY_SPAWN;
        }
    }

    record EntityDespawn(UUID sessionId, int tick, int entityId) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.ENTITY_DESPAWN;
        }
    }

    record EntityFramePacket(UUID sessionId, EntityFrame frame) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.ENTITY_FRAME;
        }

        @Override
        public int tick() {
            return frame.tick();
        }

        @Override
        public boolean critical() {
            return false;
        }
    }

    record TimelineEventPacket(UUID sessionId, PacketType packetType, TimelineEvent event) implements ReplayPacket {
        public TimelineEventPacket {
            if (packetType != PacketType.PROJECTILE_EVENT && packetType != PacketType.ITEM_EVENT
                    && packetType != PacketType.DAMAGE_EVENT && packetType != PacketType.DEATH_EVENT
                    && packetType != PacketType.KILL_EVENT && packetType != PacketType.CHAT_EVENT
                    && packetType != PacketType.CUSTOM_EVENT && packetType != PacketType.BOOKMARK
                    && packetType != PacketType.WORLD_STATE_CHANGE) {
                throw new IllegalArgumentException("Not a timeline event type: " + packetType);
            }
        }

        @Override
        public PacketType type() {
            return packetType;
        }

        @Override
        public int tick() {
            return event.tick();
        }

        @Override
        public boolean critical() {
            return event.critical();
        }
    }

    record Heartbeat(long sentAtMillis) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.HEARTBEAT;
        }

        @Override
        public boolean critical() {
            return false;
        }
    }

    record Metrics(Map<String, String> values) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.METRICS;
        }

        @Override
        public boolean critical() {
            return false;
        }
    }

    record PlayerJoin(UUID sessionId, int tick, int playerIndex) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.PLAYER_JOIN;
        }
    }

    record PlayerQuit(UUID sessionId, int tick, int playerIndex) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.PLAYER_QUIT;
        }
    }

    /** Marks a tick range where non-critical data was dropped under pressure. */
    record DegradationMarker(UUID sessionId, int tick, int endTick, int level) implements ReplayPacket {
        @Override
        public PacketType type() {
            return PacketType.DEGRADATION_MARKER;
        }
    }
}
