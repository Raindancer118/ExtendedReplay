package dev.raindancer118.extendedreplay.paper.command;

import dev.raindancer118.extendedreplay.api.ReplaySessionEndReason;
import dev.raindancer118.extendedreplay.core.model.ContainerSnapshot;
import dev.raindancer118.extendedreplay.core.model.InventorySnapshot;
import dev.raindancer118.extendedreplay.paper.ExtendedReplayPlugin;
import dev.raindancer118.extendedreplay.paper.gui.InventoryInspectGui;
import dev.raindancer118.extendedreplay.paper.producer.ActiveSession;
import dev.raindancer118.extendedreplay.paper.replay.PlaybackSession;
import dev.raindancer118.extendedreplay.paper.replay.route.RouteManager;
import dev.raindancer118.extendedreplay.storage.meta.SessionRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * /erp command tree. Delegates to producer/replay managers depending on the server role.
 */
public final class ErpCommand implements TabExecutor {

    private final ExtendedReplayPlugin plugin;

    public ErpCommand(ExtendedReplayPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return help(sender);
        }
        try {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "help" -> help(sender);
                case "status" -> status(sender);
                case "test" -> test(sender);
                case "record" -> record(sender, args);
                case "sessions" -> sessions(sender);
                case "session" -> sessionInfo(sender, args);
                case "play" -> play(sender, args);
                case "pause" -> withPlayback(sender, s -> {
                    s.pause();
                    send(sender, "⏸ Pausiert bei " + PlaybackSession.formatTicks(s.currentTick()));
                });
                case "resume" -> withPlayback(sender, s -> {
                    s.play();
                    send(sender, "▶ Wiedergabe");
                });
                case "speed" -> speed(sender, args);
                case "jump", "time" -> jump(sender, args);
                case "rewind" -> relativeJump(sender, args, -1);
                case "forward" -> relativeJump(sender, args, 1);
                case "close", "disconnect" -> close(sender);
                case "connect" -> connect(sender, args);
                case "live" -> live(sender);
                case "events" -> events(sender);
                case "bookmark" -> bookmark(sender, args);
                case "bookmarks" -> bookmarks(sender);
                case "scene" -> scene(sender, args);
                case "follow" -> follow(sender, args);
                case "freecam" -> freecam(sender);
                case "inventory" -> inventory(sender, args);
                case "container" -> container(sender, args);
                case "inspect" -> inspect(sender, args);
                case "route" -> route(sender, args);
                case "verify" -> verify(sender, args);
                case "reindex" -> reindex(sender, args);
                case "delete" -> delete(sender, args);
                case "favorite" -> favorite(sender, args);
                case "storage" -> storageInfo(sender);
                case "cleanup" -> cleanup(sender);
                case "snapshot" -> snapshot(sender, args);
                case "heatmap" -> heatmap(sender, args);
                case "pov" -> pov(sender, args);
                case "cam" -> cam(sender, args);
                case "gui" -> gui(sender);
                default -> {
                    send(sender, "Unbekannter Befehl. /erp help");
                    yield true;
                }
            };
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "/erp command failed", e);
            send(sender, "Fehler: " + e.getMessage());
            return true;
        }
    }

    // --- general ---

    private boolean help(CommandSender sender) {
        send(sender, "— ExtendedReplay —");
        send(sender, "/erp status · Rolle, Metriken, Verbindung");
        if (plugin.role().records()) {
            send(sender, "/erp record start <name> | stop · Aufnahme");
        }
        if (plugin.role().playsBack()) {
            send(sender, "/erp sessions · /erp play <id> · /erp connect <id> · /erp close");
            send(sender, "/erp pause|resume|speed <x>|jump <m:ss>|rewind|forward <s>");
            send(sender, "/erp events · /erp bookmarks · /erp scene …");
            send(sender, "/erp inventory <spieler> · /erp container <x> <y> <z>");
            send(sender, "/erp route render <spieler|all> · route clear · route mode <m>");
            send(sender, "/erp verify <id> · /erp storage · /erp gui");
        }
        return true;
    }

    private boolean status(CommandSender sender) {
        send(sender, "Rolle: " + plugin.role());
        send(sender, "Version: " + plugin.getPluginMeta().getVersion()
                + " · Format v" + dev.raindancer118.extendedreplay.core.FormatConstants.FORMAT_VERSION
                + " · Protokoll v" + dev.raindancer118.extendedreplay.core.FormatConstants.PROTOCOL_VERSION);
        plugin.metrics().snapshot().forEach((key, value) -> send(sender, "  " + key + " = " + value));
        if (plugin.producer() != null) {
            send(sender, "Queue: " + plugin.producer().queue().size()
                    + " (dropped cosmetic: " + plugin.producer().queue().droppedCosmetic() + ")");
            plugin.producer().transport().metrics().forEach((key, value) ->
                    send(sender, "  transport." + key + " = " + value));
            for (ActiveSession session : plugin.producer().activeSessions()) {
                send(sender, "Aufnahme: " + session.name() + " @ "
                        + PlaybackSession.formatTicks(session.currentTick())
                        + " (" + session.trackedPlayers().size() + " Spieler)");
            }
        }
        if (plugin.replayServer() != null) {
            plugin.replayServer().metrics().forEach((key, value) ->
                    send(sender, "  ingest." + key + " = " + value));
            send(sender, "Live-Sessions: " + plugin.replayServer().liveSessions().size()
                    + " · Playbacks: " + (plugin.playback() != null
                    ? plugin.playback().activeSessionCount() : 0));
        }
        return true;
    }

    /** Self-test: runs a synthetic session through the local pipeline and verifies it. */
    private boolean test(CommandSender sender) {
        if (!sender.hasPermission("extendedreplay.admin")) {
            return noPermission(sender);
        }
        if (plugin.replayServer() == null) {
            send(sender, "Selbsttest braucht REPLAY/STANDALONE-Rolle (Storage).");
            return true;
        }
        send(sender, "Starte Selbsttest…");
        plugin.runSelfTest(result -> send(sender, result));
        return true;
    }

    // --- recording ---

    private boolean record(CommandSender sender, String[] args) {
        if (!sender.hasPermission("extendedreplay.record")) {
            return noPermission(sender);
        }
        if (plugin.producer() == null) {
            send(sender, "Dieser Server nimmt nicht auf (Rolle " + plugin.role() + ").");
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("start")) {
            // /erp record start [name] [snapshot] [world]
            // In-game: world defaults to the player's world. Console: world argument
            // or the server's default world.
            org.bukkit.World world;
            if (args.length >= 5) {
                world = Bukkit.getWorld(args[4]);
                if (world == null) {
                    send(sender, "Welt '" + args[4] + "' nicht gefunden.");
                    return true;
                }
            } else if (sender instanceof Player player) {
                world = player.getWorld();
            } else {
                world = Bukkit.getWorlds().get(0);
            }
            String name = args.length >= 3 ? args[2]
                    : "session-" + System.currentTimeMillis() / 1000;
            Map<String, String> metadata = new java.util.HashMap<>();
            metadata.put("started-by", sender.getName());
            if (args.length >= 4 && !args[3].equals("-")) {
                if (!plugin.snapshots().exists(args[3])) {
                    send(sender, "Snapshot '" + args[3] + "' existiert nicht — "
                            + "erst /erp snapshot create " + args[3]);
                    return true;
                }
                metadata.put("snapshot", args[3]);
            }
            ActiveSession session = plugin.producer().startSession(name, null,
                    world, null, metadata);
            send(sender, "Aufnahme gestartet: " + name + " (" + session.sessionId() + ")"
                    + " · Welt: " + world.getName()
                    + (metadata.containsKey("snapshot") ? " · Snapshot: " + args[3] : ""));
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
            List<ActiveSession> active = plugin.producer().activeSessions();
            if (active.isEmpty()) {
                send(sender, "Keine aktive Aufnahme.");
                return true;
            }
            for (ActiveSession session : active) {
                plugin.producer().endSession(session.sessionId(), ReplaySessionEndReason.STOPPED);
            }
            send(sender, "Aufnahme gestoppt (" + active.size() + " Session(s)).");
            return true;
        }
        send(sender, "/erp record start <name> | stop");
        return true;
    }

    // --- sessions / playback ---

    private boolean sessions(CommandSender sender) {
        if (requireReplay(sender)) {
            return true;
        }
        try {
            List<SessionRecord> records = plugin.replayServer().storage().listSessions(15);
            if (records.isEmpty()) {
                send(sender, "Keine gespeicherten Sessions.");
                return true;
            }
            send(sender, "— Sessions (" + records.size() + ") —");
            for (SessionRecord record : records) {
                send(sender, (record.favorite() ? "★ " : "") + record.name()
                        + " · " + PlaybackSession.formatTicks(record.lastTick())
                        + " · " + (record.isFinished() ? record.endReason() : "LIVE")
                        + " · " + record.sessionId());
            }
        } catch (Exception e) {
            send(sender, "Fehler beim Laden: " + e.getMessage());
        }
        return true;
    }

    private boolean sessionInfo(CommandSender sender, String[] args) {
        if (requireReplay(sender) || args.length < 2) {
            return true;
        }
        UUID id = resolveSessionId(sender, args[1]);
        if (id == null) {
            return true;
        }
        try {
            SessionRecord record = plugin.replayServer().storage().getSession(id).orElse(null);
            if (record == null) {
                send(sender, "Session nicht gefunden.");
                return true;
            }
            send(sender, "Name: " + record.name() + " · Welt: " + record.worldName());
            send(sender, "Dauer: " + PlaybackSession.formatTicks(record.lastTick())
                    + " · Ende: " + record.endReason());
            send(sender, "Spieler: " + plugin.replayServer().storage().listPlayers(id).stream()
                    .map(p -> p.name()).toList());
            send(sender, "Events: " + plugin.replayServer().storage().listEvents(id, null, 10_000).size()
                    + " · Segmente: " + plugin.replayServer().storage().database().listSegments(id).size());
        } catch (Exception e) {
            send(sender, "Fehler: " + e.getMessage());
        }
        return true;
    }

    private boolean play(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || requireReplay(sender)) {
            return true;
        }
        if (!sender.hasPermission("extendedreplay.playback")) {
            return noPermission(sender);
        }
        if (args.length < 2) {
            // resume current playback
            plugin.playback().sessionOf(player).ifPresentOrElse(session -> {
                session.play();
                send(sender, "▶ Wiedergabe");
            }, () -> send(sender, "/erp play <sessionId|name>"));
            return true;
        }
        UUID id = resolveSessionId(sender, args[1]);
        if (id == null) {
            return true;
        }
        send(sender, "Lade Session…");
        plugin.playback().open(id, player).whenComplete((session, error) -> {
            if (error != null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        send(sender, "Konnte Session nicht öffnen: " + error.getMessage()));
            }
        });
        return true;
    }

    private boolean speed(CommandSender sender, String[] args) {
        return withPlayback(sender, session -> {
            if (args.length < 2) {
                send(sender, "Geschwindigkeit: " + session.speed() + "x");
                return;
            }
            double value = Double.parseDouble(args[1].replace("x", ""));
            session.setSpeed(value);
            send(sender, "Geschwindigkeit: " + session.speed() + "x");
        });
    }

    private boolean jump(CommandSender sender, String[] args) {
        return withPlayback(sender, session -> {
            if (args.length < 2) {
                send(sender, "/erp jump <m:ss|sekunden|event <id>>");
                return;
            }
            if (args[1].equalsIgnoreCase("event") && args.length >= 3) {
                jumpToEvent(sender, session, args[2]);
                return;
            }
            int tick = parseTime(args[1]);
            session.seek(tick);
            send(sender, "Gesprungen zu " + PlaybackSession.formatTicks(session.currentTick()));
        });
    }

    private void jumpToEvent(CommandSender sender, PlaybackSession session, String eventId) {
        try {
            plugin.replayServer().storage().database().getEvent(Long.parseLong(eventId))
                    .ifPresentOrElse(event -> {
                        session.seek(event.tick());
                        send(sender, "Gesprungen zu " + event.eventType() + " @ "
                                + PlaybackSession.formatTicks(event.tick()));
                    }, () -> send(sender, "Event nicht gefunden."));
        } catch (Exception e) {
            send(sender, "Fehler: " + e.getMessage());
        }
    }

    private boolean relativeJump(CommandSender sender, String[] args, int direction) {
        return withPlayback(sender, session -> {
            int seconds = args.length >= 2 ? Integer.parseInt(args[1]) : 10;
            session.seek(session.currentTick() + direction * seconds * 20);
            send(sender, "Jetzt bei " + PlaybackSession.formatTicks(session.currentTick()));
        });
    }

    private boolean close(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        if (plugin.liveMirror() != null && plugin.liveMirror().isViewer(player)) {
            plugin.liveMirror().leave(player);
            send(sender, "Live-Mirror verlassen.");
            return true;
        }
        if (plugin.playback() != null) {
            plugin.playback().detachViewer(player);
            send(sender, "Playback geschlossen.");
        }
        return true;
    }

    /**
     * Connects to a session in a freshly generated playback world. Every playback opens
     * its own world; when the recording carries the producer world's seed, that world is
     * generated with the identical terrain — so the replay looks like the original arena
     * even without a snapshot. Stored recordings are never touched by this.
     */
    private boolean connect(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || requireReplay(sender)) {
            return true;
        }
        if (!sender.hasPermission("extendedreplay.playback")) {
            return noPermission(sender);
        }
        if (args.length < 2) {
            send(sender, "/erp connect <sessionId|name> — Session in frischer Seed-Welt öffnen");
            return true;
        }
        // leave any current playback/mirror first so the viewer switches cleanly
        if (plugin.liveMirror() != null && plugin.liveMirror().isViewer(player)) {
            plugin.liveMirror().leave(player);
        }
        plugin.playback().detachViewer(player);
        UUID id = resolveSessionId(sender, args[1]);
        if (id == null) {
            return true;
        }
        send(sender, "Verbinde mit Session… (Welt wird mit Original-Seed generiert)");
        plugin.playback().open(id, player).whenComplete((session, error) -> {
            if (error != null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        send(sender, "Konnte Session nicht öffnen: " + error.getMessage()));
            }
        });
        return true;
    }

    private boolean live(CommandSender sender) {
        if (!(sender instanceof Player player) || requireReplay(sender)) {
            return true;
        }
        UUID liveId = plugin.replayServer().anyLiveSession().orElse(null);
        if (liveId == null) {
            send(sender, "Keine laufende Live-Session.");
            return true;
        }
        plugin.liveMirror().join(player, liveId);
        return true;
    }

    // --- events / bookmarks / scenes ---

    private boolean events(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        if (!sender.hasPermission("extendedreplay.events")) {
            return noPermission(sender);
        }
        return withPlayback(sender, session ->
                plugin.guiListener().openEventBrowser(player, session));
    }

    private boolean bookmark(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "/erp bookmark <name>");
            return true;
        }
        // producer side: live bookmark into the running session
        if (plugin.producer() != null && !plugin.producer().activeSessions().isEmpty()) {
            ActiveSession session = plugin.producer().activeSessions().get(0);
            plugin.producer().recordBookmark(session,
                    dev.raindancer118.extendedreplay.api.ReplayBookmark.now(args[1]));
            send(sender, "Bookmark '" + args[1] + "' gesetzt @ "
                    + PlaybackSession.formatTicks(session.currentTick()));
            return true;
        }
        // replay side: bookmark at current playback position
        return withPlayback(sender, session -> {
            try {
                plugin.replayServer().storage().database().insertBookmark(session.sessionId(),
                        session.currentTick(), args[1], null);
                send(sender, "Bookmark '" + args[1] + "' gesetzt @ "
                        + PlaybackSession.formatTicks(session.currentTick()));
            } catch (Exception e) {
                send(sender, "Fehler: " + e.getMessage());
            }
        });
    }

    private boolean bookmarks(CommandSender sender) {
        return withPlayback(sender, session -> {
            try {
                var list = plugin.replayServer().storage().database()
                        .listBookmarks(session.sessionId());
                if (list.isEmpty()) {
                    send(sender, "Keine Bookmarks.");
                    return;
                }
                for (var entry : list) {
                    send(sender, PlaybackSession.formatTicks(entry.getKey()) + " · "
                            + entry.getValue());
                }
            } catch (Exception e) {
                send(sender, "Fehler: " + e.getMessage());
            }
        });
    }

    private boolean scene(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "/erp scene create <name> | list | open <name> | delete <name>");
            return true;
        }
        return withPlayback(sender, session -> {
            try {
                var db = plugin.replayServer().storage().database();
                switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "create" -> {
                        if (args.length < 3) {
                            send(sender, "/erp scene create <name>");
                            return;
                        }
                        int start = Math.max(0, session.currentTick() - 300);
                        int end = Math.min(session.lastTickOfSession(), session.currentTick() + 300);
                        db.upsertScene(session.sessionId(), args[2], start, end, null,
                                session.speed(), null);
                        send(sender, "Szene '" + args[2] + "' gespeichert ("
                                + PlaybackSession.formatTicks(start) + "–"
                                + PlaybackSession.formatTicks(end) + ")");
                    }
                    case "list" -> {
                        var scenes = db.listScenes(session.sessionId());
                        if (scenes.isEmpty()) {
                            send(sender, "Keine Szenen.");
                        }
                        scenes.forEach(scene -> send(sender, scene.name() + " · "
                                + PlaybackSession.formatTicks(scene.startTick()) + "–"
                                + PlaybackSession.formatTicks(scene.endTick())));
                    }
                    case "open" -> {
                        if (args.length < 3) {
                            send(sender, "/erp scene open <name>");
                            return;
                        }
                        db.getScene(session.sessionId(), args[2]).ifPresentOrElse(scene -> {
                            session.seek(scene.startTick());
                            session.setSpeed(scene.speed());
                            session.play();
                            send(sender, "Szene '" + scene.name() + "' gestartet.");
                        }, () -> send(sender, "Szene nicht gefunden."));
                    }
                    case "delete" -> {
                        if (args.length < 3) {
                            send(sender, "/erp scene delete <name>");
                            return;
                        }
                        db.deleteScene(session.sessionId(), args[2]);
                        send(sender, "Szene gelöscht.");
                    }
                    default -> send(sender, "/erp scene create|list|open|delete");
                }
            } catch (Exception e) {
                send(sender, "Fehler: " + e.getMessage());
            }
        });
    }

    // --- camera ---

    private boolean follow(CommandSender sender, String[] args) {
        return withPlayback(sender, session -> {
            if (args.length < 2) {
                session.follow(null);
                send(sender, "Folgen beendet.");
                return;
            }
            Integer index = session.playerIndexByName(args[1]);
            if (index == null) {
                send(sender, "Spieler nicht in der Aufnahme: " + args[1]);
                return;
            }
            session.follow(index);
            send(sender, "Folge " + args[1]);
        });
    }

    private boolean freecam(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(GameMode.ADVENTURE);
            player.setAllowFlight(true);
            player.setFlying(true);
            plugin.hotbar().give(player);
            send(sender, "Freecam aus.");
        } else {
            player.setGameMode(GameMode.SPECTATOR);
            send(sender, "Freecam an. Zurück mit /erp freecam");
        }
        return true;
    }

    // --- inspection ---

    private boolean inventory(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        if (!sender.hasPermission("extendedreplay.inventory.view")) {
            return noPermission(sender);
        }
        if (args.length < 2) {
            send(sender, "/erp inventory <spieler>");
            return true;
        }
        return withPlayback(sender, session -> {
            Integer index = session.playerIndexByName(args[1]);
            if (index == null) {
                send(sender, "Spieler nicht in der Aufnahme: " + args[1]);
                return;
            }
            InventorySnapshot snapshot = session.inventoryAt(index, session.currentTick());
            if (snapshot == null) {
                send(sender, "Kein Inventar-Snapshot bis "
                        + PlaybackSession.formatTicks(session.currentTick()));
                return;
            }
            InventoryInspectGui.openPlayerInventory(player, args[1], snapshot,
                    session.profiles().get(index));
        });
    }

    private boolean container(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        if (!sender.hasPermission("extendedreplay.container.view")) {
            return noPermission(sender);
        }
        if (args.length < 4) {
            send(sender, "/erp container <x> <y> <z>");
            return true;
        }
        return withPlayback(sender, session -> {
            int x = Integer.parseInt(args[1]);
            int y = Integer.parseInt(args[2]);
            int z = Integer.parseInt(args[3]);
            ContainerSnapshot snapshot = null;
            // container ids are recorded with the original world name — try all known ids
            for (String containerId : session.allContainers().keySet()) {
                if (containerId.endsWith(":" + x + "," + y + "," + z)) {
                    snapshot = session.containerAt(containerId, session.currentTick());
                    break;
                }
            }
            if (snapshot == null) {
                send(sender, "Kein Container-Snapshot an " + x + "/" + y + "/" + z
                        + " bis " + PlaybackSession.formatTicks(session.currentTick()));
                return;
            }
            InventoryInspectGui.openContainer(player, snapshot);
        });
    }

    private boolean inspect(CommandSender sender, String[] args) {
        if (args.length >= 3 && args[1].equalsIgnoreCase("player")) {
            return inventory(sender, new String[]{"inventory", args[2]});
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("chest")
                && sender instanceof Player player) {
            var target = player.getTargetBlockExact(6);
            if (target == null) {
                send(sender, "Kein Block im Blick.");
                return true;
            }
            return container(sender, new String[]{"container",
                    Integer.toString(target.getX()), Integer.toString(target.getY()),
                    Integer.toString(target.getZ())});
        }
        send(sender, "/erp inspect player <name> | chest");
        return true;
    }

    // --- routes ---

    private boolean route(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        if (!sender.hasPermission("extendedreplay.route.render")) {
            return noPermission(sender);
        }
        if (args.length < 2) {
            send(sender, "/erp route render <spieler|all> [startSek] [endSek] | cancel | clear | mode <m>");
            return true;
        }
        RouteManager routes = plugin.routes();
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "render" -> {
                return withPlayback(sender, session -> {
                    List<Integer> indexes = new ArrayList<>();
                    if (args.length >= 3 && !args[2].equalsIgnoreCase("all")) {
                        Integer index = session.playerIndexByName(args[2]);
                        if (index == null) {
                            send(sender, "Spieler nicht in der Aufnahme: " + args[2]);
                            return;
                        }
                        indexes.add(index);
                    }
                    int start = args.length >= 4 ? Integer.parseInt(args[3]) * 20 : 0;
                    int end = args.length >= 5 ? Integer.parseInt(args[4]) * 20
                            : session.lastTickOfSession();
                    routes.render(player, session, indexes, start, end);
                });
            }
            case "cancel" -> {
                routes.cancel();
                send(sender, "Route-Job wird abgebrochen.");
            }
            case "clear" -> routes.clear(player);
            case "mode" -> {
                if (args.length >= 3) {
                    routes.setMode(RouteManager.Mode.parse(args[2]));
                }
                send(sender, "Route-Modus: " + routes.mode());
            }
            default -> send(sender, "/erp route render|cancel|clear|mode");
        }
        return true;
    }

    // --- snapshots ---

    private boolean snapshot(CommandSender sender, String[] args) {
        if (!sender.hasPermission("extendedreplay.snapshot")) {
            return noPermission(sender);
        }
        if (plugin.snapshots() == null) {
            send(sender, "Snapshots sind in Rolle " + plugin.role() + " nicht verfügbar.");
            return true;
        }
        if (args.length < 2) {
            send(sender, "/erp snapshot create <name> <radius|x1 y1 z1 x2 y2 z2> | list | info <n> | verify <n> | delete <n>");
            return true;
        }
        var snapshots = plugin.snapshots();
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "Nur in-game.");
                    return true;
                }
                if (args.length < 4) {
                    send(sender, "/erp snapshot create <name> <radius> — oder — "
                            + "/erp snapshot create <name> <x1> <y1> <z1> <x2> <y2> <z2>");
                    return true;
                }
                String name = args[2];
                int x1;
                int y1;
                int z1;
                int x2;
                int y2;
                int z2;
                if (args.length >= 9) {
                    x1 = Integer.parseInt(args[3]);
                    y1 = Integer.parseInt(args[4]);
                    z1 = Integer.parseInt(args[5]);
                    x2 = Integer.parseInt(args[6]);
                    y2 = Integer.parseInt(args[7]);
                    z2 = Integer.parseInt(args[8]);
                } else {
                    int radius = Integer.parseInt(args[3]);
                    var center = player.getLocation();
                    x1 = center.getBlockX() - radius;
                    z1 = center.getBlockZ() - radius;
                    x2 = center.getBlockX() + radius;
                    z2 = center.getBlockZ() + radius;
                    y1 = player.getWorld().getMinHeight();
                    y2 = player.getWorld().getMaxHeight() - 1;
                }
                send(sender, "Erstelle Snapshot '" + name + "'…");
                snapshots.create(name, player.getWorld(), x1, y1, z1, x2, y2, z2, player)
                        .whenComplete((sha, error) -> {
                            if (error != null) {
                                send(sender, "Snapshot fehlgeschlagen: " + error.getMessage());
                            } else {
                                send(sender, "✔ Snapshot '" + name + "' gespeichert (sha256 "
                                        + sha.substring(0, 16) + "…). Nutzung: /erp record start <session> " + name);
                            }
                        });
            }
            case "list" -> {
                try {
                    var list = snapshots.list();
                    send(sender, list.isEmpty() ? "Keine Snapshots."
                            : "Snapshots: " + String.join(", ", list));
                } catch (Exception e) {
                    send(sender, "Fehler: " + e.getMessage());
                }
            }
            case "info", "verify" -> {
                if (args.length < 3) {
                    send(sender, "/erp snapshot " + args[1] + " <name>");
                    return true;
                }
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        send(sender, snapshots.describe(args[2]));
                        send(sender, "✔ Datei lesbar, Header und Palette intakt.");
                    } catch (Exception e) {
                        send(sender, "✘ Snapshot defekt oder fehlt: " + e.getMessage());
                    }
                });
            }
            case "delete" -> {
                if (args.length < 3) {
                    send(sender, "/erp snapshot delete <name>");
                    return true;
                }
                if (args.length < 4 || !args[3].equalsIgnoreCase("confirm")) {
                    send(sender, "Bestätigen: /erp snapshot delete " + args[2] + " confirm");
                    return true;
                }
                try {
                    snapshots.delete(args[2]);
                    send(sender, "Snapshot gelöscht.");
                } catch (Exception e) {
                    send(sender, "Fehler: " + e.getMessage());
                }
            }
            default -> send(sender, "/erp snapshot create|list|info|verify|delete");
        }
        return true;
    }

    // --- heatmaps ---

    private boolean heatmap(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        if (!sender.hasPermission("extendedreplay.heatmap")) {
            return noPermission(sender);
        }
        if (args.length < 2) {
            send(sender, "/erp heatmap movement|kills|deaths|loot");
            return true;
        }
        return withPlayback(sender, session -> {
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "movement" -> plugin.routes().renderMovementHeatmap(player, session);
                case "kills" -> plugin.routes().renderEventHeatmap(player, session, "KILL");
                case "deaths" -> plugin.routes().renderEventHeatmap(player, session, "DEATH");
                case "loot" -> plugin.routes().renderEventHeatmap(player, session, "LOOT");
                default -> send(sender, "/erp heatmap movement|kills|deaths|loot");
            }
        });
    }

    // --- camera ---

    private boolean pov(CommandSender sender, String[] args) {
        return withPlayback(sender, session -> {
            if (args.length < 2) {
                session.pov(null);
                send(sender, "POV beendet.");
                return;
            }
            Integer index = session.playerIndexByName(args[1]);
            if (index == null) {
                send(sender, "Spieler nicht in der Aufnahme: " + args[1]);
                return;
            }
            session.pov(index);
            send(sender, "POV: " + args[1] + " (beenden: /erp pov)");
        });
    }

    private boolean cam(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || plugin.playback() == null) {
            return true;
        }
        if (args.length < 2) {
            send(sender, "/erp cam save|goto|list|delete <name>");
            return true;
        }
        Map<String, org.bukkit.Location> cameras = plugin.playback().camerasOf(player);
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "save" -> {
                if (args.length < 3) {
                    send(sender, "/erp cam save <name>");
                    return true;
                }
                cameras.put(args[2], player.getLocation().clone());
                send(sender, "Kamera '" + args[2] + "' gespeichert.");
            }
            case "goto" -> {
                if (args.length < 3 || !cameras.containsKey(args[2])) {
                    send(sender, "Unbekannte Kamera. Gespeichert: " + cameras.keySet());
                    return true;
                }
                player.teleport(cameras.get(args[2]));
            }
            case "list" -> send(sender, cameras.isEmpty() ? "Keine Kameras gespeichert."
                    : "Kameras: " + String.join(", ", cameras.keySet()));
            case "delete" -> {
                if (args.length >= 3 && cameras.remove(args[2]) != null) {
                    send(sender, "Kamera gelöscht.");
                } else {
                    send(sender, "Unbekannte Kamera.");
                }
            }
            default -> send(sender, "/erp cam save|goto|list|delete <name>");
        }
        return true;
    }

    // --- admin / storage ---

    private boolean reindex(CommandSender sender, String[] args) {
        if (requireReplay(sender) || args.length < 2) {
            return true;
        }
        if (!sender.hasPermission("extendedreplay.storage")) {
            return noPermission(sender);
        }
        UUID id;
        try {
            id = UUID.fromString(args[1]); // index may be gone: name lookup unavailable
        } catch (IllegalArgumentException e) {
            UUID resolved = resolveSessionId(sender, args[1]);
            if (resolved == null) {
                return true;
            }
            id = resolved;
        }
        UUID finalId = id;
        send(sender, "Reindiziere aus Segment-Dateien…");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int packets = plugin.replayServer().storage().reindex(finalId);
                send(sender, "✔ Reindex fertig: " + packets + " Pakete indiziert.");
            } catch (Exception e) {
                send(sender, "✘ Reindex fehlgeschlagen: " + e.getMessage());
            }
        });
        return true;
    }

    private boolean cleanup(CommandSender sender) {
        if (requireReplay(sender)) {
            return true;
        }
        if (!sender.hasPermission("extendedreplay.storage")) {
            return noPermission(sender);
        }
        send(sender, "Wende Retention an (max-days=" + plugin.config().retentionDays()
                + ", max-gb=" + plugin.config().maxStorageBytes() / (1024 * 1024 * 1024) + ")…");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var deleted = plugin.replayServer().storage().cleanup(
                        plugin.config().retentionDays(), plugin.config().maxStorageBytes());
                send(sender, deleted.isEmpty() ? "Nichts zu löschen."
                        : deleted.size() + " Session(s) gelöscht (Favoriten verschont).");
            } catch (Exception e) {
                send(sender, "Fehler: " + e.getMessage());
            }
        });
        return true;
    }

    private boolean verify(CommandSender sender, String[] args) {
        if (requireReplay(sender) || args.length < 2) {
            return true;
        }
        if (!sender.hasPermission("extendedreplay.storage")) {
            return noPermission(sender);
        }
        UUID id = resolveSessionId(sender, args[1]);
        if (id == null) {
            return true;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<String> problems = plugin.replayServer().verify(id);
                if (problems.isEmpty()) {
                    send(sender, "✔ Session ist intakt (alle Checksummen OK).");
                } else {
                    send(sender, "✘ " + problems.size() + " Problem(e):");
                    problems.forEach(problem -> send(sender, "  " + problem));
                }
            } catch (Exception e) {
                send(sender, "Fehler: " + e.getMessage());
            }
        });
        return true;
    }

    private boolean delete(CommandSender sender, String[] args) {
        if (requireReplay(sender) || args.length < 2) {
            return true;
        }
        if (!sender.hasPermission("extendedreplay.storage")) {
            return noPermission(sender);
        }
        UUID id = resolveSessionId(sender, args[1]);
        if (id == null) {
            return true;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            send(sender, "Löschen bestätigen: /erp delete " + args[1] + " confirm");
            return true;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.replayServer().storage().deleteSessionData(id);
                send(sender, "Session gelöscht.");
            } catch (Exception e) {
                send(sender, "Fehler: " + e.getMessage());
            }
        });
        return true;
    }

    private boolean favorite(CommandSender sender, String[] args) {
        if (requireReplay(sender) || args.length < 2) {
            return true;
        }
        UUID id = resolveSessionId(sender, args[1]);
        if (id == null) {
            return true;
        }
        try {
            var record = plugin.replayServer().storage().getSession(id).orElse(null);
            if (record == null) {
                send(sender, "Session nicht gefunden.");
                return true;
            }
            plugin.replayServer().storage().database().setFavorite(id, !record.favorite());
            send(sender, record.favorite() ? "★ entfernt." : "★ favorisiert.");
        } catch (Exception e) {
            send(sender, "Fehler: " + e.getMessage());
        }
        return true;
    }

    private boolean storageInfo(CommandSender sender) {
        if (requireReplay(sender)) {
            return true;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                long bytes = plugin.replayServer().storage().storageBytes();
                int count = plugin.replayServer().storage().listSessions(1000).size();
                send(sender, "Speicher: " + (bytes / 1024 / 1024) + " MB · "
                        + count + " Session(s)");
            } catch (Exception e) {
                send(sender, "Fehler: " + e.getMessage());
            }
        });
        return true;
    }

    private boolean gui(CommandSender sender) {
        if (sender instanceof Player player && plugin.playback() != null) {
            PlaybackSession session = plugin.playback().sessionOf(player).orElse(null);
            if (session != null) {
                plugin.hotbar().give(player);
                plugin.guiListener().openPlaybackControl(player, session);
                send(sender, "Wiedergabe-GUI geöffnet.");
                return true;
            }
            // no active playback and not currently watching the live mirror: offer the
            // full session browser instead of the hotbar so the player can pick a recording
            boolean isLiveViewer = plugin.liveMirror() != null && plugin.liveMirror().isViewer(player);
            if (!isLiveViewer) {
                if (!sender.hasPermission("extendedreplay.viewer")) {
                    return noPermission(sender);
                }
                plugin.guiListener().openSessionBrowser(player);
                send(sender, "Session-Browser wird geladen…");
                return true;
            }
        }
        send(sender, "Erst eine Session öffnen: /erp play <id>");
        return true;
    }

    // --- helpers ---

    private interface PlaybackAction {
        void run(PlaybackSession session) throws Exception;
    }

    private boolean withPlayback(CommandSender sender, PlaybackAction action) {
        if (!(sender instanceof Player player) || plugin.playback() == null) {
            send(sender, "Nur in-game auf dem Replay-Server verfügbar.");
            return true;
        }
        PlaybackSession session = plugin.playback().sessionOf(player).orElse(null);
        if (session == null) {
            send(sender, "Keine aktive Playback-Session. /erp play <id>");
            return true;
        }
        try {
            action.run(session);
        } catch (NumberFormatException e) {
            send(sender, "Ungültige Zahl.");
        } catch (Exception e) {
            send(sender, "Fehler: " + e.getMessage());
        }
        return true;
    }

    private boolean requireReplay(CommandSender sender) {
        if (plugin.replayServer() == null) {
            send(sender, "Dieser Server ist kein Replay-Server (Rolle " + plugin.role() + ").");
            return true;
        }
        return false;
    }

    /** Accepts a full UUID, a unique UUID prefix, or a session name. */
    private UUID resolveSessionId(CommandSender sender, String input) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {
            // fall through to prefix/name lookup
        }
        try {
            List<SessionRecord> all = plugin.replayServer().storage().listSessions(1000);
            List<SessionRecord> matches = all.stream()
                    .filter(record -> record.sessionId().toString().startsWith(input)
                            || record.name().equalsIgnoreCase(input))
                    .toList();
            if (matches.size() == 1) {
                return matches.get(0).sessionId();
            }
            send(sender, matches.isEmpty() ? "Keine Session gefunden: " + input
                    : "Mehrdeutig (" + matches.size() + " Treffer) — bitte UUID nutzen.");
        } catch (Exception e) {
            send(sender, "Fehler: " + e.getMessage());
        }
        return null;
    }

    /** Parses "m:ss" or plain seconds into ticks. */
    static int parseTime(String input) {
        if (input.contains(":")) {
            String[] parts = input.split(":");
            return (Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1])) * 20;
        }
        return Integer.parseInt(input) * 20;
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.GRAY));
    }

    private boolean noPermission(CommandSender sender) {
        send(sender, "Keine Berechtigung.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("help", "status"));
            if (plugin.role().records()) {
                subs.addAll(List.of("record", "bookmark"));
            }
            if (plugin.role().records() || plugin.role().playsBack()) {
                subs.add("snapshot");
            }
            if (plugin.role().playsBack()) {
                subs.addAll(List.of("sessions", "session", "play", "connect", "disconnect",
                        "pause", "resume", "speed",
                        "jump", "rewind", "forward", "close", "live", "events", "bookmarks",
                        "scene", "follow", "freecam", "pov", "cam", "inventory", "container",
                        "inspect", "route", "heatmap", "verify", "reindex", "delete",
                        "favorite", "storage", "cleanup", "gui", "test"));
            }
            return subs.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "record" -> List.of("start", "stop");
                case "route" -> List.of("render", "cancel", "clear", "mode");
                case "scene" -> List.of("create", "list", "open", "delete");
                case "speed" -> List.of("0.25", "0.5", "1", "2", "4", "8");
                case "inspect" -> List.of("player", "chest");
                case "snapshot" -> List.of("create", "list", "info", "verify", "delete");
                case "heatmap" -> List.of("movement", "kills", "deaths", "loot");
                case "cam" -> List.of("save", "goto", "list", "delete");
                default -> List.of();
            };
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("route")
                && args[1].equalsIgnoreCase("mode")) {
            return List.of("carpet", "glass", "concrete", "particles");
        }
        return List.of();
    }
}
