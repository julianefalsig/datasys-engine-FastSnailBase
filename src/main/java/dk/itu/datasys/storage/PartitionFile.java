package dk.itu.datasys.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Binary layout of one partition file (one file = one partition, per storage-design.md):
 *
 * <pre>
 * magic "QCDB"        4 bytes
 * version              4 bytes (int)
 * rowCount             4 bytes (int)
 * columnCount          4 bytes (int)
 * offset table         columnCount * (8-byte offset + 8-byte length), one entry per column, schema order
 * column data blocks   one per column, schema order; LONG/DOUBLE fixed 8 bytes/value, STRING length-prefixed
 * </pre>
 *
 * All multi-byte fields are little-endian.
 */
final class PartitionFile {

    private static final byte[] MAGIC = {'Q', 'C', 'D', 'B'};
    private static final int VERSION = 1;
    private static final int FIXED_HEADER_SIZE = 4 + 4 + 4 + 4;
    private static final int OFFSET_ENTRY_SIZE = 8 + 8;

    private PartitionFile() {
    }

    /** data.get(c) holds the values of column c, schema order, one entry per row. */
    static void write(Path file, List<ColumnSpec> columns, List<List<Object>> data, int rowCount) {
        int columnCount = columns.size();
        int headerSize = FIXED_HEADER_SIZE + columnCount * OFFSET_ENTRY_SIZE;

        int[] columnSizes = new int[columnCount];
        for (int c = 0; c < columnCount; c++) {
            ColumnType type = columns.get(c).type();
            int size = 0;
            for (Object value : data.get(c)) {
                size += ValueCodec.encodedSize(value, type);
            }
            columnSizes[c] = size;
        }

        int totalSize = headerSize;
        for (int size : columnSizes) {
            totalSize += size;
        }

        ByteBuffer buffer = ValueCodec.newBuffer(totalSize);
        buffer.put(MAGIC);
        buffer.putInt(VERSION);
        buffer.putInt(rowCount);
        buffer.putInt(columnCount);

        long offset = headerSize;
        for (int c = 0; c < columnCount; c++) {
            buffer.putLong(offset);
            buffer.putLong(columnSizes[c]);
            offset += columnSizes[c];
        }

        for (int c = 0; c < columnCount; c++) {
            ColumnType type = columns.get(c).type();
            for (Object value : data.get(c)) {
                ValueCodec.encode(buffer, value, type);
            }
        }

        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            buffer.flip();
            channel.write(buffer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads every column of the partition, schema order, using the offset table to locate each column chunk. */
    static List<List<Object>> readAllColumns(Path file, List<ColumnSpec> columns) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) channel.size()).order(ByteOrder.LITTLE_ENDIAN);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // keep reading until the buffer is full
            }
            buffer.flip();

            byte[] magic = new byte[MAGIC.length];
            buffer.get(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IllegalStateException("not a partition file (bad magic bytes): " + file);
            }
            int version = buffer.getInt();
            if (version != VERSION) {
                throw new IllegalStateException("unsupported partition format version " + version + " in " + file);
            }
            int rowCount = buffer.getInt();
            int columnCount = buffer.getInt();
            if (columnCount != columns.size()) {
                throw new IllegalStateException("schema/partition column count mismatch in " + file);
            }

            long[] offsets = new long[columnCount];
            for (int c = 0; c < columnCount; c++) {
                offsets[c] = buffer.getLong();
                buffer.getLong(); // length, unused when reading sequentially from the offset
            }

            List<List<Object>> result = new ArrayList<>(columnCount);
            for (int c = 0; c < columnCount; c++) {
                buffer.position((int) offsets[c]);
                ColumnType type = columns.get(c).type();
                List<Object> values = new ArrayList<>(rowCount);
                for (int r = 0; r < rowCount; r++) {
                    values.add(ValueCodec.decode(buffer, type));
                }
                result.add(values);
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
