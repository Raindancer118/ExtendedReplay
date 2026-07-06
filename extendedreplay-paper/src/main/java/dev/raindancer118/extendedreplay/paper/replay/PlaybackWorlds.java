package dev.raindancer118.extendedreplay.paper.replay;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.generator.ChunkGenerator;

import java.util.Random;

/** Creates and hands out empty void worlds for playback and analysis. */
public final class PlaybackWorlds {

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
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            return existing;
        }
        WorldCreator creator = new WorldCreator(name)
                .generator(new VoidGenerator())
                .type(WorldType.FLAT)
                .generateStructures(false);
        World world = creator.createWorld();
        if (world != null) {
            world.setAutoSave(false);
            world.setSpawnFlags(false, false);
            world.setTime(6000);
            world.setStorm(false);
            world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
            world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
            world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
        }
        return world;
    }
}
