package dev.raindancer118.extendedreplay.core.model;

import java.util.Map;
import java.util.Objects;

/**
 * A discrete event on the session timeline: kill, death, damage, chat, chest open,
 * custom plugin event, bookmark, world state change… The concrete kind is carried by
 * the packet type it is transported in plus {@link #eventType()}.
 */
public record TimelineEvent(
        int tick,
        String eventType,
        String category,
        int actorPlayerIndex,   // -1 when none
        int targetPlayerIndex,  // -1 when none
        String worldName,       // null when no location
        double x,
        double y,
        double z,
        boolean critical,
        Map<String, String> metadata) {

    public TimelineEvent {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(category, "category");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean hasLocation() {
        return worldName != null;
    }
}
