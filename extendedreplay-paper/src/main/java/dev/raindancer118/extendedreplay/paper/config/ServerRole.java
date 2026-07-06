package dev.raindancer118.extendedreplay.paper.config;

import java.util.Locale;

/** Operating mode of this server's ExtendedReplay installation. */
public enum ServerRole {
    /** Real game server: captures and streams, never plays back. */
    PRODUCER,
    /** Replay server: receives, stores, mirrors and plays back. */
    REPLAY,
    /** Records and plays back locally; for testing, not production. */
    STANDALONE,
    /** Plugin loads but does nothing. */
    DISABLED;

    public boolean records() {
        return this == PRODUCER || this == STANDALONE;
    }

    public boolean playsBack() {
        return this == REPLAY || this == STANDALONE;
    }

    public static ServerRole parse(String value) {
        if (value == null) {
            return DISABLED;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DISABLED;
        }
    }
}
