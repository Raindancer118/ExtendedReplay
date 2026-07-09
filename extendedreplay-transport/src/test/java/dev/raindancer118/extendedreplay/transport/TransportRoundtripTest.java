package dev.raindancer118.extendedreplay.transport;

import dev.raindancer118.extendedreplay.core.FormatConstants;
import dev.raindancer118.extendedreplay.core.model.PlayerFrame;
import dev.raindancer118.extendedreplay.core.pipeline.CaptureMetrics;
import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;
import dev.raindancer118.extendedreplay.transport.spool.SpoolFile;
import dev.raindancer118.extendedreplay.transport.websocket.WebSocketReplayClient;
import dev.raindancer118.extendedreplay.transport.websocket.WebSocketReplayServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class TransportRoundtripTest {

    private static final Logger LOGGER = Logger.getLogger("test");
    private static final UUID SESSION = UUID.randomUUID();

    private static ReplayPacket framePacket(int tick) {
        return new ReplayPacket.PlayerFramePacket(SESSION,
                new PlayerFrame(tick, 0, tick, 64, 0, (byte) 0, (byte) 0, (byte) 0,
                        (short) 0, (byte) 0, (short) 200, (byte) 20, 0));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void batchEncodingRoundtripSmallAndLarge() throws IOException {
        // small: uncompressed path
        List<ReplayPacket> small = List.of(framePacket(1));
        assertThat(PacketBatch.decode(PacketBatch.encode(small))).isEqualTo(small);

        // large: compressed path
        List<ReplayPacket> large = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            large.add(framePacket(i));
        }
        assertThat(PacketBatch.decode(PacketBatch.encode(large))).isEqualTo(large);
    }

    @Test
    void spoolAppendAndDrain(@TempDir Path dir) throws IOException {
        SpoolFile spool = new SpoolFile(dir.resolve("spool.erpq"), 1024 * 1024);
        byte[] batch1 = PacketBatch.encode(List.of(framePacket(1)));
        byte[] batch2 = PacketBatch.encode(List.of(framePacket(2), framePacket(3)));
        spool.append(batch1);
        spool.append(batch2);
        assertThat(spool.hasData()).isTrue();

        List<byte[]> drained = new ArrayList<>();
        spool.drainTo(drained::add);
        assertThat(drained).hasSize(2);
        assertThat(PacketBatch.decode(drained.get(0))).hasSize(1);
        assertThat(PacketBatch.decode(drained.get(1))).hasSize(2);
        assertThat(spool.hasData()).isFalse();
        spool.close();
    }

    @Test
    void spoolEnforcesSizeCap(@TempDir Path dir) throws IOException {
        SpoolFile spool = new SpoolFile(dir.resolve("spool.erpq"), 64);
        byte[] big = new byte[128];
        spool.append(big);
        assertThat(spool.batchesDropped()).isEqualTo(1);
        spool.close();
    }

    @Test
    void loopbackDeliversDirectly() {
        List<ReplayPacket> received = new ArrayList<>();
        LocalLoopbackTransport transport = new LocalLoopbackTransport(received::add);
        transport.send(framePacket(1)); // before start: dropped
        transport.start();
        assertThat(transport.pendingCount()).isEqualTo(0);
        assertThat(transport.isDrained()).isTrue();
        transport.send(framePacket(2));
        transport.close();
        transport.send(framePacket(3)); // after close: dropped
        assertThat(received).hasSize(1);
        assertThat(received.get(0).tick()).isEqualTo(2);
        assertThat(transport.pendingCount()).isEqualTo(0);
        assertThat(transport.isDrained()).isFalse(); // closed: not connected anymore
    }

    @Test
    @Timeout(30)
    void webSocketEndToEndWithAuthAndSpoolRecovery(@TempDir Path dir) throws Exception {
        int port = freePort();
        ConcurrentLinkedQueue<ReplayPacket> received = new ConcurrentLinkedQueue<>();
        CountDownLatch gotFrames = new CountDownLatch(3);

        WebSocketReplayServer server = new WebSocketReplayServer(LOGGER, "127.0.0.1", port,
                "test-token", packet -> {
            received.add(packet);
            if (packet instanceof ReplayPacket.PlayerFramePacket) {
                gotFrames.countDown();
            }
        });
        server.start();
        try {
            CaptureMetrics metrics = new CaptureMetrics();
            WebSocketReplayClient client = new WebSocketReplayClient(LOGGER, "127.0.0.1", port,
                    "test-token", "producer-test", 25, dir.resolve("spool.erpq"),
                    1024 * 1024, metrics);
            client.start();
            client.send(framePacket(1));
            client.send(framePacket(2));
            client.send(framePacket(3));

            assertThat(gotFrames.await(15, TimeUnit.SECONDS))
                    .as("frames should arrive via websocket").isTrue();
            assertThat(client.isConnected()).isTrue();
            client.close();
        } finally {
            server.stop(1000);
        }
        assertThat(received.stream().filter(p -> p instanceof ReplayPacket.PlayerFramePacket))
                .hasSize(3);
    }

    @Test
    @Timeout(30)
    void webSocketRejectsBadToken(@TempDir Path dir) throws Exception {
        int port = freePort();
        ConcurrentLinkedQueue<ReplayPacket> received = new ConcurrentLinkedQueue<>();
        WebSocketReplayServer server = new WebSocketReplayServer(LOGGER, "127.0.0.1", port,
                "correct-token", received::add);
        server.start();
        try {
            WebSocketReplayClient client = new WebSocketReplayClient(LOGGER, "127.0.0.1", port,
                    "wrong-token", "producer-test", 25, dir.resolve("spool.erpq"),
                    1024 * 1024, new CaptureMetrics());
            client.start();
            client.send(framePacket(1));
            Thread.sleep(1500);
            assertThat(client.isConnected()).isFalse();
            assertThat(received).isEmpty();
            client.close();
        } finally {
            server.stop(1000);
        }
    }

    @Test
    @Timeout(60)
    void spoolIsDeliveredAfterServerComesUp(@TempDir Path dir) throws Exception {
        int port = freePort();
        CaptureMetrics metrics = new CaptureMetrics();
        WebSocketReplayClient client = new WebSocketReplayClient(LOGGER, "127.0.0.1", port,
                "tok", "producer-test", 25, dir.resolve("spool.erpq"), 1024 * 1024, metrics);
        client.start();
        client.send(framePacket(1));
        client.send(framePacket(2));
        // give the pump time to spool while no server is listening
        Thread.sleep(500);
        assertThat(client.metrics().get("connected")).isEqualTo("false");
        assertThat(client.pendingCount()).isGreaterThan(0);
        assertThat(client.isDrained()).isFalse();

        ConcurrentLinkedQueue<ReplayPacket> received = new ConcurrentLinkedQueue<>();
        CountDownLatch gotFrames = new CountDownLatch(2);
        WebSocketReplayServer server = new WebSocketReplayServer(LOGGER, "127.0.0.1", port,
                "tok", packet -> {
            received.add(packet);
            if (packet instanceof ReplayPacket.PlayerFramePacket) {
                gotFrames.countDown();
            }
        });
        server.start();
        try {
            assertThat(gotFrames.await(40, TimeUnit.SECONDS))
                    .as("spooled frames should be delivered after reconnect").isTrue();
            // pump() runs on its own schedule; give it a couple more cycles to clear
            // the spool counter and report connected+drained after the flush above
            long deadline = System.currentTimeMillis() + 5000;
            while (!client.isDrained() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(client.isDrained())
                    .as("nothing should remain pending once the spool was flushed").isTrue();
            assertThat(client.pendingCount()).isEqualTo(0);
        } finally {
            client.close();
            server.stop(1000);
        }
        assertThat(received.stream().filter(p -> p instanceof ReplayPacket.PlayerFramePacket))
                .hasSize(2);
    }
}
