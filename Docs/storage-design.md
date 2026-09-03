1. **Catalog storage:** one catalog file or one per table? Which format: JSON, Java properties, or your own binary? Where on disk relative to the data directory?

    one catalog file per table in a subfolder named by the table name stored in JSON format.

2. **Catalog contents:** per table, at least the schema and the list of data files and partitions that belong to it.

    -schema name
    -list of partition datafiles and their statistics
    -configurable parameters as maxRowsPerPartition

3.  **Where the min/max summaries live.** The requirement is only that they exist per column per partition and that `select` can consult them without reading the column data they describe. Three designs are defensible. A **footer** after the data is Parquet's choice and is natural for a single-pass writer. A **header** at the front is convenient for the reader, but the writer must buffer the partition or seek back to fill it in. **In the catalog only** means that pruning needs no data-file I/O at all, as in Snowflake and Iceberg, but a data file is then no longer self-describing. Pick one and justify it.

    we pick catalog only because of awoiding the unnessesary data-fil I/O if partition are not relevant. In this case we only need to red the catalog file to know that partitions we need to look at. even though datafiles are no longer self describing, retreaving the statistics is not deficult as they are located close to the data.

4.  **Restart:** what does a fresh `StorageEngine` on the same directory have to read before it can answer a `select`?
   
   All the Table catalog

5. **Layout inside a partition:** choose either row-wise or columnar format.

    columnar format since we want to support OLAP queries more than OLTP queries
   
6. **Partition size:** maximum rows per partition, as a configurable parameter (your tests will use tiny values like 2; pick a sensible default).
   
   for tiny testcases 2 is a nice default. 
   we will pick 10.000 since it should be possible to pass in a reasonable time

7. **Value encodings and framing:** e.g. `LONG` as 8-byte two's-complement, `DOUBLE` as 8-byte IEEE 754, `STRING` as length-prefixed ASCII bytes; magic bytes and a format version number at the start of each file; how a reader finds a given partition's column chunk.
   
    one file is one partition.

    - LONG: 8 bytes, two's-complement, written via ByteBuffer.putLong().
    - DOUBLE: 8 bytes, IEEE 754, written via ByteBuffer.putDouble()
    - STRING: length-prefixed — a 4-byte int giving the number of ASCII bytes, followed by the bytes themselves. No null-termination, no fixed width.
    - File header: every data file starts with 4 magic bytes ("QCDB") followed by a 4-byte format version number (1). A reader rejects any file that doesn't start with the magic bytes, and can branch on the version number if the format changes later.
    - Offset table with file position and length of each column chunk to locate relevant columns

8. **Byte order:** `ByteBuffer` defaults to big-endian, while the machines you run on are little-endian. Pick one and document the choice.

    - Byte order: little-endian. Matches the native architecture of the machines we develop and test on (x86/ARM), so it's the "natural" choice even though it requires explicitly calling buffer.order(ByteOrder.LITTLE_ENDIAN) on every ByteBuffer, since Java defaults to big-endian. matching the computer avoids having to byte-swap on reads an writes