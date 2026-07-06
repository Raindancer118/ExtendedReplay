package dev.raindancer118.extendedreplay.api;

import java.util.UUID;

/**
 * Handle for an active (or finished) replay recording session.
 */
public interface ReplaySessionHandle {

    UUID sessionId();

    /** Human readable session name, e.g. {@code "hg-match-42"}. */
    String name();

    /** Optional key supplied by the integrating plugin, e.g. its own match id. */
    String externalKey();

    /** Name of the primary recorded world. */
    String worldName();

    /** Wall clock time the session started, epoch millis. */
    long startedAtMillis();

    /** Session-relative tick counter (starts at 0, advances 20/s). */
    int currentTick();

    boolean isActive();

    /** Convenience for {@link ExtendedReplayApi#recordEvent}. */
    void recordEvent(ReplayEvent event);

    /** Convenience for {@link ExtendedReplayApi#createBookmark}. */
    void createBookmark(ReplayBookmark bookmark);

    /** Convenience for {@link ExtendedReplayApi#endSession}. */
    void end(ReplaySessionEndReason reason);
}
