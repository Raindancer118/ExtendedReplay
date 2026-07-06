package dev.raindancer118.extendedreplay.transport.websocket;

import dev.raindancer118.extendedreplay.core.FormatConstants;
import dev.raindancer118.extendedreplay.core.pipeline.CaptureMetrics;
import dev.raindancer118.extendedreplay.core.protocol.PacketCodec;
import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;
import dev.raindancer118.extendedreplay.transport.PacketBatch;
import dev.raindancer118.extendedreplay.transport.ReplayTransport;
import dev.raindancer118.extendedreplay.transport.spool.SpoolFile;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Producer-side WebSocket transport. Packets are enqueued from any thread, batched every
 * {@code batchIntervalMs} on a dedicated sender thread, compressed and pushed to the
 * replay server. While disconnected, batches are spooled to disk and replayed after the
 * next successful handshake. Reconnects automatically with backoff.
 */
public final class WebSocketReplayClient implements ReplayTransport {

    private final Logger logger;
    private final URI uri;
    private final String authToken;
    private final String serverName;
    private final long batchIntervalMs;
    private final SpoolFile spool;
    private final CaptureMetrics metrics;

    private final ConcurrentLinkedQueue<ReplayPacket> outbound = new ConcurrentLinkedQueue<>();
    private final AtomicLong outboundSize = new AtomicLong();
    private final ScheduledExecutorService sender;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean handshakeAccepted = new AtomicBoolean();
    private volatile InnerClient client;
    private volatile long lastConnectAttempt;
    private volatile long reconnectDelayMs = 1000;

    public WebSocketReplayClient(Logger logger, String host, int port, String authToken,
                                 String serverName, long batchIntervalMs,
                                 Path spoolFile, long maxSpoolBytes,
                                 CaptureMetrics metrics) throws IOException {
        this.logger = logger;
        this.uri = URI.create("ws://" + host + ":" + port);
        this.authToken = authToken;
        this.serverName = serverName;
        this.batchIntervalMs = Math.max(10, batchIntervalMs);
        this.spool = new SpoolFile(spoolFile, maxSpoolBytes);
        this.metrics = metrics;
        this.sender = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ExtendedReplay-Transport");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        sender.scheduleWithFixedDelay(this::pump, batchIntervalMs, batchIntervalMs,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void send(ReplayPacket packet) {
        if (!running.get()) {
            return;
        }
        outbound.add(packet);
        outboundSize.incrementAndGet();
    }

    /** Runs on the sender thread: connect if needed, drain spool, send current batch. */
    private void pump() {
        try {
            ensureConnected();

            List<ReplayPacket> batch = drainOutbound();
            boolean connected = isConnected();

            if (connected && spool.hasData()) {
                try {
                    InnerClient current = client;
                    spool.drainTo(bytes -> {
                        if (current != null && current.isOpen()) {
                            current.send(bytes);
                            metrics.addTransportBatch(bytes.length);
                        }
                    });
                    logger.info("[ExtendedReplay] Spooled replay data flushed to replay server.");
                } catch (IOException e) {
                    logger.log(Level.WARNING, "[ExtendedReplay] Failed to flush spool", e);
                }
            }

            if (batch.isEmpty()) {
                return;
            }
            byte[] encoded = PacketBatch.encode(batch);
            InnerClient current = client;
            if (connected && current != null && current.isOpen()) {
                current.send(encoded);
                metrics.addTransportBatch(encoded.length);
            } else {
                spool.append(encoded);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "[ExtendedReplay] Transport pump error", e);
        }
    }

    private List<ReplayPacket> drainOutbound() {
        List<ReplayPacket> batch = new ArrayList<>();
        ReplayPacket packet;
        while ((packet = outbound.poll()) != null) {
            outboundSize.decrementAndGet();
            batch.add(packet);
        }
        return batch;
    }

    private void ensureConnected() {
        InnerClient current = client;
        if (current != null && current.isOpen() && handshakeAccepted.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastConnectAttempt < reconnectDelayMs) {
            return;
        }
        lastConnectAttempt = now;
        if (current != null) {
            try {
                current.closeBlocking();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        handshakeAccepted.set(false);
        InnerClient fresh = new InnerClient(uri);
        client = fresh;
        try {
            if (fresh.connectBlocking(5, TimeUnit.SECONDS)) {
                fresh.send(PacketCodec.encode(new ReplayPacket.Handshake(
                        FormatConstants.PROTOCOL_VERSION, authToken, serverName)));
                if (fresh.awaitHandshake(5, TimeUnit.SECONDS) && handshakeAccepted.get()) {
                    reconnectDelayMs = 1000;
                    logger.info("[ExtendedReplay] Connected to replay server " + uri);
                    return;
                }
                logger.warning("[ExtendedReplay] Replay server rejected handshake.");
                fresh.close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        reconnectDelayMs = Math.min(reconnectDelayMs * 2, 30_000);
    }

    @Override
    public boolean isConnected() {
        InnerClient current = client;
        return current != null && current.isOpen() && handshakeAccepted.get();
    }

    @Override
    public Map<String, String> metrics() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("type", "websocket");
        map.put("connected", Boolean.toString(isConnected()));
        map.put("outbound-queue", Long.toString(outboundSize.get()));
        map.put("spool-bytes", Long.toString(spool.bytesWritten()));
        map.put("spool-dropped-batches", Long.toString(spool.batchesDropped()));
        return map;
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        // final flush of whatever is queued
        pump();
        sender.shutdown();
        try {
            if (!sender.awaitTermination(3, TimeUnit.SECONDS)) {
                sender.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        InnerClient current = client;
        if (current != null) {
            current.close();
        }
        spool.close();
    }

    /** Bridges Java-WebSocket callbacks into this transport. */
    private final class InnerClient extends WebSocketClient {

        private final CountDownLatch handshakeLatch = new CountDownLatch(1);

        private InnerClient(URI uri) {
            super(uri);
            setConnectionLostTimeout(30);
        }

        boolean awaitHandshake(long timeout, TimeUnit unit) throws InterruptedException {
            return handshakeLatch.await(timeout, unit);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            // handshake packet is sent by ensureConnected()
        }

        @Override
        public void onMessage(String message) {
            // protocol is binary-only; ignore text frames
        }

        @Override
        public void onMessage(ByteBuffer bytes) {
            byte[] data = new byte[bytes.remaining()];
            bytes.get(data);
            try {
                ReplayPacket packet = PacketCodec.decode(data);
                if (packet instanceof ReplayPacket.HandshakeAck ack) {
                    handshakeAccepted.set(ack.accepted());
                    handshakeLatch.countDown();
                    if (!ack.accepted()) {
                        logger.warning("[ExtendedReplay] Handshake rejected: " + ack.message());
                    }
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "[ExtendedReplay] Bad packet from replay server", e);
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            handshakeAccepted.set(false);
        }

        @Override
        public void onError(Exception ex) {
            logger.log(Level.FINE, "[ExtendedReplay] WebSocket error", ex);
        }
    }
}
