package dev.raindancer118.extendedreplay.storage.meta;

import java.util.UUID;

/** One row of the sessions table. */
public record SessionRecord(
        UUID sessionId,
        String name,
        String externalKey,
        String worldName,
        long startedAtMillis,
        long endedAtMillis,   // 0 while active
        int lastTick,
        String endReason,     // null while active
        String snapshotName,  // null when none referenced
        boolean favorite,
        int formatVersion) {

    public boolean isFinished() {
        return endedAtMillis > 0;
    }

    public int durationSeconds() {
        return lastTick / 20;
    }
}
