package dev.raindancer118.extendedreplay.paper.snapshot;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

/**
 * Ships a locally created arena snapshot (.erpa) to the replay server. On STANDALONE
 * servers the file is already where playback expects it, so the no-op implementation
 * completes immediately.
 */
public interface SnapshotTransfer {

    /**
     * Uploads the snapshot file. Never blocks the calling thread; {@code progressPercent}
     * (0-100) may be reported from any thread.
     */
    CompletableFuture<Void> upload(String name, Path erpaFile, IntConsumer progressPercent);

    /** For STANDALONE servers: snapshot storage and playback share the same directory. */
    static SnapshotTransfer local() {
        return (name, erpaFile, progressPercent) -> {
            progressPercent.accept(100);
            return CompletableFuture.completedFuture(null);
        };
    }
}
