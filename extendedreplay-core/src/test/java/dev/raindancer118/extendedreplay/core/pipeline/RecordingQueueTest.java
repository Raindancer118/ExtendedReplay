package dev.raindancer118.extendedreplay.core.pipeline;

import dev.raindancer118.extendedreplay.core.model.EntityFrame;
import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecordingQueueTest {

    private static final UUID SESSION = UUID.randomUUID();

    private static ReplayPacket cosmetic(int tick) {
        return new ReplayPacket.EntityFramePacket(SESSION,
                new EntityFrame(tick, 1, 0, 0, 0, (byte) 0, (byte) 0));
    }

    private static ReplayPacket critical(int tick) {
        return new ReplayPacket.PlayerJoin(SESSION, tick, 0);
    }

    @Test
    void dropsCosmeticPacketsOverSoftCapacityButKeepsCritical() {
        RecordingQueue queue = new RecordingQueue(10);
        for (int i = 0; i < 10; i++) {
            assertThat(queue.offer(cosmetic(i))).isTrue();
        }
        assertThat(queue.offer(cosmetic(11))).isFalse();
        assertThat(queue.offer(critical(12))).isTrue();
        assertThat(queue.droppedCosmetic()).isEqualTo(1);
        assertThat(queue.size()).isEqualTo(11);
    }

    @Test
    void drainReturnsInOrderAndReducesSize() {
        RecordingQueue queue = new RecordingQueue(100);
        for (int i = 0; i < 5; i++) {
            queue.offer(critical(i));
        }
        var batch = queue.drain(3);
        assertThat(batch).hasSize(3);
        assertThat(batch.get(0).tick()).isEqualTo(0);
        assertThat(batch.get(2).tick()).isEqualTo(2);
        assertThat(queue.size()).isEqualTo(2);
        assertThat(queue.drain(100)).hasSize(2);
        assertThat(queue.size()).isZero();
    }

    @Test
    void pressureDetection() {
        RecordingQueue queue = new RecordingQueue(10);
        assertThat(queue.isUnderPressure()).isFalse();
        for (int i = 0; i < 5; i++) {
            queue.offer(critical(i));
        }
        assertThat(queue.isUnderPressure()).isTrue();
    }
}
