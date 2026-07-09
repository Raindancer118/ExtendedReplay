package dev.raindancer118.extendedreplay.paper.producer;

import dev.raindancer118.extendedreplay.api.ReplayBookmark;
import dev.raindancer118.extendedreplay.api.ReplayBounds;
import dev.raindancer118.extendedreplay.api.ReplayEvent;
import dev.raindancer118.extendedreplay.api.ReplaySessionEndReason;
import dev.raindancer118.extendedreplay.core.FormatConstants;
import dev.raindancer118.extendedreplay.core.model.PlayerFrame;
import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import dev.raindancer118.extendedreplay.core.model.TimelineEvent;
import dev.raindancer118.extendedreplay.core.pipeline.CaptureMetrics;
import dev.raindancer118.extendedreplay.core.pipeline.RecordingQueue;
import dev.raindancer118.extendedreplay.core.protocol.PacketIO;
import dev.raindancer118.extendedreplay.core.protocol.PacketType;
import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;
import dev.raindancer118.extendedreplay.paper.config.ReplayConfig;
import dev.raindancer118.extendedreplay.transport.ReplayTransport;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Producer orchestration: owns the recording queue, the 20 Hz frame capture task, the
 * background pipeline that feeds the transport, and the active session registry.
 *
 * <p>The main-thread tick task only reads primitives from online players and offers
 * compact packets to a non-blocking queue. A background thread drains the queue into the
 * transport. Nothing here ever blocks the game.</p>
 */
public final class ProducerManager {

    private final Plugin plugin;
    private final ReplayConfig config;
    private final ReplayTransport transport;
    private final RecordingQueue queue;
    private final CaptureMetrics metrics;
    private final EquipmentTracker equipmentTracker;
    private final InventoryTracker inventoryTracker;
    private final EntityTracker entityTracker;

    private final Map<String, ActiveSession> sessionsByWorld = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveSession> sessionsById = new ConcurrentHashMap<>();

    private final ScheduledExecutorService pipeline;
    private BukkitTask tickTask;

    public ProducerManager(Plugin plugin, ReplayConfig config, ReplayTransport transport,
                           CaptureMetrics metrics) {
        this.plugin = plugin;
        this.config = config;
        this.transport = transport;
        this.metrics = metrics;
        this.queue = new RecordingQueue(config.maxQueueSize());
        this.equipmentTracker = new EquipmentTracker(this);
        this.inventoryTracker = new InventoryTracker(this);
        this.entityTracker = new EntityTracker(this);
        this.pipeline = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ExtendedReplay-Pipeline");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        transport.start();
        pipeline.scheduleWithFixedDelay(this::drainToTransport, 25, 25, TimeUnit.MILLISECONDS);
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::captureTick, 1L, 1L);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        for (ActiveSession session : List.copyOf(sessionsById.values())) {
            endSession(session.sessionId(), ReplaySessionEndReason.SERVER_SHUTDOWN);
        }
        // final drain so SESSION_END reaches the transport before it closes
        drainToTransport();
        pipeline.shutdown();
        try {
            if (!pipeline.awaitTermination(3, TimeUnit.SECONDS)) {
                pipeline.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        transport.close();
    }

    // --- session lifecycle ---

    public ActiveSession startSession(String name, String externalKey, World world,
                                      ReplayBounds bounds, Map<String, String> metadata) {
        if (sessionsByWorld.containsKey(world.getName())) {
            throw new IllegalStateException("A session is already recording world " + world.getName());
        }
        ActiveSession session = new ActiveSession(UUID.randomUUID(), name, externalKey,
                world.getName(), bounds);
        sessionsByWorld.put(world.getName(), session);
        sessionsById.put(session.sessionId(), session);

        // metadata may be null or immutable (e.g. Map.of()): copy defensively before enriching.
        Map<String, String> sessionMetadata = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        sessionMetadata.putIfAbsent("world-seed", Long.toString(world.getSeed()));
        sessionMetadata.putIfAbsent("world-environment", world.getEnvironment().name());

        boolean hasBounds = bounds != null;
        offer(new ReplayPacket.SessionStart(session.sessionId(), name, externalKey,
                world.getName(), session.startedAtMillis(), FormatConstants.FORMAT_VERSION,
                hasBounds ? bounds.minX() : 0, hasBounds ? bounds.minY() : 0,
                hasBounds ? bounds.minZ() : 0, hasBounds ? bounds.maxX() : 0,
                hasBounds ? bounds.maxY() : 0, hasBounds ? bounds.maxZ() : 0,
                hasBounds, sessionMetadata));

        recordEvent(session, "SESSION_START", "SESSION", null, null, null, true, Map.of());
        if (sessionMetadata.containsKey("snapshot")) {
            offer(new ReplayPacket.SnapshotReference(session.sessionId(),
                    sessionMetadata.get("snapshot"), sessionMetadata.get("snapshot-sha256")));
        }
        for (Player player : world.getPlayers()) {
            registerPlayer(session, player);
        }
        entityTracker.sessionStarted(session, world);
        plugin.getLogger().info("Recording session '" + name + "' started in world "
                + world.getName() + " (" + session.sessionId() + ")");
        return session;
    }

    public void endSession(UUID sessionId, ReplaySessionEndReason reason) {
        ActiveSession session = sessionsById.remove(sessionId);
        if (session == null || !session.isActive()) {
            return;
        }
        session.deactivate();
        sessionsByWorld.remove(session.worldName(), session);
        recordEvent(session, "SESSION_END", "SESSION", null, null, null, true,
                Map.of("reason", reason.name()));
        offer(new ReplayPacket.SessionEnd(session.sessionId(), session.currentTick(), reason.name()));
        entityTracker.sessionEnded(session.sessionId());
        plugin.getLogger().info("Recording session '" + session.name() + "' ended: " + reason);
    }

    /**
     * Ends a session and starts visible transfer-progress feedback for whoever triggered
     * it: a boss bar for a player, chat/log lines for console or other command senders.
     * Feedback only makes sense when a session actually ended and there is a transport
     * that could still be catching up, so this overload is reserved for command-triggered
     * stops — the plain {@link #endSession} (also used for shutdown) stays silent.
     */
    public void endSession(UUID sessionId, ReplaySessionEndReason reason, CommandSender feedbackTarget) {
        ActiveSession session = sessionsById.get(sessionId); // peek before it gets removed
        String name = session != null ? session.name() : sessionId.toString();
        endSession(sessionId, reason);
        if (feedbackTarget != null) {
            new TransferProgressWatcher(plugin, this, name, feedbackTarget).start();
        }
    }

    public Optional<ActiveSession> sessionForWorld(String worldName) {
        return Optional.ofNullable(sessionsByWorld.get(worldName));
    }

    public Optional<ActiveSession> sessionById(UUID sessionId) {
        return Optional.ofNullable(sessionsById.get(sessionId));
    }

    public Optional<ActiveSession> sessionByExternalKey(String externalKey) {
        return sessionsById.values().stream()
                .filter(s -> externalKey.equals(s.externalKey()))
                .findFirst();
    }

    public List<ActiveSession> activeSessions() {
        return List.copyOf(sessionsById.values());
    }

    // --- capture ---

    /** Runs every server tick on the main thread. Budget: primitives only. */
    private void captureTick() {
        if (sessionsById.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        int frames = 0;
        for (ActiveSession session : sessionsById.values()) {
            int tick = session.advanceTick();
            World world = Bukkit.getWorld(session.worldName());
            if (world == null) {
                continue;
            }
            for (Player player : world.getPlayers()) {
                if (!session.isTracked(player.getUniqueId())) {
                    continue;
                }
                offer(new ReplayPacket.PlayerFramePacket(session.sessionId(),
                        buildFrame(session, tick, player)));
                frames++;
            }
            // equipment fingerprint check every 10 ticks, serialization only on change
            if (tick % 10 == 0) {
                equipmentTracker.checkWorld(session, world, tick);
            }
            entityTracker.sampleTick(session, world, tick);
        }
        inventoryTracker.flushEndOfTick();
        long elapsed = System.nanoTime() - start;
        metrics.recordCaptureTime(elapsed);
        metrics.addFrames(frames);
    }

    private PlayerFrame buildFrame(ActiveSession session, int tick, Player player) {
        var loc = player.getLocation();
        short flags = 0;
        if (player.isSneaking()) flags |= PlayerFrame.FLAG_SNEAKING;
        if (player.isSprinting()) flags |= PlayerFrame.FLAG_SPRINTING;
        if (player.isSwimming()) flags |= PlayerFrame.FLAG_SWIMMING;
        if (player.isGliding()) flags |= PlayerFrame.FLAG_GLIDING;
        if (player.isBlocking()) flags |= PlayerFrame.FLAG_BLOCKING;
        if (player.isHandRaised()) flags |= PlayerFrame.FLAG_USING_ITEM;
        if (player.isOnGround()) flags |= PlayerFrame.FLAG_ON_GROUND;
        if (player.isInsideVehicle()) flags |= PlayerFrame.FLAG_IN_VEHICLE;
        if (player.isSleeping()) flags |= PlayerFrame.FLAG_SLEEPING;
        if (player.isDead()) flags |= PlayerFrame.FLAG_DEAD;
        if (player.isInvisible()) flags |= PlayerFrame.FLAG_INVISIBLE;
        if (player.isGlowing()) flags |= PlayerFrame.FLAG_GLOWING;

        return new PlayerFrame(tick,
                session.playerIndex(player.getUniqueId()),
                loc.getX(), loc.getY(), loc.getZ(),
                PacketIO.angleToByte(loc.getYaw()),
                PacketIO.angleToByte(loc.getPitch()),
                PacketIO.angleToByte(player.getBodyYaw()),
                flags,
                (byte) player.getInventory().getHeldItemSlot(),
                PlayerFrame.packHealth(player.getHealth()),
                (byte) player.getFoodLevel(),
                session.currentEquipmentVersion(player.getUniqueId()));
    }

    /** Registers a player into the session: index, profile packet, initial state. */
    public void registerPlayer(ActiveSession session, Player player) {
        if (!session.registerPlayer(player.getUniqueId())) {
            return;
        }
        int index = session.playerIndex(player.getUniqueId());

        String skinValue = null;
        String skinSignature = null;
        for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
            if ("textures".equals(property.getName())) {
                skinValue = property.getValue();
                skinSignature = property.getSignature();
            }
        }
        offer(new ReplayPacket.PlayerProfile(session.sessionId(),
                new PlayerProfileData(index, player.getUniqueId(), player.getName(),
                        skinValue, skinSignature)));
        offer(new ReplayPacket.PlayerJoin(session.sessionId(), session.currentTick(), index));

        equipmentTracker.captureNow(session, player);
        inventoryTracker.markDirty(player.getUniqueId(), "join");
    }

    public void unregisterPlayer(ActiveSession session, Player player) {
        int index = session.playerIndex(player.getUniqueId());
        if (index >= 0) {
            offer(new ReplayPacket.PlayerQuit(session.sessionId(), session.currentTick(), index));
        }
    }

    // --- event/bookmark recording (API + listeners) ---

    public void recordEvent(ActiveSession session, String eventType, String category,
                            UUID actor, UUID target, org.bukkit.Location location,
                            boolean critical, Map<String, String> metadata) {
        PacketType packetType = switch (category) {
            case "KILL" -> PacketType.KILL_EVENT;
            case "DEATH" -> PacketType.DEATH_EVENT;
            case "COMBAT" -> PacketType.DAMAGE_EVENT;
            case "CHAT" -> PacketType.CHAT_EVENT;
            case "ITEM" -> PacketType.ITEM_EVENT;
            case "WORLD" -> PacketType.WORLD_STATE_CHANGE;
            case "BOOKMARK" -> PacketType.BOOKMARK;
            default -> PacketType.CUSTOM_EVENT;
        };
        String worldName = null;
        double x = 0;
        double y = 0;
        double z = 0;
        if (location != null && location.getWorld() != null) {
            worldName = location.getWorld().getName();
            x = location.getX();
            y = location.getY();
            z = location.getZ();
        }
        offer(new ReplayPacket.TimelineEventPacket(session.sessionId(), packetType,
                new TimelineEvent(session.currentTick(), eventType, category,
                        actor != null ? session.playerIndex(actor) : -1,
                        target != null ? session.playerIndex(target) : -1,
                        worldName, x, y, z, critical, metadata)));
    }

    public void recordApiEvent(ActiveSession session, ReplayEvent event) {
        PacketType packetType = switch (event.category()) {
            case KILL -> PacketType.KILL_EVENT;
            case DEATH -> PacketType.DEATH_EVENT;
            case COMBAT -> PacketType.DAMAGE_EVENT;
            case CHAT -> PacketType.CHAT_EVENT;
            case ITEM -> PacketType.ITEM_EVENT;
            case WORLD -> PacketType.WORLD_STATE_CHANGE;
            case BOOKMARK -> PacketType.BOOKMARK;
            default -> PacketType.CUSTOM_EVENT;
        };
        offer(new ReplayPacket.TimelineEventPacket(session.sessionId(), packetType,
                new TimelineEvent(session.currentTick(), event.type(), event.category().name(),
                        event.actor() != null ? session.playerIndex(event.actor()) : -1,
                        event.target() != null ? session.playerIndex(event.target()) : -1,
                        event.hasLocation() ? event.worldName() : null,
                        event.x(), event.y(), event.z(), event.critical(), event.metadata())));
    }

    public void recordBookmark(ActiveSession session, ReplayBookmark bookmark) {
        int tick = bookmark.tick() >= 0 ? bookmark.tick() : session.currentTick();
        offer(new ReplayPacket.TimelineEventPacket(session.sessionId(), PacketType.BOOKMARK,
                new TimelineEvent(tick, bookmark.name(), "BOOKMARK", -1, -1,
                        null, 0, 0, 0, true,
                        bookmark.note() != null ? Map.of("note", bookmark.note()) : Map.of())));
    }

    // --- plumbing ---

    public void offer(ReplayPacket packet) {
        queue.offer(packet);
    }

    private void drainToTransport() {
        List<ReplayPacket> batch = queue.drain(10_000);
        for (ReplayPacket packet : batch) {
            transport.send(packet);
        }
        metrics.setDegradationLevel(queue.isUnderPressure() ? 1 : 0);
    }

    public RecordingQueue queue() {
        return queue;
    }

    public CaptureMetrics metrics() {
        return metrics;
    }

    public ReplayTransport transport() {
        return transport;
    }

    public ReplayConfig config() {
        return config;
    }

    public InventoryTracker inventoryTracker() {
        return inventoryTracker;
    }

    public EquipmentTracker equipmentTracker() {
        return equipmentTracker;
    }

    public EntityTracker entityTracker() {
        return entityTracker;
    }

    public Plugin plugin() {
        return plugin;
    }

    /** Sessions the player participates in (player's world has an active session). */
    public Optional<ActiveSession> sessionOf(Player player) {
        ActiveSession session = sessionsByWorld.get(player.getWorld().getName());
        return session != null && session.isTracked(player.getUniqueId())
                ? Optional.of(session) : Optional.empty();
    }
}
