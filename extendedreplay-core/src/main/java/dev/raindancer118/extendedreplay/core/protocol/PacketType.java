package dev.raindancer118.extendedreplay.core.protocol;

/**
 * Wire ids of all replay packet types. Ids are part of the protocol and must never
 * be reordered; add new types at the end only.
 */
public enum PacketType {
    HANDSHAKE(0),
    HANDSHAKE_ACK(1),
    SESSION_START(2),
    SESSION_END(3),
    SNAPSHOT_REFERENCE(4),
    PLAYER_PROFILE(5),
    PLAYER_FRAME(6),
    EQUIPMENT_CHANGE(7),
    INVENTORY_SNAPSHOT(8),
    CONTAINER_SNAPSHOT(9),
    BLOCK_CHANGE(10),
    ENTITY_SPAWN(11),
    ENTITY_DESPAWN(12),
    ENTITY_FRAME(13),
    PROJECTILE_EVENT(14),
    ITEM_EVENT(15),
    DAMAGE_EVENT(16),
    DEATH_EVENT(17),
    KILL_EVENT(18),
    CHAT_EVENT(19),
    CUSTOM_EVENT(20),
    BOOKMARK(21),
    WORLD_STATE_CHANGE(22),
    HEARTBEAT(23),
    METRICS(24),
    PLAYER_JOIN(25),
    PLAYER_QUIT(26),
    DEGRADATION_MARKER(27);

    private static final PacketType[] BY_ID;

    static {
        PacketType[] values = values();
        int max = 0;
        for (PacketType type : values) {
            max = Math.max(max, type.id);
        }
        BY_ID = new PacketType[max + 1];
        for (PacketType type : values) {
            BY_ID[type.id] = type;
        }
    }

    private final int id;

    PacketType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    /** Returns null for unknown ids (forward compatibility: skip unknown packets). */
    public static PacketType byId(int id) {
        return id >= 0 && id < BY_ID.length ? BY_ID[id] : null;
    }
}
