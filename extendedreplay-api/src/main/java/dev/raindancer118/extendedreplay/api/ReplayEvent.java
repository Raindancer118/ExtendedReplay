package dev.raindancer118.extendedreplay.api;

import org.bukkit.Location;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A discrete event on the session timeline (kill, chest open, supply drop, custom event…).
 * Create through {@link #builder(String, ReplayEventCategory)}.
 */
public final class ReplayEvent {

    private final String type;
    private final ReplayEventCategory category;
    private final UUID actor;
    private final UUID target;
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    private final boolean hasLocation;
    private final boolean critical;
    private final Map<String, String> metadata;

    private ReplayEvent(Builder builder) {
        this.type = builder.type;
        this.category = builder.category;
        this.actor = builder.actor;
        this.target = builder.target;
        this.worldName = builder.worldName;
        this.x = builder.x;
        this.y = builder.y;
        this.z = builder.z;
        this.hasLocation = builder.hasLocation;
        this.critical = builder.critical;
        this.metadata = Map.copyOf(builder.metadata);
    }

    public String type() {
        return type;
    }

    public ReplayEventCategory category() {
        return category;
    }

    /** May be null. */
    public UUID actor() {
        return actor;
    }

    /** May be null. */
    public UUID target() {
        return target;
    }

    /** May be null when {@link #hasLocation()} is false. */
    public String worldName() {
        return worldName;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public boolean hasLocation() {
        return hasLocation;
    }

    /** Critical events are never dropped under load. */
    public boolean critical() {
        return critical;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public static Builder builder(String type, ReplayEventCategory category) {
        return new Builder(type, category);
    }

    public static final class Builder {
        private final String type;
        private final ReplayEventCategory category;
        private UUID actor;
        private UUID target;
        private String worldName;
        private double x;
        private double y;
        private double z;
        private boolean hasLocation;
        private boolean critical = true;
        private final Map<String, String> metadata = new LinkedHashMap<>();

        private Builder(String type, ReplayEventCategory category) {
            this.type = Objects.requireNonNull(type, "type");
            this.category = Objects.requireNonNull(category, "category");
        }

        public Builder actor(UUID actor) {
            this.actor = actor;
            return this;
        }

        public Builder target(UUID target) {
            this.target = target;
            return this;
        }

        public Builder location(Location location) {
            return location(location.getWorld() != null ? location.getWorld().getName() : null,
                    location.getX(), location.getY(), location.getZ());
        }

        public Builder location(String worldName, double x, double y, double z) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.hasLocation = true;
            return this;
        }

        /** Marks the event droppable under queue pressure. Default is critical. */
        public Builder cosmetic() {
            this.critical = false;
            return this;
        }

        public Builder metadata(String key, String value) {
            if (value != null) {
                this.metadata.put(key, value);
            }
            return this;
        }

        public ReplayEvent build() {
            return new ReplayEvent(this);
        }
    }
}
