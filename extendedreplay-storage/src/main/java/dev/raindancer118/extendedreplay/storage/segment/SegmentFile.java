package dev.raindancer118.extendedreplay.storage.segment;

import java.util.UUID;

/**
 * Metadata of one sealed segment file.
 *
 * <p>File layout (all big-endian):</p>
 * <pre>
 * int      magic "ERPS"
 * varint   format version
 * uuid     session id (2× long)
 * varint   segment index
 * varint   start tick
 * varint   end tick
 * varint   packet count
 * varint   uncompressed payload length
 * varint   compressed payload length
 * byte[]   zstd-compressed payload: repeated ([varint length][packet bytes])
 * long     CRC32 of the compressed payload
 * </pre>
 */
public record SegmentFile(
        UUID sessionId,
        int index,
        int startTick,
        int endTick,
        int packetCount,
        long compressedBytes,
        long crc32,
        String fileName) {

    public static String fileName(int index) {
        return String.format(java.util.Locale.ROOT, "segment-%05d.erps", index);
    }
}
