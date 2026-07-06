package dev.raindancer118.extendedreplay.core.protocol;

import dev.raindancer118.extendedreplay.core.model.BlockChange;
import dev.raindancer118.extendedreplay.core.model.ContainerSnapshot;
import dev.raindancer118.extendedreplay.core.model.EntityFrame;
import dev.raindancer118.extendedreplay.core.model.EquipmentChange;
import dev.raindancer118.extendedreplay.core.model.InventorySnapshot;
import dev.raindancer118.extendedreplay.core.model.PlayerFrame;
import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import dev.raindancer118.extendedreplay.core.model.TimelineEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Binary encoder/decoder of {@link ReplayPacket}s.
 *
 * <p>Frame layout: {@code [varint packetTypeId][payload]}. The codec is symmetric and
 * stateless; unknown packet type ids decode to {@code null} so newer producers keep
 * working against older replay servers.</p>
 */
public final class PacketCodec {

    private PacketCodec() {
    }

    public static byte[] encode(ReplayPacket packet) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
        DataOutputStream out = new DataOutputStream(buffer);
        try {
            PacketIO.writeVarInt(out, packet.type().id());
            encodePayload(out, packet);
        } catch (IOException e) {
            throw new IllegalStateException("In-memory encode failed", e);
        }
        return buffer.toByteArray();
    }

    /** Returns null for unknown packet types. */
    public static ReplayPacket decode(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int typeId = PacketIO.readVarInt(in);
        PacketType type = PacketType.byId(typeId);
        if (type == null) {
            return null;
        }
        return decodePayload(in, type);
    }

    private static void encodePayload(DataOutput out, ReplayPacket packet) throws IOException {
        switch (packet) {
            case ReplayPacket.Handshake p -> {
                PacketIO.writeVarInt(out, p.protocolVersion());
                PacketIO.writeString(out, p.authToken());
                PacketIO.writeString(out, p.serverName());
            }
            case ReplayPacket.HandshakeAck p -> {
                PacketIO.writeVarInt(out, p.protocolVersion());
                out.writeBoolean(p.accepted());
                PacketIO.writeString(out, p.message());
            }
            case ReplayPacket.SessionStart p -> {
                PacketIO.writeUuid(out, p.sessionId());
                PacketIO.writeString(out, p.name());
                PacketIO.writeOptionalString(out, p.externalKey());
                PacketIO.writeString(out, p.worldName());
                out.writeLong(p.startedAtMillis());
                PacketIO.writeVarInt(out, p.formatVersion());
                out.writeBoolean(p.hasBounds());
                if (p.hasBounds()) {
                    out.writeDouble(p.minX());
                    out.writeDouble(p.minY());
                    out.writeDouble(p.minZ());
                    out.writeDouble(p.maxX());
                    out.writeDouble(p.maxY());
                    out.writeDouble(p.maxZ());
                }
                PacketIO.writeStringMap(out, p.metadata());
            }
            case ReplayPacket.SessionEnd p -> {
                PacketIO.writeUuid(out, p.sessionId());
                PacketIO.writeVarInt(out, p.tick());
                PacketIO.writeString(out, p.reason());
            }
            case ReplayPacket.SnapshotReference p -> {
                PacketIO.writeUuid(out, p.sessionId());
                PacketIO.writeString(out, p.snapshotName());
                PacketIO.writeOptionalString(out, p.snapshotSha256());
            }
            case ReplayPacket.PlayerProfile p -> {
                PacketIO.writeUuid(out, p.sessionId());
                PlayerProfileData profile = p.profile();
                PacketIO.writeVarInt(out, profile.playerIndex());
                PacketIO.writeUuid(out, profile.uuid());
                PacketIO.writeString(out, profile.name());
                PacketIO.writeOptionalString(out, profile.skinTextureValue());
                PacketIO.writeOptionalString(out, profile.skinTextureSignature());
            }
            case ReplayPacket.PlayerFramePacket p -> {
                PacketIO.writeUuid(out, p.sessionId());
                PlayerFrame f = p.frame();
                PacketIO.writeVarInt(out, f.tick());
                PacketIO.writeVarInt(out, f.playerIndex());
                out.writeDouble(f.x());
                out.writeDouble(f.y());
                out.writeDouble(f.z());
                out.writeByte(f.yaw());
                out.writeByte(f.pitch());
                out.writeByte(f.headYaw());
                out.writeShort(f.stateFlags());
                out.writeByte(f.selectedSlot());
                out.writeShort(f.health());
                out.writeByte(f.food());
                PacketIO.writeVarInt(out, f.equipmentVersion());
            }
            case ReplayPacket.EquipmentChangePacket p -> {
                PacketIO.writeUuid(out, p.sessionId());
                EquipmentChange c = p.change();
                PacketIO.writeVarInt(out, c.tick());
                PacketIO.writeVarInt(out, c.playerIndex());
                PacketIO.writeVarInt(out, c.version());
                PacketIO.writeOptionalByteArray(out, c.mainHand());
                PacketIO.writeOptionalByteArray(out, c.offHand());
                PacketIO.writeOptionalByteArray(out, c.helmet());
                PacketIO.writeOptionalByteArray(out, c.chestplate());
                PacketIO.writeOptionalByteArray(out, c.leggings());
                PacketIO.writeOptionalByteArray(out, c.boots());
            }
            case ReplayPacket.InventorySnapshotPacket p -> {
                PacketIO.writeUuid(out, p.sessionId());
                InventorySnapshot s = p.snapshot();
                PacketIO.writeVarInt(out, s.tick());
                PacketIO.writeVarInt(out, s.playerIndex());
                writeSlots(out, s.slots());
                PacketIO.writeOptionalByteArray(out, s.cursor());
                out.writeByte(s.selectedSlot());
                out.writeShort(s.health());
                out.writeByte(s.food());
                PacketIO.writeVarInt(out, s.xpLevel());
                out.writeLong(s.contentHash());
                PacketIO.writeString(out, s.cause());
            }
            case ReplayPacket.ContainerSnapshotPacket p -> {
                PacketIO.writeUuid(out, p.sessionId());
                ContainerSnapshot s = p.snapshot();
                PacketIO.writeVarInt(out, s.tick());
                PacketIO.writeString(out, s.containerId());
                PacketIO.writeString(out, s.worldName());
                PacketIO.writeVarInt(out, s.x());
                PacketIO.writeVarInt(out, s.y());
                PacketIO.writeVarInt(out, s.z());
                PacketIO.writeString(out, s.containerType());
                writeSlots(out, s.slots());
                PacketIO.writeVarInt(out, s.lastInteractionPlayerIndex() + 1);
                out.writeLong(s.contentHash());
                PacketIO.writeString(out, s.cause());
            }
            case ReplayPacket.BlockChangePacket p -> {
                PacketIO.writeUuid(out, p.sessionId());
                BlockChange c = p.change();
                PacketIO.writeVarInt(out, c.tick());
                PacketIO.writeString(out, c.worldName());
                PacketIO.writeVarInt(out, c.x());
                PacketIO.writeVarInt(out, c.y());
                PacketIO.writeVarInt(out, c.z());
                PacketIO.writeString(out, c.blockData());
                PacketIO.writeVarInt(out, c.actorPlayerIndex() + 1);
                PacketIO.writeString(out, c.cause());
            }
            case ReplayPacket.EntitySpawn p -> {
                PacketIO.writeUuid(out, p.sessionId());
                PacketIO.writeVarInt(out, p.tick());
                PacketIO.writeVarInt(out, p.entityId());
                PacketIO.writeString(out, p.entityType());
                out.writeDouble(p.x());
                out.writeDouble(p.y());
                out.writeDouble(p.z());
                out.writeByte(PacketIO.angleToByte(p.yaw()));
                out.writeByte(PacketIO.angleToByte(p.pitch()));
                PacketIO.writeStringMap(out, p.metadata());
            }
            case ReplayPacket.EntityDespawn p -> {
                PacketIO.writeUuid(out, p.sessionId());
                PacketIO.writeVarInt(out, p.tick());
                PacketIO.writeVarInt(out, p.entityId());
            }
            case ReplayPacket.EntityFramePacket p -> {
                PacketIO.writeUuid(out, p.sessionId());
                EntityFrame f = p.frame();
                PacketIO.writeVarInt(out, f.tick());
                PacketIO.writeVarInt(out, f.entityId());
                out.writeDouble(f.x());
                out.writeDouble(f.y());
                out.writeDouble(f.z());
                out.writeByte(f.yaw());
                out.writeByte(f.pitch());
            }
            case ReplayPacket.TimelineEventPacket p -> {
                PacketIO.writeUuid(out, p.sessionId());
                TimelineEvent e = p.event();
                PacketIO.writeVarInt(out, e.tick());
                PacketIO.writeString(out, e.eventType());
                PacketIO.writeString(out, e.category());
                PacketIO.writeVarInt(out, e.actorPlayerIndex() + 1);
                PacketIO.writeVarInt(out, e.targetPlayerIndex() + 1);
                out.writeBoolean(e.hasLocation());
                if (e.hasLocation()) {
                    PacketIO.writeString(out, e.worldName());
                    out.writeDouble(e.x());
                    out.writeDouble(e.y());
                    out.writeDouble(e.z());
                }
                out.writeBoolean(e.critical());
                PacketIO.writeStringMap(out, e.metadata());
            }
            case ReplayPacket.Heartbeat p -> out.writeLong(p.sentAtMillis());
            case ReplayPacket.Metrics p -> PacketIO.writeStringMap(out, p.values());
            case ReplayPacket.PlayerJoin p -> {
                PacketIO.writeUuid(out, p.sessionId());
                PacketIO.writeVarInt(out, p.tick());
                PacketIO.writeVarInt(out, p.playerIndex());
            }
            case ReplayPacket.PlayerQuit p -> {
                PacketIO.writeUuid(out, p.sessionId());
                PacketIO.writeVarInt(out, p.tick());
                PacketIO.writeVarInt(out, p.playerIndex());
            }
            case ReplayPacket.DegradationMarker p -> {
                PacketIO.writeUuid(out, p.sessionId());
                PacketIO.writeVarInt(out, p.tick());
                PacketIO.writeVarInt(out, p.endTick());
                PacketIO.writeVarInt(out, p.level());
            }
        }
    }

    private static ReplayPacket decodePayload(DataInput in, PacketType type) throws IOException {
        return switch (type) {
            case HANDSHAKE -> new ReplayPacket.Handshake(
                    PacketIO.readVarInt(in), PacketIO.readString(in), PacketIO.readString(in));
            case HANDSHAKE_ACK -> new ReplayPacket.HandshakeAck(
                    PacketIO.readVarInt(in), in.readBoolean(), PacketIO.readString(in));
            case SESSION_START -> {
                UUID sessionId = PacketIO.readUuid(in);
                String name = PacketIO.readString(in);
                String externalKey = PacketIO.readOptionalString(in);
                String worldName = PacketIO.readString(in);
                long startedAt = in.readLong();
                int formatVersion = PacketIO.readVarInt(in);
                boolean hasBounds = in.readBoolean();
                double minX = 0;
                double minY = 0;
                double minZ = 0;
                double maxX = 0;
                double maxY = 0;
                double maxZ = 0;
                if (hasBounds) {
                    minX = in.readDouble();
                    minY = in.readDouble();
                    minZ = in.readDouble();
                    maxX = in.readDouble();
                    maxY = in.readDouble();
                    maxZ = in.readDouble();
                }
                yield new ReplayPacket.SessionStart(sessionId, name, externalKey, worldName,
                        startedAt, formatVersion, minX, minY, minZ, maxX, maxY, maxZ, hasBounds,
                        PacketIO.readStringMap(in));
            }
            case SESSION_END -> new ReplayPacket.SessionEnd(
                    PacketIO.readUuid(in), PacketIO.readVarInt(in), PacketIO.readString(in));
            case SNAPSHOT_REFERENCE -> new ReplayPacket.SnapshotReference(
                    PacketIO.readUuid(in), PacketIO.readString(in), PacketIO.readOptionalString(in));
            case PLAYER_PROFILE -> new ReplayPacket.PlayerProfile(
                    PacketIO.readUuid(in),
                    new PlayerProfileData(PacketIO.readVarInt(in), PacketIO.readUuid(in),
                            PacketIO.readString(in), PacketIO.readOptionalString(in),
                            PacketIO.readOptionalString(in)));
            case PLAYER_FRAME -> new ReplayPacket.PlayerFramePacket(
                    PacketIO.readUuid(in),
                    new PlayerFrame(PacketIO.readVarInt(in), PacketIO.readVarInt(in),
                            in.readDouble(), in.readDouble(), in.readDouble(),
                            in.readByte(), in.readByte(), in.readByte(),
                            in.readShort(), in.readByte(), in.readShort(), in.readByte(),
                            PacketIO.readVarInt(in)));
            case EQUIPMENT_CHANGE -> new ReplayPacket.EquipmentChangePacket(
                    PacketIO.readUuid(in),
                    new EquipmentChange(PacketIO.readVarInt(in), PacketIO.readVarInt(in),
                            PacketIO.readVarInt(in),
                            PacketIO.readOptionalByteArray(in), PacketIO.readOptionalByteArray(in),
                            PacketIO.readOptionalByteArray(in), PacketIO.readOptionalByteArray(in),
                            PacketIO.readOptionalByteArray(in), PacketIO.readOptionalByteArray(in)));
            case INVENTORY_SNAPSHOT -> new ReplayPacket.InventorySnapshotPacket(
                    PacketIO.readUuid(in),
                    new InventorySnapshot(PacketIO.readVarInt(in), PacketIO.readVarInt(in),
                            readSlots(in), PacketIO.readOptionalByteArray(in),
                            in.readByte(), in.readShort(), in.readByte(),
                            PacketIO.readVarInt(in), in.readLong(), PacketIO.readString(in)));
            case CONTAINER_SNAPSHOT -> new ReplayPacket.ContainerSnapshotPacket(
                    PacketIO.readUuid(in),
                    new ContainerSnapshot(PacketIO.readVarInt(in), PacketIO.readString(in),
                            PacketIO.readString(in), PacketIO.readVarInt(in),
                            PacketIO.readVarInt(in), PacketIO.readVarInt(in),
                            PacketIO.readString(in), readSlots(in),
                            PacketIO.readVarInt(in) - 1, in.readLong(), PacketIO.readString(in)));
            case BLOCK_CHANGE -> new ReplayPacket.BlockChangePacket(
                    PacketIO.readUuid(in),
                    new BlockChange(PacketIO.readVarInt(in), PacketIO.readString(in),
                            PacketIO.readVarInt(in), PacketIO.readVarInt(in), PacketIO.readVarInt(in),
                            PacketIO.readString(in), PacketIO.readVarInt(in) - 1,
                            PacketIO.readString(in)));
            case ENTITY_SPAWN -> new ReplayPacket.EntitySpawn(
                    PacketIO.readUuid(in), PacketIO.readVarInt(in), PacketIO.readVarInt(in),
                    PacketIO.readString(in), in.readDouble(), in.readDouble(), in.readDouble(),
                    PacketIO.byteToAngle(in.readByte()), PacketIO.byteToAngle(in.readByte()),
                    PacketIO.readStringMap(in));
            case ENTITY_DESPAWN -> new ReplayPacket.EntityDespawn(
                    PacketIO.readUuid(in), PacketIO.readVarInt(in), PacketIO.readVarInt(in));
            case ENTITY_FRAME -> new ReplayPacket.EntityFramePacket(
                    PacketIO.readUuid(in),
                    new EntityFrame(PacketIO.readVarInt(in), PacketIO.readVarInt(in),
                            in.readDouble(), in.readDouble(), in.readDouble(),
                            in.readByte(), in.readByte()));
            case PROJECTILE_EVENT, ITEM_EVENT, DAMAGE_EVENT, DEATH_EVENT, KILL_EVENT,
                 CHAT_EVENT, CUSTOM_EVENT, BOOKMARK, WORLD_STATE_CHANGE -> {
                UUID sessionId = PacketIO.readUuid(in);
                int tick = PacketIO.readVarInt(in);
                String eventType = PacketIO.readString(in);
                String category = PacketIO.readString(in);
                int actor = PacketIO.readVarInt(in) - 1;
                int target = PacketIO.readVarInt(in) - 1;
                boolean hasLocation = in.readBoolean();
                String worldName = null;
                double x = 0;
                double y = 0;
                double z = 0;
                if (hasLocation) {
                    worldName = PacketIO.readString(in);
                    x = in.readDouble();
                    y = in.readDouble();
                    z = in.readDouble();
                }
                boolean critical = in.readBoolean();
                yield new ReplayPacket.TimelineEventPacket(sessionId, type,
                        new TimelineEvent(tick, eventType, category, actor, target,
                                worldName, x, y, z, critical, PacketIO.readStringMap(in)));
            }
            case HEARTBEAT -> new ReplayPacket.Heartbeat(in.readLong());
            case METRICS -> new ReplayPacket.Metrics(PacketIO.readStringMap(in));
            case PLAYER_JOIN -> new ReplayPacket.PlayerJoin(
                    PacketIO.readUuid(in), PacketIO.readVarInt(in), PacketIO.readVarInt(in));
            case PLAYER_QUIT -> new ReplayPacket.PlayerQuit(
                    PacketIO.readUuid(in), PacketIO.readVarInt(in), PacketIO.readVarInt(in));
            case DEGRADATION_MARKER -> new ReplayPacket.DegradationMarker(
                    PacketIO.readUuid(in), PacketIO.readVarInt(in), PacketIO.readVarInt(in),
                    PacketIO.readVarInt(in));
        };
    }

    private static void writeSlots(DataOutput out, byte[][] slots) throws IOException {
        PacketIO.writeVarInt(out, slots.length);
        for (byte[] slot : slots) {
            PacketIO.writeOptionalByteArray(out, slot);
        }
    }

    private static byte[][] readSlots(DataInput in) throws IOException {
        int count = PacketIO.readVarInt(in);
        if (count < 0 || count > 4096) {
            throw new IOException("Invalid slot count: " + count);
        }
        byte[][] slots = new byte[count][];
        for (int i = 0; i < count; i++) {
            slots[i] = PacketIO.readOptionalByteArray(in);
        }
        return slots;
    }
}
