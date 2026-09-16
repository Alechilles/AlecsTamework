package com.alechilles.alecstamework.persistence.adapter.sqlite;

/** The durable checkpoint work and physical bytes reclaimed by database compaction. */
public record SqliteDatabaseCompactionResult(
        long bytesBefore,
        long bytesAfter,
        int compactedOperations
) { }
