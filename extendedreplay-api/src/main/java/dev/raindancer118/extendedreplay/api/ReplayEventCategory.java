package dev.raindancer118.extendedreplay.api;

/** Coarse grouping used by the event browser. */
public enum ReplayEventCategory {
    SESSION,
    KILL,
    DEATH,
    COMBAT,
    LOOT,
    ITEM,
    MOVEMENT,
    WORLD,
    CHAT,
    SUPPLY,
    ADMIN,
    ANOMALY,
    BOOKMARK,
    CUSTOM;

    /** Lenient parse falling back to {@link #CUSTOM}. */
    public static ReplayEventCategory fromString(String value) {
        if (value == null) {
            return CUSTOM;
        }
        try {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CUSTOM;
        }
    }
}
