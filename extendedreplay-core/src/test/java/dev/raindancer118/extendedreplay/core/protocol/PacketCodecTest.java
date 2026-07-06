package dev.raindancer118.extendedreplay.core.protocol;

import dev.raindancer118.extendedreplay.core.FormatConstants;
import dev.raindancer118.extendedreplay.core.model.BlockChange;
import dev.raindancer118.extendedreplay.core.model.ContainerSnapshot;
import dev.raindancer118.extendedreplay.core.model.EntityFrame;
import dev.raindancer118.extendedreplay.core.model.EquipmentChange;
import dev.raindancer118.extendedreplay.core.model.InventorySnapshot;
import dev.raindancer118.extendedreplay.core.model.PlayerFrame;
import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import dev.raindancer118.extendedreplay.core.model.TimelineEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PacketCodecTest {

    private static final UUID SESSION = UUID.randomUUID();

    private static ReplayPacket roundtrip(ReplayPacket packet) throws IOException {
        return PacketCodec.decode(PacketCodec.encode(packet));
    }

    @Test
    void handshakeRoundtrip() throws IOException {
        var packet = new ReplayPacket.Handshake(FormatConstants.PROTOCOL_VERSION, "secret", "game-1");
        assertThat(roundtrip(packet)).isEqualTo(packet);
    }

    @Test
    void sessionStartWithBoundsRoundtrip() throws IOException {
        var packet = new ReplayPacket.SessionStart(SESSION, "match-1", "hg-42", "arena",
                123456789L, 1, -100, 0, -100, 100, 256, 100, true,
                Map.of("mode", "hungergames"));
        assertThat(roundtrip(packet)).isEqualTo(packet);
    }

    @Test
    void sessionStartWithoutBoundsRoundtrip() throws IOException {
        var packet = new ReplayPacket.SessionStart(SESSION, "match-1", null, "arena",
                1L, 1, 0, 0, 0, 0, 0, 0, false, Map.of());
        assertThat(roundtrip(packet)).isEqualTo(packet);
    }

    @Test
    void playerFrameRoundtrip() throws IOException {
        var frame = new PlayerFrame(1234, 3, 10.5, 64.0, -20.25,
                (byte) 100, (byte) -20, (byte) 101,
                (short) (PlayerFrame.FLAG_SPRINTING | PlayerFrame.FLAG_ON_GROUND),
                (byte) 4, PlayerFrame.packHealth(19.5), (byte) 18, 7);
        var packet = new ReplayPacket.PlayerFramePacket(SESSION, frame);
        ReplayPacket decoded = roundtrip(packet);
        assertThat(decoded).isEqualTo(packet);
        assertThat(((ReplayPacket.PlayerFramePacket) decoded).frame().healthValue()).isEqualTo(19.5f);
    }

    @Test
    void playerProfileRoundtrip() throws IOException {
        var profile = new PlayerProfileData(0, UUID.randomUUID(), "Raindancer118",
                "texturevalue==", "signature==");
        var packet = new ReplayPacket.PlayerProfile(SESSION, profile);
        assertThat(roundtrip(packet)).isEqualTo(packet);
    }

    @Test
    void equipmentChangeRoundtripComparesArrays() throws IOException {
        var change = new EquipmentChange(50, 1, 2,
                new byte[]{1, 2, 3}, null, new byte[]{4}, null, null, new byte[0]);
        var decoded = (ReplayPacket.EquipmentChangePacket) roundtrip(
                new ReplayPacket.EquipmentChangePacket(SESSION, change));
        assertThat(decoded.change().tick()).isEqualTo(50);
        assertThat(decoded.change().mainHand()).containsExactly(1, 2, 3);
        assertThat(decoded.change().offHand()).isNull();
        assertThat(decoded.change().helmet()).containsExactly(4);
        assertThat(decoded.change().boots()).isEmpty();
    }

    @Test
    void inventorySnapshotRoundtrip() throws IOException {
        byte[][] slots = new byte[41][];
        slots[0] = new byte[]{9, 9};
        slots[40] = new byte[]{7};
        var snapshot = new InventorySnapshot(99, 2, slots, new byte[]{1}, (byte) 3,
                (short) 200, (byte) 20, 5, 0xCAFEBABEL, "pickup");
        var decoded = (ReplayPacket.InventorySnapshotPacket) roundtrip(
                new ReplayPacket.InventorySnapshotPacket(SESSION, snapshot));
        assertThat(decoded.snapshot().slots().length).isEqualTo(41);
        assertThat(decoded.snapshot().slots()[0]).containsExactly(9, 9);
        assertThat(decoded.snapshot().slots()[1]).isNull();
        assertThat(decoded.snapshot().slots()[40]).containsExactly(7);
        assertThat(decoded.snapshot().cause()).isEqualTo("pickup");
        assertThat(decoded.snapshot().contentHash()).isEqualTo(0xCAFEBABEL);
    }

    @Test
    void containerSnapshotRoundtrip() throws IOException {
        byte[][] slots = new byte[27][];
        slots[13] = new byte[]{42};
        var snapshot = new ContainerSnapshot(500, ContainerSnapshot.containerId("arena", 1, 64, -3),
                "arena", 1, 64, -3, "CHEST", slots, -1, 123L, "open");
        var decoded = (ReplayPacket.ContainerSnapshotPacket) roundtrip(
                new ReplayPacket.ContainerSnapshotPacket(SESSION, snapshot));
        assertThat(decoded.snapshot().containerId()).isEqualTo("arena:1,64,-3");
        assertThat(decoded.snapshot().lastInteractionPlayerIndex()).isEqualTo(-1);
        assertThat(decoded.snapshot().slots()[13]).containsExactly(42);
    }

    @Test
    void blockChangeRoundtrip() throws IOException {
        var change = new BlockChange(77, "arena", -5, 70, 12,
                "minecraft:chest[facing=north]", 2, "place");
        var packet = new ReplayPacket.BlockChangePacket(SESSION, change);
        assertThat(roundtrip(packet)).isEqualTo(packet);
    }

    @Test
    void negativeCoordinatesInBlockChangeSurviveVarIntEncoding() throws IOException {
        var change = new BlockChange(1, "arena", -1000000, -64, -999999, "minecraft:air", -1, "break");
        var decoded = (ReplayPacket.BlockChangePacket) roundtrip(
                new ReplayPacket.BlockChangePacket(SESSION, change));
        assertThat(decoded.change().x()).isEqualTo(-1000000);
        assertThat(decoded.change().y()).isEqualTo(-64);
        assertThat(decoded.change().z()).isEqualTo(-999999);
        assertThat(decoded.change().actorPlayerIndex()).isEqualTo(-1);
    }

    @Test
    void timelineEventRoundtripForAllEventPacketTypes() throws IOException {
        for (PacketType type : new PacketType[]{PacketType.PROJECTILE_EVENT, PacketType.ITEM_EVENT,
                PacketType.DAMAGE_EVENT, PacketType.DEATH_EVENT, PacketType.KILL_EVENT,
                PacketType.CHAT_EVENT, PacketType.CUSTOM_EVENT, PacketType.BOOKMARK,
                PacketType.WORLD_STATE_CHANGE}) {
            var event = new TimelineEvent(42, "HG_KILL", "KILL", 1, 2, "arena",
                    1.5, 64, -3.25, true, Map.of("weapon", "diamond_sword"));
            var packet = new ReplayPacket.TimelineEventPacket(SESSION, type, event);
            ReplayPacket decoded = roundtrip(packet);
            assertThat(decoded).as(type.name()).isEqualTo(packet);
            assertThat(decoded.type()).isEqualTo(type);
        }
    }

    @Test
    void entityPacketsRoundtrip() throws IOException {
        var spawn = new ReplayPacket.EntitySpawn(SESSION, 10, 555, "ARROW",
                1, 2, 3, 90.0f, -45.0f, Map.of("shooter", "0"));
        var decodedSpawn = (ReplayPacket.EntitySpawn) roundtrip(spawn);
        assertThat(decodedSpawn.entityId()).isEqualTo(555);
        assertThat(decodedSpawn.yaw()).isCloseTo(90.0f, org.assertj.core.data.Offset.offset(1.5f));

        var frame = new ReplayPacket.EntityFramePacket(SESSION,
                new EntityFrame(11, 555, 1.5, 2.5, 3.5, (byte) 5, (byte) 6));
        assertThat(roundtrip(frame)).isEqualTo(frame);
        assertThat(frame.critical()).isFalse();

        var despawn = new ReplayPacket.EntityDespawn(SESSION, 12, 555);
        assertThat(roundtrip(despawn)).isEqualTo(despawn);
    }

    @Test
    void miscPacketsRoundtrip() throws IOException {
        assertThat(roundtrip(new ReplayPacket.Heartbeat(42L)))
                .isEqualTo(new ReplayPacket.Heartbeat(42L));
        assertThat(roundtrip(new ReplayPacket.Metrics(Map.of("tps", "20.0"))))
                .isEqualTo(new ReplayPacket.Metrics(Map.of("tps", "20.0")));
        assertThat(roundtrip(new ReplayPacket.PlayerJoin(SESSION, 5, 1)))
                .isEqualTo(new ReplayPacket.PlayerJoin(SESSION, 5, 1));
        assertThat(roundtrip(new ReplayPacket.DegradationMarker(SESSION, 100, 200, 2)))
                .isEqualTo(new ReplayPacket.DegradationMarker(SESSION, 100, 200, 2));
        assertThat(roundtrip(new ReplayPacket.SnapshotReference(SESSION, "arena-v1", "abc123")))
                .isEqualTo(new ReplayPacket.SnapshotReference(SESSION, "arena-v1", "abc123"));
        assertThat(roundtrip(new ReplayPacket.SessionEnd(SESSION, 999, "COMPLETED")))
                .isEqualTo(new ReplayPacket.SessionEnd(SESSION, 999, "COMPLETED"));
    }

    @Test
    void unknownPacketTypeDecodesToNull() throws IOException {
        byte[] data = new byte[]{(byte) 200, 1}; // varint 200 = unknown type id
        assertThat(PacketCodec.decode(data)).isNull();
    }

    @Test
    void angleConversionIsStable() {
        for (float angle : new float[]{0, 45, 90, 180, 270, 359}) {
            float restored = PacketIO.byteToAngle(PacketIO.angleToByte(angle));
            float diff = Math.abs(((restored - angle) % 360 + 540) % 360 - 180);
            assertThat(diff).as("angle " + angle).isLessThanOrEqualTo(1.5f);
        }
    }
}
