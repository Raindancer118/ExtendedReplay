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
 * Paginated chest GUI listing every stored session on this replay server, optionally
 * narrowed down by {@link Filter}. A click opens {@link SessionDetailsGui} for that
 * session — this GUI itself never touches playback/live-mirror directly. Mirrors
 * {@link EventBrowserGui}'s layout and dispatch style: raw-slot lookups into the
 * page-sliced (and filter-sliced) list, no per-item PDC tagging needed since nav/refresh/
 * filter sit on fixed slots that {@link GuiListener} checks directly.
 */
public final class SessionBrowserGui implements InventoryHolder {

    public static final int PAGE_SIZE = 45;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY)
                    .withZone(ZoneId.systemDefault());

    /** Which subset of the passed-in session list is shown; cycled by the filter button. */
    public enum Filter {
        ALL, FAVORITES, LIVE;

        public Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public String label() {
            return switch (this) {
                case ALL -> "Filter: Alle";
                case FAVORITES -> "Filter: ★ Favoriten";
                case LIVE -> "Filter: Live";
            };
        }

        public Material icon() {
            return switch (this) {
                case ALL -> Material.PAPER;
                case FAVORITES -> Material.NETHER_STAR;
                case LIVE -> Material.ENDER_EYE;
            };
        }

        public boolean matches(SessionRecord record) {
            return switch (this) {
                case ALL -> true;
                case FAVORITES -> record.favorite();
                case LIVE -> !record.isFinished();
            };
        }
    }

    private final List<SessionRecord> sessions;
    private final Filter filter;
    private final List<SessionRecord> filtered;
    private final int page;
    private Inventory inventory;

    public SessionBrowserGui(List<SessionRecord> sessions, int page, Filter filter) {
        this.sessions = sessions;
        this.filter = filter;
        this.filtered = sessions.stream().filter(filter::matches).toList();
        this.page = page;
    }

    public static void open(Player viewer, List<SessionRecord> sessions, int page) {
        open(viewer, sessions, page, Filter.ALL);
    }

    public static void open(Player viewer, List<SessionRecord> sessions, int page, Filter filter) {
        SessionBrowserGui probe = new SessionBrowserGui(sessions, 0, filter);
        int maxPage = Math.max(0, (probe.filtered.size() - 1) / PAGE_SIZE);
        SessionBrowserGui gui = new SessionBrowserGui(sessions,
                Math.max(0, Math.min(page, maxPage)), filter);
        viewer.openInventory(gui.getInventory());
    }

    @Override
    public Inventory getInventory() {
        if (inventory != null) {
            return inventory;
        }
        int maxPage = Math.max(0, (filtered.size() - 1) / PAGE_SIZE);
        inventory = org.bukkit.Bukkit.createInventory(this, 54,
                Component.text("Sessions – Seite " + (page + 1) + "/" + (maxPage + 1)));
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < filtered.size(); i++) {
            inventory.setItem(i, sessionItem(filtered.get(from + i)));
        }
        if (page > 0) {
            inventory.setItem(45, navItem(Material.ARROW, "« Vorherige Seite"));
        }
        inventory.setItem(46, navItem(filter.icon(), filter.label()));
        inventory.setItem(47, navItem(Material.SUNFLOWER, "⟳ Aktualisieren"));
        inventory.setItem(49, navItem(Material.BARRIER, "Schließen"));
        if (from + PAGE_SIZE < filtered.size()) {
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
            if (record.serverName() != null) {
                String group = record.metadata().get("server-group");
                lore.add(Component.text("Server: " + record.serverName()
                        + (group != null ? " (" + group + ")" : ""), NamedTextColor.AQUA));
            }
            lore.add(Component.text("Welt: " + record.worldName(), NamedTextColor.GRAY));
            lore.add(Component.text("Dauer: " + PlaybackSession.formatTicks(record.lastTick()),
                    NamedTextColor.GRAY));
            lore.add(Component.text("Spieler: " + record.playerCount(), NamedTextColor.GRAY));
            lore.add(Component.text("Größe: " + formatBytes(record.sizeBytes()), NamedTextColor.GRAY));
            lore.add(Component.text("Start: " + DATE_FORMAT.format(
                    Instant.ofEpochMilli(record.startedAtMillis())), NamedTextColor.GRAY));
            lore.add(Component.text((live ? "Status: " : "Ende: ")
                    + (live ? "LIVE" : record.endReason()),
                    live ? NamedTextColor.RED : NamedTextColor.GRAY));
            lore.add(Component.text("Integrität: " + record.integrity(), integrityColor(record.integrity())));
            lore.add(Component.text("Snapshot: "
                    + (record.snapshotName() != null ? record.snapshotName() : "—"), NamedTextColor.GRAY));
            if (record.startedBy() != null) {
                lore.add(Component.text("Gestartet von: " + record.startedBy(), NamedTextColor.GRAY));
            }
            if (record.favorite()) {
                lore.add(Component.text("★ Favorit", NamedTextColor.YELLOW));
            }
            lore.add(Component.empty());
            lore.add(Component.text(record.sessionId().toString(), NamedTextColor.DARK_GRAY));
            lore.add(Component.empty());
            lore.add(Component.text("Klick: Details", NamedTextColor.YELLOW));
            meta.lore(lore);
        });
        return item;
    }

    /** Human-readable byte size (B/KB/MB/GB, one decimal place from KB up). Shared with
     * {@link SessionDetailsGui}. */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.GERMANY, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.GERMANY, "%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format(Locale.GERMANY, "%.1f GB", gb);
    }

    /** Color for an integrity classification string. Shared with {@link SessionDetailsGui}. */
    public static NamedTextColor integrityColor(String integrity) {
        return switch (integrity) {
            case "EXACT" -> NamedTextColor.GREEN;
            case "VERIFIED" -> NamedTextColor.DARK_GREEN;
            case "DEGRADED" -> NamedTextColor.GOLD;
            case "INCOMPLETE" -> NamedTextColor.RED;
            default -> NamedTextColor.GRAY;
        };
    }

    private static ItemStack navItem(Material material, String label) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> meta.displayName(Component.text(label)));
        return item;
    }

    public int page() {
        return page;
    }

    /** The original, unfiltered session list this GUI was opened with. */
    public List<SessionRecord> sessions() {
        return sessions;
    }

    public Filter filter() {
        return filter;
    }

    /** The session behind a raw slot click, or null for nav/empty slots. Indexes into the
     * currently filtered list. */
    public SessionRecord sessionAt(int slot) {
        if (slot < 0 || slot >= PAGE_SIZE) {
            return null;
        }
        int index = page * PAGE_SIZE + slot;
        return index < filtered.size() ? filtered.get(index) : null;
    }
}
