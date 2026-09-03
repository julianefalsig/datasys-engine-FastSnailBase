package dk.itu.datasys.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class StorageEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);
    private static final int DEFAULT_MAX_ROWS_PER_PARTITION = 10_000;

    private final Path dataDirectory;
    private final int defaultMaxRowsPerPartition;
    private final Map<String, CatalogData> catalogs = new ConcurrentHashMap<>();
    private volatile ScanStats lastScanStats;

    public StorageEngine(Path dataDirectory) {
        this(dataDirectory, DEFAULT_MAX_ROWS_PER_PARTITION);
    }

    public StorageEngine(Path dataDirectory, int defaultMaxRowsPerPartition) {
        this.dataDirectory = dataDirectory;
        this.defaultMaxRowsPerPartition = defaultMaxRowsPerPartition;
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        loadCatalogs();
    }

    private void loadCatalogs() {
        try (DirectoryStream<Path> tableDirs = Files.newDirectoryStream(dataDirectory, Files::isDirectory)) {
            for (Path tableDir : tableDirs) {
                if (CatalogStore.exists(tableDir)) {
                    catalogs.put(tableDir.getFileName().toString(), CatalogStore.read(tableDir));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void createTable(String tableName, List<ColumnSpec> columns) {
        if (catalogs.containsKey(tableName)) {
            throw new IllegalArgumentException("table already exists: " + tableName);
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("a table needs at least one column");
        }
        long distinctNames = columns.stream().map(ColumnSpec::name).distinct().count();
        if (distinctNames != columns.size()) {
            throw new IllegalArgumentException("duplicate column names in schema for table: " + tableName);
        }

        CatalogData catalog = new CatalogData();
        catalog.maxRowsPerPartition = defaultMaxRowsPerPartition;
        catalog.columns.addAll(List.copyOf(columns));

        CatalogStore.write(tableDirectory(tableName), catalog);
        catalogs.put(tableName, catalog);
        LOGGER.debug("op=createTable table={} columns={}", tableName, columns.size());
    }

    public void copyFile(String tableName, String csvFilePath) {
        long start = System.currentTimeMillis();
        CatalogData catalog = requireCatalog(tableName);
        if (!catalog.partitions.isEmpty()) {
            throw new UnsupportedOperationException(
                    "table " + tableName + " already has data; appending is not supported yet");
        }

        List<ColumnSpec> columns = catalog.columns;
        List<Object[]> rows = readCsv(csvFilePath, columns);

        int partitionIndex = 0;
        for (int rowStart = 0; rowStart < rows.size(); rowStart += catalog.maxRowsPerPartition) {
            int rowEnd = Math.min(rowStart + catalog.maxRowsPerPartition, rows.size());
            writePartition(tableName, catalog, columns, rows.subList(rowStart, rowEnd), partitionIndex);
            partitionIndex++;
        }

        CatalogStore.write(tableDirectory(tableName), catalog);

        long durationMs = System.currentTimeMillis() - start;
        LOGGER.debug("op=copyFile table={} file={} rows={} partitions={} durationMs={}",
                tableName, csvFilePath, rows.size(), partitionIndex, durationMs);
    }

    private void writePartition(String tableName, CatalogData catalog, List<ColumnSpec> columns,
                                 List<Object[]> partitionRows, int partitionIndex) {
        int columnCount = columns.size();
        List<List<Object>> columnData = new ArrayList<>(columnCount);
        for (int c = 0; c < columnCount; c++) {
            columnData.add(new ArrayList<>(partitionRows.size()));
        }
        for (Object[] row : partitionRows) {
            for (int c = 0; c < columnCount; c++) {
                columnData.get(c).add(row[c]);
            }
        }

        String dataFileName = "partition-%d.bin".formatted(partitionIndex);
        PartitionFile.write(tableDirectory(tableName).resolve(dataFileName), columns, columnData, partitionRows.size());

        Map<String, CatalogData.Range> statsByColumn = new LinkedHashMap<>();
        for (int c = 0; c < columnCount; c++) {
            ColumnSpec column = columns.get(c);
            ColumnStats stats = ColumnStats.of(columnData.get(c), column.type());
            statsByColumn.put(column.name(), new CatalogData.Range(stats.min, stats.max));
            LOGGER.debug("op=copyFile table={} partition={} column={} min={} max={}",
                    tableName, partitionIndex, column.name(), stats.min, stats.max);
        }
        catalog.partitions.add(new CatalogData.Partition(dataFileName, partitionRows.size(), statsByColumn));
    }

    public List<Object[]> select(String tableName, String columnName, Comparison comparison, Object constant) {
        long start = System.currentTimeMillis();
        CatalogData catalog = requireCatalog(tableName);
        List<ColumnSpec> columns = catalog.columns;

        int predicateIndex = -1;
        ColumnSpec predicateColumn = null;
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(columnName)) {
                predicateIndex = i;
                predicateColumn = columns.get(i);
                break;
            }
        }
        if (predicateColumn == null) {
            throw new IllegalArgumentException("unknown column: " + columnName + " on table " + tableName);
        }
        requireMatchingType(predicateColumn, constant);

        List<Object[]> results = new ArrayList<>();
        int partitionsTotal = catalog.partitions.size();
        int partitionsRead = 0;
        int partitionsPruned = 0;

        for (int p = 0; p < catalog.partitions.size(); p++) {
            CatalogData.Partition entry = catalog.partitions.get(p);
            CatalogData.Range stats = entry.stats().get(columnName);
            Object min = stats.min();
            Object max = stats.max();

            boolean prune = Pruner.canPrune(comparison, constant, min, max, predicateColumn.type());
            LOGGER.debug("op=select table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                    tableName, columnName, comparison, constant, p, min, max, prune ? "PRUNED" : "READ");

            if (prune) {
                partitionsPruned++;
                continue;
            }
            partitionsRead++;

            List<List<Object>> partitionData =
                    PartitionFile.readAllColumns(tableDirectory(tableName).resolve(entry.dataFile()), columns);

            for (int r = 0; r < entry.rowCount(); r++) {
                Object value = partitionData.get(predicateIndex).get(r);
                if (matches(comparison, constant, value, predicateColumn.type())) {
                    Object[] row = new Object[columns.size()];
                    for (int c = 0; c < columns.size(); c++) {
                        row[c] = partitionData.get(c).get(r);
                    }
                    results.add(row);
                }
            }
        }

        long durationMs = System.currentTimeMillis() - start;
        lastScanStats = new ScanStats(partitionsTotal, partitionsRead, partitionsPruned);
        LOGGER.debug("op=select table={} column={} comparison={} const={} partitionsRead={} partitionsPruned={} rowsOut={} durationMs={}",
                tableName, columnName, comparison, constant, partitionsRead, partitionsPruned, results.size(), durationMs);

        return results;
    }

    /** Pruning stats from the most recent {@link #select}, so pruning decisions are observable beyond the log. */
    public ScanStats lastScanStats() {
        return lastScanStats;
    }

    private Path tableDirectory(String tableName) {
        return dataDirectory.resolve(tableName);
    }

    private CatalogData requireCatalog(String tableName) {
        CatalogData catalog = catalogs.get(tableName);
        if (catalog == null) {
            throw new IllegalArgumentException("unknown table: " + tableName);
        }
        return catalog;
    }

    private static void requireMatchingType(ColumnSpec column, Object constant) {
        boolean matches = switch (column.type()) {
            case STRING -> constant instanceof String;
            case LONG -> constant instanceof Long;
            case DOUBLE -> constant instanceof Double;
        };
        if (!matches) {
            throw new IllegalArgumentException(
                    "constant type %s does not match column %s of type %s"
                            .formatted(constant.getClass().getSimpleName(), column.name(), column.type()));
        }
    }

    private static boolean matches(Comparison comparison, Object constant, Object value, ColumnType type) {
        Comparator<Object> cmp = ColumnStats.comparatorFor(type);
        int c = cmp.compare(value, constant);
        return switch (comparison) {
            case EQUALS -> c == 0;
            case LESS_THAN -> c < 0;
            case GREATER_THAN -> c > 0;
        };
    }

    private static List<Object[]> readCsv(String csvFilePath, List<ColumnSpec> columns) {
        List<Object[]> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(Path.of(csvFilePath))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                rows.add(CsvParser.parseLine(line, columns, csvFilePath, lineNumber));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }
}
