package dev.raindancer118.extendedreplay.paper.producer;

import dev.raindancer118.extendedreplay.paper.config.ReplayConfig;
import dev.raindancer118.extendedreplay.paper.snapshot.SnapshotService;
import dev.raindancer118.extendedreplay.paper.snapshot.SnapshotTransfer;
import dev.raindancer118.extendedreplay.paper.util.Errors;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Encapsulates the full "start a recording" flow shared by {@code /erp record start} and
 * the producer control GUI ({@link dev.raindancer118.extendedreplay.paper.gui.RecordControlGui}).
 *
 * <p>Three paths, in order: (a) a session is already recording the target world — refuse;
 * (b) a manual snapshot name was given ("-" explicitly skips it, gated by
 * {@link ReplayConfig#requireSnapshot()}) — start right away; (c) otherwise, if
 * {@link ReplayConfig#autoSnapshot()} is on, create an arena snapshot around the anchor,
 * upload it via {@link SnapshotTransfer}, then start the session with a
 * {@code snapshot} metadata reference. Snapshot creation/upload never touch the main
 * thread themselves (see {@link SnapshotService#create} / {@link SnapshotTransfer#upload});
 * {@link ProducerManager#startSession} always runs there because it touches Bukkit world
 * state (online players, seed, …).</p>
 */
public final class RecordingStarter {

    private final Plugin plugin;
    private final ProducerManager producer;
    private final SnapshotService snapshots;
    private final SnapshotTransfer snapshotTransfer;
    private final ReplayConfig config;

    public RecordingStarter(Plugin plugin, ProducerManager producer, SnapshotService snapshots,
                            SnapshotTransfer snapshotTransfer) {
        this.plugin = plugin;
        this.producer = producer;
        this.snapshots = snapshots;
        this.snapshotTransfer = snapshotTransfer;
        this.config = producer.config();
    }

    /**
     * Starts a recording session for {@code world}, resolving the arena snapshot as
     * described in the class doc.
     *
     * @param anchor             world-space origin for the auto-snapshot radius and for
     *                           the console fallback (world spawn) — ignored for (b)/plain
     * @param radiusOverride     GUI/command override for the auto-snapshot radius, or
     *                           {@code null} to use {@link ReplayConfig#autoSnapshotRadius()}
     * @param manualSnapshotName an explicit snapshot name, {@code "-"} to explicitly record
     *                           without a snapshot, or {@code null} to fall back to
     *                           auto-snapshot / plain start
     */
    public void start(CommandSender initiator, String sessionName, World world, Location anchor,
                      Integer radiusOverride, String manualSnapshotName) {
        if (producer.sessionForWorld(world.getName()).isPresent()) {
            send(initiator, "In Welt '" + world.getName() + "' läuft bereits eine Aufnahme.");
            return;
        }
        if (manualSnapshotName != null) {
            startWithManualSnapshot(initiator, sessionName, world, manualSnapshotName);
            return;
        }
        if (config.autoSnapshot()) {
            startWithAutoSnapshot(initiator, sessionName, world, anchor, radiusOverride);
            return;
        }
        if (config.requireSnapshot()) {
            send(initiator, "Aufnahme ohne Snapshot ist deaktiviert (producer.auto-snapshot.require).");
            return;
        }
        startSessionNow(initiator, sessionName, world, new HashMap<>(), null);
    }

    private void startWithManualSnapshot(CommandSender initiator, String sessionName, World world,
                                         String manualSnapshotName) {
        if (manualSnapshotName.equals("-")) {
            if (config.requireSnapshot()) {
                send(initiator, "Aufnahme ohne Snapshot ist deaktiviert (producer.auto-snapshot.require).");
                return;
            }
            startSessionNow(initiator, sessionName, world, new HashMap<>(), null);
            return;
        }
        if (!snapshots.exists(manualSnapshotName)) {
            send(initiator, "Snapshot '" + manualSnapshotName + "' existiert nicht — "
                    + "erst /erp snapshot create " + manualSnapshotName);
            return;
        }
        Map<String, String> metadata = new HashMap<>();
        metadata.put("snapshot", manualSnapshotName);
        startSessionNow(initiator, sessionName, world, metadata, null);
    }

    private void startWithAutoSnapshot(CommandSender initiator, String sessionName, World world,
                                       Location anchor, Integer radiusOverride) {
        String snapshotName = sessionName + "-arena";
        int radius = radiusOverride != null ? radiusOverride : config.autoSnapshotRadius();
        int minX = anchor.getBlockX() - radius;
        int maxX = anchor.getBlockX() + radius;
        int minZ = anchor.getBlockZ() - radius;
        int maxZ = anchor.getBlockZ() + radius;
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;

        Feedback feedback = new Feedback(plugin, initiator, sessionName);
        feedback.phase("Erstelle Arena-Snapshot '" + snapshotName + "'…", BossBar.Color.BLUE, 0f);

        Player progressViewer = initiator instanceof Player p ? p : null;
        snapshots.create(snapshotName, world, minX, minY, minZ, maxX, maxY, maxZ, progressViewer)
                .whenComplete((sha, createError) -> {
                    if (createError != null) {
                        feedback.fail("Snapshot fehlgeschlagen: " + Errors.describe(createError));
                        return;
                    }
                    feedback.phase("Übertrage Snapshot '" + snapshotName + "'… 0%", BossBar.Color.BLUE, 0f);
                    Path erpaFile = snapshots.fileOf(snapshotName);
                    snapshotTransfer.upload(snapshotName, erpaFile, percent ->
                            feedback.phase("Übertrage Snapshot '" + snapshotName + "'… " + percent + "%",
                                    BossBar.Color.BLUE, Math.max(0f, Math.min(1f, percent / 100f))))
                            .whenComplete((v, uploadError) -> {
                                if (uploadError != null) {
                                    feedback.fail("Snapshot-Übertragung fehlgeschlagen: "
                                            + Errors.describe(uploadError));
                                    return;
                                }
                                Map<String, String> metadata = new HashMap<>();
                                metadata.put("snapshot", snapshotName);
                                metadata.put("snapshot-sha256", sha);
                                metadata.put("auto-snapshot-radius", String.valueOf(radius));
                                Bukkit.getScheduler().runTask(plugin, () ->
                                        startSessionNow(initiator, sessionName, world, metadata, feedback));
                            });
                });
    }

    private void startSessionNow(CommandSender initiator, String sessionName, World world,
                                 Map<String, String> metadata, Feedback feedback) {
        metadata.putIfAbsent("started-by", initiator.getName());
        if (initiator instanceof Player player) {
            metadata.putIfAbsent("started-by-uuid", player.getUniqueId().toString());
        }
        try {
            ActiveSession session = producer.startSession(sessionName, null, world, null, metadata);
            String snapshotNote = metadata.containsKey("snapshot")
                    ? " · Snapshot: " + metadata.get("snapshot") : "";
            String message = "Aufnahme gestartet: " + sessionName + " (" + session.sessionId() + ")"
                    + " · Welt: " + world.getName() + snapshotNote;
            if (feedback != null) {
                feedback.succeed(message);
            } else {
                send(initiator, message);
            }
        } catch (Exception e) {
            String message = "Aufnahme konnte nicht gestartet werden: " + Errors.describe(e);
            if (feedback != null) {
                feedback.fail(message);
            } else {
                send(initiator, message);
            }
        }
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message));
    }

    /**
     * Visible feedback for the multi-step auto-snapshot flow: a boss bar for a player
     * initiator, chat/log lines for console. Never holds a {@link Player} reference across
     * async steps — only the {@link UUID}, resolved fresh every update — exactly like
     * {@link TransferProgressWatcher}'s memory-leak-safe pattern.
     */
    private static final class Feedback {
        private static final long LINGER_TICKS = 40L; // 2 seconds

        private final Plugin plugin;
        private final UUID playerId;
        private final CommandSender consoleLikeSender;
        private final String sessionName;
        private BossBar bossBar;

        Feedback(Plugin plugin, CommandSender sender, String sessionName) {
            this.plugin = plugin;
            this.sessionName = sessionName;
            if (sender instanceof Player player) {
                this.playerId = player.getUniqueId();
                this.consoleLikeSender = null;
            } else {
                this.playerId = null;
                this.consoleLikeSender = sender;
            }
        }

        void phase(String text, BossBar.Color color, float progress) {
            runOnMain(() -> {
                Player player = resolvePlayer();
                if (player == null) {
                    return;
                }
                BossBar bar = ensureBossBar(player);
                bar.name(Component.text(text, NamedTextColor.AQUA));
                bar.color(color);
                bar.progress(progress);
            });
            if (consoleLikeSender != null) {
                consoleLikeSender.sendMessage(Component.text(text));
            }
        }

        void succeed(String message) {
            runOnMain(() -> {
                Player player = resolvePlayer();
                if (player == null) {
                    return;
                }
                BossBar bar = ensureBossBar(player);
                bar.name(Component.text("✔ Aufnahme läuft", NamedTextColor.GREEN));
                bar.color(BossBar.Color.GREEN);
                bar.progress(1f);
                lingerThenHide(player);
            });
            plugin.getLogger().info(message);
            if (consoleLikeSender != null) {
                consoleLikeSender.sendMessage(Component.text("✔ " + message, NamedTextColor.GREEN));
            }
        }

        void fail(String message) {
            runOnMain(() -> {
                Player player = resolvePlayer();
                if (player == null) {
                    return;
                }
                BossBar bar = ensureBossBar(player);
                bar.name(Component.text("✘ " + message, NamedTextColor.RED));
                bar.color(BossBar.Color.RED);
                lingerThenHide(player);
            });
            plugin.getLogger().warning("Aufnahme '" + sessionName + "': " + message);
            if (consoleLikeSender != null) {
                consoleLikeSender.sendMessage(Component.text("✘ " + message, NamedTextColor.RED));
            }
        }

        private void lingerThenHide(Player player) {
            BossBar bar = bossBar;
            if (bar == null) {
                return;
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player p = resolvePlayer();
                if (p != null) {
                    p.hideBossBar(bar);
                }
            }, LINGER_TICKS);
        }

        private BossBar ensureBossBar(Player player) {
            if (bossBar == null) {
                bossBar = BossBar.bossBar(Component.text("Aufnahme wird vorbereitet…"),
                        0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            }
            player.showBossBar(bossBar);
            return bossBar;
        }

        private Player resolvePlayer() {
            return playerId != null ? Bukkit.getPlayer(playerId) : null;
        }

        private void runOnMain(Runnable action) {
            if (Bukkit.isPrimaryThread()) {
                action.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, action);
            }
        }
    }
}
