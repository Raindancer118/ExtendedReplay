package dev.raindancer118.extendedreplay.storage;

import dev.raindancer118.extendedreplay.core.FormatConstants;
import dev.raindancer118.extendedreplay.core.model.BlockChange;
import dev.raindancer118.extendedreplay.core.model.PlayerFrame;
import dev.raindancer118.extendedreplay.core.model.PlayerProfileData;
import dev.raindancer118.extendedreplay.core.model.TimelineEvent;
import dev.raindancer118.extendedreplay.core.pipeline.CaptureMetrics;
import dev.raindancer118.extendedreplay.core.protocol.PacketType;
import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;
import dev.raindancer118.extendedreplay.storage.meta.SessionRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayStorageTest {

    @TempDir
    Path tempDir;

    private ReplayStorage storage;
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        storage = new ReplayStorage(tempDir.resolve("replays"), 30, new CaptureMetrics());
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    private void recordSampleSession() throws Exception {
        storage.ingest(new ReplayPacket.SessionStart(sessionId, "test-match", "ext-1", "arena",
                System.currentTimeMillis(), FormatConstants.FORMAT_VERSION,
                0, 0, 0, 0, 0, 0, false, Map.of("mode", "test")));
        storage.ingest(new ReplayPacket.PlayerProfile(sessionId,
                new PlayerProfileData(0, UUID.randomUUID(), "Alice", null, null)));
        storage.ingest(new ReplayPacket.PlayerProfile(sessionId,
                new PlayerProfileData(1, UUID.randomUUID(), "Bob", "tex", "sig")));

        // > 30s worth of ticks so at least one segment seals mid-session
        for (int tick = 0; tick < 1300; tick++) {
            storage.ingest(new ReplayPacket.PlayerFramePacket(sessionId,
                    new PlayerFrame(tick, 0, tick * 0.1, 64, 0,
                            (byte) 0, (byte) 0, (byte) 0, (short) 0, (byte) 0,
                            (short) 200, (byte) 20, 0)));
        }
        storage.ingest(new ReplayPacket.BlockChangePacket(sessionId,
                new BlockChange(600, "arena", 5, 64, 5, "minecraft:air", 0, "break")));
        storage.ingest(new ReplayPacket.TimelineEventPacket(sessionId, PacketType.KILL_EVENT,
                new TimelineEvent(650, "KILL", "KILL", 0, 1, "arena", 5, 64, 5, true,
                        Map.of("weapon", "bow"))));
        storage.ingest(new ReplayPacket.SessionEnd(sessionId, 1300, "COMPLETED"));
    }

    @Test
    void fullSessionLifecycleRoundtrip() throws Exception {
        recordSampleSession();

        SessionRecord record = storage.getSession(sessionId).orElseThrow();
        assertThat(record.name()).isEqualTo("test-match");
        assertThat(record.isFinished()).isTrue();
        assertThat(record.lastTick()).isEqualTo(1300);
        assertThat(record.endReason()).isEqualTo("COMPLETED");

        assertThat(storage.listPlayers(sessionId))
                .extracting(PlayerProfileData::name)
                .containsExactly("Alice", "Bob");

        var events = storage.listEvents(sessionId, null, 100);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo("KILL");
        assertThat(events.get(0).tick()).isEqualTo(650);
        assertThat(events.get(0).metadata()).containsEntry("weapon", "bow");

        List<ReplayPacket> packets = storage.loadSession(sessionId);
        long frames = packets.stream()
                .filter(p -> p instanceof ReplayPacket.PlayerFramePacket)
                .count();
        assertThat(frames).isEqualTo(1300);
        assertThat(packets.stream().anyMatch(p -> p instanceof ReplayPacket.SessionEnd)).isTrue();

        // multiple segments: 1300 ticks > 600-tick segments
        assertThat(storage.database().listSegments(sessionId).size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void verifyPassesOnHealthySession() throws Exception {
        recordSampleSession();
        assertThat(storage.verifySession(sessionId)).isEmpty();
    }

    @Test
    void verifyDetectsCorruptedSegment() throws Exception {
        recordSampleSession();
        Path dir = storage.sessionDirectory(sessionId);
        try (Stream<Path> files = Files.list(dir)) {
            Path segment = files.filter(p -> p.toString().endsWith(".erps")).findFirst().orElseThrow();
            byte[] bytes = Files.readAllBytes(segment);
            bytes[bytes.length - 20] ^= (byte) 0xFF; // flip a bit inside the payload
            Files.write(segment, bytes);
        }
        assertThat(storage.verifySession(sessionId)).isNotEmpty();
    }

    @Test
    void verifyDetectsMissingSegment() throws Exception {
        recordSampleSession();
        Path dir = storage.sessionDirectory(sessionId);
        try (Stream<Path> files = Files.list(dir)) {
            Files.delete(files.filter(p -> p.toString().endsWith(".erps")).findFirst().orElseThrow());
        }
        assertThat(storage.verifySession(sessionId))
                .anyMatch(problem -> problem.contains("Missing segment file"));
    }

    @Test
    void deleteRemovesEverything() throws Exception {
        recordSampleSession();
        storage.deleteSessionData(sessionId);
        assertThat(storage.getSession(sessionId)).isEmpty();
        assertThat(Files.exists(storage.sessionDirectory(sessionId))).isFalse();
    }

    @Test
    void sealAllRecoversUnfinishedSessions() throws Exception {
        storage.ingest(new ReplayPacket.SessionStart(sessionId, "crash-match", null, "arena",
                System.currentTimeMillis(), FormatConstants.FORMAT_VERSION,
                0, 0, 0, 0, 0, 0, false, Map.of()));
        storage.ingest(new ReplayPacket.PlayerFramePacket(sessionId,
                new PlayerFrame(10, 0, 0, 64, 0, (byte) 0, (byte) 0, (byte) 0,
                        (short) 0, (byte) 0, (short) 200, (byte) 20, 0)));
        storage.sealAll("SERVER_SHUTDOWN");

        SessionRecord record = storage.getSession(sessionId).orElseThrow();
        assertThat(record.isFinished()).isTrue();
        assertThat(record.endReason()).isEqualTo("SERVER_SHUTDOWN");
        assertThat(storage.loadSession(sessionId)).isNotEmpty();
    }

    @Test
    void scenesAndBookmarks() throws Exception {
        recordSampleSession();
        storage.database().insertBookmark(sessionId, 100, "first-blood", null);
        storage.database().upsertScene(sessionId, "kill-scene", 500, 800, "Alice", 1.0, "the kill");

        assertThat(storage.database().listBookmarks(sessionId))
                .containsExactly(Map.entry(100, "first-blood"));
        var scene = storage.database().getScene(sessionId, "kill-scene").orElseThrow();
        assertThat(scene.startTick()).isEqualTo(500);
        assertThat(scene.endTick()).isEqualTo(800);

        storage.database().deleteScene(sessionId, "kill-scene");
        assertThat(storage.database().getScene(sessionId, "kill-scene")).isEmpty();
    }

    @Test
    void storageBytesReflectsWrittenData() throws Exception {
        recordSampleSession();
        assertThat(storage.storageBytes()).isGreaterThan(0);
    }

    @Test
    void reindexRebuildsMetadataFromSegments() throws Exception {
        recordSampleSession();
        // simulate index loss
        storage.database().deleteSession(sessionId);
        assertThat(storage.getSession(sessionId)).isEmpty();

        int packets = storage.reindex(sessionId);
        assertThat(packets).isGreaterThan(1300);

        SessionRecord record = storage.getSession(sessionId).orElseThrow();
        assertThat(record.name()).isEqualTo("test-match");
        assertThat(record.endReason()).isEqualTo("COMPLETED");
        assertThat(storage.listPlayers(sessionId)).hasSize(2);
        assertThat(storage.listEvents(sessionId, null, 100)).hasSize(1);
        assertThat(storage.verifySession(sessionId)).isEmpty();
    }

    @Test
    void cleanupDeletesOldNonFavoriteSessions() throws Exception {
        recordSampleSession();
        // make the session look 40 days old
        var record = storage.getSession(sessionId).orElseThrow();
        storage.database().insertSession(new SessionRecord(record.sessionId(), record.name(),
                record.externalKey(), record.worldName(),
                System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000,
                record.endedAtMillis(), record.lastTick(), record.endReason(),
                record.snapshotName(), false, record.formatVersion()));

        var deleted = storage.cleanup(30, Long.MAX_VALUE);
        assertThat(deleted).containsExactly(sessionId);
        assertThat(storage.getSession(sessionId)).isEmpty();
    }

    @Test
    void cleanupSparesFavorites() throws Exception {
        recordSampleSession();
        var record = storage.getSession(sessionId).orElseThrow();
        storage.database().insertSession(new SessionRecord(record.sessionId(), record.name(),
                record.externalKey(), record.worldName(),
                System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000,
                record.endedAtMillis(), record.lastTick(), record.endReason(),
                record.snapshotName(), true, record.formatVersion()));

        assertThat(storage.cleanup(30, Long.MAX_VALUE)).isEmpty();
        assertThat(storage.getSession(sessionId)).isPresent();
    }

    @Test
    void cleanupEnforcesSizeCap() throws Exception {
        recordSampleSession();
        var deleted = storage.cleanup(1000, 1); // 1 byte cap forces deletion
        assertThat(deleted).containsExactly(sessionId);
    }
}
