package dk.itu.datasys.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Reads/writes one table's catalog.json, in its own subfolder named after the table. */
final class CatalogStore {

    private static final String CATALOG_FILE_NAME = "catalog.json";
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private CatalogStore() {
    }

    static Path catalogPath(Path tableDirectory) {
        return tableDirectory.resolve(CATALOG_FILE_NAME);
    }

    static boolean exists(Path tableDirectory) {
        return Files.exists(catalogPath(tableDirectory));
    }

    static CatalogData read(Path tableDirectory) {
        try {
            CatalogData data = MAPPER.readValue(catalogPath(tableDirectory).toFile(), CatalogData.class);
            data.coerceStats();
            return data;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes via a temp file + rename so a crash mid-write never leaves a half-written catalog. */
    static void write(Path tableDirectory, CatalogData data) {
        try {
            Files.createDirectories(tableDirectory);
            Path target = catalogPath(tableDirectory);
            Path tmp = tableDirectory.resolve(CATALOG_FILE_NAME + ".tmp");
            MAPPER.writeValue(tmp.toFile(), data);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
