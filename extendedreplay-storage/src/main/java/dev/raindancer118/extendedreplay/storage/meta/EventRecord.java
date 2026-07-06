package dev.raindancer118.extendedreplay.storage.meta;

import java.util.Map;
import java.util.UUID;

/** One indexed timeline event row, used by /erp events and the event browser. */
public record EventRecord(
        long id,
        UUID sessionId,
        int tick,
        String eventType,
        String category,
        UUID actor,        // may be null
        UUID target,       // may be null
        String worldName,  // may be null
        double x,
        double y,
        double z,
        Map<String, String> metadata) {

    public boolean hasLocation() {
        return worldName != null;
    }
}
