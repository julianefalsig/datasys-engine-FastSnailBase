# Exercise 2: Your Own Binary Format, Catalog, and Filtered Scans

How to Build Data Systems – Fall 2026

In this exercise, you implement the storage core of your engine as a plain Java API. There is no SQL text and no parsing yet; parsing arrives in week 3, the operator pipeline in week 4. This exercise builds on your Exercise 1 repository.

Rules:

- All code enters via pull requests with a teammate's review and green CI.
- No SQL strings anywhere. This week's API takes table names, column specs, and constants as Java values.
- No external storage or file-format libraries (no Parquet, Arrow, SQLite, …). Designing and writing the binary format yourself is the point. Plain-Java CSV reading (`BufferedReader.readLine()` + `String.split(",")`) is fine: course CSV files contain no quoted fields or embedded commas. The ban covers the *data* format only: a JSON or properties library for the catalog (Jackson, for instance) is allowed.

## 1. Design discussion

Decide as a team and write the decisions down in `docs/storage-design.md`, merged via a reviewed PR. It will feed the Part 1 report's architecture section.

1. **Catalog storage:** one catalog file or one per table? Which format: JSON, Java properties, or your own binary? Where on disk relative to the data directory?
2. **Catalog contents:** per table, at least the schema and the list of data files and partitions that belong to it.
3. **Where the min/max summaries live.** The requirement is only that they exist per column per partition and that `select` can consult them without reading the column data they describe. Three designs are defensible. A **footer** after the data is Parquet's choice and is natural for a single-pass writer. A **header** at the front is convenient for the reader, but the writer must buffer the partition or seek back to fill it in. **In the catalog only** means that pruning needs no data-file I/O at all, as in Snowflake and Iceberg, but a data file is then no longer self-describing. Pick one and justify it.
4. **Restart:** what does a fresh `StorageEngine` on the same directory have to read before it can answer a `select`?
5. **Layout inside a partition:** choose either row-wise or columnar format.
6. **Partition size:** maximum rows per partition, as a configurable parameter (your tests will use tiny values like 2; pick a sensible default).
7. **Value encodings and framing:** e.g. `LONG` as 8-byte two's-complement, `DOUBLE` as 8-byte IEEE 754, `STRING` as length-prefixed ASCII bytes; magic bytes and a format version number at the start of each file; how a reader finds a given partition's column chunk.
8. **Byte order:** `ByteBuffer` defaults to big-endian, while the machines you run on are little-endian. Pick one and document the choice.

## 2. The API

Define these types:

```java
public enum ColumnType { STRING, LONG, DOUBLE }

public record ColumnSpec(String name, ColumnType type) { }

public enum Comparison { EQUALS, LESS_THAN, GREATER_THAN }
```

Implement this class (names may differ; signatures should not):

```java
public final class StorageEngine {

    /** All persistent state (catalog + data files) lives under this directory. */
    public StorageEngine(Path dataDirectory) { /* ... */ }

    public void createTable(String tableName, List<ColumnSpec> columns) { /* ... */ }

    public void copyFile(String tableName, String csvFilePath) { /* ... */ }

    public List<Object[]> select(String tableName, String columnName,
                                 Comparison comparison, Object constant) { /* ... */ }
}
```

Taking the data directory as a constructor argument is what makes the engine testable: each test runs against a fresh temporary directory, and a restart is simulated by constructing a second engine on the same directory.

`createTable`:

- Throws `IllegalArgumentException` if the table already exists, the column list is empty, or column names repeat.
- Persists the schema in the catalog: after a restart, the table is still known.
- Creates no data files yet.

`copyFile`:

- Throws if the table does not exist.
- CSV input has no header line; line 1 is already data. Fields are positional: the *n*-th value in every line belongs to the *n*-th column of the schema. A line whose field count differs from the schema's column count is an error. Values are ASCII; no quoted fields. This matches the engine's own log files, which you will `COPY` in week 5.
- Parses each value according to the column type; a malformed value fails the whole copy with an error message naming the file and line number.
- Writes the data in your binary format: rows split into partitions of at most `maxRowsPerPartition` rows; per column and per partition, min and max values computed during the write and persisted where your design puts them.
- Registers the new file and its partitions in the catalog, persistently.
- One copy per table is enough in Part 1: a second `copyFile` on the same table may throw `UnsupportedOperationException`. (Appending arrives in Part 2.)

`select`:

- Throws if the table or column is unknown, or if `constant`'s Java type does not match the column type (`String` for `STRING`, `Long` for `LONG`, `Double` for `DOUBLE`). The match is exact: an `Integer` constant against a `LONG` column is an error, not a widening.
- Comparison semantics: numeric for `LONG`/`DOUBLE`; lexicographic ASCII comparison for `STRING` (this matches DuckDB for plain ASCII data).
- Returns every matching row as an `Object[]` in schema column order, rows in on-disk order.
- **Pruning is required:** a partition whose min/max range cannot contain a match is skipped without reading its column data. Examples: predicate `distance > 100` and partition max 87 → skip; predicate `city = 'Odense'` and partition range `[Aalborg, Copenhagen]` → skip.
- Make pruning observable: a `record ScanStats(int partitionsTotal, int partitionsRead, int partitionsPruned)` retrievable after a select, and log lines for every min/max created and every prune-or-read decision made (Task 3). A value that only lives in a Java object is not observable.

## 3. Logging

Emit at least one CSV log line per API call, with the measurements in the message as `key=value` pairs. Log messages must not contain commas, so that each line parses into exactly seven values by position.

Log statistics where they are created and where they are used, not only summarized at the end of the call:

```csv
2027-02-14 12:01:15.204,3f9c1a2e,0,1,DEBUG,StorageEngine,table=trips file=trips_sorted.csv rows=8 partitions=4 durationMs=12
2027-02-14 12:01:15.207,3f9c1a2e,0,1,DEBUG,StorageEngine,table=trips partition=0 column=distance min=12 max=31
2027-02-14 12:01:15.209,3f9c1a2e,0,1,DEBUG,StorageEngine,table=trips partition=1 column=distance min=88 max=95
2027-02-14 12:01:15.318,3f9c1a2e,0,1,DEBUG,StorageEngine,table=trips column=distance comparison=GREATER_THAN const=100 partition=0 min=12 max=31 decision=PRUNED
2027-02-14 12:01:15.319,3f9c1a2e,0,1,DEBUG,StorageEngine,table=trips column=distance comparison=GREATER_THAN const=100 partition=2 min=140 max=187 decision=READ
2027-02-14 12:01:15.321,3f9c1a2e,0,1,DEBUG,StorageEngine,table=trips column=distance comparison=GREATER_THAN const=100 partitionsRead=2 partitionsPruned=2 rowsOut=4 durationMs=3
```

(The scenario is test 8 below: the golden data sorted by `distance`, `maxRowsPerPartition = 2`. Only some per-partition lines are shown. `statementNumber` is 0 throughout, because this week's API takes Java calls, not SQL statements; it starts counting in week 4.) Every min/max computed during `copyFile` and every prune-or-read decision during `select` gets its own `LOGGER.debug` line at the point it happens; the per-call summary line comes on top. In week 5 you will load these very lines with your own `COPY` and analyze them with your own `SELECT`.

## 4. Required tests (JUnit 6)

Two kinds of test, both required from now on, in separate classes (e.g. `*Test` for unit, `*IT` for integration):

- **Unit tests** call one Java method directly and check its result in isolation. This includes internal methods you introduce yourself, not just the public API.
- **Integration tests** drive the public `StorageEngine` API end-to-end: CSV in, binary format on disk, `select` back out.

Note on visibility: JUnit cannot call a `private` method from a separate test class. Any internal method you want to unit-test directly must be **package-private** (no modifier), with the test class in the same package under `src/test/java/...`. Keep `StorageEngine`'s public surface limited to the three API methods.

Note on the build: Surefire, the plugin from Exercise 1, runs `*Test` classes only, so your `*IT` classes would silently never run. Add Failsafe to `pom.xml` to run them in the `verify` phase:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-failsafe-plugin</artifactId>
  <version>3.5.6</version>
  <executions>
    <execution>
      <goals>
        <goal>integration-test</goal>
        <goal>verify</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

`mvn test` then runs the unit tests alone, while `mvn -B verify`, what CI runs, runs both kinds.

Unit tests, against whatever internal methods your design produces:

1. **Value encoding round trip:** for each `ColumnType`, encoding a value and decoding it back returns the original value.
2. **Min/max computation:** given a small list of values for one column, the min/max method returns the correct pair (include a single-value case and a negative case).
3. **Pruning decision:** given a predicate (`Comparison` + constant) and a partition's min/max, the decision method returns the correct boolean; at least one pruning and one non-pruning case per comparison.
4. **CSV line parsing:** a well-formed line parses into correctly typed values by position; a malformed value and a wrong field count are each rejected with file and line number.

Integration tests, each on a fresh `@TempDir`:

1. **Schema persistence:** `createTable`, then a new `StorageEngine` on the same directory still knows the table.
2. **Duplicate table:** second `createTable` with the same name throws.
3. **Round trip:** copy the golden CSV below; a predicate matching everything (e.g. `distance > -1`) returns all 8 rows with correct values and types.
4. **All comparisons × all types:** `=`, `<`, `>` against a `STRING`, a `LONG`, and a `DOUBLE` column (9 combinations), checked against the golden data.
5. **Empty result:** a predicate matching nothing returns an empty list.
6. **Errors:** unknown table, unknown column, and a type-mismatched constant each throw.
7. **Partitioning:** with `maxRowsPerPartition = 2`, the golden file produces 4 partitions with correct min/max values, read back from wherever you store them.
8. **Pruning:** copy a CSV sorted by `distance` with `maxRowsPerPartition = 2`; a selective predicate (e.g. `distance > 200`) reports at least 2 partitions pruned and returns exactly the right rows.
9. **Data persistence:** copy with engine A; a new engine B on the same directory returns the same rows.

## 5. Golden example and runnable demo

`src/test/resources/trips.csv` (eight lines, all data):

```csv
Copenhagen,12,23.5
Aarhus,187,301.0
Odense,95,120.75
Copenhagen,140,210.0
Aalborg,210,340.5
Roskilde,31,45.0
Copenhagen,88,99.99
Esbjerg,299,450.25
```

Expected results:

| Predicate | Matching rows |
|---|---|
| `distance GREATER_THAN 100` | Aarhus 187, Copenhagen 140, Aalborg 210, Esbjerg 299 (4 rows) |
| `city EQUALS "Copenhagen"` | distances 12, 140, 88 (3 rows) |
| `price LESS_THAN 50.0` | Copenhagen 23.5, Roskilde 45.0 (2 rows) |

**Requirement:** `mvn compile exec:java`, with no further arguments, runs this example and prints the three results. This replaces Exercise 1's team-name printout.

## 6. Cut release v0.2

```bash
git checkout main && git pull
git tag -a v0.2 -m "Exercise 2: storage engine"
git push origin v0.2
```

This creates a tag that the teaching assistant will check out to validate the state of your project.

## Definition of done

- [ ] `docs/storage-design.md` merged, covering all design decisions with justifications.
- [ ] `createTable`, `copyFile`, and `select` implemented against your own binary format with partitions and per-partition min/max summaries.
- [ ] Catalog and data survive a restart.
- [ ] Pruning observable as `ScanStats` and as individual log lines for every statistic created and every decision made.
- [ ] `copyFile` reads headerless CSV positionally and rejects wrong field counts.
- [ ] All unit and integration tests green in CI.
- [ ] `mvn compile exec:java` prints the results of the three golden queries.
- [ ] All of it merged through reviewed PRs.
- [ ] Tag `v0.2` is pushed.

## Outlook

In week 3, an ANTLR-generated parser turns SQL text into an AST that maps onto exactly this API. In week 4, `select` is refactored into a Volcano-style operator pipeline.