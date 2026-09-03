package dk.itu.datasys.storage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Encodes/decodes single column values to/from bytes.
 * LONG: 8 bytes two's-complement. DOUBLE: 8 bytes IEEE 754. STRING: 4-byte length prefix + ASCII bytes.
 * Byte order is little-endian throughout, per storage-design.md.
 */
final class ValueCodec {

    private ValueCodec() {
    }

    static ByteBuffer newBuffer(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }

    static int encodedSize(Object value, ColumnType type) {
        return switch (type) {
            case LONG, DOUBLE -> 8;
            case STRING -> 4 + ((String) value).getBytes(StandardCharsets.US_ASCII).length;
        };
    }

    static void encode(ByteBuffer buffer, Object value, ColumnType type) {
        switch (type) {
            case LONG -> buffer.putLong((Long) value);
            case DOUBLE -> buffer.putDouble((Double) value);
            case STRING -> {
                byte[] bytes = ((String) value).getBytes(StandardCharsets.US_ASCII);
                buffer.putInt(bytes.length);
                buffer.put(bytes);
            }
        }
    }

    static Object decode(ByteBuffer buffer, ColumnType type) {
        return switch (type) {
            case LONG -> buffer.getLong();
            case DOUBLE -> buffer.getDouble();
            case STRING -> {
                int length = buffer.getInt();
                byte[] bytes = new byte[length];
                buffer.get(bytes);
                yield new String(bytes, StandardCharsets.US_ASCII);
            }
        };
    }
}
