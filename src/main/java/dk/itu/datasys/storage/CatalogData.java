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

    public List<ColumnEntry> columns = new ArrayList<>();
    public int maxRowsPerPartition;
    public List<PartitionEntry> partitions = new ArrayList<>();

    static final class ColumnEntry {
        public String name;
        public ColumnType type;

        ColumnEntry() {
        }

        ColumnEntry(String name, ColumnType type) {
            this.name = name;
            this.type = type;
        }
    }

    static final class PartitionEntry {
        public String dataFile;
        public int rowCount;
        public Map<String, ColumnStatsEntry> stats = new LinkedHashMap<>();
    }

    /** min/max stored as strings, parsed back to the column's native type using the schema. */
    static final class ColumnStatsEntry {
        public String min;
        public String max;

        ColumnStatsEntry() {
        }

        ColumnStatsEntry(String min, String max) {
            this.min = min;
            this.max = max;
        }
    }
}
