package dev.raindancer118.extendedreplay.paper.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.UUID;

/**
 * Builds {@code PLAYER_HEAD} items that show the real, recorded skin of a captured
 * player instead of Steve/Alex placeholders. Applies the already-signed "textures"
 * property directly to a native Paper profile, so building a head never triggers a
 * Mojang lookup or URL round-trip — the skin data was captured once during recording
 * and travels with the session. Used by every GUI/renderer that shows a recorded
 * player's head (armor stand actors, follow selection, inventory inspection, …) so the
 * skin logic lives in exactly one place.
 */
public final class Heads {

    private Heads() {
    }

    /** Head item for a recorded player; falls back to the default skin if none was captured. */
    public static ItemStack playerHead(PlayerProfileData profile) {
        return playerHead(profile.uuid(), profile.name(),
                profile.skinTextureValue(), profile.skinTextureSignature());
    }

    /** Head item built from raw profile fields. {@code skinValue} may be null (no captured skin). */
    public static ItemStack playerHead(UUID uuid, String name, String skinValue, String skinSignature) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (!(head.getItemMeta() instanceof SkullMeta meta)) {
            return head;
        }
        PlayerProfile bukkitProfile = Bukkit.createProfile(uuid, name);
        if (skinValue != null) {
            bukkitProfile.setProperty(new ProfileProperty("textures", skinValue, skinSignature));
        }
        meta.setPlayerProfile(bukkitProfile);
        head.setItemMeta(meta);
        return head;
    }
}
