package dev.raindancer118.extendedreplay.core;

/** Version numbers and magic values of the replay wire and file formats. */
public final class FormatConstants {

    /** Transport protocol version, checked during handshake. */
    public static final int PROTOCOL_VERSION = 1;

    /** Replay session storage format version, stored per session. */
    public static final int FORMAT_VERSION = 1;

    /** Magic prefix of segment files: "ERPS". */
    public static final int SEGMENT_MAGIC = 0x45525053;

    /** Magic prefix of spool files: "ERPQ". */
    public static final int SPOOL_MAGIC = 0x45525051;

    private FormatConstants() {
    }
}
