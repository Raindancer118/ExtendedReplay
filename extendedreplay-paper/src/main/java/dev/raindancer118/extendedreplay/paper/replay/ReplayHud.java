package dev.raindancer118.extendedreplay.paper.replay;

import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent per-viewer boss bar showing playback status:
 * {@code ▶/⏸ <SessionName> · m:ss / m:ss · <speed>x[ · folgt <Name>][ · POV]}.
 * Progress reflects {@code currentTick / lastTick}; color is BLUE while playing, YELLOW
 * while paused and GREEN once the session has finished playing.
 *
 * <p>Main thread only — boss bars are Bukkit/Adventure state tied to the player connection.</p>
 */
public final class ReplayHud {

    /** Last pushed state per viewer, so unchanged ticks don't resend bar packets. */
    private record HudState(String text, float progress, BossBar.Color color) {
    }

    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, HudState> lastState = new HashMap<>();

    /** Shows the HUD bar for a viewer attaching to a session, creating it if needed. */
    public void show(Player viewer, PlaybackSession session) {
        UUID viewerId = viewer.getUniqueId();
        BossBar bar = bars.get(viewerId);
        if (bar == null) {
            bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            bars.put(viewerId, bar);
        }
        lastState.remove(viewerId); // force a fresh push even if the text happens to match
        viewer.showBossBar(bar);
        update(viewer, session);
    }

    /** Hides and forgets the viewer's HUD bar (called when they detach from a session). */
    public void hide(Player viewer) {
        UUID viewerId = viewer.getUniqueId();
        BossBar bar = bars.remove(viewerId);
        lastState.remove(viewerId);
        if (bar != null) {
            viewer.hideBossBar(bar);
        }
    }

    /**
     * Refreshes the viewer's HUD to match the session's current state. No-op if the text,
     * progress and color are all unchanged since the last update (saves network traffic on
     * the frequent poll from {@link PlaybackManager}'s tick loop).
     */
    public void update(Player viewer, PlaybackSession session) {
        UUID viewerId = viewer.getUniqueId();
        BossBar bar = bars.get(viewerId);
        if (bar == null) {
            return;
        }
        HudState state = new HudState(buildText(session), progressOf(session), colorOf(session));
        if (state.equals(lastState.get(viewerId))) {
            return;
        }
        bar.name(Component.text(state.text()));
        bar.progress(state.progress());
        bar.color(state.color());
        lastState.put(viewerId, state);
    }

    private float progressOf(PlaybackSession session) {
        int last = session.lastTickOfSession();
        if (last <= 0) {
            return 1f;
        }
        return Math.max(0f, Math.min(1f, session.currentTick() / (float) last));
    }

    private BossBar.Color colorOf(PlaybackSession session) {
        if (session.isFinished()) {
            return BossBar.Color.GREEN;
        }
        return session.isPaused() ? BossBar.Color.YELLOW : BossBar.Color.BLUE;
    }

    private String buildText(PlaybackSession session) {
        StringBuilder text = new StringBuilder();
        text.append(session.isPaused() ? '⏸' : '▶').append(' ')
                .append(session.sessionName()).append(" · ")
                .append(PlaybackSession.formatTicks(session.currentTick())).append(" / ")
                .append(PlaybackSession.formatTicks(session.lastTickOfSession())).append(" · ")
                .append(formatSpeed(session.speed())).append('x');
        Integer followed = session.followedPlayer();
        if (followed != null) {
            text.append(" · folgt ").append(followedName(session, followed));
        }
        if (session.isPovMode()) {
            text.append(" · POV");
        }
        return text.toString();
    }

    private String followedName(PlaybackSession session, int playerIndex) {
        PlayerProfileData profile = session.profiles().get(playerIndex);
        return profile != null ? profile.name() : ("Player-" + playerIndex);
    }

    /** Whole numbers print without decimals ("2x"), everything else keeps trimmed decimals ("0.5x"). */
    private static String formatSpeed(double speed) {
        if (speed == Math.rint(speed)) {
            return Long.toString((long) speed);
        }
        String formatted = String.format(Locale.ROOT, "%.2f", speed);
        formatted = formatted.replaceAll("0+$", "");
        if (formatted.endsWith(".")) {
            formatted += "0";
        }
        return formatted;
    }
}
