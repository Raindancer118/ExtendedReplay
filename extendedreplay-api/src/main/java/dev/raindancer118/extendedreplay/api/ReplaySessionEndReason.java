package dev.raindancer118.extendedreplay.api;

/** Why a recording session ended. Stored in the session metadata. */
public enum ReplaySessionEndReason {
    /** Regular end, e.g. the match finished. */
    COMPLETED,
    /** Manually stopped by a command or the integrating plugin. */
    STOPPED,
    /** Aborted, e.g. match cancelled. */
    ABORTED,
    /** The server is shutting down. */
    SERVER_SHUTDOWN,
    /** Recording infrastructure failed; the session may be incomplete. */
    FAILURE
}
