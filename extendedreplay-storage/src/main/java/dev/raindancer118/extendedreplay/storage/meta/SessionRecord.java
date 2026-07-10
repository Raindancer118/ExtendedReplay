package dev.raindancer118.extendedreplay.storage.meta;

import java.util.Map;
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
        int formatVersion,
        Long worldSeed,          // null when the producer world's seed is unknown
        String worldEnvironment, // null when unknown; e.g. "NORMAL", "NETHER", "THE_END"
        Map<String, String> metadata, // free-form session metadata; never null, possibly empty
        String integrity,       // one of EXACT/VERIFIED/DEGRADED/INCOMPLETE/UNKNOWN
        long sizeBytes,         // computed: sum of compressed_bytes across the session's segments
        int playerCount) {      // computed: number of distinct players indexed for the session

    public boolean isFinished() {
        return endedAtMillis > 0;
    }

    public int durationSeconds() {
        return lastTick / 20;
    }

    /** {@code metadata.get("server-name")}; null when not recorded. */
    public String serverName() {
        return metadata.get("server-name");
    }

    /** {@code metadata.get("started-by")}; null when not recorded. */
    public String startedBy() {
        return metadata.get("started-by");
    }
}
