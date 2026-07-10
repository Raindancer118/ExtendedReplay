package dev.raindancer118.extendedreplay.paper.replay;

import dev.raindancer118.extendedreplay.core.pipeline.CaptureMetrics;
import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;
import dev.raindancer118.extendedreplay.paper.config.ReplayConfig;
import dev.raindancer118.extendedreplay.paper.snapshot.SnapshotReceiver;
import dev.raindancer118.extendedreplay.storage.ReplayStorage;
import dev.raindancer118.extendedreplay.transport.websocket.WebSocketReplayServer;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Replay-server side: receives packets (via WebSocket from producers, or in-process in
 * STANDALONE mode), persists them through a dedicated storage thread and tracks which
 * sessions are currently live. Live packet listeners (live mirror) are fed from the
 * storage thread after persistence.
 */
public final class ReplayServerManager {

    private final Plugin plugin;
    private final ReplayStorage storage;
    private final WebSocketReplayServer server; // null in STANDALONE mode

    private final ConcurrentLinkedQueue<ReplayPacket> ingestQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService storageThread;
    private final Map<UUID, String> liveSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Consumer<ReplayPacket>> liveListeners = new ConcurrentHashMap<>();
    private volatile SnapshotReceiver snapshotReceiver; // set once by registerReplayParts()

    public ReplayServerManager(Plugin plugin, ReplayConfig config, CaptureMetrics metrics,
                               boolean startWebSocket) throws SQLException, IOException {
        this.plugin = plugin;
        this.storage = new ReplayStorage(
                plugin.getServer().getWorldContainer().toPath().resolve(config.storagePath()),
                config.segmentLengthSeconds(), metrics);
        this.storageThread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ExtendedReplay-Storage");
            t.setDaemon(true);
            return t;
        });
        this.server = startWebSocket
                ? new WebSocketReplayServer(plugin.getLogger(), config.bindHost(),
                config.bindPort(), config.authToken(), this::ingest)
                : null;
    }

    public void start() {
        storageThread.scheduleWithFixedDelay(this::drainToStorage, 25, 25, TimeUnit.MILLISECONDS);
        if (server != null) {
            server.start();
        }
    }

    public void shutdown() {
        if (server != null) {
            try {
                server.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        drainToStorage();
        storageThread.shutdown();
        try {
            if (!storageThread.awaitTermination(5, TimeUnit.SECONDS)) {
                storageThread.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        storage.close();
    }

    /** Entry point for all incoming packets. Thread-safe, never blocks. */
    public void ingest(ReplayPacket packet) {
        ingestQueue.add(packet);
    }

    private void drainToStorage() {
        ReplayPacket packet;
        while ((packet = ingestQueue.poll()) != null) {
            if (isSnapshotFilePacket(packet)) {
                // Snapshot file transfers are session-less and never persisted through the
                // normal segment/metadata pipeline — they go straight to the receiver, which
                // writes the incoming .erpa to disk itself. Live listeners don't need these.
                SnapshotReceiver receiver = snapshotReceiver;
                if (receiver != null) {
                    try {
                        receiver.accept(packet);
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "Snapshot receiver failed", e);
                    }
                } else {
                    plugin.getLogger().warning("Received " + packet.type()
                            + " before the snapshot receiver was ready — packet dropped.");
                }
                continue;
            }
            try {
                storage.ingest(packet);
            } catch (IOException | SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to persist replay packet", e);
            }
            trackLiveness(packet);
            for (Consumer<ReplayPacket> listener : liveListeners.values()) {
                try {
                    listener.accept(packet);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Live listener failed", e);
                }
            }
        }
    }

    private static boolean isSnapshotFilePacket(ReplayPacket packet) {
        return packet instanceof ReplayPacket.SnapshotFileBegin
                || packet instanceof ReplayPacket.SnapshotFileChunk
                || packet instanceof ReplayPacket.SnapshotFileEnd;
    }

    private void trackLiveness(ReplayPacket packet) {
        if (packet instanceof ReplayPacket.SessionStart start) {
            liveSessions.put(start.sessionId(), start.name());
            plugin.getLogger().info("Live session started: " + start.name()
                    + " (" + start.sessionId() + ")");
        } else if (packet instanceof ReplayPacket.SessionEnd end) {
            liveSessions.remove(end.sessionId());
            plugin.getLogger().info("Live session ended: " + end.sessionId());
        }
    }

    public ReplayStorage storage() {
        return storage;
    }

    /**
     * Registers the receiver that {@code SNAPSHOT_FILE_*} packets are routed to instead of
     * {@link ReplayStorage#ingest}. Set once from {@code ExtendedReplayPlugin.registerReplayParts()}
     * after the {@link dev.raindancer118.extendedreplay.paper.snapshot.SnapshotService}
     * (and thus its target directory) is known.
     */
    public void setSnapshotReceiver(SnapshotReceiver snapshotReceiver) {
        this.snapshotReceiver = snapshotReceiver;
    }

    public SnapshotReceiver snapshotReceiver() {
        return snapshotReceiver;
    }

    public Map<UUID, String> liveSessions() {
        return Map.copyOf(liveSessions);
    }

    public Optional<UUID> anyLiveSession() {
        return liveSessions.keySet().stream().findFirst();
    }

    /** Registers a consumer that sees every packet after persistence (storage thread!). */
    public void addLiveListener(UUID key, Consumer<ReplayPacket> listener) {
        liveListeners.put(key, listener);
    }

    public void removeLiveListener(UUID key) {
        liveListeners.remove(key);
    }

    public Map<String, String> metrics() {
        return server != null ? server.metrics() : Map.of("type", "local");
    }

    public List<String> verify(UUID sessionId) throws SQLException {
        return storage.verifySession(sessionId);
    }

    public int ingestQueueSize() {
        return ingestQueue.size();
    }
}
