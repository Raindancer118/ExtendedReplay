package dev.raindancer118.extendedreplay.transport;

import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;

import java.util.Map;
import java.util.function.Consumer;

/**
 * In-process transport for STANDALONE mode and tests: packets go straight to the
 * consumer (typically the storage ingestion pipeline).
 */
public final class LocalLoopbackTransport implements ReplayTransport {

    private final Consumer<ReplayPacket> consumer;
    private volatile boolean started;
    private long delivered;

    public LocalLoopbackTransport(Consumer<ReplayPacket> consumer) {
        this.consumer = consumer;
    }

    @Override
    public void start() {
        started = true;
    }

    @Override
    public void send(ReplayPacket packet) {
        if (started) {
            delivered++;
            consumer.accept(packet);
        }
    }

    @Override
    public boolean isConnected() {
        return started;
    }

    @Override
    public Map<String, String> metrics() {
        return Map.of("type", "loopback", "delivered", Long.toString(delivered));
    }

    /** Delivery is synchronous in {@link #send}, so nothing is ever left pending. */
    @Override
    public int pendingCount() {
        return 0;
    }

    @Override
    public void close() {
        started = false;
    }
}
