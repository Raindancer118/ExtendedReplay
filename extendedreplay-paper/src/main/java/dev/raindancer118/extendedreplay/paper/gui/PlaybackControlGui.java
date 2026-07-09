package dev.raindancer118.extendedreplay.paper.gui;

import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import dev.raindancer118.extendedreplay.paper.replay.PlaybackSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Full playback control panel: play/pause, ±10s/±1min skip, speed 0.25x–8x, jump to
 * event, follow selection, freecam and session close — all as fancy items in one chest
 * GUI. Complements {@link HotbarUI} (quick hotbar actions) with a richer, at-a-glance
 * control surface. A fresh instance/inventory is built on every open so its state
 * (paused/speed/tick) always reflects the session at open time; clicks are dispatched
 * by {@link GuiListener} which re-reads the live {@link PlaybackSession} rather than a
 * stored reference here.
 */
public final class PlaybackControlGui implements InventoryHolder {

    public static final double[] SPEED_STEPS = {0.25, 0.5, 1.0, 2.0, 4.0, 8.0};

    public enum Action {
        REWIND_MINUTE(null),
        REWIND_10S(null),
        PLAY_PAUSE(null),
        FORWARD_10S(null),
        FORWARD_MINUTE(null),
        SPEED_0_25(0.25),
        SPEED_0_5(0.5),
        SPEED_1(1.0),
        SPEED_2(2.0),
        SPEED_4(4.0),
        SPEED_8(8.0),
        JUMP_EVENT(null),
        FOLLOW(null),
        FREECAM(null),
        CLOSE(null);

        private final Double speedValue;

        Action(Double speedValue) {
            this.speedValue = speedValue;
        }

        /** The playback speed this button applies, or null if it isn't a speed button. */
        public Double speedValue() {
            return speedValue;
        }
    }

    private final NamespacedKey key;
    private Inventory inventory;

    private PlaybackControlGui(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "erp-playback-gui-action");
    }

    /** Builds and opens the control panel for the viewer's current playback state. */
    public static void open(Player viewer, Plugin plugin, PlaybackSession session) {
        PlaybackControlGui gui = new PlaybackControlGui(plugin);
        viewer.openInventory(gui.build(session));
    }

    private Inventory build(PlaybackSession session) {
        inventory = Bukkit.createInventory(this, 54,
                Component.text("⏯ Wiedergabe – " + session.sessionName()));
        fill(Material.GRAY_STAINED_GLASS_PANE);

        inventory.setItem(4, infoItem(session));

        inventory.setItem(10, button(Material.SPECTRAL_ARROW,
                Component.text("«« 1 Minute zurück", NamedTextColor.AQUA),
                List.of(Component.text("Springt 60 Sekunden zurück.", NamedTextColor.GRAY)),
                Action.REWIND_MINUTE, false));
        inventory.setItem(11, button(Material.ARROW,
                Component.text("« 10 Sekunden zurück", NamedTextColor.AQUA),
                List.of(Component.text("Springt 10 Sekunden zurück.", NamedTextColor.GRAY)),
                Action.REWIND_10S, false));

        boolean paused = session.isPaused();
        inventory.setItem(13, button(paused ? Material.LIME_DYE : Material.ORANGE_DYE,
                Component.text(paused ? "▶ Play" : "⏸ Pause",
                        paused ? NamedTextColor.GREEN : NamedTextColor.GOLD),
                List.of(Component.text(paused
                        ? "Wiedergabe fortsetzen." : "Wiedergabe pausieren.", NamedTextColor.GRAY)),
                Action.PLAY_PAUSE, false));

        inventory.setItem(15, button(Material.ARROW,
                Component.text("10 Sekunden vor »", NamedTextColor.AQUA),
                List.of(Component.text("Springt 10 Sekunden vor.", NamedTextColor.GRAY)),
                Action.FORWARD_10S, false));
        inventory.setItem(16, button(Material.SPECTRAL_ARROW,
                Component.text("1 Minute vor »»", NamedTextColor.AQUA),
                List.of(Component.text("Springt 60 Sekunden vor.", NamedTextColor.GRAY)),
                Action.FORWARD_MINUTE, false));

        double speed = session.speed();
        Action[] speedActions = {Action.SPEED_0_25, Action.SPEED_0_5, Action.SPEED_1,
                Action.SPEED_2, Action.SPEED_4, Action.SPEED_8};
        int[] speedSlots = {19, 20, 21, 22, 23, 24};
        for (int i = 0; i < speedActions.length; i++) {
            Action action = speedActions[i];
            boolean active = Math.abs(action.speedValue() - speed) < 0.001;
            inventory.setItem(speedSlots[i], button(Material.CLOCK,
                    Component.text((active ? "» " : "") + formatSpeed(action.speedValue())
                            + "x" + (active ? " «" : ""),
                            active ? NamedTextColor.YELLOW : NamedTextColor.WHITE),
                    List.of(Component.text("Setzt die Wiedergabegeschwindigkeit.",
                            NamedTextColor.GRAY)),
                    action, active));
        }

        inventory.setItem(30, button(Material.BOOK,
                Component.text("📖 Zu Event springen", NamedTextColor.LIGHT_PURPLE),
                List.of(Component.text("Öffnet den Event-Browser.", NamedTextColor.GRAY)),
                Action.JUMP_EVENT, false));

        inventory.setItem(32, followItem(session));

        inventory.setItem(34, button(Material.ENDER_EYE,
                Component.text("🎥 Freecam", NamedTextColor.LIGHT_PURPLE),
                List.of(Component.text("Wechselt in den Spectator-Freiflug.", NamedTextColor.GRAY)),
                Action.FREECAM, false));

        inventory.setItem(49, button(Material.BARRIER,
                Component.text("✖ Session schließen", NamedTextColor.RED),
                List.of(Component.text("Beendet die Wiedergabe-Session.", NamedTextColor.GRAY)),
                Action.CLOSE, false));

        return inventory;
    }

    private void fill(Material material) {
        ItemStack pane = new ItemStack(material);
        pane.editMeta(meta -> meta.displayName(Component.text(" ")));
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, pane);
        }
    }

    private ItemStack infoItem(PlaybackSession session) {
        ItemStack item = new ItemStack(Material.CLOCK);
        item.editMeta(meta -> {
            meta.displayName(Component.text("🕐 " + session.sessionName(), NamedTextColor.GOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(PlaybackSession.formatTicks(session.currentTick())
                    + " / " + PlaybackSession.formatTicks(session.lastTickOfSession()),
                    NamedTextColor.GRAY));
            lore.add(Component.text("Status: " + (session.isPaused() ? "⏸ Pausiert" : "▶ Läuft"),
                    NamedTextColor.GRAY));
            lore.add(Component.text("Geschwindigkeit: " + formatSpeed(session.speed()) + "x",
                    NamedTextColor.GRAY));
            Integer followed = session.followedPlayer();
            if (followed != null) {
                PlayerProfileData profile = session.profiles().get(followed);
                lore.add(Component.text("Folgt: " + (profile != null ? profile.name() : "#" + followed),
                        NamedTextColor.GRAY));
            }
            meta.lore(lore);
        });
        return item;
    }

    private ItemStack followItem(PlaybackSession session) {
        Integer followed = session.followedPlayer();
        PlayerProfileData profile = followed != null ? session.profiles().get(followed) : null;
        ItemStack item = profile != null
                ? Heads.playerHead(profile)
                : new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(meta -> {
            meta.displayName(Component.text("👤 Spieler folgen"
                    + (profile != null ? ": " + profile.name() : ""), NamedTextColor.AQUA));
            meta.lore(List.of(
                    Component.text("Öffnet die Spielerauswahl.", NamedTextColor.GRAY),
                    Component.text("Die Kamera folgt der Auswahl.", NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING,
                    Action.FOLLOW.name());
        });
        return item;
    }

    private ItemStack button(Material material, Component name, List<Component> lore,
                             Action action, boolean glint) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name);
            meta.lore(lore);
            meta.setEnchantmentGlintOverride(glint);
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, action.name());
        });
        return item;
    }

    private static String formatSpeed(double speed) {
        return speed == Math.floor(speed) ? String.valueOf((int) speed) : String.valueOf(speed);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** The action bound to the clicked item, or null (filler/empty slots). */
    public Action actionOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String value = item.getItemMeta().getPersistentDataContainer()
                .get(key, PersistentDataType.STRING);
        if (value == null) {
            return null;
        }
        try {
            return Action.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
