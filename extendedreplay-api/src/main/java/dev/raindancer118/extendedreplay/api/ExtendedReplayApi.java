package dev.raindancer118.extendedreplay.api;

import org.bukkit.World;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public entry point of ExtendedReplay for other plugins.
 *
 * <p>Obtain an instance through the Bukkit {@code ServicesManager}:</p>
 *
 * <pre>{@code
 * RegisteredServiceProvider<ExtendedReplayApi> provider =
 *         Bukkit.getServicesManager().getRegistration(ExtendedReplayApi.class);
 * if (provider != null) {
 *     ExtendedReplayApi replay = provider.getProvider();
 * }
 * }</pre>
 *
 * <p>All methods are safe to call from the main server thread. Methods never block on
 * storage or network I/O; recording work is handed off to background workers.</p>
 */
public interface ExtendedReplayApi {

    /** API semantic version, bumped on breaking changes. */
    int API_VERSION = 1;

    /**
     * Starts a new replay recording session.
     *
     * @param request session parameters (name, world, bounds, metadata)
     * @return a live handle for the started session
     * @throws IllegalStateException if this server's role does not record (REPLAY/DISABLED)
     *                               or a session is already active for the requested world
     */
    ReplaySessionHandle startSession(ReplaySessionStartRequest request);

    /**
     * Ends an active session. No-op if the session is not active.
     */
    void endSession(UUID sessionId, ReplaySessionEndReason reason);

    /**
     * Records a custom event into an active session. Events recorded through this method
     * appear in the replay event browser and can be jumped to.
     * No-op if the session is not active.
     */
    void recordEvent(UUID sessionId, ReplayEvent event);

    /**
     * Creates a bookmark at the current session time. No-op if the session is not active.
     */
    void createBookmark(UUID sessionId, ReplayBookmark bookmark);

    /** The active session recording the given world, if any. */
    Optional<ReplaySessionHandle> getActiveSession(World world);

    /** The active session registered under the given external key, if any. */
    Optional<ReplaySessionHandle> getActiveSession(String externalSessionKey);

    /** All currently active recording sessions. */
    List<ReplaySessionHandle> getActiveSessions();

    /** Whether the given session is currently recording. */
    boolean isRecording(UUID sessionId);

    /**
     * Registers a display name for a custom event type so the event browser can show a
     * friendly label instead of the raw type string. Optional.
     */
    void registerEventType(String eventType, ReplayEventCategory category, String displayName);
}
