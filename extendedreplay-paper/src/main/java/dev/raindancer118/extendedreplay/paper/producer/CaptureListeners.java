package dev.raindancer118.extendedreplay.paper.producer;

import dev.raindancer118.extendedreplay.core.model.BlockChange;
import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Translates Bukkit events into replay packets and dirty markers. All handlers run at
 * MONITOR priority (never modify the event) and do minimal work: primitives, dirty
 * flags, queue offers.
 */
public final class CaptureListeners implements Listener {

    private final ProducerManager producer;

    public CaptureListeners(ProducerManager producer) {
        this.producer = producer;
    }

    private ActiveSession sessionAt(Location location) {
        if (location.getWorld() == null) {
            return null;
        }
        ActiveSession session = producer.sessionForWorld(location.getWorld().getName()).orElse(null);
        if (session == null
                || !session.inBounds(location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ())) {
            return null;
        }
        return session;
    }

    // --- player lifecycle ---

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        producer.sessionForWorld(event.getPlayer().getWorld().getName())
                .ifPresent(session -> producer.registerPlayer(session, event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        producer.sessionOf(event.getPlayer())
                .ifPresent(session -> producer.unregisterPlayer(session, event.getPlayer()));
        producer.inventoryTracker().forget(event.getPlayer().getUniqueId());
        producer.equipmentTracker().forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        producer.sessionOf(event.getPlayer()).ifPresent(session ->
                producer.recordEvent(session, "TELEPORT", "MOVEMENT",
                        event.getPlayer().getUniqueId(), null, event.getTo(), true,
                        Map.of("cause", event.getCause().name())));
    }

    // --- combat / death ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        ActiveSession session = sessionAt(victim.getLocation());
        if (session == null || !session.isTracked(victim.getUniqueId())) {
            return;
        }
        Map<String, String> meta = new HashMap<>();
        meta.put("damage", String.format(java.util.Locale.ROOT, "%.2f", event.getFinalDamage()));
        meta.put("cause", event.getCause().name());
        var attacker = attackerOf(event);
        producer.recordEvent(session, "DAMAGE", "COMBAT",
                attacker != null ? attacker.getUniqueId() : null,
                victim.getUniqueId(), victim.getLocation(), true, meta);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        ActiveSession session = producer.sessionOf(victim).orElse(null);
        if (session == null) {
            return;
        }
        // exact inventory before drops are spawned
        producer.inventoryTracker().snapshotNow(victim, "death");

        Player killer = victim.getKiller();
        Map<String, String> meta = new HashMap<>();
        meta.put("death-message", event.deathMessage() != null
                ? PlainTextComponentSerializer.plainText().serialize(event.deathMessage()) : "");
        if (killer != null) {
            meta.put("killer", killer.getName());
            meta.put("weapon", killer.getInventory().getItemInMainHand().getType().name());
            producer.recordEvent(session, "KILL", "KILL", killer.getUniqueId(),
                    victim.getUniqueId(), victim.getLocation(), true, meta);
        }
        producer.recordEvent(session, "DEATH", "DEATH",
                killer != null ? killer.getUniqueId() : null,
                victim.getUniqueId(), victim.getLocation(), true, meta);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        producer.sessionOf(event.getPlayer()).ifPresent(session -> {
            producer.inventoryTracker().markDirty(event.getPlayer().getUniqueId(), "respawn");
            producer.recordEvent(session, "RESPAWN", "MOVEMENT",
                    event.getPlayer().getUniqueId(), null, event.getRespawnLocation(), true, Map.of());
        });
    }

    // --- blocks ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        recordBlockChange(event.getBlock(), event.getPlayer(), "break", "minecraft:air");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        recordBlockChange(event.getBlock(), event.getPlayer(), "place",
                event.getBlock().getBlockData().getAsString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        ActiveSession session = sessionAt(event.getLocation());
        if (session == null || !producer.config().captureBlockChanges()) {
            return;
        }
        producer.recordEvent(session, "EXPLOSION", "WORLD", null, null,
                event.getLocation(), true,
                Map.of("entity", event.getEntityType().name(),
                        "blocks", Integer.toString(event.blockList().size())));
        for (Block block : event.blockList()) {
            producer.offer(new ReplayPacket.BlockChangePacket(session.sessionId(),
                    new BlockChange(session.currentTick(), block.getWorld().getName(),
                            block.getX(), block.getY(), block.getZ(),
                            "minecraft:air", -1, "explosion")));
            if (block.getState() instanceof Container) {
                producer.inventoryTracker().markContainerDirty(block, "explosion");
            }
        }
    }

    private void recordBlockChange(Block block, Player actor, String cause, String newData) {
        if (!producer.config().captureBlockChanges()) {
            return;
        }
        ActiveSession session = sessionAt(block.getLocation());
        if (session == null) {
            return;
        }
        producer.offer(new ReplayPacket.BlockChangePacket(session.sessionId(),
                new BlockChange(session.currentTick(), block.getWorld().getName(),
                        block.getX(), block.getY(), block.getZ(), newData,
                        actor != null ? session.playerIndex(actor.getUniqueId()) : -1, cause)));
        if (block.getState() instanceof Container) {
            producer.inventoryTracker().markContainerDirty(block, cause);
        }
    }

    // --- inventories / containers ---

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        ActiveSession session = producer.sessionOf(player).orElse(null);
        if (session == null) {
            return;
        }
        if (event.getInventory().getHolder() instanceof Container container) {
            producer.inventoryTracker().markContainerDirty(container.getBlock(), "open");
            producer.recordEvent(session, "CONTAINER_OPEN", "LOOT", player.getUniqueId(),
                    null, container.getLocation(), true,
                    Map.of("type", container.getBlock().getType().name()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        producer.inventoryTracker().markDirty(player.getUniqueId(), "inventory-close");
        if (event.getInventory().getHolder() instanceof Container container) {
            producer.inventoryTracker().markContainerDirty(container.getBlock(), "close");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        producer.inventoryTracker().markDirty(player.getUniqueId(), "click");
        producer.equipmentTracker().markEquipmentDirty(player.getUniqueId());
        if (event.getInventory().getHolder() instanceof Container container) {
            producer.inventoryTracker().markContainerDirty(container.getBlock(), "click");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        producer.inventoryTracker().markDirty(player.getUniqueId(), "drag");
        if (event.getInventory().getHolder() instanceof Container container) {
            producer.inventoryTracker().markContainerDirty(container.getBlock(), "drag");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        producer.inventoryTracker().markDirty(player.getUniqueId(), "pickup");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ActiveSession session = producer.sessionOf(player).orElse(null);
        if (session == null) {
            return;
        }
        producer.inventoryTracker().markDirty(player.getUniqueId(), "drop");
        if (producer.config().captureItems()) {
            producer.recordEvent(session, "ITEM_DROP", "ITEM", player.getUniqueId(), null,
                    player.getLocation(), false,
                    Map.of("item", event.getItemDrop().getItemStack().getType().name(),
                            "amount", Integer.toString(event.getItemDrop().getItemStack().getAmount())));
        }
    }

    // --- projectiles ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!producer.config().captureProjectiles()) {
            return;
        }
        ActiveSession session = sessionAt(event.getLocation());
        if (session == null) {
            return;
        }
        Projectile projectile = event.getEntity();
        Player shooter = projectile.getShooter() instanceof Player p ? p : null;
        producer.recordEvent(session, "PROJECTILE_LAUNCH", "COMBAT",
                shooter != null ? shooter.getUniqueId() : null, null,
                event.getLocation(), false,
                Map.of("type", projectile.getType().name()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!producer.config().captureProjectiles()) {
            return;
        }
        ActiveSession session = sessionAt(event.getEntity().getLocation());
        if (session == null) {
            return;
        }
        Player shooter = event.getEntity().getShooter() instanceof Player p ? p : null;
        Player hit = event.getHitEntity() instanceof Player p ? p : null;
        producer.recordEvent(session, "PROJECTILE_HIT", "COMBAT",
                shooter != null ? shooter.getUniqueId() : null,
                hit != null ? hit.getUniqueId() : null,
                event.getEntity().getLocation(), hit != null,
                Map.of("type", event.getEntityType().name()));
    }

    // --- chat / world state ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!producer.config().captureChat()) {
            return;
        }
        // async event: only enqueue (queue is thread-safe); session tick read is volatile
        ActiveSession session = producer.sessionForWorld(
                event.getPlayer().getWorld().getName()).orElse(null);
        if (session == null || !session.isTracked(event.getPlayer().getUniqueId())) {
            return;
        }
        producer.recordEvent(session, "CHAT", "CHAT", event.getPlayer().getUniqueId(), null,
                event.getPlayer().getLocation(), true,
                Map.of("message", PlainTextComponentSerializer.plainText()
                        .serialize(event.message())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWeatherChange(WeatherChangeEvent event) {
        if (!producer.config().captureWeatherTime()) {
            return;
        }
        producer.sessionForWorld(event.getWorld().getName()).ifPresent(session ->
                producer.recordEvent(session, "WEATHER_CHANGE", "WORLD", null, null, null,
                        true, Map.of("raining", Boolean.toString(event.toWeatherState()))));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldBorderChange(WorldBorderBoundsChangeEvent event) {
        if (!producer.config().captureWorldBorder()) {
            return;
        }
        producer.sessionForWorld(event.getWorld().getName()).ifPresent(session ->
                producer.recordEvent(session, "WORLD_BORDER", "WORLD", null, null, null, true,
                        Map.of("new-size", Double.toString(event.getNewSize()),
                                "duration-ms", Long.toString(event.getDuration()))));
    }

    private static Player attackerOf(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Player player) {
                return player;
            }
            if (damager instanceof Projectile projectile
                    && projectile.getShooter() instanceof Player shooter) {
                return shooter;
            }
        }
        return null;
    }
}
