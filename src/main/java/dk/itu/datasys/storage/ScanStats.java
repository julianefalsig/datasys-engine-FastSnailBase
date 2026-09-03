package dk.itu.datasys.storage;

public record ScanStats(int partitionsTotal, int partitionsRead, int partitionsPruned) {
}
