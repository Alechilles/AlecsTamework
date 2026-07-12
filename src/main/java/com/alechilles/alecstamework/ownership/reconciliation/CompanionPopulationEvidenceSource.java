package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resumable, bounded source of persisted companion evidence.
 */
public interface CompanionPopulationEvidenceSource {
    @Nonnull
    Descriptor descriptor();

    /**
     * Scans at most {@code maxUnits} source units after the supplied durable offset.
     */
    @Nonnull
    CompletableFuture<Batch> scan(long offset, int maxUnits);

    record Descriptor(
            @Nonnull String coverageKey,
            @Nonnull CompanionPopulationCoverageRecord.Dimension dimension,
            @Nullable String worldOrSaveId,
            @Nonnull String scanGeneration,
            long estimatedTotal
    ) {
        public Descriptor {
            coverageKey = requireText(coverageKey, "coverageKey");
            Objects.requireNonNull(dimension, "dimension");
            scanGeneration = requireText(scanGeneration, "scanGeneration");
            if (estimatedTotal < 0L) {
                throw new IllegalArgumentException("estimatedTotal must be non-negative.");
            }
        }
    }

    record Batch(@Nonnull List<CompanionPopulationEvidence> evidence,
                 long nextOffset,
                 long scannedUnits,
                 boolean complete) {
        public Batch {
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
            if (nextOffset < 0L || scannedUnits < 0L) {
                throw new IllegalArgumentException("Batch offsets and counts must be non-negative.");
            }
        }
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return normalized;
    }
}
