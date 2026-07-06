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

    @Override
    void close();
}
