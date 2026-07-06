package dev.raindancer118.extendedreplay.example;

import dev.raindancer118.extendedreplay.api.ExtendedReplayApi;
import dev.raindancer118.extendedreplay.api.ReplayBookmark;
import dev.raindancer118.extendedreplay.api.ReplayBounds;
import dev.raindancer118.extendedreplay.api.ReplayEvent;
import dev.raindancer118.extendedreplay.api.ReplayEventCategory;
import dev.raindancer118.extendedreplay.api.ReplaySessionEndReason;
import dev.raindancer118.extendedreplay.api.ReplaySessionHandle;
import dev.raindancer118.extendedreplay.api.ReplaySessionStartRequest;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Reference integration for TheHungerGames (or any minigame plugin).
 *
 * <p>The dependency direction is strictly {@code YourPlugin → ExtendedReplay API}.
 * ExtendedReplay must never depend on your plugin, and your plugin must work when
 * ExtendedReplay is absent — every method here degrades to a no-op in that case.</p>
 *
 * <p>Wire the calls into your match lifecycle:</p>
 * <pre>{@code
 * integration = new HungerGamesReplayIntegration();
 * integration.onMatchStart("match-42", arenaWorld, arenaBounds);   // match begins
 * integration.onKill(killer, victim);                              // every kill
 * integration.onSupplyDropSpawn(dropLocation, "supply_tier_2");    // supply drops
 * integration.onMatchEnd(winnerName);                              // match over
 * }</pre>
 */
public final class HungerGamesReplayIntegration {

    private ReplaySessionHandle session;

    /** Resolves the API if ExtendedReplay is installed; null otherwise. */
    private static ExtendedReplayApi api() {
        RegisteredServiceProvider<ExtendedReplayApi> registration =
                Bukkit.getServicesManager().getRegistration(ExtendedReplayApi.class);
        return registration != null ? registration.getProvider() : null;
    }

    public void onMatchStart(String matchId, World arenaWorld, ReplayBounds arenaBounds) {
        ExtendedReplayApi api = api();
        if (api == null) {
            return; // ExtendedReplay not installed — the game runs unaffected
        }
        api.registerEventType("HG_SUPPLY_DROP_SPAWN", ReplayEventCategory.SUPPLY, "Supply-Drop");
        api.registerEventType("HG_SPONSOR_BEACON_USE", ReplayEventCategory.SUPPLY, "Sponsor-Beacon");
        api.registerEventType("HG_DEATHMATCH_TRIGGERED", ReplayEventCategory.SESSION, "Deathmatch");
        session = api.startSession(ReplaySessionStartRequest
                .builder("hg-" + matchId, arenaWorld)
                .externalKey(matchId)
                .bounds(arenaBounds)
                .metadata("gamemode", "hungergames")
                .build());
    }

    public void onKill(Player killer, Player victim) {
        if (session == null) {
            return;
        }
        session.recordEvent(ReplayEvent.builder("HG_KILL", ReplayEventCategory.KILL)
                .actor(killer.getUniqueId())
                .target(victim.getUniqueId())
                .location(victim.getLocation())
                .metadata("weapon", killer.getInventory().getItemInMainHand().getType().name())
                .build());
    }

    public void onSupplyDropSpawn(Location location, String lootTable) {
        if (session == null) {
            return;
        }
        session.recordEvent(ReplayEvent.builder("HG_SUPPLY_DROP_SPAWN", ReplayEventCategory.SUPPLY)
                .location(location)
                .metadata("loot-table", lootTable)
                .build());
    }

    public void onDeathmatchTriggered() {
        if (session == null) {
            return;
        }
        session.recordEvent(ReplayEvent.builder("HG_DEATHMATCH_TRIGGERED",
                ReplayEventCategory.SESSION).build());
        session.createBookmark(ReplayBookmark.now("deathmatch"));
    }

    public void onMatchEnd(String winnerName) {
        if (session == null) {
            return;
        }
        session.recordEvent(ReplayEvent.builder("HG_PLAYER_PLACEMENT", ReplayEventCategory.SESSION)
                .metadata("winner", winnerName)
                .build());
        session.end(ReplaySessionEndReason.COMPLETED);
        session = null;
    }

    public void onMatchAborted() {
        if (session != null) {
            session.end(ReplaySessionEndReason.ABORTED);
            session = null;
        }
    }
}
