package dev.raindancer118.extendedreplay.transport.spool;

import dev.raindancer118.extendedreplay.core.FormatConstants;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/**
 * Append-only on-disk buffer of encoded transport batches, used while the replay server
 * is unreachable. Layout: {@code int magic} once, then repeated {@code [int length][batch]}.
 */
public final class SpoolFile implements AutoCloseable {

    private final Path file;
    private final long maxBytes;
    private DataOutputStream out;
    private long bytesWritten;
    private long batchesDropped;

    public SpoolFile(Path file, long maxBytes) throws IOException {
        this.file = file;
        this.maxBytes = maxBytes;
        Files.createDirectories(file.toAbsolutePath().getParent());
    }

    /** Appends one encoded batch; drops it (counted) when the size cap is reached. */
    public synchronized void append(byte[] batch) throws IOException {
        if (bytesWritten + batch.length + 4 > maxBytes) {
            batchesDropped++;
            return;
        }
        if (out == null) {
            boolean fresh = !Files.exists(file) || Files.size(file) == 0;
            out = new DataOutputStream(Files.newOutputStream(file,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND));
            if (fresh) {
                out.writeInt(FormatConstants.SPOOL_MAGIC);
            }
            bytesWritten = Files.size(file);
        }
        out.writeInt(batch.length);
        out.write(batch);
        out.flush();
        bytesWritten += batch.length + 4;
    }

    public synchronized boolean hasData() {
        try {
            return Files.exists(file) && Files.size(file) > 4;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Streams all spooled batches to the consumer and truncates the spool afterwards.
     * A torn final record (crash during append) is skipped silently.
     */
    public synchronized void drainTo(Consumer<byte[]> consumer) throws IOException {
        closeWriter();
        if (!Files.exists(file)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            int magic = in.readInt();
            if (magic != FormatConstants.SPOOL_MAGIC) {
                throw new IOException("Bad spool magic in " + file);
            }
            while (true) {
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException e) {
                    break;
                }
                if (length <= 0 || length > 64 * 1024 * 1024) {
                    break; // torn or corrupt tail
                }
                byte[] batch = new byte[length];
                try {
                    in.readFully(batch);
                } catch (EOFException e) {
                    break; // torn tail
                }
                consumer.accept(batch);
            }
        }
        Files.deleteIfExists(file);
        bytesWritten = 0;
    }

    public long bytesWritten() {
        return bytesWritten;
    }

    public long batchesDropped() {
        return batchesDropped;
    }

    private void closeWriter() throws IOException {
        if (out != null) {
            out.close();
            out = null;
        }
    }

    @Override
    public synchronized void close() {
        try {
            closeWriter();
        } catch (IOException ignored) {
            // shutdown path
        }
    }
}
