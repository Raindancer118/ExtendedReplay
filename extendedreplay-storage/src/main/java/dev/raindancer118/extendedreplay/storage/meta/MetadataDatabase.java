package dev.raindancer118.extendedreplay.storage.meta;

import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import dev.raindancer118.extendedreplay.storage.segment.SegmentFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite-backed metadata index: sessions, players, events, bookmarks, segment references.
 * Heavy replay data lives in segment files; this database only holds what needs to be
 * queryable (session list, event browser, integrity info).
 *
 * <p>All methods are synchronized — callers are the storage pipeline thread and command
 * handlers, throughput is not a concern here.</p>
 */
public final class MetadataDatabase implements AutoCloseable {

    private final Connection connection;

    public MetadataDatabase(Path databaseFile) throws SQLException, IOException {
        Files.createDirectories(databaseFile.toAbsolutePath().getParent());
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
        }
        createSchema();
        migrateSchema();
    }

    private void createSchema() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS sessions (
                      session_id TEXT PRIMARY KEY,
                      name TEXT NOT NULL,
                      external_key TEXT,
                      world_name TEXT NOT NULL,
                      started_at INTEGER NOT NULL,
                      ended_at INTEGER NOT NULL DEFAULT 0,
                      last_tick INTEGER NOT NULL DEFAULT 0,
                      end_reason TEXT,
                      snapshot_name TEXT,
                      favorite INTEGER NOT NULL DEFAULT 0,
                      format_version INTEGER NOT NULL,
                      world_seed INTEGER,
                      world_environment TEXT
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS players (
                      session_id TEXT NOT NULL,
                      player_index INTEGER NOT NULL,
                      uuid TEXT NOT NULL,
                      name TEXT NOT NULL,
                      skin_value TEXT,
                      skin_signature TEXT,
                      PRIMARY KEY (session_id, player_index)
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS events (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      session_id TEXT NOT NULL,
                      tick INTEGER NOT NULL,
                      event_type TEXT NOT NULL,
                      category TEXT NOT NULL,
                      actor_uuid TEXT,
                      target_uuid TEXT,
                      world_name TEXT,
                      x REAL, y REAL, z REAL,
                      metadata TEXT
                    )""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_events_session_tick ON events(session_id, tick)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_events_session_category ON events(session_id, category)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS bookmarks (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      session_id TEXT NOT NULL,
                      tick INTEGER NOT NULL,
                      name TEXT NOT NULL,
                      note TEXT
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS segments (
                      session_id TEXT NOT NULL,
                      segment_index INTEGER NOT NULL,
                      file_name TEXT NOT NULL,
                      start_tick INTEGER NOT NULL,
                      end_tick INTEGER NOT NULL,
                      packet_count INTEGER NOT NULL,
                      compressed_bytes INTEGER NOT NULL,
                      crc32 INTEGER NOT NULL,
                      PRIMARY KEY (session_id, segment_index)
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS scenes (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      session_id TEXT NOT NULL,
                      name TEXT NOT NULL,
                      start_tick INTEGER NOT NULL,
                      end_tick INTEGER NOT NULL,
                      followed_player TEXT,
                      speed REAL NOT NULL DEFAULT 1.0,
                      notes TEXT,
                      UNIQUE (session_id, name)
                    )""");
        }
    }

    /**
     * Adds columns to existing databases created before a given column existed. New
     * databases already have the column from {@link #createSchema()}; the
     * {@code PRAGMA table_info} check makes each addition a no-op when it already ran.
     */
    private void migrateSchema() throws SQLException {
        addColumnIfMissing("sessions", "world_seed", "INTEGER");
        addColumnIfMissing("sessions", "world_environment", "TEXT");
    }

    private void addColumnIfMissing(String table, String column, String type) throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement s = connection.createStatement()) {
            s.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    // --- sessions ---

    /**
     * Base SELECT for session rows: {@code last_tick} is the effective value, i.e. the max
     * of the persisted {@code sessions.last_tick} (only written on session end) and the
     * highest {@code end_tick} already flushed to the segments table. This keeps live
     * (not-yet-ended) sessions from reporting a 0 tick / 0:00 duration in listings.
     */
    private static final String SESSION_SELECT = """
            SELECT s.session_id, s.name, s.external_key, s.world_name, s.started_at, s.ended_at,
                   MAX(s.last_tick, COALESCE(seg.max_end_tick, 0)) AS last_tick,
                   s.end_reason, s.snapshot_name, s.favorite, s.format_version,
                   s.world_seed, s.world_environment
            FROM sessions s
            LEFT JOIN (
              SELECT session_id, MAX(end_tick) AS max_end_tick FROM segments GROUP BY session_id
            ) seg ON seg.session_id = s.session_id
            """;

    public synchronized void insertSession(SessionRecord record) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("""
                INSERT OR REPLACE INTO sessions
                  (session_id, name, external_key, world_name, started_at, ended_at,
                   last_tick, end_reason, snapshot_name, favorite, format_version,
                   world_seed, world_environment)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""")) {
            p.setString(1, record.sessionId().toString());
            p.setString(2, record.name());
            p.setString(3, record.externalKey());
            p.setString(4, record.worldName());
            p.setLong(5, record.startedAtMillis());
            p.setLong(6, record.endedAtMillis());
            p.setInt(7, record.lastTick());
            p.setString(8, record.endReason());
            p.setString(9, record.snapshotName());
            p.setInt(10, record.favorite() ? 1 : 0);
            p.setInt(11, record.formatVersion());
            if (record.worldSeed() != null) {
                p.setLong(12, record.worldSeed());
            } else {
                p.setNull(12, java.sql.Types.INTEGER);
            }
            p.setString(13, record.worldEnvironment());
            p.executeUpdate();
        }
    }

    public synchronized void finishSession(UUID sessionId, long endedAtMillis, int lastTick,
                                           String endReason) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(
                "UPDATE sessions SET ended_at=?, last_tick=?, end_reason=? WHERE session_id=?")) {
            p.setLong(1, endedAtMillis);
            p.setInt(2, lastTick);
            p.setString(3, endReason);
            p.setString(4, sessionId.toString());
            p.executeUpdate();
        }
    }

    public synchronized void setSnapshotName(UUID sessionId, String snapshotName) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(
                "UPDATE sessions SET snapshot_name=? WHERE session_id=?")) {
            p.setString(1, snapshotName);
            p.setString(2, sessionId.toString());
            p.executeUpdate();
        }
    }

    public synchronized void setFavorite(UUID sessionId, boolean favorite) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(
                "UPDATE sessions SET favorite=? WHERE session_id=?")) {
            p.setInt(1, favorite ? 1 : 0);
            p.setString(2, sessionId.toString());
            p.executeUpdate();
        }
    }

    public synchronized Optional<SessionRecord> getSession(UUID sessionId) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(
                SESSION_SELECT + "WHERE s.session_id=?")) {
            p.setString(1, sessionId.toString());
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(readSession(rs)) : Optional.empty();
            }
        }
    }

    public synchronized List<SessionRecord> listSessions(int limit) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(
                SESSION_SELECT + "ORDER BY s.started_at DESC LIMIT ?")) {
            p.setInt(1, limit);
            try (ResultSet rs = p.executeQuery()) {
                List<SessionRecord> sessions = new ArrayList<>();
                while (rs.next()) {
                    sessions.add(readSession(rs));
                }
                return sessions;
            }
        }
    }

    public synchronized void deleteSession(UUID sessionId) throws SQLException {
        for (String table : List.of("sessions", "players", "events", "bookmarks", "segments", "scenes")) {
            try (PreparedStatement p = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE session_id=?")) {
                p.setString(1, sessionId.toString());
                p.executeUpdate();
            }
        }
    }

    private SessionRecord readSession(ResultSet rs) throws SQLException {
        // read world_seed (nullable) first: wasNull() reflects the most recently read column
        long rawWorldSeed = rs.getLong("world_seed");
        Long worldSeed = rs.wasNull() ? null : rawWorldSeed;
        return new SessionRecord(
                UUID.fromString(rs.getString("session_id")),
                rs.getString("name"),
                rs.getString("external_key"),
                rs.getString("world_name"),
                rs.getLong("started_at"),
                rs.getLong("ended_at"),
                rs.getInt("last_tick"),
                rs.getString("end_reason"),
                rs.getString("snapshot_name"),
                rs.getInt("favorite") != 0,
                rs.getInt("format_version"),
                worldSeed,
                rs.getString("world_environment"));
    }

    // --- players ---

    public synchronized void insertPlayer(UUID sessionId, PlayerProfileData profile) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("""
                INSERT OR REPLACE INTO players
                  (session_id, player_index, uuid, name, skin_value, skin_signature)
                VALUES (?,?,?,?,?,?)""")) {
            p.setString(1, sessionId.toString());
            p.setInt(2, profile.playerIndex());
            p.setString(3, profile.uuid().toString());
            p.setString(4, profile.name());
            p.setString(5, profile.skinTextureValue());
            p.setString(6, profile.skinTextureSignature());
            p.executeUpdate();
        }
    }

    public synchronized List<PlayerProfileData> listPlayers(UUID sessionId) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT * FROM players WHERE session_id=? ORDER BY player_index")) {
            p.setString(1, sessionId.toString());
            try (ResultSet rs = p.executeQuery()) {
                List<PlayerProfileData> players = new ArrayList<>();
                while (rs.next()) {
                    players.add(new PlayerProfileData(
                            rs.getInt("player_index"),
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("name"),
                            rs.getString("skin_value"),
                            rs.getString("skin_signature")));
                }
                return players;
            }
        }
    }

    // --- events ---

    public synchronized void insertEvent(UUID sessionId, int tick, String eventType, String category,
                                         UUID actor, UUID target, String worldName,
                                         double x, double y, double z,
                                         Map<String, String> metadata) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("""
                INSERT INTO events
                  (session_id, tick, event_type, category, actor_uuid, target_uuid,
                   world_name, x, y, z, metadata)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)""")) {
            p.setString(1, sessionId.toString());
            p.setInt(2, tick);
            p.setString(3, eventType);
            p.setString(4, category);
            p.setString(5, actor != null ? actor.toString() : null);
            p.setString(6, target != null ? target.toString() : null);
            p.setString(7, worldName);
            p.setDouble(8, x);
            p.setDouble(9, y);
            p.setDouble(10, z);
            p.setString(11, encodeMetadata(metadata));
            p.executeUpdate();
        }
    }

    public synchronized List<EventRecord> listEvents(UUID sessionId, String category,
                                                     int limit) throws SQLException {
        String sql = category == null
                ? "SELECT * FROM events WHERE session_id=? ORDER BY tick LIMIT ?"
                : "SELECT * FROM events WHERE session_id=? AND category=? ORDER BY tick LIMIT ?";
        try (PreparedStatement p = connection.prepareStatement(sql)) {
            p.setString(1, sessionId.toString());
            if (category == null) {
                p.setInt(2, limit);
            } else {
                p.setString(2, category);
                p.setInt(3, limit);
            }
            try (ResultSet rs = p.executeQuery()) {
                List<EventRecord> events = new ArrayList<>();
                while (rs.next()) {
                    events.add(readEvent(rs));
                }
                return events;
            }
        }
    }

    public synchronized Optional<EventRecord> getEvent(long eventId) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("SELECT * FROM events WHERE id=?")) {
            p.setLong(1, eventId);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(readEvent(rs)) : Optional.empty();
            }
        }
    }

    private EventRecord readEvent(ResultSet rs) throws SQLException {
        String actor = rs.getString("actor_uuid");
        String target = rs.getString("target_uuid");
        return new EventRecord(
                rs.getLong("id"),
                UUID.fromString(rs.getString("session_id")),
                rs.getInt("tick"),
                rs.getString("event_type"),
                rs.getString("category"),
                actor != null ? UUID.fromString(actor) : null,
                target != null ? UUID.fromString(target) : null,
                rs.getString("world_name"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                decodeMetadata(rs.getString("metadata")));
    }

    // --- bookmarks ---

    public synchronized void insertBookmark(UUID sessionId, int tick, String name, String note)
            throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(
                "INSERT INTO bookmarks (session_id, tick, name, note) VALUES (?,?,?,?)")) {
            p.setString(1, sessionId.toString());
            p.setInt(2, tick);
            p.setString(3, name);
            p.setString(4, note);
            p.executeUpdate();
        }
    }

    public synchronized List<Map.Entry<Integer, String>> listBookmarks(UUID sessionId) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT tick, name FROM bookmarks WHERE session_id=? ORDER BY tick")) {
            p.setString(1, sessionId.toString());
            try (ResultSet rs = p.executeQuery()) {
                List<Map.Entry<Integer, String>> bookmarks = new ArrayList<>();
                while (rs.next()) {
                    bookmarks.add(Map.entry(rs.getInt("tick"), rs.getString("name")));
                }
                return bookmarks;
            }
        }
    }

    // --- segments ---

    public synchronized void insertSegment(SegmentFile segment) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("""
                INSERT OR REPLACE INTO segments
                  (session_id, segment_index, file_name, start_tick, end_tick,
                   packet_count, compressed_bytes, crc32)
                VALUES (?,?,?,?,?,?,?,?)""")) {
            p.setString(1, segment.sessionId().toString());
            p.setInt(2, segment.index());
            p.setString(3, segment.fileName());
            p.setInt(4, segment.startTick());
            p.setInt(5, segment.endTick());
            p.setInt(6, segment.packetCount());
            p.setLong(7, segment.compressedBytes());
            p.setLong(8, segment.crc32());
            p.executeUpdate();
        }
    }

    public synchronized List<SegmentFile> listSegments(UUID sessionId) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT * FROM segments WHERE session_id=? ORDER BY segment_index")) {
            p.setString(1, sessionId.toString());
            try (ResultSet rs = p.executeQuery()) {
                List<SegmentFile> segments = new ArrayList<>();
                while (rs.next()) {
                    segments.add(new SegmentFile(
                            sessionId,
                            rs.getInt("segment_index"),
                            rs.getInt("start_tick"),
                            rs.getInt("end_tick"),
                            rs.getInt("packet_count"),
                            rs.getLong("compressed_bytes"),
                            rs.getLong("crc32"),
                            rs.getString("file_name")));
                }
                return segments;
            }
        }
    }

    // --- scenes ---

    public synchronized void upsertScene(UUID sessionId, String name, int startTick, int endTick,
                                         String followedPlayer, double speed, String notes)
            throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("""
                INSERT INTO scenes (session_id, name, start_tick, end_tick, followed_player, speed, notes)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (session_id, name) DO UPDATE SET
                  start_tick=excluded.start_tick, end_tick=excluded.end_tick,
                  followed_player=excluded.followed_player, speed=excluded.speed,
                  notes=excluded.notes""")) {
            p.setString(1, sessionId.toString());
            p.setString(2, name);
            p.setInt(3, startTick);
            p.setInt(4, endTick);
            p.setString(5, followedPlayer);
            p.setDouble(6, speed);
            p.setString(7, notes);
            p.executeUpdate();
        }
    }

    public record SceneRecord(String name, int startTick, int endTick, String followedPlayer,
                              double speed, String notes) {
    }

    public synchronized List<SceneRecord> listScenes(UUID sessionId) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT * FROM scenes WHERE session_id=? ORDER BY start_tick")) {
            p.setString(1, sessionId.toString());
            try (ResultSet rs = p.executeQuery()) {
                List<SceneRecord> scenes = new ArrayList<>();
                while (rs.next()) {
                    scenes.add(new SceneRecord(rs.getString("name"), rs.getInt("start_tick"),
                            rs.getInt("end_tick"), rs.getString("followed_player"),
                            rs.getDouble("speed"), rs.getString("notes")));
                }
                return scenes;
            }
        }
    }

    public synchronized Optional<SceneRecord> getScene(UUID sessionId, String name) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT * FROM scenes WHERE session_id=? AND name=?")) {
            p.setString(1, sessionId.toString());
            p.setString(2, name);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next()
                        ? Optional.of(new SceneRecord(rs.getString("name"), rs.getInt("start_tick"),
                        rs.getInt("end_tick"), rs.getString("followed_player"),
                        rs.getDouble("speed"), rs.getString("notes")))
                        : Optional.empty();
            }
        }
    }

    public synchronized void deleteScene(UUID sessionId, String name) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(
                "DELETE FROM scenes WHERE session_id=? AND name=?")) {
            p.setString(1, sessionId.toString());
            p.setString(2, name);
            p.executeUpdate();
        }
    }

    // --- metadata encoding: simple tab/newline separated, values escaped ---

    static String encodeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(escape(e.getKey())).append('\t').append(escape(e.getValue()));
        }
        return sb.toString();
    }

    static Map<String, String> decodeMetadata(String encoded) {
        Map<String, String> map = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return map;
        }
        for (String line : encoded.split("\n", -1)) {
            int tab = line.indexOf('\t');
            if (tab >= 0) {
                map.put(unescape(line.substring(0, tab)), unescape(line.substring(tab + 1)));
            }
        }
        return map;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String unescape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case 't' -> sb.append('\t');
                    case 'n' -> sb.append('\n');
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // closing on shutdown; nothing sensible to do
        }
    }
}
