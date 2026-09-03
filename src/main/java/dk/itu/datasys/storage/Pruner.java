package dk.itu.datasys.storage;

import java.util.Comparator;

/** Decides, from a partition's [min, max] range alone, whether it can contain a match for a predicate. */
final class Pruner {

    private Pruner() {
    }

    /** Returns true if the partition cannot contain a match and can be skipped without reading its data. */
    static boolean canPrune(Comparison comparison, Object constant, Object min, Object max, ColumnType type) {
        Comparator<Object> cmp = ColumnStats.comparatorFor(type);
        return switch (comparison) {
            case EQUALS -> cmp.compare(constant, min) < 0 || cmp.compare(constant, max) > 0;
            case LESS_THAN -> cmp.compare(min, constant) >= 0;
            case GREATER_THAN -> cmp.compare(max, constant) <= 0;
        };
    }
}
