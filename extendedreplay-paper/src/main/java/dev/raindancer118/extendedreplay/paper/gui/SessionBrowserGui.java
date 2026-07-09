package dev.raindancer118.extendedreplay.paper.gui;

import dev.raindancer118.extendedreplay.paper.replay.PlaybackSession;
import dev.raindancer118.extendedreplay.storage.meta.SessionRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Paginated chest GUI listing every stored session on this replay server. Left click
 * loads a finished session into playback, or joins the live mirror for a still-running
 * one. Mirrors {@link EventBrowserGui}'s layout and dispatch style: raw-slot lookups into
 * the page-sliced list, no per-item PDC tagging needed since nav/refresh sit on fixed
 * slots that {@link GuiListener} checks directly.
 */
public final class SessionBrowserGui implements InventoryHolder {

    public static final int PAGE_SIZE = 45;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY)
                    .withZone(ZoneId.systemDefault());

    private final List<SessionRecord> sessions;
    private final int page;
    private Inventory inventory;

    public SessionBrowserGui(List<SessionRecord> sessions, int page) {
        this.sessions = sessions;
        this.page = page;
    }

    public static void open(Player viewer, List<SessionRecord> sessions, int page) {
        int maxPage = Math.max(0, (sessions.size() - 1) / PAGE_SIZE);
        SessionBrowserGui gui = new SessionBrowserGui(sessions,
                Math.max(0, Math.min(page, maxPage)));
        viewer.openInventory(gui.getInventory());
    }

    @Override
    public Inventory getInventory() {
        if (inventory != null) {
            return inventory;
        }
        int maxPage = Math.max(0, (sessions.size() - 1) / PAGE_SIZE);
        inventory = org.bukkit.Bukkit.createInventory(this, 54,
                Component.text("Sessions – Seite " + (page + 1) + "/" + (maxPage + 1)));
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < sessions.size(); i++) {
            inventory.setItem(i, sessionItem(sessions.get(from + i)));
        }
        if (page > 0) {
            inventory.setItem(45, navItem(Material.ARROW, "« Vorherige Seite"));
        }
        inventory.setItem(47, navItem(Material.SUNFLOWER, "⟳ Aktualisieren"));
        inventory.setItem(49, navItem(Material.BARRIER, "Schließen"));
        if (from + PAGE_SIZE < sessions.size()) {
            inventory.setItem(53, navItem(Material.ARROW, "Nächste Seite »"));
        }
        return inventory;
    }

    private ItemStack sessionItem(SessionRecord record) {
        boolean live = !record.isFinished();
        Material material = live ? Material.ENDER_EYE
                : record.favorite() ? Material.NETHER_STAR : Material.CHEST_MINECART;
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            NamedTextColor nameColor = live ? NamedTextColor.RED
                    : record.favorite() ? NamedTextColor.YELLOW : NamedTextColor.WHITE;
            meta.displayName(Component.text(record.name(), nameColor));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Dauer: " + PlaybackSession.formatTicks(record.lastTick()),
                    NamedTextColor.GRAY));
            lore.add(Component.text("Start: " + DATE_FORMAT.format(
                    Instant.ofEpochMilli(record.startedAtMillis())), NamedTextColor.GRAY));
            lore.add(Component.text((live ? "Status: " : "Ende: ")
                    + (live ? "LIVE" : record.endReason()),
                    live ? NamedTextColor.RED : NamedTextColor.GRAY));
            if (record.favorite()) {
                lore.add(Component.text("★ Favorit", NamedTextColor.YELLOW));
            }
            lore.add(Component.empty());
            lore.add(Component.text(record.sessionId().toString(), NamedTextColor.DARK_GRAY));
            lore.add(Component.empty());
            lore.add(Component.text(live ? "Klick: Live-Mirror beitreten" : "Klick: Wiedergabe laden",
                    NamedTextColor.YELLOW));
            meta.lore(lore);
        });
        return item;
    }

    private static ItemStack navItem(Material material, String label) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> meta.displayName(Component.text(label)));
        return item;
    }

    public int page() {
        return page;
    }

    public List<SessionRecord> sessions() {
        return sessions;
    }

    /** The session behind a raw slot click, or null for nav/empty slots. */
    public SessionRecord sessionAt(int slot) {
        if (slot < 0 || slot >= PAGE_SIZE) {
            return null;
        }
        int index = page * PAGE_SIZE + slot;
        return index < sessions.size() ? sessions.get(index) : null;
    }
}
