package dev.raindancer118.extendedreplay.api;

import java.util.Objects;

/**
 * A named marker on the session timeline. When {@code tick < 0} the bookmark is placed
 * at the current session tick.
 */
public record ReplayBookmark(String name, int tick, String note) {

    public ReplayBookmark {
        Objects.requireNonNull(name, "name");
    }

    public static ReplayBookmark now(String name) {
        return new ReplayBookmark(name, -1, null);
    }

    public static ReplayBookmark at(String name, int tick) {
        return new ReplayBookmark(name, tick, null);
    }
}
