package dev.raindancer118.extendedreplay.core.model;

/**
 * One 20 Hz movement/state sample of a recorded player.
 *
 * <p>Deliberately compact: primitives only, no ItemStacks, no NBT, no strings. The
 * player is referenced by its session-local index; equipment by a version id that
 * points at the latest {@link EquipmentChange}.</p>
 */
public record PlayerFrame(
        int tick,
        int playerIndex,
        double x,
        double y,
        double z,
        byte yaw,
        byte pitch,
        byte headYaw,
        short stateFlags,
        byte selectedSlot,
        short health,          // health * 10, fixed point
        byte food,
        int equipmentVersion) {

    public static final short FLAG_SNEAKING = 1;
    public static final short FLAG_SPRINTING = 1 << 1;
    public static final short FLAG_SWIMMING = 1 << 2;
    public static final short FLAG_GLIDING = 1 << 3;
    public static final short FLAG_BLOCKING = 1 << 4;
    public static final short FLAG_USING_ITEM = 1 << 5;
    public static final short FLAG_ON_GROUND = 1 << 6;
    public static final short FLAG_IN_VEHICLE = 1 << 7;
    public static final short FLAG_SLEEPING = 1 << 8;
    public static final short FLAG_DEAD = 1 << 9;
    public static final short FLAG_INVISIBLE = 1 << 10;
    public static final short FLAG_GLOWING = 1 << 11;

    public boolean hasFlag(short flag) {
        return (stateFlags & flag) != 0;
    }

    public float healthValue() {
        return health / 10.0f;
    }

    public static short packHealth(double health) {
        return (short) Math.round(health * 10.0);
    }
}
