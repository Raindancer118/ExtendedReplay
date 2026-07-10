package dev.raindancer118.extendedreplay.paper.snapshot;

import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;
import dev.raindancer118.extendedreplay.paper.producer.ProducerManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import java.util.logging.Level;

/**
 * Producer-side sender of arena snapshot (.erpa) files to the replay server, over the
 * same {@link dev.raindancer118.extendedreplay.transport.ReplayTransport} the packet
 * stream already uses: no separate connection, no manual file copying between servers.
 *
 * <p>The file is read entirely off the main thread. It is streamed in {@link #CHUNK_SIZE}
 * pieces so only one chunk is ever held in memory at a time; the sha256 is computed in a
 * first streaming pass so {@code SNAPSHOT_FILE_BEGIN} can announce it up front, matching
 * what the replay-side {@link SnapshotReceiver} verifies once the transfer completes.</p>
 */
public final class SnapshotUploader {

    /** Wire chunk size for snapshot file transfer. */
    public static final int CHUNK_SIZE = 256 * 1024;

    private final Plugin plugin;
    private final ProducerManager producer;

    public SnapshotUploader(Plugin plugin, ProducerManager producer) {
        this.plugin = plugin;
        this.producer = producer;
    }

    /** Uploads without progress reporting. See {@link #upload(String, Path, IntConsumer)}. */
    public CompletableFuture<Void> upload(String name, Path erpaFile) {
        return upload(name, erpaFile, null);
    }

    /**
     * Reads {@code erpaFile} asynchronously and streams it to the replay server as
     * {@code SNAPSHOT_FILE_BEGIN} + {@code SNAPSHOT_FILE_CHUNK}* + {@code SNAPSHOT_FILE_END}
     * through {@link ProducerManager#offer}. All three packet types are critical (never
     * dropped by the recording queue under pressure).
     *
     * @param progressPercent optional 0-100 progress callback, invoked from the async
     *                        upload thread (not the main thread) — for future GUI use.
     */
    public CompletableFuture<Void> upload(String name, Path erpaFile, IntConsumer progressPercent) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                doUpload(name, erpaFile, progressPercent);
                future.complete(null);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Snapshot-Upload fehlgeschlagen: " + name, e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private void doUpload(String name, Path file, IntConsumer progressPercent) throws IOException {
        long totalBytes = Files.size(file);
        int chunkCount = totalBytes == 0 ? 0 : (int) ((totalBytes + CHUNK_SIZE - 1) / CHUNK_SIZE);
        String sha256 = sha256(file);

        producer.offer(new ReplayPacket.SnapshotFileBegin(name, sha256, totalBytes, chunkCount));

        long sent = 0;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int index = 0;
            int read;
            while ((read = in.readNBytes(buffer, 0, buffer.length)) > 0) {
                byte[] chunk = read == buffer.length ? buffer.clone() : Arrays.copyOf(buffer, read);
                producer.offer(new ReplayPacket.SnapshotFileChunk(name, index, chunk));
                sent += read;
                index++;
                if (progressPercent != null && totalBytes > 0) {
                    progressPercent.accept((int) Math.min(99, sent * 100 / totalBytes));
                }
            }
        }

        producer.offer(new ReplayPacket.SnapshotFileEnd(name));
        if (progressPercent != null) {
            progressPercent.accept(100);
        }
        plugin.getLogger().info("Snapshot '" + name + "' zum Replay-Server hochgeladen ("
                + (sent / 1024 / 1024) + " MB, " + chunkCount + " Chunks).");
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
