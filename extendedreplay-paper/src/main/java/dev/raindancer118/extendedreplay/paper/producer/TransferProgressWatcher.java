package dev.raindancer118.extendedreplay.paper.producer;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/**
 * Watches a just-ended recording session drain out of the local queue and the transport
 * (spool/outbound buffer) after {@code /erp record stop}, giving the operator visible
 * feedback on the producer server about when the recording is actually safe to play back
 * on the replay server.
 *
 * <p>Never holds a {@link Player} reference across ticks — only the {@link UUID}, resolved
 * fresh every poll, so a disconnecting player cannot leak. A non-player sender (console,
 * command block, …) is a stable Bukkit-owned singleton and is safe to hold directly.</p>
 */
final class TransferProgressWatcher {

    private static final long POLL_PERIOD_TICKS = 10L;
    private static final long TIMEOUT_TICKS = 5L * 60L * 20L; // 5 minutes
    private static final long BOSS_BAR_LINGER_TICKS = 60L; // 3 seconds

    private final Plugin plugin;
    private final ProducerManager producer;
    private final String sessionName;
    private final UUID playerId;
    private final CommandSender consoleLikeSender;

    private BukkitTask task;
    private BossBar bossBar;
    private long elapsedTicks;
    private int initialPending = -1;

    TransferProgressWatcher(Plugin plugin, ProducerManager producer, String sessionName,
                            CommandSender sender) {
        this.plugin = plugin;
        this.producer = producer;
        this.sessionName = sessionName;
        if (sender instanceof Player player) {
            this.playerId = player.getUniqueId();
            this.consoleLikeSender = null;
        } else {
            this.playerId = null;
            this.consoleLikeSender = sender;
        }
    }

    /** Starts polling. Safe to call from the main thread only (Bukkit scheduler + Adventure). */
    void start() {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, this::start);
            return;
        }
        // check once immediately: a local/idle transport may already be fully drained
        if (poll()) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> poll(), POLL_PERIOD_TICKS, POLL_PERIOD_TICKS);
    }

    /** Runs one check. Returns true if the watch is finished (completed or timed out). */
    private boolean poll() {
        int queuePending = producer.queue().size();
        int transportPending = producer.transport().pendingCount();
        int totalPending = queuePending + transportPending;
        boolean connected = producer.transport().isConnected();

        if (initialPending < 0 || totalPending > initialPending) {
            initialPending = Math.max(totalPending, 1);
        }

        if (totalPending == 0 && connected) {
            finishCompleted();
            return true;
        }
        if (elapsedTicks >= TIMEOUT_TICKS) {
            finishTimedOut();
            return true;
        }
        elapsedTicks += POLL_PERIOD_TICKS;
        updateProgress(totalPending);
        return false;
    }

    private void updateProgress(int totalPending) {
        Player player = resolvePlayer();
        if (player == null) {
            return;
        }
        float progress = Math.max(0f, Math.min(1f, 1f - (float) totalPending / initialPending));
        BossBar bar = ensureBossBar(player);
        bar.progress(progress);
        bar.color(BossBar.Color.BLUE);
        bar.name(Component.text("Übertrage Aufnahme '" + sessionName + "'… "
                + totalPending + " Pakete ausstehend", NamedTextColor.AQUA));
    }

    private void finishCompleted() {
        cancel();
        Player player = resolvePlayer();
        if (player != null) {
            BossBar bar = ensureBossBar(player);
            bar.progress(1f);
            bar.color(BossBar.Color.GREEN);
            bar.name(Component.text("✔ Aufnahme übertragen — bereit auf dem Replay-Server",
                    NamedTextColor.GREEN));
            scheduleBossBarRemoval();
        }
        String message = "Aufnahme '" + sessionName + "' vollständig auf den Replay-Server übertragen.";
        plugin.getLogger().info(message);
        if (consoleLikeSender != null) {
            consoleLikeSender.sendMessage(Component.text("✔ " + message, NamedTextColor.GREEN));
        }
    }

    private void finishTimedOut() {
        cancel();
        String message = "Replay-Server nicht erreichbar — Daten liegen im Spool und werden "
                + "nach Reconnect übertragen.";
        Player player = resolvePlayer();
        if (player != null) {
            BossBar bar = ensureBossBar(player);
            bar.color(BossBar.Color.RED);
            bar.name(Component.text("⚠ " + message, NamedTextColor.RED));
            scheduleBossBarRemoval();
        }
        plugin.getLogger().warning("Aufnahme '" + sessionName + "': " + message);
        if (consoleLikeSender != null) {
            consoleLikeSender.sendMessage(Component.text("⚠ Aufnahme '" + sessionName + "': "
                    + message, NamedTextColor.RED));
        }
    }

    private void scheduleBossBarRemoval() {
        BossBar bar = bossBar;
        if (bar == null) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = resolvePlayer();
            if (player != null) {
                player.hideBossBar(bar);
            }
        }, BOSS_BAR_LINGER_TICKS);
    }

    private BossBar ensureBossBar(Player player) {
        if (bossBar == null) {
            bossBar = BossBar.bossBar(Component.text("Übertrage Aufnahme '" + sessionName + "'…"),
                    0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        }
        player.showBossBar(bossBar);
        return bossBar;
    }

    private Player resolvePlayer() {
        return playerId != null ? Bukkit.getPlayer(playerId) : null;
    }

    private void cancel() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
