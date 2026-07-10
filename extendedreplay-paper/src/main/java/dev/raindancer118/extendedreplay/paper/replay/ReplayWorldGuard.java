package dev.raindancer118.extendedreplay.paper.replay;

import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns playback worlds read-only and shields viewers from the world/each other: nothing
 * ticks, breaks, burns, spreads or spawns naturally in a playback world, and a "protected"
 * player (someone in a playback session, or — in lobby mode — anyone holding
 * {@code extendedreplay.viewer}) cannot take damage, lose food, pick up items, be targeted,
 * open containers directly, use portals, manipulate armor stands or damage anything.
 *
 * <p>{@link PlaybackWorlds} already sets deterministic gamerules on every playback world,
 * but gamerules alone don't cover explicit player actions (breaking/placing blocks, using
 * buckets, hitting actors) — this listener closes those gaps.</p>
 */
public final class ReplayWorldGuard implements Listener {

    private static final long CONTAINER_MESSAGE_COOLDOWN_MS = 5_000L;
    private static final String VIEWER_PERMISSION = "extendedreplay.viewer";

    private final PlaybackManager playback;
    private final String playbackWorldPrefix;
    private final boolean replayLobbyMode;

    /** Last time (epoch millis) a viewer was told to use the inspection GUI instead — keyed
     * by UUID, never by Player, so it never keeps a stale player object alive. */
    private final Map<UUID, Long> lastContainerWarning = new HashMap<>();

    public ReplayWorldGuard(PlaybackManager playback, String playbackWorldPrefix,
                            boolean replayLobbyMode) {
        this.playback = playback;
        this.playbackWorldPrefix = playbackWorldPrefix;
        this.replayLobbyMode = replayLobbyMode;
    }

    private boolean isPlaybackWorld(World world) {
        return world != null && world.getName().startsWith(playbackWorldPrefix);
    }

    /** A viewer currently inside a playback session, or — in lobby mode — anyone who holds
     * the viewer permission (they are between sessions but still shouldn't touch the lobby). */
    private boolean isProtected(Player player) {
        if (playback.sessionOf(player).isPresent()) {
            return true;
        }
        return replayLobbyMode && player.hasPermission(VIEWER_PERMISSION);
    }

    // --- world-based: playback worlds are read-only terrain ---

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isPlaybackWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isPlaybackWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (isPlaybackWorld(event.getPlayer().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (isPlaybackWorld(event.getPlayer().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (isPlaybackWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (isPlaybackWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        if (isPlaybackWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        if (isPlaybackWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        if (isPlaybackWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        if (isPlaybackWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        if (isPlaybackWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        if (isPlaybackWorld(event.getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        // SpawnReason.CUSTOM is how this plugin's own actor renderers spawn entities
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) {
            return;
        }
        if (isPlaybackWorld(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (isPlaybackWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (isPlaybackWorld(event.getLocation().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (isPlaybackWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onWeatherChange(WeatherChangeEvent event) {
        if (!isPlaybackWorld(event.getWorld())) {
            return;
        }
        WeatherChangeEvent.Cause cause = event.getCause();
        if (cause == WeatherChangeEvent.Cause.NATURAL || cause == WeatherChangeEvent.Cause.COMMAND) {
            event.setCancelled(true);
        }
    }

    // --- player-based: protected viewers can't be hurt, fed on, targeted or grabbed ---

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isProtected(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // viewers must not be able to hit replay actors/entities either
        if (event.getDamager() instanceof Player damager && isProtected(damager)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && isProtected(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isProtected(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityTarget(EntityTargetEvent event) {
        if (event.getTarget() instanceof Player player && isProtected(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (isProtected(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Containers must be inspected through the player GUI, not opened directly — the
     * contents are historical, recorded state and would desync from what the GUI shows. */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || !isPlaybackWorld(block.getWorld())) {
            return;
        }
        if (!(block.getState() instanceof Container)) {
            return;
        }
        event.setCancelled(true);
        warnContainerBlocked(event.getPlayer());
    }

    /** Protects armor-stand actors in playback worlds from being posed/equipped by viewers. */
    @EventHandler(ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (isPlaybackWorld(event.getPlayer().getWorld())) {
            event.setCancelled(true);
        }
    }

    private void warnContainerBlocked(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastContainerWarning.get(player.getUniqueId());
        if (last != null && now - last < CONTAINER_MESSAGE_COOLDOWN_MS) {
            return;
        }
        lastContainerWarning.put(player.getUniqueId(), now);
        player.sendMessage(Component.text("Container-Inhalte: über die Inspektion (Spieler-GUI)"));
    }
}
