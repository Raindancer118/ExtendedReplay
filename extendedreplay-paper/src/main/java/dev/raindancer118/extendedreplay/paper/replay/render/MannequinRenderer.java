package dev.raindancer118.extendedreplay.paper.replay.render;

import com.destroystokyo.paper.profile.ProfileProperty;
import dev.raindancer118.extendedreplay.core.model.EquipmentChange;
import dev.raindancer118.extendedreplay.core.model.PlayerFrame;
import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import dev.raindancer118.extendedreplay.core.protocol.PacketIO;
import dev.raindancer118.extendedreplay.paper.util.ItemBytes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Pose;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;

/**
 * Preferred renderer using Paper's native Mannequin entities: real player models with
 * skins, poses and equipment, without any NPC library or fake network players.
 */
public final class MannequinRenderer implements ReplayActorRenderer {

    private final Map<Integer, Mannequin> actors = new HashMap<>();
    private boolean nameTagsVisible = true;

    @Override
    public void spawnActor(World world, int playerIndex, PlayerProfileData profile,
                           Location location) {
        if (actors.containsKey(playerIndex)) {
            return;
        }
        Mannequin mannequin = world.spawn(location, Mannequin.class, entity -> {
            entity.setImmovable(true);
            entity.setGravity(false);
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            var builder = ResolvableProfile.resolvableProfile()
                    .name(sanitizeName(profile.name()))
                    .uuid(profile.uuid());
            if (profile.hasSkin()) {
                builder.addProperty(new ProfileProperty("textures",
                        profile.skinTextureValue(), profile.skinTextureSignature()));
            }
            entity.setProfile(builder.build());
            entity.customName(Component.text(profile.name()));
            entity.setCustomNameVisible(nameTagsVisible);
        });
        actors.put(playerIndex, mannequin);
    }

    @Override
    public void despawnActor(int playerIndex) {
        Mannequin mannequin = actors.remove(playerIndex);
        if (mannequin != null) {
            mannequin.remove();
        }
    }

    @Override
    public void despawnAll() {
        actors.values().forEach(Mannequin::remove);
        actors.clear();
    }

    @Override
    public void applyFrame(int playerIndex, World world, PlayerFrame frame) {
        Mannequin mannequin = actors.get(playerIndex);
        if (mannequin == null || !mannequin.isValid()) {
            return;
        }
        Location target = new Location(world, frame.x(), frame.y(), frame.z(),
                PacketIO.byteToAngle(frame.yaw()), PacketIO.byteToAngle(frame.pitch()));
        mannequin.teleport(target);

        Pose pose = poseOf(frame);
        if (mannequin.getPose() != pose) {
            mannequin.setPose(pose, true);
        }
        mannequin.setGlowing(frame.hasFlag(PlayerFrame.FLAG_GLOWING));
        boolean visible = !frame.hasFlag(PlayerFrame.FLAG_INVISIBLE)
                && !frame.hasFlag(PlayerFrame.FLAG_DEAD);
        mannequin.setInvisible(!visible);
    }

    private static Pose poseOf(PlayerFrame frame) {
        if (frame.hasFlag(PlayerFrame.FLAG_DEAD)) {
            return Pose.DYING;
        }
        if (frame.hasFlag(PlayerFrame.FLAG_SLEEPING)) {
            return Pose.SLEEPING;
        }
        if (frame.hasFlag(PlayerFrame.FLAG_SWIMMING)) {
            return Pose.SWIMMING;
        }
        if (frame.hasFlag(PlayerFrame.FLAG_GLIDING)) {
            return Pose.FALL_FLYING;
        }
        if (frame.hasFlag(PlayerFrame.FLAG_SNEAKING)) {
            return Pose.SNEAKING;
        }
        return Pose.STANDING;
    }

    @Override
    public void applyEquipment(int playerIndex, EquipmentChange equipment) {
        Mannequin mannequin = actors.get(playerIndex);
        if (mannequin == null || !mannequin.isValid()) {
            return;
        }
        var entityEquipment = mannequin.getEquipment();
        entityEquipment.setItem(EquipmentSlot.HAND, ItemBytes.deserialize(equipment.mainHand()));
        entityEquipment.setItem(EquipmentSlot.OFF_HAND, ItemBytes.deserialize(equipment.offHand()));
        entityEquipment.setItem(EquipmentSlot.HEAD, ItemBytes.deserialize(equipment.helmet()));
        entityEquipment.setItem(EquipmentSlot.CHEST, ItemBytes.deserialize(equipment.chestplate()));
        entityEquipment.setItem(EquipmentSlot.LEGS, ItemBytes.deserialize(equipment.leggings()));
        entityEquipment.setItem(EquipmentSlot.FEET, ItemBytes.deserialize(equipment.boots()));
    }

    @Override
    public void setNameTagVisible(boolean visible) {
        this.nameTagsVisible = visible;
        actors.values().forEach(m -> m.setCustomNameVisible(visible));
    }

    @Override
    public boolean isSpawned(int playerIndex) {
        Mannequin mannequin = actors.get(playerIndex);
        return mannequin != null && mannequin.isValid();
    }

    @Override
    public Location actorLocation(int playerIndex) {
        Mannequin mannequin = actors.get(playerIndex);
        return mannequin != null && mannequin.isValid() ? mannequin.getLocation() : null;
    }

    /** Mannequin profile names must fit the 16-char player name constraint. */
    private static String sanitizeName(String name) {
        return name.length() <= 16 ? name : name.substring(0, 16);
    }

    /** Checks Mannequin availability on this server version. */
    public static boolean isSupported() {
        try {
            Class.forName("org.bukkit.entity.Mannequin");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
