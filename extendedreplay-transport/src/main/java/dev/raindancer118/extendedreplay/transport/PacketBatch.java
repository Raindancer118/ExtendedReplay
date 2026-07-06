package dev.raindancer118.extendedreplay.transport;

import com.github.luben.zstd.Zstd;
import dev.raindancer118.extendedreplay.core.protocol.PacketCodec;
import dev.raindancer118.extendedreplay.core.protocol.PacketIO;
import dev.raindancer118.extendedreplay.core.protocol.ReplayPacket;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire format of one transport message: a batch of packets, zstd-compressed above a
 * size threshold.
 *
 * <pre>
 * byte    flags (bit 0: compressed)
 * varint  uncompressed length (only when compressed)
 * body    repeated ([varint length][packet bytes]) — possibly compressed
 * </pre>
 */
public final class PacketBatch {

    private static final int COMPRESSION_THRESHOLD = 512;
    private static final int MAX_UNCOMPRESSED = 64 * 1024 * 1024;

    private PacketBatch() {
    }

    public static byte[] encode(List<ReplayPacket> packets) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream(1024);
            DataOutputStream bodyOut = new DataOutputStream(body);
            PacketIO.writeVarInt(bodyOut, packets.size());
            for (ReplayPacket packet : packets) {
                byte[] encoded = PacketCodec.encode(packet);
                PacketIO.writeVarInt(bodyOut, encoded.length);
                bodyOut.write(encoded);
            }
            byte[] raw = body.toByteArray();

            ByteArrayOutputStream message = new ByteArrayOutputStream(raw.length + 8);
            DataOutputStream out = new DataOutputStream(message);
            if (raw.length >= COMPRESSION_THRESHOLD) {
                byte[] compressed = Zstd.compress(raw, 1);
                out.writeByte(1);
                PacketIO.writeVarInt(out, raw.length);
                out.write(compressed);
            } else {
                out.writeByte(0);
                out.write(raw);
            }
            return message.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("In-memory encode failed", e);
        }
    }

    public static List<ReplayPacket> decode(byte[] message) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
        int flags = in.readUnsignedByte();
        byte[] raw;
        if ((flags & 1) != 0) {
            int uncompressedLength = PacketIO.readVarInt(in);
            if (uncompressedLength < 0 || uncompressedLength > MAX_UNCOMPRESSED) {
                throw new IOException("Invalid batch length: " + uncompressedLength);
            }
            byte[] compressed = in.readAllBytes();
            raw = Zstd.decompress(compressed, uncompressedLength);
        } else {
            raw = in.readAllBytes();
        }

        DataInputStream body = new DataInputStream(new ByteArrayInputStream(raw));
        int count = PacketIO.readVarInt(body);
        if (count < 0) {
            throw new IOException("Invalid packet count: " + count);
        }
        List<ReplayPacket> packets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int length = PacketIO.readVarInt(body);
            byte[] packetBytes = new byte[length];
            body.readFully(packetBytes);
            ReplayPacket packet = PacketCodec.decode(packetBytes);
            if (packet != null) {
                packets.add(packet);
            }
        }
        return packets;
    }
}
