package dk.itu.datasys.storage;

import java.util.Comparator;
import java.util.List;

/** Per-column min/max over a list of values, and the comparator used to compute it. */
final class ColumnStats {

    final Object min;
    final Object max;

    private ColumnStats(Object min, Object max) {
        this.min = min;
        this.max = max;
    }

    static ColumnStats of(List<Object> values, ColumnType type) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("cannot compute min/max of an empty column");
        }
        Comparator<Object> comparator = comparatorFor(type);
        Object min = values.get(0);
        Object max = values.get(0);
        for (Object value : values) {
            if (comparator.compare(value, min) < 0) {
                min = value;
            }
            if (comparator.compare(value, max) > 0) {
                max = value;
            }
        }
        return new ColumnStats(min, max);
    }

    static Comparator<Object> comparatorFor(ColumnType type) {
        return switch (type) {
            case LONG -> (a, b) -> Long.compare((Long) a, (Long) b);
            case DOUBLE -> (a, b) -> Double.compare((Double) a, (Double) b);
            case STRING -> (a, b) -> ((String) a).compareTo((String) b);
        };
    }
}
