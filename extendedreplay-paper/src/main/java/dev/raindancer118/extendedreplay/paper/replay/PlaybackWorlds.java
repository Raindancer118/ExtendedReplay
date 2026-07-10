package dev.raindancer118.extendedreplay.paper.replay;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
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

    /** Stored in {@link #trackedSeeds} for void worlds — the map cannot hold null values. */
    private static final Long VOID_SEED_SENTINEL = Long.MIN_VALUE;

    private PlaybackWorlds() {
    }

    /** An entirely empty chunk generator: playback worlds contain only replayed state.
     * Also fixes the spawn location so the vanilla spawn-point scan (a synchronous,
     * main-thread search for a "safe" surface block) never runs for this tiny world. */
    private static final class VoidGenerator extends ChunkGenerator {
        @Override
        public void generateNoise(org.bukkit.generator.WorldInfo worldInfo, Random random,
                                  int chunkX, int chunkZ, ChunkData chunkData) {
            // intentionally empty: void world
        }

        @Override
        public Location getFixedSpawnLocation(World world, Random random) {
            return new Location(world, 0.5, 64, 0.5);
        }
    }

    /**
     * Wraps vanilla chunk generation (noise/surface/caves/decorations/mobs/structures all
     * delegated to the server, exactly as if no custom generator were set) but skips the
     * vanilla spawn-point scan by supplying a fixed spawn. That scan runs synchronously on
     * the main thread while the world is created and was observed to freeze the server for
     * over 10 seconds on seeded natural worlds (watchdog: "Selecting spawn point for world
     * ..."); {@code keepSpawnLoaded(FALSE)} alone does not skip it, only a fixed spawn does.
     */
    private static final class VanillaWithFixedSpawn extends ChunkGenerator {
        @Override
        public boolean shouldGenerateNoise(org.bukkit.generator.WorldInfo worldInfo, Random random,
                                            int chunkX, int chunkZ) {
            return true;
        }

        @Override
        public boolean shouldGenerateSurface(org.bukkit.generator.WorldInfo worldInfo, Random random,
                                              int chunkX, int chunkZ) {
            return true;
        }

        @Override
        public boolean shouldGenerateCaves(org.bukkit.generator.WorldInfo worldInfo, Random random,
                                            int chunkX, int chunkZ) {
            return true;
        }

        @Override
        public boolean shouldGenerateDecorations(org.bukkit.generator.WorldInfo worldInfo, Random random,
                                                  int chunkX, int chunkZ) {
            return true;
        }

        @Override
        public boolean shouldGenerateMobs(org.bukkit.generator.WorldInfo worldInfo, Random random,
                                           int chunkX, int chunkZ) {
            return true;
        }

        @Override
        public boolean shouldGenerateStructures(org.bukkit.generator.WorldInfo worldInfo, Random random,
                                                 int chunkX, int chunkZ) {
            return true;
        }

        @Override
        public Location getFixedSpawnLocation(World world, Random random) {
            return new Location(world, 0.5, 128, 0.5);
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
            configureWorld(existing);
            return existing;
        }
        // a leftover folder from an earlier run would make Bukkit load the OLD world and
        // silently ignore the requested seed — the viewer would land in the wrong terrain
        if (name.startsWith(PLAYBACK_PREFIX)) {
            deleteWorldFolder(name);
        }
        World world = createWorld(name, seed, environment);
        configureWorld(world);
        // ConcurrentHashMap forbids null values — void worlds (no seed) use a sentinel
        trackedSeeds.put(name, seed != null ? seed : VOID_SEED_SENTINEL);
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
                    .generator(new VanillaWithFixedSpawn())
                    .keepSpawnLoaded(net.kyori.adventure.util.TriState.FALSE);
            return creator.createWorld();
        }
        // no WorldType.FLAT here: the custom generator makes it meaningless and the empty
        // flat settings made every world creation log "No key layers in MapLike[{}]"
        WorldCreator creator = new WorldCreator(name)
                .generator(new VoidGenerator())
                .generateStructures(false)
                .keepSpawnLoaded(net.kyori.adventure.util.TriState.FALSE);
        return creator.createWorld();
    }

    /**
     * Applies gamerules that make a playback world fully deterministic: nothing ticks,
     * spawns, spreads or grieves on its own, so the only state changes ever seen in the
     * world are the ones this plugin replays or the arena snapshot it started from. Always
     * (re-)applied — idempotent, so simpler than tracking "was this world freshly created".
     */
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
        world.setGameRule(org.bukkit.GameRule.DO_FIRE_TICK, false);
        world.setGameRule(org.bukkit.GameRule.RANDOM_TICK_SPEED, 0);
        world.setGameRule(org.bukkit.GameRule.MOB_GRIEFING, false);
        world.setGameRule(org.bukkit.GameRule.DO_TILE_DROPS, false);
        world.setGameRule(org.bukkit.GameRule.DO_VINES_SPREAD, false);
        world.setGameRule(org.bukkit.GameRule.DO_TRADER_SPAWNING, false);
        world.setGameRule(org.bukkit.GameRule.DO_PATROL_SPAWNING, false);
        world.setGameRule(org.bukkit.GameRule.DO_INSOMNIA, false);
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
        Long wanted = seed != null ? seed : VOID_SEED_SENTINEL;
        if (Objects.equals(tracked, wanted) && loaded != null) {
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

    /**
     * Deletes all not-currently-loaded {@value #PLAYBACK_PREFIX}* world folders. Playback
     * worlds are throwaway state (auto-save is off, content is rebuilt from the recording
     * on every open), but their folders survive restarts — and a stale folder hijacks any
     * later world of the same name, seed included. Call once on plugin enable.
     */
    public static int cleanupStaleWorldFolders() {
        Path root = worldContainer();
        int deleted = 0;
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (name.startsWith(PLAYBACK_PREFIX) && Files.isDirectory(child)
                        && Bukkit.getWorld(name) == null
                        && deleteWorldFolder(name)) {
                    deleted++;
                }
            }
        } catch (IOException ignored) {
            // best effort — a failed cleanup only means slightly more disk usage
        }
        return deleted;
    }

    /** Absolute world container path — getWorldContainer() is the relative "." in
     * containerized servers, which breaks parent-directory safety checks. */
    private static Path worldContainer() {
        return Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
    }

    /** Deletes a playback world's folder from disk. Only ever a direct child of the server
     * world container whose name exactly matches the (already prefix-checked) world name.
     * Returns true when the folder is gone afterwards. */
    private static boolean deleteWorldFolder(String name) {
        Path root = worldContainer();
        Path worldDir = root.resolve(name).normalize();
        if (!root.equals(worldDir.getParent()) || !name.equals(worldDir.getFileName().toString())) {
            return false;
        }
        if (!Files.isDirectory(worldDir)) {
            return true; // nothing on disk — same outcome as a successful delete
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
        return !Files.isDirectory(worldDir);
    }
}
