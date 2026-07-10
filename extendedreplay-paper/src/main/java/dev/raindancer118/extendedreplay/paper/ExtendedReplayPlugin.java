package dev.raindancer118.extendedreplay.paper;

import dev.raindancer118.extendedreplay.api.ExtendedReplayApi;
import dev.raindancer118.extendedreplay.core.pipeline.CaptureMetrics;
import dev.raindancer118.extendedreplay.paper.api.ApiImpl;
import dev.raindancer118.extendedreplay.paper.command.ErpCommand;
import dev.raindancer118.extendedreplay.paper.config.ReplayConfig;
import dev.raindancer118.extendedreplay.paper.config.ServerRole;
import dev.raindancer118.extendedreplay.paper.gui.GuiListener;
import dev.raindancer118.extendedreplay.paper.gui.HotbarUI;
import dev.raindancer118.extendedreplay.paper.producer.CaptureListeners;
import dev.raindancer118.extendedreplay.paper.producer.ProducerManager;
import dev.raindancer118.extendedreplay.paper.replay.PlaybackManager;
import dev.raindancer118.extendedreplay.paper.replay.ReplayServerManager;
import dev.raindancer118.extendedreplay.paper.replay.route.RouteManager;
import dev.raindancer118.extendedreplay.transport.LocalLoopbackTransport;
import dev.raindancer118.extendedreplay.transport.ReplayTransport;
import dev.raindancer118.extendedreplay.transport.websocket.WebSocketReplayClient;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Plugin entry point. Wires producer/replay components according to the configured
 * server role; every component failure degrades gracefully — the game server never dies
 * because of replay infrastructure.
 */
public final class ExtendedReplayPlugin extends JavaPlugin {

    private ReplayConfig config;
    private final CaptureMetrics metrics = new CaptureMetrics();

    private ProducerManager producer;
    private ReplayServerManager replayServer;
    private PlaybackManager playback;
    private RouteManager routes;
    private HotbarUI hotbar;
    private GuiListener guiListener;
    private ApiImpl api;
    private dev.raindancer118.extendedreplay.paper.snapshot.SnapshotService snapshots;
    private dev.raindancer118.extendedreplay.paper.replay.live.LiveMirrorManager liveMirror;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = ReplayConfig.from(getConfig());
        getLogger().info("Server role: " + config.role());

        try {
            switch (config.role()) {
                case PRODUCER -> enableProducer();
                case REPLAY -> enableReplayServer();
                case STANDALONE -> enableStandalone();
                case DISABLED -> getLogger().info("ExtendedReplay is DISABLED by config.");
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE,
                    "ExtendedReplay failed to start — plugin stays inactive, game continues.", e);
            producer = null;
            replayServer = null;
            playback = null;
        }

        var command = getCommand("erp");
        if (command != null) {
            ErpCommand executor = new ErpCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    private void enableProducer() throws Exception {
        if (!config.producerEnabled()) {
            getLogger().info("Producer capture disabled by config.");
            return;
        }
        ReplayTransport transport = new WebSocketReplayClient(getLogger(),
                config.transportHost(), config.transportPort(), config.authToken(),
                "producer:" + Bukkit.getPort(),
                config.batchIntervalMs(),
                getDataPath().resolve("spool.erpq"), config.maxSpoolBytes(), metrics);
        producer = new ProducerManager(this, config, transport, metrics);
        producer.start();
        registerProducerParts();
        getLogger().info("Producer active — streaming to " + config.transportHost()
                + ":" + config.transportPort());
    }

    private void enableReplayServer() throws Exception {
        replayServer = new ReplayServerManager(this, config, metrics, true);
        replayServer.start();
        registerReplayParts();
        getLogger().info("Replay server active — listening on " + config.bindHost()
                + ":" + config.bindPort());
    }

    private void enableStandalone() throws Exception {
        replayServer = new ReplayServerManager(this, config, metrics, false);
        replayServer.start();
        ReplayTransport transport = new LocalLoopbackTransport(replayServer::ingest);
        producer = new ProducerManager(this, config, transport, metrics);
        producer.start();
        registerProducerParts();
        registerReplayParts();
        getLogger().info("STANDALONE mode: local recording and playback active.");
    }

    private void registerProducerParts() {
        Bukkit.getPluginManager().registerEvents(new CaptureListeners(producer), this);
        api = new ApiImpl(producer);
        Bukkit.getServicesManager().register(ExtendedReplayApi.class, api, this,
                ServicePriority.Normal);
        ensureSnapshotService();
        getLogger().info("ExtendedReplayApi registered in the ServicesManager.");
    }

    private void registerReplayParts() {
        int stale = dev.raindancer118.extendedreplay.paper.replay.PlaybackWorlds.cleanupStaleWorldFolders();
        if (stale > 0) {
            getLogger().info(stale + " alte Playback-Welt(en) aufgeräumt.");
        }
        hotbar = new HotbarUI(this);
        snapshots = new dev.raindancer118.extendedreplay.paper.snapshot.SnapshotService(this,
                getServer().getWorldContainer().toPath().resolve(config.snapshotPath()));
        // only the dedicated REPLAY server puts viewers in a lobby between sessions —
        // STANDALONE is actually played/recorded on and keeps the classic restore behavior
        boolean replayLobbyMode = config.role() == ServerRole.REPLAY;
        playback = new PlaybackManager(this, config, replayServer, hotbar, snapshots, replayLobbyMode);
        playback.start();
        routes = new RouteManager(this, config, replayServer.storage());
        liveMirror = new dev.raindancer118.extendedreplay.paper.replay.live.LiveMirrorManager(
                this, config, replayServer, snapshots, hotbar, replayLobbyMode);
        guiListener = new GuiListener(this, playback, replayServer.storage(), hotbar, routes);
        Bukkit.getPluginManager().registerEvents(guiListener, this);
        scheduleRetention();
        if (replayLobbyMode) {
            Bukkit.getPluginManager().registerEvents(
                    new dev.raindancer118.extendedreplay.paper.gui.ReplayLobbyListener(this), this);
            if (config.disableNaturalSpawning()) {
                var spawningGuard = new dev.raindancer118.extendedreplay.paper.replay.NaturalSpawningGuard();
                spawningGuard.disableForLoadedWorlds();
                Bukkit.getPluginManager().registerEvents(spawningGuard, this);
            }
        }
    }

    /** Applies retention rules shortly after start and then once per day. */
    private void scheduleRetention() {
        long dayTicks = 24L * 60 * 60 * 20;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                var deleted = replayServer.storage().cleanup(config.retentionDays(),
                        config.maxStorageBytes());
                if (!deleted.isEmpty()) {
                    getLogger().info("Retention: " + deleted.size() + " alte Session(s) gelöscht.");
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Retention cleanup failed", e);
            }
        }, 20L * 60, dayTicks);
    }

    /** Snapshots are producer-side too: /erp snapshot create on the game server. */
    private void ensureSnapshotService() {
        if (snapshots == null) {
            snapshots = new dev.raindancer118.extendedreplay.paper.snapshot.SnapshotService(this,
                    getServer().getWorldContainer().toPath().resolve(config.snapshotPath()));
        }
    }

    @Override
    public void onDisable() {
        if (producer != null) {
            producer.shutdown();
        }
        if (liveMirror != null) {
            liveMirror.stop();
        }
        if (playback != null) {
            playback.shutdown();
        }
        if (replayServer != null) {
            replayServer.shutdown();
        }
    }

    /**
     * End-to-end self-test for /erp test: records a synthetic session through the real
     * pipeline into storage, reads it back and verifies checksums. Runs async, reports
     * through the callback (already thread-safe: Adventure messages may be sent async).
     */
    public void runSelfTest(Consumer<String> report) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                var storage = replayServer.storage();
                java.util.UUID id = java.util.UUID.randomUUID();
                storage.ingest(new dev.raindancer118.extendedreplay.core.protocol.ReplayPacket.SessionStart(
                        id, "self-test", null, "self-test-world", System.currentTimeMillis(),
                        dev.raindancer118.extendedreplay.core.FormatConstants.FORMAT_VERSION,
                        0, 0, 0, 0, 0, 0, false, java.util.Map.of("origin", "self-test")));
                storage.ingest(new dev.raindancer118.extendedreplay.core.protocol.ReplayPacket.PlayerProfile(
                        id, new dev.raindancer118.extendedreplay.core.model.PlayerProfileData(
                        0, java.util.UUID.randomUUID(), "TestPlayer", null, null)));
                for (int tick = 0; tick < 200; tick++) {
                    storage.ingest(new dev.raindancer118.extendedreplay.core.protocol.ReplayPacket.PlayerFramePacket(
                            id, new dev.raindancer118.extendedreplay.core.model.PlayerFrame(
                            tick, 0, tick * 0.3, 64, 0, (byte) 0, (byte) 0, (byte) 0,
                            (short) 0, (byte) 0, (short) 200, (byte) 20, 0)));
                }
                storage.ingest(new dev.raindancer118.extendedreplay.core.protocol.ReplayPacket.SessionEnd(
                        id, 200, "COMPLETED"));

                int packets = storage.loadSession(id).size();
                var problems = storage.verifySession(id);
                if (problems.isEmpty() && packets >= 202) {
                    report.accept("✔ Selbsttest OK: " + packets
                            + " Pakete geschrieben, gelesen und verifiziert (Session self-test).");
                } else {
                    report.accept("✘ Selbsttest fehlgeschlagen: " + packets
                            + " Pakete, Probleme: " + problems);
                }
                storage.deleteSessionData(id);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Self-test failed", e);
                report.accept("✘ Selbsttest-Fehler: " + e.getMessage());
            }
        });
    }

    // --- accessors for command/GUI layer ---

    public ServerRole role() {
        return config.role();
    }

    public ReplayConfig config() {
        return config;
    }

    public CaptureMetrics metrics() {
        return metrics;
    }

    public ProducerManager producer() {
        return producer;
    }

    public ReplayServerManager replayServer() {
        return replayServer;
    }

    public PlaybackManager playback() {
        return playback;
    }

    public RouteManager routes() {
        return routes;
    }

    public HotbarUI hotbar() {
        return hotbar;
    }

    public GuiListener guiListener() {
        return guiListener;
    }

    public ApiImpl api() {
        return api;
    }

    public dev.raindancer118.extendedreplay.paper.snapshot.SnapshotService snapshots() {
        return snapshots;
    }

    public dev.raindancer118.extendedreplay.paper.replay.live.LiveMirrorManager liveMirror() {
        return liveMirror;
    }
}
