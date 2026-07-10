package dev.raindancer118.extendedreplay.paper.gui;

import dev.raindancer118.extendedreplay.core.model.ContainerSnapshot;
import dev.raindancer118.extendedreplay.core.model.InventorySnapshot;
import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import dev.raindancer118.extendedreplay.paper.replay.PlaybackSession;
import dev.raindancer118.extendedreplay.paper.util.ItemBytes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Live-updating, read-only view of a recorded inventory or container. The GUI is opened
 * once for a player index or container id and then kept in sync with the running
 * playback by {@link #refresh(PlaybackSession)} — called periodically by
 * {@code PlaybackManager}'s HUD tick — rather than being closed/reopened on every tick.
 *
 * <p>Player inventory layout: rows 1–3 = main (9–35), row 4 = hotbar (0–8),
 * row 5 = armor + offhand + cursor, row 6 = head + info.</p>
 */
public final class InventoryInspectGui implements InventoryHolder {

    private enum Mode { PLAYER, CONTAINER }

    private final Mode mode;
    private final int playerIndex;
    private final String playerName;
    private final PlayerProfileData profile;
    private final String containerId;
    private final Inventory inventory;

    /** Tick of the last snapshot actually rendered, so unchanged ticks skip the rebuild.
     * -1 means "nothing rendered yet". */
    private int lastRenderedSnapshotTick = -1;

    private InventoryInspectGui(Mode mode, int playerIndex, String playerName,
                                PlayerProfileData profile, String containerId,
                                Component title, int size) {
        this.mode = mode;
        this.playerIndex = playerIndex;
        this.playerName = playerName;
        this.profile = profile;
        this.containerId = containerId;
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    /** Opens a live view of a recorded player's inventory at the session's current tick. */
    public static void openPlayerInventory(Player viewer, PlaybackSession session, int playerIndex) {
        InventorySnapshot snapshot = session.inventoryAt(playerIndex, session.currentTick());
        if (snapshot == null) {
            viewer.sendMessage(Component.text("Kein Inventar-Snapshot bis "
                    + PlaybackSession.formatTicks(session.currentTick()) + "."));
            return;
        }
        PlayerProfileData profile = session.profiles().get(playerIndex);
        String name = profile != null ? profile.name() : ("Spieler #" + playerIndex);
        InventoryInspectGui gui = new InventoryInspectGui(Mode.PLAYER, playerIndex, name, profile,
                null, Component.text("🎒 " + name), 54);
        gui.renderPlayer(snapshot);
        viewer.openInventory(gui.getInventory());
    }

    /** Opens a live view of a recorded container at the session's current tick. */
    public static void openContainer(Player viewer, PlaybackSession session, String containerId) {
        var byTick = session.allContainers().get(containerId);
        if (byTick == null || byTick.isEmpty()) {
            viewer.sendMessage(Component.text("Unbekannter Container: " + containerId));
            return;
        }
        // container size never changes for a given containerId — safe to size the GUI off
        // any recorded snapshot, even one that lies in the future relative to the current tick
        ContainerSnapshot anySnapshot = byTick.lastEntry().getValue();
        int size = Math.min(54, ((anySnapshot.slots().length + 8) / 9) * 9 + 9);
        InventoryInspectGui gui = new InventoryInspectGui(Mode.CONTAINER, -1, null, null,
                containerId, Component.text("📦 " + anySnapshot.containerType() + " @ "
                        + anySnapshot.x() + "/" + anySnapshot.y() + "/" + anySnapshot.z()), size);
        gui.renderContainer(session);
        viewer.openInventory(gui.getInventory());
    }

    /**
     * Re-checks the current snapshot for this GUI's target and, if it changed since the
     * last render, rebuilds the slot contents in-place ({@code setContents}, no reopen —
     * the title stays fixed, only the info item's timestamp/lore changes).
     */
    public void refresh(PlaybackSession session) {
        if (mode == Mode.PLAYER) {
            InventorySnapshot snapshot = session.inventoryAt(playerIndex, session.currentTick());
            if (snapshot != null && snapshot.tick() != lastRenderedSnapshotTick) {
                renderPlayer(snapshot);
            }
        } else {
            ContainerSnapshot snapshot = session.containerAt(containerId, session.currentTick());
            if (snapshot == null) {
                renderNoContainerSnapshot(session.currentTick());
            } else if (snapshot.tick() != lastRenderedSnapshotTick) {
                renderContainerSnapshot(snapshot);
            }
        }
    }

    private void renderPlayer(InventorySnapshot snapshot) {
        ItemStack[] gui = new ItemStack[inventory.getSize()];
        // main inventory 9..35 → rows 1-3
        for (int i = 9; i <= 35 && i < snapshot.slots().length; i++) {
            gui[i - 9] = ItemBytes.deserialize(snapshot.slots()[i]);
        }
        // hotbar 0..8 → row 4
        for (int i = 0; i <= 8 && i < snapshot.slots().length; i++) {
            gui[27 + i] = ItemBytes.deserialize(snapshot.slots()[i]);
        }
        // armor 36..39 + offhand 40 → row 5
        for (int i = 36; i <= 40 && i < snapshot.slots().length; i++) {
            gui[36 + (i - 36)] = ItemBytes.deserialize(snapshot.slots()[i]);
        }
        gui[44] = ItemBytes.deserialize(snapshot.cursor());
        // real skin of the inspected player, if one was captured
        gui[45] = profile != null ? Heads.playerHead(profile) : null;
        gui[49] = infoItem(List.of(
                "Spieler: " + playerName,
                "Zeit: " + PlaybackSession.formatTicks(snapshot.tick()),
                "Grund: " + snapshot.cause(),
                "Leben: " + (snapshot.health() / 10.0),
                "Hunger: " + snapshot.food(),
                "XP-Level: " + snapshot.xpLevel()));
        inventory.setContents(gui);
        lastRenderedSnapshotTick = snapshot.tick();
    }

    private void renderContainer(PlaybackSession session) {
        ContainerSnapshot snapshot = session.containerAt(containerId, session.currentTick());
        if (snapshot == null) {
            renderNoContainerSnapshot(session.currentTick());
        } else {
            renderContainerSnapshot(snapshot);
        }
    }

    private void renderContainerSnapshot(ContainerSnapshot snapshot) {
        int size = inventory.getSize();
        ItemStack[] gui = new ItemStack[size];
        // defensive: a container's size cannot change for the same containerId, but never
        // write past the reserved info row regardless
        for (int i = 0; i < snapshot.slots().length && i < size - 9; i++) {
            gui[i] = ItemBytes.deserialize(snapshot.slots()[i]);
        }
        gui[size - 5] = infoItem(List.of(
                "Container: " + snapshot.containerType(),
                "Position: " + snapshot.x() + " / " + snapshot.y() + " / " + snapshot.z(),
                "Zeit: " + PlaybackSession.formatTicks(snapshot.tick()),
                "Grund: " + snapshot.cause()));
        inventory.setContents(gui);
        lastRenderedSnapshotTick = snapshot.tick();
    }

    private void renderNoContainerSnapshot(int currentTick) {
        int size = inventory.getSize();
        ItemStack[] gui = new ItemStack[size];
        gui[size - 5] = infoItem(List.of(
                "Noch kein Snapshot bis " + PlaybackSession.formatTicks(currentTick)));
        inventory.setContents(gui);
        lastRenderedSnapshotTick = -1;
    }

    private static ItemStack infoItem(List<String> lines) {
        ItemStack item = new ItemStack(Material.OAK_SIGN);
        item.editMeta(meta -> {
            meta.displayName(Component.text("ℹ Snapshot-Info", NamedTextColor.AQUA));
            meta.lore(lines.stream()
                    .map(line -> (Component) Component.text(line, NamedTextColor.GRAY))
                    .toList());
        });
        return item;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
