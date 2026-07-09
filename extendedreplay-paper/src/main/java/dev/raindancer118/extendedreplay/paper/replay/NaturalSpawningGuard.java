package dev.raindancer118.extendedreplay.paper.replay;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * Disables natural mob/monster spawning on every world of a dedicated REPLAY server: the
 * server exists only to watch replays, so real-time monster spawns would be pure noise
 * (and a griefing/lag risk in the lobby world). Playback/mirror worlds already set this
 * gamerule themselves on creation (see {@link PlaybackWorlds}); this guard additionally
 * covers the default/lobby world and any other world loaded later.
 *
 * <p>Entities the replay system spawns itself (actor renderers, dropped items via
 * {@code World#spawnEntity}/{@code #dropItem}) are unaffected — {@code DO_MOB_SPAWNING}
 * only gates the natural spawn cycle, never explicit spawn calls.</p>
 */
public final class NaturalSpawningGuard implements Listener {

    /** Applies the gamerule to every world already loaded at enable time. */
    public void disableForLoadedWorlds() {
        for (World world : Bukkit.getWorlds()) {
            disable(world);
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        disable(event.getWorld());
    }

    private void disable(World world) {
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
    }
}
