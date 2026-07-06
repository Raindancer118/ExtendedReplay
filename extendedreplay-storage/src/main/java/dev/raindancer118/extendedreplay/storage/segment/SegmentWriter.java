package dev.raindancer118.extendedreplay.storage.segment;

import com.github.luben.zstd.Zstd;
import dev.raindancer118.extendedreplay.core.FormatConstants;
import dev.raindancer118.extendedreplay.core.protocol.PacketCodec;
import dev.raindancer118.extendedreplay.core.protocol.PacketIO;
import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Accumulates packets of one session and seals them into compressed segment files.
 * Not thread-safe by design: owned by the single storage pipeline thread.
 */
public final class SegmentWriter {

    private final Path sessionDirectory;
    private final UUID sessionId;
    private final int segmentLengthTicks;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
    private final DataOutputStream bufferOut = new DataOutputStream(buffer);
    private int segmentIndex;
    private int segmentStartTick = -1;
    private int lastTick;
    private int packetCount;

    public SegmentWriter(Path sessionDirectory, UUID sessionId, int segmentLengthSeconds) throws IOException {
        this.sessionDirectory = sessionDirectory;
        this.sessionId = sessionId;
        this.segmentLengthTicks = Math.max(20, segmentLengthSeconds * 20);
        Files.createDirectories(sessionDirectory);
    }

    /** Appends a packet; seals and returns a segment when the tick span is full, else null. */
    public SegmentFile append(ReplayPacket packet) throws IOException {
        int tick = Math.max(packet.tick(), 0);
        if (segmentStartTick < 0) {
            segmentStartTick = tick;
        }
        lastTick = Math.max(lastTick, tick);

        byte[] encoded = PacketCodec.encode(packet);
        PacketIO.writeVarInt(bufferOut, encoded.length);
        bufferOut.write(encoded);
        packetCount++;

        if (lastTick - segmentStartTick >= segmentLengthTicks) {
            return seal();
        }
        return null;
    }

    /** Seals the current segment, returns null when it is empty. */
    public SegmentFile seal() throws IOException {
        if (packetCount == 0) {
            return null;
        }
        byte[] uncompressed = buffer.toByteArray();
        byte[] compressed = Zstd.compress(uncompressed, 3);

        CRC32 crc = new CRC32();
        crc.update(compressed);

        String fileName = SegmentFile.fileName(segmentIndex);
        Path target = sessionDirectory.resolve(fileName);
        Path temp = sessionDirectory.resolve(fileName + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
                Files.newOutputStream(temp))) {
            out.writeInt(FormatConstants.SEGMENT_MAGIC);
            PacketIO.writeVarInt(out, FormatConstants.FORMAT_VERSION);
            PacketIO.writeUuid(out, sessionId);
            PacketIO.writeVarInt(out, segmentIndex);
            PacketIO.writeVarInt(out, segmentStartTick);
            PacketIO.writeVarInt(out, lastTick);
            PacketIO.writeVarInt(out, packetCount);
            PacketIO.writeVarInt(out, uncompressed.length);
            PacketIO.writeVarInt(out, compressed.length);
            out.write(compressed);
            out.writeLong(crc.getValue());
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        SegmentFile segment = new SegmentFile(sessionId, segmentIndex, segmentStartTick,
                lastTick, packetCount, Files.size(target), crc.getValue(), fileName);

        segmentIndex++;
        segmentStartTick = -1;
        lastTick = 0;
        packetCount = 0;
        buffer.reset();
        return segment;
    }
}
