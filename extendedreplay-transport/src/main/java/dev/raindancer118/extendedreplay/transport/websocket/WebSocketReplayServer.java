package dev.raindancer118.extendedreplay.transport.websocket;

import dev.raindancer118.extendedreplay.core.FormatConstants;
import dev.raindancer118.extendedreplay.core.protocol.PacketCodec;
import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;
import dev.raindancer118.extendedreplay.transport.PacketBatch;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Replay-server-side WebSocket endpoint. Authenticates producers via handshake token,
 * decodes packet batches and hands each packet to the sink (storage ingestion + live
 * mirror). The sink is invoked on WebSocket worker threads — implementations must be
 * thread-safe and must not touch Bukkit API directly.
 */
public final class WebSocketReplayServer extends WebSocketServer {

    private final Logger logger;
    private final String authToken;
    private final Consumer<ReplayPacket> sink;
    private final Set<WebSocket> authenticated = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicLong packetsReceived = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private volatile boolean startedOk;

    public WebSocketReplayServer(Logger logger, String bindHost, int bindPort,
                                 String authToken, Consumer<ReplayPacket> sink) {
        super(new InetSocketAddress(bindHost, bindPort));
        this.logger = logger;
        this.authToken = authToken;
        this.sink = sink;
        setReuseAddr(true);
        setConnectionLostTimeout(30);
    }

    @Override
    public void onStart() {
        startedOk = true;
        logger.info("[ExtendedReplay] Replay ingest listening on " + getAddress());
    }

    public boolean isRunning() {
        return startedOk;
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        // must authenticate with the first binary message
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
        // binary-only protocol
    }

    @Override
    public void onMessage(WebSocket connection, ByteBuffer buffer) {
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        bytesReceived.addAndGet(data.length);

        if (!authenticated.contains(connection)) {
            handleHandshake(connection, data);
            return;
        }
        try {
            List<ReplayPacket> packets = PacketBatch.decode(data);
            for (ReplayPacket packet : packets) {
                packetsReceived.incrementAndGet();
                sink.accept(packet);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "[ExtendedReplay] Bad batch from " + remote(connection), e);
        }
    }

    private void handleHandshake(WebSocket connection, byte[] data) {
        try {
            ReplayPacket packet = PacketCodec.decode(data);
            if (!(packet instanceof ReplayPacket.Handshake hs)) {
                connection.close(4000, "Handshake expected");
                return;
            }
            if (hs.protocolVersion() != FormatConstants.PROTOCOL_VERSION) {
                connection.send(PacketCodec.encode(new ReplayPacket.HandshakeAck(
                        FormatConstants.PROTOCOL_VERSION, false,
                        "Protocol mismatch: server=" + FormatConstants.PROTOCOL_VERSION
                                + " client=" + hs.protocolVersion())));
                connection.close(4001, "Protocol mismatch");
                return;
            }
            if (!constantTimeEquals(authToken, hs.authToken())) {
                connection.send(PacketCodec.encode(new ReplayPacket.HandshakeAck(
                        FormatConstants.PROTOCOL_VERSION, false, "Invalid auth token")));
                connection.close(4003, "Auth failed");
                logger.warning("[ExtendedReplay] Rejected producer with bad token from "
                        + remote(connection));
                return;
            }
            authenticated.add(connection);
            connection.send(PacketCodec.encode(new ReplayPacket.HandshakeAck(
                    FormatConstants.PROTOCOL_VERSION, true, "OK")));
            logger.info("[ExtendedReplay] Producer '" + hs.serverName() + "' connected from "
                    + remote(connection));
        } catch (IOException e) {
            connection.close(4000, "Bad handshake");
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        authenticated.remove(connection);
        logger.info("[ExtendedReplay] Producer disconnected: " + remote(connection)
                + " (" + code + " " + reason + ")");
    }

    @Override
    public void onError(WebSocket connection, Exception ex) {
        logger.log(Level.WARNING, "[ExtendedReplay] Ingest server error", ex);
    }

    public Map<String, String> metrics() {
        return Map.of(
                "type", "websocket-server",
                "producers", Integer.toString(authenticated.size()),
                "packets-received", Long.toString(packetsReceived.get()),
                "bytes-received", Long.toString(bytesReceived.get()));
    }

    private static String remote(WebSocket connection) {
        InetSocketAddress address = connection.getRemoteSocketAddress();
        return address != null ? address.toString() : "unknown";
    }
}
