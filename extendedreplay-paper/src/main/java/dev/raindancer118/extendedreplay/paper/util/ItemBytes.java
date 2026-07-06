package dev.raindancer118.extendedreplay.paper.util;

import org.bukkit.inventory.ItemStack;

/**
 * ItemStack ↔ byte[] using Paper's version-aware binary serialization, plus a cheap
 * content hash used to deduplicate inventory snapshots.
 */
public final class ItemBytes {

    private ItemBytes() {
    }

    /** Null-safe serialize; empty/air stacks become null. */
    public static byte[] serialize(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        return item.serializeAsBytes();
    }

    /** Null-safe deserialize. */
    public static ItemStack deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return ItemStack.deserializeBytes(bytes);
    }

    /** FNV-1a over the serialized slot contents. */
    public static long contentHash(byte[][] slots, byte[] extra) {
        long hash = 0xcbf29ce484222325L;
        for (byte[] slot : slots) {
            hash = mix(hash, slot);
        }
        hash = mix(hash, extra);
        return hash;
    }

    private static long mix(long hash, byte[] data) {
        hash ^= 0x9E3779B97F4A7C15L;
        hash *= 0x100000001b3L;
        if (data != null) {
            for (byte b : data) {
                hash ^= b & 0xFF;
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }
}
