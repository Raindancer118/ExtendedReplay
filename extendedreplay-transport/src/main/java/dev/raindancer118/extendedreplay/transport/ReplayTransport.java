package dev.raindancer118.extendedreplay.transport;

import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;

import java.util.Map;

/**
 * Producer-side transport: carries replay packets to the replay server.
 *
 * <p>{@link #send} must be callable from any thread and must never block. Batching,
 * compression, reconnects and spooling are the transport's concern.</p>
 */
public interface ReplayTransport extends AutoCloseable {

    void start();

    /** Enqueues a packet for delivery. Never blocks, never throws. */
    void send(ReplayPacket packet);

    boolean isConnected();

    Map<String, String> metrics();

    /**
     * Approximate count of packets that have left the recording queue but are not yet
     * confirmed delivered to the replay server: still sitting in the transport's send
     * buffer, or spooled to disk while disconnected. Never exact for the spooled portion
     * (batches are only decoded lazily), but always {@code > 0} while spooled data exists
     * and {@code 0} once everything has left this transport. Used purely for operator
     * feedback (e.g. "/erp status", boss bar progress) — never for correctness decisions.
     */
    int pendingCount();

    /**
     * True once nothing is queued or spooled in this transport <em>and</em> it is
     * currently connected. A transport that merely has nothing pending because it has
     * been disconnected the whole time is deliberately not considered "drained" — data
     * still needs a live connection to actually reach the replay server.
     */
    default boolean isDrained() {
        return isConnected() && pendingCount() == 0;
    }

    @Override
    void close();
}
