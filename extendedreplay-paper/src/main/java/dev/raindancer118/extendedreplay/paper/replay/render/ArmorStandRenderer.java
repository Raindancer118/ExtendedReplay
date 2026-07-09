package dev.raindancer118.extendedreplay.paper.replay.render;

import dev.raindancer118.extendedreplay.core.model.EquipmentChange;
import dev.raindancer118.extendedreplay.core.model.PlayerFrame;
import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import dev.raindancer118.extendedreplay.core.protocol.PacketIO;
import dev.raindancer118.extendedreplay.paper.gui.Heads;
import dev.raindancer118.extendedreplay.paper.util.ItemBytes;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

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
            ItemStack head = Heads.playerHead(profile);
            entity.getEquipment().setItem(EquipmentSlot.HEAD, head);
        });
        actors.put(playerIndex, stand);
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
