package dev.raindancer118.extendedreplay.core.protocol;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Low level read/write helpers of the binary replay protocol. */
public final class PacketIO {

    private static final int MAX_STRING_BYTES = 1 << 20;
    private static final int MAX_ARRAY_BYTES = 1 << 24;
    private static final int MAX_MAP_ENTRIES = 1 << 16;

    private PacketIO() {
    }

    public static void writeVarInt(DataOutput out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    public static int readVarInt(DataInput in) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            byte b = in.readByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift > 35) {
                throw new IOException("VarInt too long");
            }
        }
    }

    public static void writeVarLong(DataOutput out, long value) throws IOException {
        while ((value & ~0x7FL) != 0) {
            out.writeByte((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.writeByte((int) value);
    }

    public static long readVarLong(DataInput in) throws IOException {
        long value = 0;
        int shift = 0;
        while (true) {
            byte b = in.readByte();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift > 70) {
                throw new IOException("VarLong too long");
            }
        }
    }

    public static void writeString(DataOutput out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    public static String readString(DataInput in) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Invalid string length: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeOptionalString(DataOutput out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            writeString(out, value);
        }
    }

    public static String readOptionalString(DataInput in) throws IOException {
        return in.readBoolean() ? readString(in) : null;
    }

    public static void writeUuid(DataOutput out, UUID uuid) throws IOException {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    public static UUID readUuid(DataInput in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    public static void writeByteArray(DataOutput out, byte[] bytes) throws IOException {
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    public static byte[] readByteArray(DataInput in) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > MAX_ARRAY_BYTES) {
            throw new IOException("Invalid array length: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    public static void writeOptionalByteArray(DataOutput out, byte[] bytes) throws IOException {
        out.writeBoolean(bytes != null);
        if (bytes != null) {
            writeByteArray(out, bytes);
        }
    }

    public static byte[] readOptionalByteArray(DataInput in) throws IOException {
        return in.readBoolean() ? readByteArray(in) : null;
    }

    public static void writeStringMap(DataOutput out, Map<String, String> map) throws IOException {
        writeVarInt(out, map.size());
        for (Map.Entry<String, String> entry : map.entrySet()) {
            writeString(out, entry.getKey());
            writeString(out, entry.getValue());
        }
    }

    public static Map<String, String> readStringMap(DataInput in) throws IOException {
        int size = readVarInt(in);
        if (size < 0 || size > MAX_MAP_ENTRIES) {
            throw new IOException("Invalid map size: " + size);
        }
        Map<String, String> map = new LinkedHashMap<>(Math.max(4, size * 2));
        for (int i = 0; i < size; i++) {
            map.put(readString(in), readString(in));
        }
        return map;
    }

    /** Encodes an angle in degrees as a single byte (360° / 256 steps). */
    public static byte angleToByte(float degrees) {
        return (byte) Math.round(degrees * 256.0f / 360.0f);
    }

    public static float byteToAngle(byte angle) {
        return angle * 360.0f / 256.0f;
    }
}
