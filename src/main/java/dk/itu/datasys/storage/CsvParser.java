package dk.itu.datasys.storage;

import java.util.List;

/** Parses headerless, positional CSV lines (no quoting, no embedded commas) into typed values. */
final class CsvParser {

    private CsvParser() {
    }

    static Object[] parseLine(String line, List<ColumnSpec> columns, String sourceFile, int lineNumber) {
        String[] fields = line.split(",", -1);
        if (fields.length != columns.size()) {
            throw new IllegalArgumentException(
                    "wrong field count in %s at line %d: expected %d, got %d"
                            .formatted(sourceFile, lineNumber, columns.size(), fields.length));
        }
        Object[] values = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            ColumnSpec column = columns.get(i);
            String raw = fields[i];
            try {
                values[i] = switch (column.type()) {
                    case LONG -> Long.parseLong(raw);
                    case DOUBLE -> Double.parseDouble(raw);
                    case STRING -> raw;
                };
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "malformed value in %s at line %d, column %s: '%s'"
                                .formatted(sourceFile, lineNumber, column.name(), raw), e);
            }
        }
        return values;
    }
}
