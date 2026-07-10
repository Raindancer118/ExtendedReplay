package dev.raindancer118.extendedreplay.paper.gui;

import dev.raindancer118.extendedreplay.paper.producer.ActiveSession;
import dev.raindancer118.extendedreplay.paper.producer.ProducerManager;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Producer-side recording control panel: shows whether a session is running in the
 * viewer's current world, lets the operator dial in the auto-snapshot radius, and starts
 * or stops the recording. Mirrors {@link PlaybackControlGui}'s structure exactly: an
 * {@link InventoryHolder} rebuilt fresh on every open, PDC-tagged action buttons, a
 * glass-pane border, and click dispatch left entirely to {@link GuiListener}, which
 * re-reads the live {@link ProducerManager} state rather than a reference stored here.
 */
public final class RecordControlGui implements InventoryHolder {

    private static final int SIZE = 27;
    private static final int MIN_RADIUS = 50;
    private static final int MAX_RADIUS = 1000;
    private static final int SMALL_STEP = 10;
    private static final int LARGE_STEP = 50;

    /**
     * Radius chosen per player, keyed by {@link UUID} only (never a {@link Player}
     * reference, per the platform's memory-leak rule). A fresh {@link RecordControlGui}
     * instance/inventory is built on every open or re-render (same pattern as
     * {@link PlaybackControlGui}), so the in-progress radius selection has to live outside
     * the instance to survive the +/- click → re-render round trip.
     */
    private static final Map<UUID, Integer> radiusByPlayer = new ConcurrentHashMap<>();

    public enum Action {
        RADIUS_DOWN, RADIUS_UP, START, STOP, CLOSE
    }

    private final NamespacedKey key;
    private Inventory inventory;

    private RecordControlGui(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "erp-record-gui-action");
    }

    /** Builds and opens the panel for the viewer's current world/session state. */
    public static void open(Player viewer, Plugin plugin, ProducerManager producer) {
        RecordControlGui gui = new RecordControlGui(plugin);
        viewer.openInventory(gui.build(viewer, producer));
    }

    /** Applies a radius delta (clamped) for the player and returns the new value. */
    public static int adjustRadius(UUID playerId, int fallbackDefault, int delta) {
        int current = radiusByPlayer.computeIfAbsent(playerId, id -> fallbackDefault);
        int updated = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, current + delta));
        radiusByPlayer.put(playerId, updated);
        return updated;
    }

    public static int radiusOf(UUID playerId, int fallbackDefault) {
        return radiusByPlayer.getOrDefault(playerId, fallbackDefault);
    }

    /** Drops the player's in-progress radius selection, e.g. on logout — pure hygiene. */
    public static void forget(UUID playerId) {
        radiusByPlayer.remove(playerId);
    }

    private Inventory build(Player viewer, ProducerManager producer) {
        Optional<ActiveSession> current = producer.sessionForWorld(viewer.getWorld().getName());
        inventory = Bukkit.createInventory(this, SIZE, Component.text("🎬 Aufnahme-Steuerung"));
        fill(Material.GRAY_STAINED_GLASS_PANE);

        inventory.setItem(4, statusItem(current));

        int radius = radiusByPlayer.computeIfAbsent(viewer.getUniqueId(),
                id -> producer.config().autoSnapshotRadius());

        inventory.setItem(10, button(Material.ARROW,
                Component.text("− Radius", NamedTextColor.AQUA),
                List.of(Component.text("−" + SMALL_STEP + " (Shift: −" + LARGE_STEP + ")",
                                NamedTextColor.GRAY),
                        Component.text("Minimum: " + MIN_RADIUS, NamedTextColor.DARK_GRAY)),
                Action.RADIUS_DOWN, false));

        inventory.setItem(12, radiusItem(producer, radius));

        inventory.setItem(14, button(Material.SPECTRAL_ARROW,
                Component.text("+ Radius", NamedTextColor.AQUA),
                List.of(Component.text("+" + SMALL_STEP + " (Shift: +" + LARGE_STEP + ")",
                                NamedTextColor.GRAY),
                        Component.text("Maximum: " + MAX_RADIUS, NamedTextColor.DARK_GRAY)),
                Action.RADIUS_UP, false));

        inventory.setItem(16, current.isPresent() ? stopItem(current.get()) : startItem());

        inventory.setItem(22, button(Material.BARRIER,
                Component.text("✖ Schließen", NamedTextColor.RED),
                List.of(), Action.CLOSE, false));

        return inventory;
    }

    private void fill(Material material) {
        ItemStack pane = new ItemStack(material);
        pane.editMeta(meta -> meta.displayName(Component.text(" ")));
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, pane);
        }
    }

    private ItemStack statusItem(Optional<ActiveSession> current) {
        ItemStack item = new ItemStack(Material.CLOCK);
        item.editMeta(meta -> {
            meta.displayName(Component.text(current.isPresent() ? "🔴 Aufnahme läuft" : "🟢 Bereit",
                    current.isPresent() ? NamedTextColor.RED : NamedTextColor.GREEN));
            List<Component> lore = new ArrayList<>();
            if (current.isPresent()) {
                ActiveSession session = current.get();
                lore.add(Component.text(session.name(), NamedTextColor.GOLD));
                lore.add(Component.text("Dauer: " + PlaybackSession.formatTicks(session.currentTick()),
                        NamedTextColor.GRAY));
                lore.add(Component.text("Spieler: " + session.trackedPlayers().size(),
                        NamedTextColor.GRAY));
            } else {
                lore.add(Component.text("Keine aktive Aufnahme in dieser Welt.", NamedTextColor.GRAY));
            }
            meta.lore(lore);
        });
        return item;
    }

    private ItemStack radiusItem(ProducerManager producer, int radius) {
        ItemStack item = new ItemStack(Material.MAP);
        item.editMeta(meta -> {
            meta.displayName(Component.text("🎯 Radius: " + radius, NamedTextColor.YELLOW));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Arena-Größe für den Auto-Snapshot.", NamedTextColor.GRAY));
            lore.add(producer.config().autoSnapshot()
                    ? Component.text("Snapshot wird automatisch erstellt & übertragen.",
                            NamedTextColor.GRAY)
                    : Component.text("Auto-Snapshot ist deaktiviert (config.yml).",
                            NamedTextColor.DARK_GRAY));
            meta.lore(lore);
        });
        return item;
    }

    private ItemStack startItem() {
        ItemStack item = new ItemStack(Material.LIME_DYE);
        item.editMeta(meta -> {
            meta.displayName(Component.text("▶ Aufnahme starten", NamedTextColor.GREEN));
            meta.lore(List.of(Component.text("Erstellt bei Bedarf automatisch einen Arena-Snapshot.",
                    NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, Action.START.name());
        });
        return item;
    }

    private ItemStack stopItem(ActiveSession session) {
        ItemStack item = new ItemStack(Material.RED_DYE);
        item.editMeta(meta -> {
            meta.displayName(Component.text("■ Aufnahme stoppen", NamedTextColor.RED));
            meta.lore(List.of(Component.text("Beendet '" + session.name() + "'.", NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, Action.STOP.name());
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
