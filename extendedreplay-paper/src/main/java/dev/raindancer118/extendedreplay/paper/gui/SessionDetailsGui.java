package dev.raindancer118.extendedreplay.paper.gui;

import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import dev.raindancer118.extendedreplay.paper.replay.PlaybackSession;
import dev.raindancer118.extendedreplay.storage.ReplayStorage;
import dev.raindancer118.extendedreplay.storage.meta.SessionRecord;
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

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Full metadata + action panel for a single session, reached by clicking a session item
 * in {@link SessionBrowserGui}. Always opened with freshly loaded data (async DB read,
 * main-thread build) so favorite/verify/reindex actions performed from here are reflected
 * immediately on re-open. Click dispatch lives in {@link GuiListener}, which re-reads the
 * live managers rather than a stored reference here — same pattern as every other GUI in
 * this package.
 */
public final class SessionDetailsGui implements InventoryHolder {

    private static final int SIZE = 45;
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY)
                    .withZone(ZoneId.systemDefault());

    public enum Action {
        PLAY, LIVE, VERIFY, FAVORITE, REINDEX, DELETE, BACK
    }

    private final SessionRecord record;
    private final List<PlayerProfileData> players;
    private final NamespacedKey key;
    private Inventory inventory;

    private SessionDetailsGui(Plugin plugin, SessionRecord record, List<PlayerProfileData> players) {
        this.key = new NamespacedKey(plugin, "erp-session-details-action");
        this.record = record;
        this.players = players;
    }

    /** Loads fresh session + player data async and opens the panel on the main thread. */
    public static void open(Player viewer, Plugin plugin, ReplayStorage storage,
                            UUID sessionId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            SessionRecord record;
            List<PlayerProfileData> players;
            try {
                record = storage.getSession(sessionId).orElse(null);
                players = record != null ? storage.listPlayers(sessionId) : List.of();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Session details failed", e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (viewer.isOnline()) {
                        viewer.sendMessage(Component.text("Session-Details konnten nicht geladen werden."));
                    }
                });
                return;
            }
            if (record == null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (viewer.isOnline()) {
                        viewer.sendMessage(Component.text("Session nicht gefunden."));
                    }
                });
                return;
            }
            SessionRecord finalRecord = record;
            List<PlayerProfileData> finalPlayers = players;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) {
                    return;
                }
                SessionDetailsGui gui = new SessionDetailsGui(plugin, finalRecord, finalPlayers);
                viewer.openInventory(gui.build(viewer));
            });
        });
    }

    private Inventory build(Player viewer) {
        inventory = Bukkit.createInventory(this, SIZE, Component.text(record.name()));
        fill(Material.GRAY_STAINED_GLASS_PANE);

        inventory.setItem(4, infoItem());

        boolean live = !record.isFinished();
        if (live) {
            inventory.setItem(19, disabledItem(Material.GRAY_DYE, "▶ Abspielen",
                    List.of(Component.text("Session läuft noch — erst Live beitreten.", NamedTextColor.GRAY))));
            inventory.setItem(20, button(Material.ENDER_EYE,
                    Component.text("📡 Live beitreten", NamedTextColor.RED), List.of(), Action.LIVE));
        } else {
            inventory.setItem(19, button(Material.LIME_DYE,
                    Component.text("▶ Abspielen", NamedTextColor.GREEN), List.of(), Action.PLAY));
        }

        inventory.setItem(21, button(Material.OBSERVER,
                Component.text("✔ Verifizieren (Job)", NamedTextColor.AQUA),
                List.of(Component.text("Prüft Checksummen & Segmente asynchron.", NamedTextColor.GRAY)),
                Action.VERIFY));

        inventory.setItem(22, record.favorite()
                ? button(Material.NETHER_STAR, Component.text("★ Favorit entfernen", NamedTextColor.YELLOW),
                        List.of(), Action.FAVORITE)
                : button(Material.GRAY_DYE, Component.text("★ Favorit setzen", NamedTextColor.YELLOW),
                        List.of(), Action.FAVORITE));

        if (viewer.hasPermission("extendedreplay.storage")) {
            inventory.setItem(23, button(Material.ANVIL,
                    Component.text("🔁 Reindex (Job)", NamedTextColor.AQUA),
                    List.of(Component.text("Baut den Index aus den Segment-Dateien neu.", NamedTextColor.GRAY)),
                    Action.REINDEX));
            inventory.setItem(24, button(Material.TNT,
                    Component.text("🗑 Löschen", NamedTextColor.RED),
                    List.of(Component.text("Löscht Session-Daten unwiderruflich.", NamedTextColor.RED)),
                    Action.DELETE));
        }

        inventory.setItem(40, button(Material.ARROW,
                Component.text("« Zurück zum Browser", NamedTextColor.GRAY), List.of(), Action.BACK));

        return inventory;
    }

    private ItemStack infoItem() {
        Material material = record.favorite() ? Material.NETHER_STAR : Material.CHEST_MINECART;
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(record.name(),
                    record.favorite() ? NamedTextColor.YELLOW : NamedTextColor.WHITE));
            List<Component> lore = new ArrayList<>();
            if (record.serverName() != null) {
                String group = record.metadata().get("server-group");
                lore.add(Component.text("Server: " + record.serverName()
                        + (group != null ? " (" + group + ")" : ""), NamedTextColor.AQUA));
            }
            lore.add(Component.text("Welt: " + record.worldName()
                    + (record.worldEnvironment() != null ? " (" + record.worldEnvironment() + ")" : ""),
                    NamedTextColor.GRAY));
            if (record.worldSeed() != null) {
                lore.add(Component.text("Seed: " + record.worldSeed(), NamedTextColor.GRAY));
            }
            lore.add(Component.text("Dauer: " + PlaybackSession.formatTicks(record.lastTick()),
                    NamedTextColor.GRAY));
            lore.add(Component.text("Spieler (" + players.size() + "): "
                    + players.stream().limit(8).map(PlayerProfileData::name)
                            .reduce((a, b) -> a + ", " + b).orElse("—")
                    + (players.size() > 8 ? ", …" : ""), NamedTextColor.GRAY));
            lore.add(Component.text("Größe: " + SessionBrowserGui.formatBytes(record.sizeBytes()),
                    NamedTextColor.GRAY));
            lore.add(Component.text("Start: " + DATE_FORMAT.format(
                    Instant.ofEpochMilli(record.startedAtMillis())), NamedTextColor.GRAY));
            if (record.isFinished()) {
                lore.add(Component.text("Ende: " + DATE_FORMAT.format(
                        Instant.ofEpochMilli(record.endedAtMillis())), NamedTextColor.GRAY));
                lore.add(Component.text("End-Grund: " + record.endReason(), NamedTextColor.GRAY));
            } else {
                lore.add(Component.text("Status: LIVE", NamedTextColor.RED));
            }
            lore.add(Component.text("Format-Version: " + record.formatVersion(), NamedTextColor.DARK_GRAY));
            String mcVersion = record.metadata().get("mc-version");
            String pluginVersion = record.metadata().get("plugin-version");
            if (mcVersion != null || pluginVersion != null) {
                lore.add(Component.text("MC/Plugin: "
                        + (mcVersion != null ? mcVersion : "?") + " / "
                        + (pluginVersion != null ? pluginVersion : "?"), NamedTextColor.DARK_GRAY));
            }
            if (record.startedBy() != null) {
                lore.add(Component.text("Gestartet von: " + record.startedBy(), NamedTextColor.GRAY));
            }
            lore.add(Component.text("Snapshot: "
                    + (record.snapshotName() != null ? record.snapshotName() : "—"), NamedTextColor.GRAY));
            lore.add(Component.text("Integrität: " + record.integrity(),
                    SessionBrowserGui.integrityColor(record.integrity())));
            lore.add(Component.empty());
            lore.add(Component.text(record.sessionId().toString(), NamedTextColor.DARK_GRAY));
            meta.lore(lore);
        });
        return item;
    }

    private void fill(Material material) {
        ItemStack pane = new ItemStack(material);
        pane.editMeta(meta -> meta.displayName(Component.text(" ")));
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, pane);
        }
    }

    private ItemStack button(Material material, Component name, List<Component> lore, Action action) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name);
            meta.lore(lore);
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, action.name());
        });
        return item;
    }

    /** An inert item (no PDC tag, so {@link #actionOf} returns null for it). */
    private ItemStack disabledItem(Material material, String name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(name, NamedTextColor.DARK_GRAY));
            meta.lore(lore);
        });
        return item;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public SessionRecord record() {
        return record;
    }

    /** The action bound to the clicked item, or null (filler/disabled/empty slots). */
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
