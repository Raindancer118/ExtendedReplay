package dev.raindancer118.extendedreplay.paper.config;

import org.bukkit.configuration.file.FileConfiguration;

/** Typed view over config.yml. Values are read once at load/reload. */
public record ReplayConfig(
        ServerRole role,
        String serverName,
        String serverGroup,
        // producer
        boolean producerEnabled,
        boolean captureInventories,
        boolean captureContainers,
        boolean captureBlockChanges,
        boolean captureEntities,
        boolean captureProjectiles,
        boolean captureItems,
        boolean captureChat,
        boolean captureWorldBorder,
        boolean captureWeatherTime,
        boolean autoSnapshot,
        int autoSnapshotRadius,
        boolean requireSnapshot,
        int maxQueueSize,
        // transport
        String transportHost,
        int transportPort,
        String authToken,
        long batchIntervalMs,
        long maxSpoolBytes,
        // replay server
        String bindHost,
        int bindPort,
        int liveDelaySeconds,
        String playbackWorldPrefix,
        boolean disableNaturalSpawning,
        // storage
        String storagePath,
        int segmentLengthSeconds,
        int retentionDays,
        long maxStorageBytes,
        // snapshots
        String snapshotPath,
        int keyframeIntervalSeconds,
        // renderer
        String preferredRenderer,
        boolean showNametags,
        // analysis
        int maxBlockChangesPerTick) {

    public static ReplayConfig from(FileConfiguration c) {
        return new ReplayConfig(
                ServerRole.parse(c.getString("extendedreplay.server-role", "DISABLED")),
                c.getString("extendedreplay.server-name", ""),
                c.getString("extendedreplay.server-group", ""),
                c.getBoolean("producer.enabled", true),
                c.getBoolean("producer.capture-inventories", true),
                c.getBoolean("producer.capture-containers", true),
                c.getBoolean("producer.capture-block-changes", true),
                c.getBoolean("producer.capture-entities", true),
                c.getBoolean("producer.capture-projectiles", true),
                c.getBoolean("producer.capture-items", true),
                c.getBoolean("producer.capture-chat", true),
                c.getBoolean("producer.capture-world-border", true),
                c.getBoolean("producer.capture-weather-time", true),
                c.getBoolean("producer.auto-snapshot.enabled", true),
                c.getInt("producer.auto-snapshot.radius", 200),
                c.getBoolean("producer.auto-snapshot.require", true),
                c.getInt("performance.max-queue-size", 100_000),
                c.getString("transport.host", "127.0.0.1"),
                c.getInt("transport.port", 8787),
                c.getString("transport.auth-token", "change-me"),
                c.getLong("transport.batch-interval-ms", 50),
                c.getLong("transport.max-spool-size-mb", 1024) * 1024L * 1024L,
                c.getString("replay-server.bind-host", "0.0.0.0"),
                c.getInt("replay-server.bind-port", 8787),
                c.getInt("replay-server.live-delay-seconds", 2),
                c.getString("replay-server.playback-world-prefix", "erp_playback"),
                c.getBoolean("replay-server.disable-natural-spawning", true),
                c.getString("storage.path", "plugins/ExtendedReplay/replays"),
                c.getInt("storage.segment-length-seconds", 30),
                c.getInt("storage.max-days", 30),
                c.getLong("storage.max-gb", 100) * 1024L * 1024L * 1024L,
                c.getString("snapshots.path", "plugins/ExtendedReplay/snapshots"),
                c.getInt("snapshots.keyframe-interval-seconds", 300),
                c.getString("renderer.preferred-player-renderer", "MANNEQUIN"),
                c.getBoolean("renderer.show-nametags", true),
                c.getInt("analysis.max-block-changes-per-tick", 1000));
    }
}
