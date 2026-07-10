package dev.raindancer118.extendedreplay.storage;

import dev.raindancer118.extendedreplay.core.FormatConstants;
import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import dev.raindancer118.extendedreplay.core.pipeline.CaptureMetrics;
import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;
import dev.raindancer118.extendedreplay.storage.meta.EventRecord;
import dev.raindancer118.extendedreplay.storage.meta.MetadataDatabase;
import dev.raindancer118.extendedreplay.storage.meta.SessionRecord;
import dev.raindancer118.extendedreplay.storage.segment.SegmentFile;
import dev.raindancer118.extendedreplay.storage.segment.SegmentReader;
import dev.raindancer118.extendedreplay.storage.segment.SegmentWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

/**
 * Persistence facade: ingests {@link ReplayPacket}s of one or more sessions into segment
 * files and the metadata index, and reads sessions back for playback.
 *
 * <p>Ingestion methods must be called from a single background pipeline thread — never
 * from the server main thread. Read methods may be called from any thread.</p>
 */
public final class ReplayStorage implements AutoCloseable {

    private final Path replaysDirectory;
    private final MetadataDatabase database;
    private final int segmentLengthSeconds;
    private final CaptureMetrics metrics;

    private final Map<UUID, SegmentWriter> writers = new HashMap<>();
    private final Map<UUID, Map<Integer, UUID>> playerIndexToUuid = new HashMap<>();
    private final Map<UUID, Integer> lastTicks = new HashMap<>();

    public ReplayStorage(Path replaysDirectory, int segmentLengthSeconds, CaptureMetrics metrics)
            throws SQLException, IOException {
        this.replaysDirectory = replaysDirectory;
        this.segmentLengthSeconds = segmentLengthSeconds;
        this.metrics = metrics;
        Files.createDirectories(replaysDirectory);
        this.database = new MetadataDatabase(replaysDirectory.resolve("metadata.db"));
    }

    public MetadataDatabase database() {
        return database;
    }

    public Path sessionDirectory(UUID sessionId) {
        return replaysDirectory.resolve(sessionId.toString());
    }

    // --- ingestion (pipeline thread only) ---

    /** Routes one packet into the session's segment writer and metadata index. */
    public void ingest(ReplayPacket packet) throws IOException, SQLException {
        switch (packet) {
            case ReplayPacket.SessionStart p -> {
                database.insertSession(new SessionRecord(p.sessionId(), p.name(), p.externalKey(),
                        p.worldName(), p.startedAtMillis(), 0, 0, null, null, false,
                        p.formatVersion(), parseWorldSeed(p.metadata()),
                        p.metadata() != null ? p.metadata().get("world-environment") : null));
                writers.put(p.sessionId(),
                        new SegmentWriter(sessionDirectory(p.sessionId()), p.sessionId(),
                                segmentLengthSeconds));
                playerIndexToUuid.put(p.sessionId(), new HashMap<>());
                lastTicks.put(p.sessionId(), 0);
                appendToSegment(p.sessionId(), packet);
            }
            case ReplayPacket.SessionEnd p -> {
                appendToSegment(p.sessionId(), packet);
                sealSession(p.sessionId());
                database.finishSession(p.sessionId(), System.currentTimeMillis(), p.tick(), p.reason());
                playerIndexToUuid.remove(p.sessionId());
                lastTicks.remove(p.sessionId());
            }
            case ReplayPacket.SnapshotReference p -> {
                database.setSnapshotName(p.sessionId(), p.snapshotName());
                appendToSegment(p.sessionId(), packet);
            }
            case ReplayPacket.PlayerProfile p -> {
                database.insertPlayer(p.sessionId(), p.profile());
                Map<Integer, UUID> mapping = playerIndexToUuid.get(p.sessionId());
                if (mapping != null) {
                    mapping.put(p.profile().playerIndex(), p.profile().uuid());
                }
                appendToSegment(p.sessionId(), packet);
            }
            case ReplayPacket.TimelineEventPacket p -> {
                Map<Integer, UUID> mapping = playerIndexToUuid.getOrDefault(p.sessionId(), Map.of());
                var e = p.event();
                database.insertEvent(p.sessionId(), e.tick(), e.eventType(), e.category(),
                        mapping.get(e.actorPlayerIndex()), mapping.get(e.targetPlayerIndex()),
                        e.worldName(), e.x(), e.y(), e.z(), e.metadata());
                metrics.addEvent();
                appendToSegment(p.sessionId(), packet);
            }
            case ReplayPacket.Heartbeat ignored -> {
                // transport-level, not persisted
            }
            case ReplayPacket.Metrics ignored -> {
                // transport-level, not persisted
            }
            case ReplayPacket.Handshake ignored -> {
                // transport-level, not persisted
            }
            case ReplayPacket.HandshakeAck ignored -> {
                // transport-level, not persisted
            }
            default -> {
                UUID sessionId = sessionIdOf(packet);
                if (sessionId != null) {
                    appendToSegment(sessionId, packet);
                }
            }
        }
        UUID sessionId = sessionIdOf(packet);
        if (sessionId != null && packet.tick() >= 0) {
            lastTicks.merge(sessionId, packet.tick(), Math::max);
        }
    }

    private void appendToSegment(UUID sessionId, ReplayPacket packet) throws IOException, SQLException {
        SegmentWriter writer = writers.get(sessionId);
        if (writer == null) {
            // Session unknown (e.g. replay server restarted mid-session): recover a writer.
            writer = new SegmentWriter(sessionDirectory(sessionId), sessionId, segmentLengthSeconds);
            writers.put(sessionId, writer);
        }
        SegmentFile sealed = writer.append(packet);
        if (sealed != null) {
            database.insertSegment(sealed);
            metrics.addSegment(sealed.compressedBytes());
        }
    }

    /** Seals open segments of the session. Safe to call twice. */
    public void sealSession(UUID sessionId) throws IOException, SQLException {
        SegmentWriter writer = writers.remove(sessionId);
        if (writer != null) {
            SegmentFile sealed = writer.seal();
            if (sealed != null) {
                database.insertSegment(sealed);
                metrics.addSegment(sealed.compressedBytes());
            }
        }
    }

    /** Seals everything, e.g. on shutdown. Updates last known tick so sessions stay usable. */
    public void sealAll(String endReason) {
        for (UUID sessionId : List.copyOf(writers.keySet())) {
            try {
                sealSession(sessionId);
                database.finishSession(sessionId, System.currentTimeMillis(),
                        lastTicks.getOrDefault(sessionId, 0), endReason);
            } catch (IOException | SQLException e) {
                // best effort during shutdown
            }
        }
    }

    /** Defensively parses the {@code world-seed} metadata entry; null when absent or malformed. */
    private static Long parseWorldSeed(Map<String, String> metadata) {
        if (metadata == null) {
            return null;
        }
        String raw = metadata.get("world-seed");
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static UUID sessionIdOf(ReplayPacket packet) {
        return switch (packet) {
            case ReplayPacket.SessionStart p -> p.sessionId();
            case ReplayPacket.SessionEnd p -> p.sessionId();
            case ReplayPacket.SnapshotReference p -> p.sessionId();
            case ReplayPacket.PlayerProfile p -> p.sessionId();
            case ReplayPacket.PlayerFramePacket p -> p.sessionId();
            case ReplayPacket.EquipmentChangePacket p -> p.sessionId();
            case ReplayPacket.InventorySnapshotPacket p -> p.sessionId();
            case ReplayPacket.ContainerSnapshotPacket p -> p.sessionId();
            case ReplayPacket.BlockChangePacket p -> p.sessionId();
            case ReplayPacket.EntitySpawn p -> p.sessionId();
            case ReplayPacket.EntityDespawn p -> p.sessionId();
            case ReplayPacket.EntityFramePacket p -> p.sessionId();
            case ReplayPacket.TimelineEventPacket p -> p.sessionId();
            case ReplayPacket.PlayerJoin p -> p.sessionId();
            case ReplayPacket.PlayerQuit p -> p.sessionId();
            case ReplayPacket.DegradationMarker p -> p.sessionId();
            case ReplayPacket.Handshake ignored -> null;
            case ReplayPacket.HandshakeAck ignored -> null;
            case ReplayPacket.Heartbeat ignored -> null;
            case ReplayPacket.Metrics ignored -> null;
        };
    }

    // --- reading ---

    public Optional<SessionRecord> getSession(UUID sessionId) throws SQLException {
        return database.getSession(sessionId);
    }

    public List<SessionRecord> listSessions(int limit) throws SQLException {
        return database.listSessions(limit);
    }

    public List<PlayerProfileData> listPlayers(UUID sessionId) throws SQLException {
        return database.listPlayers(sessionId);
    }

    public List<EventRecord> listEvents(UUID sessionId, String category, int limit) throws SQLException {
        return database.listEvents(sessionId, category, limit);
    }

    /**
     * Streams all packets of a session in tick order to the consumer.
     * Reads segment files listed in the index; falls back to a directory scan when the
     * index has no rows (e.g. after a crash before the DB row was written).
     */
    public void readSession(UUID sessionId, Consumer<ReplayPacket> consumer) throws IOException, SQLException {
        readSession(sessionId, consumer, ignored -> { });
    }

    /**
     * Same as {@link #readSession(UUID, Consumer)}, additionally reporting load progress
     * as a 0-100 percentage after each segment file has been fully read (segments are read
     * strictly sequentially, so the percentage only ever increases and reaches 100 once the
     * last segment has been consumed). A session with zero segments reports 100 immediately.
     */
    public void readSession(UUID sessionId, Consumer<ReplayPacket> consumer, IntConsumer progressPercent)
            throws IOException, SQLException {
        List<Path> files = segmentPaths(sessionId);
        int segmentCount = files.size();
        if (segmentCount == 0) {
            progressPercent.accept(100);
            return;
        }
        for (int i = 0; i < segmentCount; i++) {
            SegmentReader.SegmentContent content = SegmentReader.read(files.get(i));
            content.packets().forEach(consumer);
            progressPercent.accept((i + 1) * 100 / segmentCount);
        }
    }

    /** All packets of a session, tick-sorted. Convenient for playback preparation. */
    public List<ReplayPacket> loadSession(UUID sessionId) throws IOException, SQLException {
        return loadSession(sessionId, ignored -> { });
    }

    /** Same as {@link #loadSession(UUID)}, additionally reporting load progress; see
     * {@link #readSession(UUID, Consumer, IntConsumer)}. */
    public List<ReplayPacket> loadSession(UUID sessionId, IntConsumer progressPercent)
            throws IOException, SQLException {
        List<ReplayPacket> packets = new ArrayList<>();
        readSession(sessionId, packets::add, progressPercent);
        packets.sort(Comparator.comparingInt(p -> Math.max(p.tick(), 0)));
        return packets;
    }

    private List<Path> segmentPaths(UUID sessionId) throws SQLException, IOException {
        Path directory = sessionDirectory(sessionId);
        List<SegmentFile> indexed = database.listSegments(sessionId);
        if (!indexed.isEmpty()) {
            List<Path> paths = new ArrayList<>(indexed.size());
            for (SegmentFile segment : indexed) {
                Path file = directory.resolve(segment.fileName());
                if (Files.exists(file)) {
                    paths.add(file);
                }
            }
            return paths;
        }
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".erps"))
                    .sorted()
                    .toList();
        }
    }

    /** Verifies all segments of a session. Returns human-readable problem list, empty when OK. */
    public List<String> verifySession(UUID sessionId) throws SQLException {
        List<String> problems = new ArrayList<>();
        try {
            List<SegmentFile> indexed = database.listSegments(sessionId);
            Path directory = sessionDirectory(sessionId);
            if (indexed.isEmpty()) {
                problems.add("No segments indexed for session " + sessionId);
            }
            int expectedIndex = 0;
            for (SegmentFile segment : indexed) {
                if (segment.index() != expectedIndex) {
                    problems.add("Segment gap: expected index " + expectedIndex + " but found "
                            + segment.index());
                }
                expectedIndex = segment.index() + 1;
                Path file = directory.resolve(segment.fileName());
                if (!Files.exists(file)) {
                    problems.add("Missing segment file: " + segment.fileName());
                    continue;
                }
                try {
                    SegmentFile actual = SegmentReader.verify(file);
                    if (actual.crc32() != segment.crc32()) {
                        problems.add("CRC mismatch vs index in " + segment.fileName());
                    }
                    if (actual.packetCount() != segment.packetCount()) {
                        problems.add("Packet count mismatch vs index in " + segment.fileName());
                    }
                } catch (IOException e) {
                    problems.add("Corrupt segment " + segment.fileName() + ": " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            throw e;
        }
        return problems;
    }

    /**
     * Rebuilds the metadata index of a session from its segment files on disk. Used by
     * /erp reindex after index corruption or a restore from backup. Returns the number
     * of packets re-indexed.
     */
    public int reindex(UUID sessionId) throws SQLException, IOException {
        Path directory = sessionDirectory(sessionId);
        if (!Files.isDirectory(directory)) {
            throw new IOException("No segment directory for session " + sessionId);
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".erps"))
                    .sorted()
                    .toList();
        }
        if (files.isEmpty()) {
            throw new IOException("No segment files for session " + sessionId);
        }
        database.deleteSession(sessionId);

        int packets = 0;
        Map<Integer, UUID> indexToUuid = new HashMap<>();
        long startedAt = 0;
        long endedAt = 0;
        int lastTick = 0;
        String name = sessionId.toString();
        String externalKey = null;
        String worldName = "?";
        String endReason = null;
        String snapshotName = null;
        int formatVersion = FormatConstants.FORMAT_VERSION;
        Long worldSeed = null;
        String worldEnvironment = null;

        for (Path file : files) {
            SegmentReader.SegmentContent content = SegmentReader.read(file);
            database.insertSegment(content.info());
            for (ReplayPacket packet : content.packets()) {
                packets++;
                lastTick = Math.max(lastTick, packet.tick());
                switch (packet) {
                    case ReplayPacket.SessionStart p -> {
                        name = p.name();
                        externalKey = p.externalKey();
                        worldName = p.worldName();
                        startedAt = p.startedAtMillis();
                        formatVersion = p.formatVersion();
                        worldSeed = parseWorldSeed(p.metadata());
                        worldEnvironment = p.metadata() != null
                                ? p.metadata().get("world-environment") : null;
                    }
                    case ReplayPacket.SessionEnd p -> {
                        endReason = p.reason();
                        endedAt = startedAt + (long) p.tick() * 50;
                    }
                    case ReplayPacket.SnapshotReference p -> snapshotName = p.snapshotName();
                    case ReplayPacket.PlayerProfile p -> {
                        database.insertPlayer(sessionId, p.profile());
                        indexToUuid.put(p.profile().playerIndex(), p.profile().uuid());
                    }
                    case ReplayPacket.TimelineEventPacket p -> {
                        var e = p.event();
                        database.insertEvent(sessionId, e.tick(), e.eventType(), e.category(),
                                indexToUuid.get(e.actorPlayerIndex()),
                                indexToUuid.get(e.targetPlayerIndex()),
                                e.worldName(), e.x(), e.y(), e.z(), e.metadata());
                    }
                    default -> {
                        // frames/deltas need no index entry
                    }
                }
            }
        }
        database.insertSession(new SessionRecord(sessionId, name, externalKey, worldName,
                startedAt, endedAt > 0 ? endedAt : System.currentTimeMillis(), lastTick,
                endReason != null ? endReason : "REINDEXED", snapshotName, false, formatVersion,
                worldSeed, worldEnvironment));
        return packets;
    }

    /**
     * Applies retention rules: deletes finished, non-favorite sessions older than
     * {@code maxAgeDays}, then deletes oldest non-favorite sessions while total storage
     * exceeds {@code maxBytes}. Returns the deleted session ids.
     */
    public List<UUID> cleanup(int maxAgeDays, long maxBytes) throws SQLException, IOException {
        List<UUID> deleted = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 60 * 60 * 1000;
        List<SessionRecord> sessions = database.listSessions(10_000);
        for (SessionRecord session : sessions) {
            if (session.isFinished() && !session.favorite() && session.startedAtMillis() < cutoff) {
                deleteSessionData(session.sessionId());
                deleted.add(session.sessionId());
            }
        }
        // oldest first for the size-based pass
        List<SessionRecord> remaining = new ArrayList<>(database.listSessions(10_000));
        remaining.sort(Comparator.comparingLong(SessionRecord::startedAtMillis));
        for (SessionRecord session : remaining) {
            if (storageBytes() <= maxBytes) {
                break;
            }
            if (session.isFinished() && !session.favorite()) {
                deleteSessionData(session.sessionId());
                deleted.add(session.sessionId());
            }
        }
        return deleted;
    }

    /** Deletes session data from disk and index. */
    public void deleteSessionData(UUID sessionId) throws SQLException, IOException {
        database.deleteSession(sessionId);
        Path directory = sessionDirectory(sessionId);
        if (Files.isDirectory(directory)) {
            try (Stream<Path> stream = Files.list(directory)) {
                for (Path file : stream.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(directory);
        }
    }

    /** Total bytes of all stored segment files. */
    public long storageBytes() throws IOException {
        if (!Files.isDirectory(replaysDirectory)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(replaysDirectory)) {
            return stream.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        }
    }

    public int formatVersion() {
        return FormatConstants.FORMAT_VERSION;
    }

    @Override
    public void close() {
        sealAll("SERVER_SHUTDOWN");
        database.close();
    }
}
