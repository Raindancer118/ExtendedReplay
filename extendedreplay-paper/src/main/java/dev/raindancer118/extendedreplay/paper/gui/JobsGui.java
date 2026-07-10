package dev.raindancer118.extendedreplay.paper.gui;

import dev.raindancer118.extendedreplay.paper.job.Job;
import dev.raindancer118.extendedreplay.paper.job.JobManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
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
 * Paginated chest GUI listing every job tracked by {@link JobManager}, newest first.
 * Mirrors {@link SessionBrowserGui}'s layout/dispatch style. Clicking a still-{@code
 * RUNNING} job requests its cancellation and refreshes the page; other jobs are inert.
 */
public final class JobsGui implements InventoryHolder {

    public static final int PAGE_SIZE = 45;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss", Locale.GERMANY)
                    .withZone(ZoneId.systemDefault());

    private final List<Job> jobs;
    private final int page;
    private Inventory inventory;

    public JobsGui(List<Job> jobs, int page) {
        this.jobs = jobs;
        this.page = page;
    }

    public static void open(Player viewer, JobManager manager, int page) {
        List<Job> list = manager.jobs();
        int maxPage = Math.max(0, (list.size() - 1) / PAGE_SIZE);
        JobsGui gui = new JobsGui(list, Math.max(0, Math.min(page, maxPage)));
        viewer.openInventory(gui.getInventory());
    }

    @Override
    public Inventory getInventory() {
        if (inventory != null) {
            return inventory;
        }
        int maxPage = Math.max(0, (jobs.size() - 1) / PAGE_SIZE);
        inventory = Bukkit.createInventory(this, 54,
                Component.text("Jobs – Seite " + (page + 1) + "/" + (maxPage + 1)));
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < jobs.size(); i++) {
            inventory.setItem(i, jobItem(jobs.get(from + i)));
        }
        if (page > 0) {
            inventory.setItem(45, navItem(Material.ARROW, "« Vorherige Seite"));
        }
        inventory.setItem(47, navItem(Material.SUNFLOWER, "⟳ Aktualisieren"));
        inventory.setItem(49, navItem(Material.BARRIER, "Schließen"));
        if (from + PAGE_SIZE < jobs.size()) {
            inventory.setItem(53, navItem(Material.ARROW, "Nächste Seite »"));
        }
        return inventory;
    }

    private ItemStack jobItem(Job job) {
        Material material = switch (job.status()) {
            case RUNNING -> Material.YELLOW_CONCRETE;
            case COMPLETED -> Material.GREEN_CONCRETE;
            case FAILED -> Material.RED_CONCRETE;
            case CANCELLED -> Material.GRAY_CONCRETE;
        };
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text("#" + job.id() + " " + job.name(), colorFor(job.status())));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(job.detail(), NamedTextColor.GRAY));
            lore.add(Component.text("Status: " + job.status(), NamedTextColor.GRAY));
            lore.add(Component.text("Fortschritt: "
                    + (job.progress() < 0 ? "unbestimmt" : job.progress() + "%"), NamedTextColor.GRAY));
            if (job.message() != null && !job.message().isBlank()) {
                lore.add(Component.text("Meldung: " + job.message(), NamedTextColor.GRAY));
            }
            lore.add(Component.text("Start: " + TIME_FORMAT.format(Instant.ofEpochMilli(job.startedAtMillis())),
                    NamedTextColor.DARK_GRAY));
            long durationEndMillis = job.finishedAtMillis() > 0
                    ? job.finishedAtMillis() : System.currentTimeMillis();
            if (job.finishedAtMillis() > 0) {
                lore.add(Component.text("Ende: " + TIME_FORMAT.format(Instant.ofEpochMilli(job.finishedAtMillis())),
                        NamedTextColor.DARK_GRAY));
            }
            lore.add(Component.text("Dauer: " + formatDuration(durationEndMillis - job.startedAtMillis()),
                    NamedTextColor.DARK_GRAY));
            if (job.status() == Job.Status.RUNNING) {
                lore.add(Component.empty());
                lore.add(Component.text("Klick: Abbrechen", NamedTextColor.YELLOW));
            }
            meta.lore(lore);
        });
        return item;
    }

    private static NamedTextColor colorFor(Job.Status status) {
        return switch (status) {
            case RUNNING -> NamedTextColor.YELLOW;
            case COMPLETED -> NamedTextColor.GREEN;
            case FAILED -> NamedTextColor.RED;
            case CANCELLED -> NamedTextColor.GRAY;
        };
    }

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0, millis / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
    }

    private static ItemStack navItem(Material material, String label) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> meta.displayName(Component.text(label)));
        return item;
    }

    public int page() {
        return page;
    }

    public List<Job> jobs() {
        return jobs;
    }

    /** The job behind a raw slot click, or null for nav/empty slots. */
    public Job jobAt(int slot) {
        if (slot < 0 || slot >= PAGE_SIZE) {
            return null;
        }
        int index = page * PAGE_SIZE + slot;
        return index < jobs.size() ? jobs.get(index) : null;
    }
}
