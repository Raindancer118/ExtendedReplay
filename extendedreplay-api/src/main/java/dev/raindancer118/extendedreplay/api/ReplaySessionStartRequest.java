package dev.raindancer118.extendedreplay.api;

import org.bukkit.World;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Parameters for starting a recording session. Create through {@link #builder(String, World)}.
 */
public final class ReplaySessionStartRequest {

    private final String name;
    private final String worldName;
    private final String externalKey;
    private final ReplayBounds bounds;
    private final Map<String, String> metadata;

    private ReplaySessionStartRequest(Builder builder) {
        this.name = builder.name;
        this.worldName = builder.worldName;
        this.externalKey = builder.externalKey;
        this.bounds = builder.bounds;
        this.metadata = Map.copyOf(builder.metadata);
    }

    public String name() {
        return name;
    }

    public String worldName() {
        return worldName;
    }

    /** May be null. */
    public String externalKey() {
        return externalKey;
    }

    /** May be null: record the whole world. */
    public ReplayBounds bounds() {
        return bounds;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public static Builder builder(String name, World world) {
        return new Builder(name, world.getName());
    }

    public static Builder builder(String name, String worldName) {
        return new Builder(name, worldName);
    }

    public static final class Builder {
        private final String name;
        private final String worldName;
        private String externalKey;
        private ReplayBounds bounds;
        private final Map<String, String> metadata = new LinkedHashMap<>();

        private Builder(String name, String worldName) {
            this.name = Objects.requireNonNull(name, "name");
            this.worldName = Objects.requireNonNull(worldName, "worldName");
        }

        public Builder externalKey(String externalKey) {
            this.externalKey = externalKey;
            return this;
        }

        public Builder bounds(ReplayBounds bounds) {
            this.bounds = bounds;
            return this;
        }

        public Builder metadata(String key, String value) {
            this.metadata.put(key, value);
            return this;
        }

        public ReplaySessionStartRequest build() {
            return new ReplaySessionStartRequest(this);
        }
    }
}
