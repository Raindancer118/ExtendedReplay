package dev.raindancer118.extendedreplay.paper.snapshot;

import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Replay-server side receiver of {@code SNAPSHOT_FILE_BEGIN}/{@code _CHUNK}/{@code _END}
 * packets: streams chunks straight into a temp file next to the final snapshot location
 * (never buffers the whole file in memory), verifies the sha256 announced in
 * {@code BEGIN} once {@code END} arrives, and only then atomically moves the result into
 * place using {@link SnapshotService}'s naming convention.
 *
 * <p>{@link #accept(ReplayPacket)} is called from the replay server's storage/network
 * thread (see {@code ReplayServerManager}) — plain file I/O is fine there, but this class
 * must never touch the Bukkit API.</p>
 */
public final class SnapshotReceiver implements Consumer<ReplayPacket> {

    /** A transfer that received no chunk for this long is considered abandoned. */
    private static final long STALE_TIMEOUT_MILLIS = 5 * 60 * 1000L;

    private final Logger logger;
    private final SnapshotService snapshotService;
    private final Map<String, Transfer> transfers = new ConcurrentHashMap<>();

    public SnapshotReceiver(Logger logger, SnapshotService snapshotService) {
        this.logger = logger;
        this.snapshotService = snapshotService;
    }

    @Override
    public void accept(ReplayPacket packet) {
        switch (packet) {
            case ReplayPacket.SnapshotFileBegin p -> begin(p);
            case ReplayPacket.SnapshotFileChunk p -> chunk(p);
            case ReplayPacket.SnapshotFileEnd p -> end(p);
            default -> { /* not a snapshot-file packet: nothing to do here */ }
        }
    }

    private void begin(ReplayPacket.SnapshotFileBegin p) {
        Transfer previous = transfers.remove(p.name());
        if (previous != null) {
            abort(previous, "Neuer BEGIN traf ein, bevor die vorherige Übertragung endete");
        }
        Path target = snapshotService.fileOf(p.name());
        Path temp = target.resolveSibling(target.getFileName().toString() + ".part");
        try {
            Files.createDirectories(temp.toAbsolutePath().getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE));
            transfers.put(p.name(), new Transfer(temp, out, digest, p.sha256(), p.totalBytes()));
        } catch (IOException | NoSuchAlgorithmException e) {
            logger.log(Level.WARNING, "Snapshot-Transfer '" + p.name() + "' konnte nicht"
                    + " gestartet werden", e);
        }
    }

    private void chunk(ReplayPacket.SnapshotFileChunk p) {
        Transfer transfer = transfers.get(p.name());
        if (transfer == null) {
            logger.warning("Snapshot-Chunk für unbekannte/abgelaufene Übertragung '"
                    + p.name() + "' verworfen.");
            return;
        }
        if (p.chunkIndex() != transfer.nextChunkIndex) {
            logger.warning("Snapshot-Transfer '" + p.name() + "': Chunk " + p.chunkIndex()
                    + " aus der Reihenfolge (erwartet " + transfer.nextChunkIndex
                    + ") — Übertragung abgebrochen.");
            transfers.remove(p.name());
            abort(transfer, null);
            return;
        }
        try {
            transfer.out.write(p.data());
            transfer.digest.update(p.data());
            transfer.bytesWritten += p.data().length;
            transfer.nextChunkIndex++;
            transfer.lastActivityMillis = System.currentTimeMillis();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Snapshot-Chunk für '" + p.name() + "' konnte nicht"
                    + " geschrieben werden — Übertragung abgebrochen", e);
            transfers.remove(p.name());
            abort(transfer, null);
        }
    }

    private void end(ReplayPacket.SnapshotFileEnd p) {
        Transfer transfer = transfers.remove(p.name());
        if (transfer == null) {
            logger.warning("Snapshot-END für unbekannte/abgelaufene Übertragung '"
                    + p.name() + "' verworfen.");
            return;
        }
        try {
            transfer.out.close();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Snapshot-Transfer '" + p.name() + "' konnte nicht"
                    + " abgeschlossen werden", e);
            deleteQuietly(transfer.tempFile);
            return;
        }
        if (transfer.bytesWritten != transfer.totalBytes) {
            logger.warning("Snapshot '" + p.name() + "': erhaltene Bytegröße (" + transfer.bytesWritten
                    + ") weicht von der angekündigten Größe (" + transfer.totalBytes + ") ab.");
        }
        String actualSha256 = HexFormat.of().formatHex(transfer.digest.digest());
        if (!actualSha256.equalsIgnoreCase(transfer.expectedSha256)) {
            deleteQuietly(transfer.tempFile);
            logger.warning("Snapshot '" + p.name() + "' vom Producer verworfen: sha256-Mismatch"
                    + " (erwartet " + transfer.expectedSha256 + ", erhalten " + actualSha256 + ").");
            return;
        }
        try {
            Path target = snapshotService.fileOf(p.name());
            Files.move(transfer.tempFile, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            logger.info("Snapshot '" + p.name() + "' vom Producer empfangen ("
                    + (transfer.bytesWritten / 1024 / 1024) + " MB).");
        } catch (IOException e) {
            logger.log(Level.WARNING, "Snapshot '" + p.name() + "' konnte nicht an den"
                    + " Zielort verschoben werden", e);
            deleteQuietly(transfer.tempFile);
        }
    }

    private void abort(Transfer transfer, String reason) {
        if (reason != null) {
            logger.warning(reason + " — verwerfe unvollständige Snapshot-Daten.");
        }
        closeQuietly(transfer.out);
        deleteQuietly(transfer.tempFile);
    }

    /**
     * Drops transfers that have not received a chunk (or BEGIN) for longer than
     * {@link #STALE_TIMEOUT_MILLIS}. Intended to be called periodically (e.g. every
     * minute) — never automatically, since this class has no scheduler of its own.
     */
    public void cleanupStaleTransfers() {
        long now = System.currentTimeMillis();
        for (String name : List.copyOf(transfers.keySet())) {
            Transfer transfer = transfers.get(name);
            if (transfer != null && now - transfer.lastActivityMillis > STALE_TIMEOUT_MILLIS) {
                transfers.remove(name);
                abort(transfer, "Snapshot-Transfer '" + name + "' seit über 5 Minuten inaktiv");
            }
        }
    }

    /** Number of transfers currently in progress. Exposed for diagnostics/tests. */
    public int activeTransferCount() {
        return transfers.size();
    }

    private static void closeQuietly(OutputStream out) {
        try {
            out.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static final class Transfer {
        private final Path tempFile;
        private final OutputStream out;
        private final MessageDigest digest;
        private final String expectedSha256;
        private final long totalBytes;
        private long bytesWritten;
        private int nextChunkIndex;
        private volatile long lastActivityMillis = System.currentTimeMillis();

        private Transfer(Path tempFile, OutputStream out, MessageDigest digest,
                         String expectedSha256, long totalBytes) {
            this.tempFile = tempFile;
            this.out = out;
            this.digest = digest;
            this.expectedSha256 = expectedSha256;
            this.totalBytes = totalBytes;
        }
    }
}
