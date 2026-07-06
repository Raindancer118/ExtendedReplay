package dev.raindancer118.extendedreplay.core.pipeline;

import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Non-blocking handoff between the main-thread capture and the background pipeline.
 *
 * <p>The producer side ({@link #offer}) never blocks and never throws. When the queue is
 * over capacity, cosmetic packets are dropped and counted; critical packets are still
 * accepted (the queue is unbounded underneath, the capacity is a soft limit that drives
 * degradation) so gameplay-relevant data survives pressure spikes.</p>
 */
public final class RecordingQueue {

    private final ConcurrentLinkedQueue<ReplayPacket> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong droppedCosmetic = new AtomicLong();
    private final AtomicLong enqueued = new AtomicLong();
    private final int softCapacity;

    public RecordingQueue(int softCapacity) {
        this.softCapacity = softCapacity;
    }

    /** Main-thread safe, never blocks. Returns false if the packet was dropped. */
    public boolean offer(ReplayPacket packet) {
        if (!packet.critical() && size.get() >= softCapacity) {
            droppedCosmetic.incrementAndGet();
            return false;
        }
        queue.add(packet);
        size.incrementAndGet();
        enqueued.incrementAndGet();
        return true;
    }

    /** Drains up to {@code max} packets into a list. Called from the pipeline thread. */
    public List<ReplayPacket> drain(int max) {
        List<ReplayPacket> batch = new ArrayList<>(Math.min(max, 512));
        ReplayPacket packet;
        while (batch.size() < max && (packet = queue.poll()) != null) {
            size.decrementAndGet();
            batch.add(packet);
        }
        return batch;
    }

    public int size() {
        return size.get();
    }

    public boolean isUnderPressure() {
        return size.get() >= softCapacity / 2;
    }

    public long droppedCosmetic() {
        return droppedCosmetic.get();
    }

    public long totalEnqueued() {
        return enqueued.get();
    }
}
