package dev.raindancer118.extendedreplay.core.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a recorded player, captured once per session. The skin texture value and
 * signature come from the already-loaded game profile; no Mojang lookups happen during
 * recording.
 */
public record PlayerProfileData(
        int playerIndex,
        UUID uuid,
        String name,
        String skinTextureValue,     // may be null
        String skinTextureSignature  // may be null
) {

    public PlayerProfileData {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
    }

    public boolean hasSkin() {
        return skinTextureValue != null;
    }
}
