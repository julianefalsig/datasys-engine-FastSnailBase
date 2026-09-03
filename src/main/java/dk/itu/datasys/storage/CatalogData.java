package dk.itu.datasys.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON-serializable contents of one table's catalog file: schema, partition size,
 * and the list of partitions with their per-column min/max (catalog-only pruning, per storage-design.md).
 */
final class CatalogData {

    public List<ColumnSpec> columns = new ArrayList<>();
    public int maxRowsPerPartition;
    public List<Partition> partitions = new ArrayList<>();

    record Partition(String dataFile, int rowCount, Map<String, Range> stats) {
    }

    /** min/max as JSON numbers or strings matching the column type. */
    record Range(Object min, Object max) {
    }

    void coerceStats() {
        List<Partition> coerced = new ArrayList<>(partitions.size());
        for (Partition partition : partitions) {
            Map<String, Range> stats = new LinkedHashMap<>();
            for (ColumnSpec column : columns) {
                Range range = partition.stats().get(column.name());
                if (range == null) {
                    throw new IllegalStateException(
                            "catalog is missing min/max for column " + column.name());
                }
                stats.put(column.name(), new Range(
                        coerce(range.min(), column.type()),
                        coerce(range.max(), column.type())));
            }
            coerced.add(new Partition(partition.dataFile(), partition.rowCount(), stats));
        }
        partitions.clear();
        partitions.addAll(coerced);
    }

    static Object coerce(Object raw, ColumnType type) {
        if (raw == null) {
            throw new IllegalArgumentException("min/max must not be null for type " + type);
        }
        return switch (type) {
            case LONG -> {
                if (raw instanceof Number number) {
                    yield number.longValue();
                }
                if (raw instanceof String text) {
                    yield Long.parseLong(text);
                }
                throw new IllegalArgumentException("cannot coerce " + raw.getClass().getSimpleName() + " to LONG");
            }
            case DOUBLE -> {
                if (raw instanceof Number number) {
                    yield number.doubleValue();
                }
                if (raw instanceof String text) {
                    yield Double.parseDouble(text);
                }
                throw new IllegalArgumentException("cannot coerce " + raw.getClass().getSimpleName() + " to DOUBLE");
            }
            case STRING -> {
                if (raw instanceof String text) {
                    yield text;
                }
                throw new IllegalArgumentException("cannot coerce " + raw.getClass().getSimpleName() + " to STRING");
            }
        };
    }
}
