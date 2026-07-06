package dev.raindancer118.extendedreplay.core.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Lock-free counters describing producer health, exposed via /erp status. */
public final class CaptureMetrics {

    private final AtomicLong captureNanosTotal = new AtomicLong();
    private final AtomicLong captureNanosMax = new AtomicLong();
    private final AtomicLong captureTicks = new AtomicLong();
    private final AtomicLong framesRecorded = new AtomicLong();
    private final AtomicLong eventsRecorded = new AtomicLong();
    private final AtomicLong segmentsWritten = new AtomicLong();
    private final AtomicLong bytesWritten = new AtomicLong();
    private final AtomicLong transportBatchesSent = new AtomicLong();
    private final AtomicLong transportBytesSent = new AtomicLong();
    private volatile int degradationLevel;

    public void recordCaptureTime(long nanos) {
        captureNanosTotal.addAndGet(nanos);
        captureTicks.incrementAndGet();
        captureNanosMax.accumulateAndGet(nanos, Math::max);
    }

    public void addFrames(int count) {
        framesRecorded.addAndGet(count);
    }

    public void addEvent() {
        eventsRecorded.incrementAndGet();
    }

    public void addSegment(long bytes) {
        segmentsWritten.incrementAndGet();
        bytesWritten.addAndGet(bytes);
    }

    public void addTransportBatch(long bytes) {
        transportBatchesSent.incrementAndGet();
        transportBytesSent.addAndGet(bytes);
    }

    public void setDegradationLevel(int level) {
        this.degradationLevel = level;
    }

    public int degradationLevel() {
        return degradationLevel;
    }

    public double averageCaptureMicros() {
        long ticks = captureTicks.get();
        return ticks == 0 ? 0 : captureNanosTotal.get() / 1000.0 / ticks;
    }

    public double maxCaptureMicros() {
        return captureNanosMax.get() / 1000.0;
    }

    public long framesRecorded() {
        return framesRecorded.get();
    }

    public Map<String, String> snapshot() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("capture.avg-us", String.format(java.util.Locale.ROOT, "%.1f", averageCaptureMicros()));
        map.put("capture.max-us", String.format(java.util.Locale.ROOT, "%.1f", maxCaptureMicros()));
        map.put("frames", Long.toString(framesRecorded.get()));
        map.put("events", Long.toString(eventsRecorded.get()));
        map.put("segments", Long.toString(segmentsWritten.get()));
        map.put("segment-bytes", Long.toString(bytesWritten.get()));
        map.put("transport-batches", Long.toString(transportBatchesSent.get()));
        map.put("transport-bytes", Long.toString(transportBytesSent.get()));
        map.put("degradation-level", Integer.toString(degradationLevel));
        return map;
    }
}
