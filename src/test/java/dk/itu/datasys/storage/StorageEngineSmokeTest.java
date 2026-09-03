package dk.itu.datasys.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end sanity check against the exercise's golden dataset, exercising createTable,
 * copyFile, select, pruning, and restart together. Not a substitute for the full required
 * test suite in Exercise 2 section 4 — just a smoke test for the API implementation.
 */
class StorageEngineSmokeTest {

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void goldenQueriesAndPruning(@TempDir Path dataDir) {
        StorageEngine engine = new StorageEngine(dataDir, 2); // tiny partitions to force pruning
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", "src/test/resources/trips.csv");

        List<Object[]> distanceOver100 = engine.select("trips", "distance", Comparison.GREATER_THAN, 100L);
        assertEquals(4, distanceOver100.size());
        assertEquals(engine.lastScanStats().partitionsTotal(),
                engine.lastScanStats().partitionsRead() + engine.lastScanStats().partitionsPruned());

        // trips.csv isn't sorted by distance, so every 2-row partition mixes low/high distances and
        // none can be pruned on that column; city is where partitioning happens to produce a prunable range.
        engine.select("trips", "city", Comparison.EQUALS, "Aalborg");
        assertTrue(engine.lastScanStats().partitionsPruned() > 0);

        List<Object[]> copenhagen = engine.select("trips", "city", Comparison.EQUALS, "Copenhagen");
        assertEquals(3, copenhagen.size());

        List<Object[]> cheap = engine.select("trips", "price", Comparison.LESS_THAN, 50.0);
        assertEquals(2, cheap.size());
    }

    @Test
    void restartSeesPersistedData(@TempDir Path dataDir) {
        StorageEngine first = new StorageEngine(dataDir, 2);
        first.createTable("trips", TRIPS_SCHEMA);
        first.copyFile("trips", "src/test/resources/trips.csv");

        StorageEngine restarted = new StorageEngine(dataDir);
        List<Object[]> all = restarted.select("trips", "distance", Comparison.GREATER_THAN, -1L);
        assertEquals(8, all.size());
    }

    @Test
    void secondCopyFileIsRejected(@TempDir Path dataDir) {
        StorageEngine engine = new StorageEngine(dataDir, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", "src/test/resources/trips.csv");

        assertThrows(UnsupportedOperationException.class,
                () -> engine.copyFile("trips", "src/test/resources/trips.csv"));
    }

    @Test
    void typeMismatchIsRejected(@TempDir Path dataDir) {
        StorageEngine engine = new StorageEngine(dataDir, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", "src/test/resources/trips.csv");

        assertThrows(IllegalArgumentException.class,
                () -> engine.select("trips", "distance", Comparison.GREATER_THAN, 100)); // Integer, not Long
    }
}
