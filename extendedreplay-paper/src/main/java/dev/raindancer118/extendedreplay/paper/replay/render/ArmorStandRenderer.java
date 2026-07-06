package dev.raindancer118.extendedreplay.paper.replay.render;

import dev.raindancer118.extendedreplay.core.model.EquipmentChange;
import dev.raindancer118.extendedreplay.core.model.PlayerFrame;
import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import dev.raindancer118.extendedreplay.core.protocol.PacketIO;
import dev.raindancer118.extendedreplay.paper.util.ItemBytes;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fallback renderer: armor stands with the recorded player's head, name tag and
 * equipment. Less pretty than Mannequins but works on any Paper version.
 */
public final class ArmorStandRenderer implements ReplayActorRenderer {

    private final Map<Integer, ArmorStand> actors = new HashMap<>();
    private boolean nameTagsVisible = true;

    @Override
    public void spawnActor(World world, int playerIndex, PlayerProfileData profile,
                           Location location) {
        if (actors.containsKey(playerIndex)) {
            return;
        }
        ArmorStand stand = world.spawn(location, ArmorStand.class, entity -> {
            entity.setGravity(false);
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setArms(true);
            entity.setBasePlate(false);
            entity.customName(Component.text(profile.name()));
            entity.setCustomNameVisible(nameTagsVisible);
            ItemStack head = playerHead(profile);
            if (head != null) {
                entity.getEquipment().setItem(EquipmentSlot.HEAD, head);
            }
        });
        actors.put(playerIndex, stand);
    }

    private static ItemStack playerHead(PlayerProfileData profile) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (!(head.getItemMeta() instanceof SkullMeta meta)) {
            return null;
        }
        PlayerProfile bukkitProfile = org.bukkit.Bukkit.createPlayerProfile(
                profile.uuid(), profile.name());
        if (profile.hasSkin()) {
            URL skinUrl = skinUrlFromTexturesProperty(profile.skinTextureValue());
            if (skinUrl != null) {
                PlayerTextures textures = bukkitProfile.getTextures();
                textures.setSkin(skinUrl);
                bukkitProfile.setTextures(textures);
            }
        }
        meta.setOwnerProfile(bukkitProfile);
        head.setItemMeta(meta);
        return head;
    }

    /** Extracts the skin URL out of the base64 "textures" property without a JSON library. */
    static URL skinUrlFromTexturesProperty(String base64) {
        try {
            String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            int urlIndex = json.indexOf("\"url\"");
            if (urlIndex < 0) {
                return null;
            }
            int start = json.indexOf('"', json.indexOf(':', urlIndex) + 1) + 1;
            int end = json.indexOf('"', start);
            if (start <= 0 || end <= start) {
                return null;
            }
            return java.net.URI.create(json.substring(start, end)).toURL();
        } catch (IllegalArgumentException | MalformedURLException e) {
            return null;
        }
    }

    @Override
    public void despawnActor(int playerIndex) {
        ArmorStand stand = actors.remove(playerIndex);
        if (stand != null) {
            stand.remove();
        }
    }

    @Override
    public void despawnAll() {
        actors.values().forEach(ArmorStand::remove);
        actors.clear();
    }

    @Override
    public void applyFrame(int playerIndex, World world, PlayerFrame frame) {
        ArmorStand stand = actors.get(playerIndex);
        if (stand == null || !stand.isValid()) {
            return;
        }
        stand.teleport(new Location(world, frame.x(), frame.y(), frame.z(),
                PacketIO.byteToAngle(frame.yaw()), 0));
        boolean visible = !frame.hasFlag(PlayerFrame.FLAG_INVISIBLE)
                && !frame.hasFlag(PlayerFrame.FLAG_DEAD);
        stand.setInvisible(!visible);
        stand.setGlowing(frame.hasFlag(PlayerFrame.FLAG_GLOWING));
        stand.setSmall(frame.hasFlag(PlayerFrame.FLAG_SNEAKING));
    }

    @Override
    public void applyEquipment(int playerIndex, EquipmentChange equipment) {
        ArmorStand stand = actors.get(playerIndex);
        if (stand == null || !stand.isValid()) {
            return;
        }
        var entityEquipment = stand.getEquipment();
        entityEquipment.setItem(EquipmentSlot.HAND, ItemBytes.deserialize(equipment.mainHand()));
        entityEquipment.setItem(EquipmentSlot.OFF_HAND, ItemBytes.deserialize(equipment.offHand()));
        // head slot keeps the player skull; armor pieces go on the stand
        entityEquipment.setItem(EquipmentSlot.CHEST, ItemBytes.deserialize(equipment.chestplate()));
        entityEquipment.setItem(EquipmentSlot.LEGS, ItemBytes.deserialize(equipment.leggings()));
        entityEquipment.setItem(EquipmentSlot.FEET, ItemBytes.deserialize(equipment.boots()));
    }

    @Override
    public void setNameTagVisible(boolean visible) {
        this.nameTagsVisible = visible;
        actors.values().forEach(s -> s.setCustomNameVisible(visible));
    }

    @Override
    public boolean isSpawned(int playerIndex) {
        ArmorStand stand = actors.get(playerIndex);
        return stand != null && stand.isValid();
    }

    @Override
    public Location actorLocation(int playerIndex) {
        ArmorStand stand = actors.get(playerIndex);
        return stand != null && stand.isValid() ? stand.getLocation() : null;
    }
}
