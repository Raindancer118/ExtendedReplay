package dev.raindancer118.extendedreplay.storage.segment;

import com.github.luben.zstd.Zstd;
import dev.raindancer118.extendedreplay.core.FormatConstants;
import dev.raindancer118.extendedreplay.core.protocol.PacketCodec;
import dev.raindancer118.extendedreplay.core.protocol.PacketIO;
import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

/** Reads sealed segment files back into packets, verifying integrity on the way. */
public final class SegmentReader {

    private SegmentReader() {
    }

    public record SegmentContent(SegmentFile info, List<ReplayPacket> packets) {
    }

    public static SegmentContent read(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));

        int magic = in.readInt();
        if (magic != FormatConstants.SEGMENT_MAGIC) {
            throw new IOException("Not a segment file (bad magic): " + file);
        }
        int formatVersion = PacketIO.readVarInt(in);
        if (formatVersion > FormatConstants.FORMAT_VERSION) {
            throw new IOException("Segment format " + formatVersion + " is newer than supported "
                    + FormatConstants.FORMAT_VERSION + ": " + file);
        }
        UUID sessionId = PacketIO.readUuid(in);
        int index = PacketIO.readVarInt(in);
        int startTick = PacketIO.readVarInt(in);
        int endTick = PacketIO.readVarInt(in);
        int packetCount = PacketIO.readVarInt(in);
        int uncompressedLength = PacketIO.readVarInt(in);
        int compressedLength = PacketIO.readVarInt(in);
        if (compressedLength < 0 || uncompressedLength < 0) {
            throw new IOException("Corrupt segment header: " + file);
        }
        byte[] compressed = new byte[compressedLength];
        in.readFully(compressed);
        long storedCrc = in.readLong();

        CRC32 crc = new CRC32();
        crc.update(compressed);
        if (crc.getValue() != storedCrc) {
            throw new IOException("CRC mismatch in segment " + file
                    + " (stored=" + storedCrc + ", actual=" + crc.getValue() + ")");
        }

        byte[] uncompressed = Zstd.decompress(compressed, uncompressedLength);
        List<ReplayPacket> packets = new ArrayList<>(packetCount);
        DataInputStream payload = new DataInputStream(new ByteArrayInputStream(uncompressed));
        for (int i = 0; i < packetCount; i++) {
            int length;
            try {
                length = PacketIO.readVarInt(payload);
            } catch (EOFException e) {
                throw new IOException("Truncated segment payload (" + i + "/" + packetCount
                        + " packets) in " + file, e);
            }
            byte[] packetBytes = new byte[length];
            payload.readFully(packetBytes);
            ReplayPacket packet = PacketCodec.decode(packetBytes);
            if (packet != null) {
                packets.add(packet);
            }
        }

        SegmentFile info = new SegmentFile(sessionId, index, startTick, endTick, packetCount,
                raw.length, storedCrc, file.getFileName().toString());
        return new SegmentContent(info, packets);
    }

    /** Header + CRC check without decoding packets; used by /erp verify. */
    public static SegmentFile verify(Path file) throws IOException {
        SegmentContent content = read(file);
        return content.info();
    }
}
