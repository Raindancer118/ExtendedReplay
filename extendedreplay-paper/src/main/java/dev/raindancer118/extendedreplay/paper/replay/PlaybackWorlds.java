package dev.raindancer118.extendedreplay.paper.replay;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Creates and hands out playback/mirror worlds: void worlds by default, or seed-matched
 * natural worlds when the recorded session's producer world seed is known. */
public final class PlaybackWorlds {

    /** Only world names starting with this prefix are ever recreated/deleted from disk. */
    private static final String PLAYBACK_PREFIX = "erp_playback";

    /** Tracks the seed this process created each playback world with (null = void world). */
    private static final Map<String, Long> trackedSeeds = new ConcurrentHashMap<>();

    private PlaybackWorlds() {
    }

    /** An entirely empty chunk generator: playback worlds contain only replayed state. */
    private static final class VoidGenerator extends ChunkGenerator {
        @Override
        public void generateNoise(org.bukkit.generator.WorldInfo worldInfo, Random random,
                                  int chunkX, int chunkZ, ChunkData chunkData) {
            // intentionally empty: void world
        }
    }

    /** Gets or creates the void world with the given name. Main thread only. */
    public static World getOrCreate(String name) {
        return getOrCreate(name, null, null);
    }

    /**
     * Gets or creates the world with the given name. When {@code seed} is non-null the
     * world is generated naturally with that seed/environment (so recorded terrain matches
     * the producer world); otherwise the legacy empty void world is created. Main thread only.
     */
    public static World getOrCreate(String name, Long seed, String environment) {
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            return existing;
        }
        World world = createWorld(name, seed, environment);
        configureWorld(world);
        trackedSeeds.put(name, seed);
        return world;
    }

    private static World createWorld(String name, Long seed, String environment) {
        // keepSpawnLoaded(FALSE): skip generating the whole spawn-chunk plate on the main
        // thread — a seeded natural world would otherwise freeze the server for seconds
        // (observed >10s watchdog dumps); chunks stream in when the viewer teleports
        if (seed != null) {
            WorldCreator creator = new WorldCreator(name)
                    .seed(seed)
                    .environment(parseEnvironment(environment))
                    .generateStructures(true)
                    .keepSpawnLoaded(net.kyori.adventure.util.TriState.FALSE);
            return creator.createWorld();
        }
        WorldCreator creator = new WorldCreator(name)
                .generator(new VoidGenerator())
                .type(WorldType.FLAT)
                .generateStructures(false)
                .keepSpawnLoaded(net.kyori.adventure.util.TriState.FALSE);
        return creator.createWorld();
    }

    private static void configureWorld(World world) {
        if (world == null) {
            return;
        }
        world.setAutoSave(false);
        world.setSpawnFlags(false, false);
        world.setTime(6000);
        world.setStorm(false);
        world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
    }

    private static World.Environment parseEnvironment(String environment) {
        if (environment == null) {
            return World.Environment.NORMAL;
        }
        try {
            return World.Environment.valueOf(environment);
        } catch (IllegalArgumentException e) {
            return World.Environment.NORMAL;
        }
    }

    /**
     * Ensures {@code name} is backed by a world matching {@code seed}/{@code environment},
     * recreating it from scratch when it isn't. The seed of an unloaded or on-disk-only
     * world cannot be read cheaply, so any call where this process has no record of having
     * created {@code name} with the exact same seed (e.g. after a server restart, or the
     * previous occupant of a reused world name like the live-mirror world) is treated as a
     * mismatch: any players currently in the world are teleported to the default world's
     * spawn, the world is unloaded without saving, its folder is deleted, and a fresh world
     * is created. This is the semantics behind "connect enforces a fresh world" for the
     * live-mirror world, whose name is reused across producer sessions.
     *
     * <p>Safety: only ever touches worlds whose name starts with {@value #PLAYBACK_PREFIX},
     * so a caller can never make this unload/delete a real world.</p>
     */
    public static World recreateIfSeedDiffers(String name, Long seed, String environment) {
        Long tracked = trackedSeeds.get(name);
        World loaded = Bukkit.getWorld(name);
        if (Objects.equals(tracked, seed) && loaded != null) {
            return loaded;
        }
        if (!name.startsWith(PLAYBACK_PREFIX)) {
            // custom playback-world-prefix: never delete anything outside the known prefix —
            // reuse or create instead of recreating (stale terrain beats accidental deletion)
            return getOrCreate(name, seed, environment);
        }
        if (loaded != null) {
            Location fallback = Bukkit.getWorlds().get(0).getSpawnLocation();
            for (Player player : loaded.getPlayers()) {
                player.teleport(fallback);
            }
            Bukkit.unloadWorld(loaded, false);
        }
        trackedSeeds.remove(name);
        deleteWorldFolder(name);
        return getOrCreate(name, seed, environment);
    }

    /** Deletes a playback world's folder from disk. Only ever a direct child of the server
     * world container whose name exactly matches the (already prefix-checked) world name. */
    private static void deleteWorldFolder(String name) {
        Path root = Bukkit.getWorldContainer().toPath().normalize();
        Path worldDir = root.resolve(name).normalize();
        if (!root.equals(worldDir.getParent()) || !name.equals(worldDir.getFileName().toString())) {
            return;
        }
        if (!Files.isDirectory(worldDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(worldDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort: a stray file left behind is not worth failing the connect for
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
