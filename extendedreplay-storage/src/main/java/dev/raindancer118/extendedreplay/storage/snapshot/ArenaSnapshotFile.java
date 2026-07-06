package dev.raindancer118.extendedreplay.storage.snapshot;

import com.github.luben.zstd.Zstd;
import dev.raindancer118.extendedreplay.core.protocol.PacketIO;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Compact arena snapshot format (.erpa): the full block state of a cuboid region,
 * palette-compressed and zstd-packed. Pure data — block states are stored as BlockData
 * strings, so the format is platform- and version-agnostic.
 *
 * <p>Layout (big-endian):</p>
 * <pre>
 * int      magic "ERPA" (0x45525041)
 * varint   format version (1)
 * utf      world name
 * varint×6 minX minY minZ sizeX sizeY sizeZ (zigzag for mins)
 * varint   palette size, then that many strings
 * varint   compressed length, varint uncompressed length
 * byte[]   zstd( varint palette index per block, X→Z→Y order )
 * </pre>
 */
public final class ArenaSnapshotFile {

    public static final int MAGIC = 0x45525041;
    public static final int VERSION = 1;

    private final String worldName;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final List<String> palette;
    private final int[] blocks; // palette indices, indexed x + z*sizeX + y*sizeX*sizeZ

    public ArenaSnapshotFile(String worldName, int minX, int minY, int minZ,
                             int sizeX, int sizeY, int sizeZ,
                             List<String> palette, int[] blocks) {
        this.worldName = worldName;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.palette = palette;
        this.blocks = blocks;
    }

    public String worldName() {
        return worldName;
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public long blockCount() {
        return (long) sizeX * sizeY * sizeZ;
    }

    public List<String> palette() {
        return palette;
    }

    public int index(int x, int y, int z) {
        return x + z * sizeX + y * sizeX * sizeZ;
    }

    /** BlockData string at region-relative coordinates. */
    public String blockData(int x, int y, int z) {
        return palette.get(blocks[index(x, y, z)]);
    }

    /** Raw palette index at region-relative coordinates (for fast bulk application). */
    public int paletteIndexAt(int x, int y, int z) {
        return blocks[index(x, y, z)];
    }

    /** Builder used by the chunked main-thread scanner. */
    public static final class Builder {
        private final String worldName;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final List<String> palette = new ArrayList<>();
        private final Map<String, Integer> paletteIndex = new HashMap<>();
        private final int[] blocks;

        public Builder(String worldName, int minX, int minY, int minZ,
                       int sizeX, int sizeY, int sizeZ) {
            this.worldName = worldName;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            long total = (long) sizeX * sizeY * sizeZ;
            if (total > 128_000_000L) {
                throw new IllegalArgumentException("Snapshot region too large: " + total + " blocks");
            }
            this.blocks = new int[(int) total];
        }

        public void set(int x, int y, int z, String blockData) {
            Integer index = paletteIndex.get(blockData);
            if (index == null) {
                index = palette.size();
                palette.add(blockData);
                paletteIndex.put(blockData, index);
            }
            blocks[x + z * sizeX + y * sizeX * sizeZ] = index;
        }

        public ArenaSnapshotFile build() {
            return new ArenaSnapshotFile(worldName, minX, minY, minZ,
                    sizeX, sizeY, sizeZ, List.copyOf(palette), blocks);
        }
    }

    // --- I/O ---

    public void write(Path file) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(blocks.length);
        DataOutputStream bodyOut = new DataOutputStream(body);
        for (int block : blocks) {
            PacketIO.writeVarInt(bodyOut, block);
        }
        byte[] raw = body.toByteArray();
        byte[] compressed = Zstd.compress(raw, 6);

        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(temp))) {
            out.writeInt(MAGIC);
            PacketIO.writeVarInt(out, VERSION);
            PacketIO.writeString(out, worldName);
            writeZigzag(out, minX);
            writeZigzag(out, minY);
            writeZigzag(out, minZ);
            PacketIO.writeVarInt(out, sizeX);
            PacketIO.writeVarInt(out, sizeY);
            PacketIO.writeVarInt(out, sizeZ);
            PacketIO.writeVarInt(out, palette.size());
            for (String entry : palette) {
                PacketIO.writeString(out, entry);
            }
            PacketIO.writeVarInt(out, compressed.length);
            PacketIO.writeVarInt(out, raw.length);
            out.write(compressed);
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static ArenaSnapshotFile read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("Not an arena snapshot (bad magic): " + file);
            }
            int version = PacketIO.readVarInt(in);
            if (version > VERSION) {
                throw new IOException("Snapshot format " + version + " newer than supported: " + file);
            }
            String worldName = PacketIO.readString(in);
            int minX = readZigzag(in);
            int minY = readZigzag(in);
            int minZ = readZigzag(in);
            int sizeX = PacketIO.readVarInt(in);
            int sizeY = PacketIO.readVarInt(in);
            int sizeZ = PacketIO.readVarInt(in);
            int paletteSize = PacketIO.readVarInt(in);
            if (paletteSize < 0 || paletteSize > 1_000_000) {
                throw new IOException("Corrupt palette size: " + paletteSize);
            }
            List<String> palette = new ArrayList<>(paletteSize);
            for (int i = 0; i < paletteSize; i++) {
                palette.add(PacketIO.readString(in));
            }
            int compressedLength = PacketIO.readVarInt(in);
            int rawLength = PacketIO.readVarInt(in);
            byte[] compressed = new byte[compressedLength];
            in.readFully(compressed);
            byte[] raw = Zstd.decompress(compressed, rawLength);

            long total = (long) sizeX * sizeY * sizeZ;
            int[] blocks = new int[(int) total];
            DataInputStream body = new DataInputStream(new ByteArrayInputStream(raw));
            for (int i = 0; i < blocks.length; i++) {
                int index = PacketIO.readVarInt(body);
                if (index < 0 || index >= paletteSize) {
                    throw new IOException("Corrupt palette index " + index + " at block " + i);
                }
                blocks[i] = index;
            }
            return new ArenaSnapshotFile(worldName, minX, minY, minZ,
                    sizeX, sizeY, sizeZ, palette, blocks);
        }
    }

    /** SHA-256 of the file on disk, hex-encoded. */
    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeZigzag(DataOutputStream out, int value) throws IOException {
        PacketIO.writeVarInt(out, (value << 1) ^ (value >> 31));
    }

    private static int readZigzag(DataInputStream in) throws IOException {
        int raw = PacketIO.readVarInt(in);
        return (raw >>> 1) ^ -(raw & 1);
    }
}
