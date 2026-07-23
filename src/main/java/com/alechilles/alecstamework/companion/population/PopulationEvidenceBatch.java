package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Durable result of scanning one owner-population evidence source.
 *
 * <p>The key deliberately includes boot, world, generation, and source. Negative evidence is
 * valid only when the matching disk and live batches are both sealed.</p>
 */
public record PopulationEvidenceBatch(
        @Nonnull Key key,
        @Nonnull Status status,
        long openedAtMs,
        @Nullable Long closedAtMs,
        @Nullable String failureCode
) {
    public PopulationEvidenceBatch {
        if (key == null || status == null) {
            throw new IllegalArgumentException(
                    "Population evidence batch key and status are required"
            );
        }
        failureCode = normalize(failureCode);
        if (status == Status.OPEN
                && (closedAtMs != null || failureCode != null)) {
            throw new IllegalArgumentException(
                    "Open evidence batches cannot carry a close result"
            );
        }
        if (status == Status.SEALED
                && (closedAtMs == null || failureCode != null)) {
            throw new IllegalArgumentException(
                    "Sealed evidence batches require only a close time"
            );
        }
        if (status == Status.FAILED
                && (closedAtMs == null || failureCode == null)) {
            throw new IllegalArgumentException(
                    "Failed evidence batches require close time and failure code"
            );
        }
    }

    /** Creates an open source batch. */
    @Nonnull
    public static PopulationEvidenceBatch open(
            @Nonnull Key key,
            long openedAtMs
    ) {
        return new PopulationEvidenceBatch(
                key, Status.OPEN, openedAtMs, null, null
        );
    }

    /** Exact immutable identity of one source scan. */
    public record Key(
            @Nonnull String bootId,
            @Nonnull String worldKey,
            @Nonnull ReconciliationGeneration generation,
            @Nonnull Source source
    ) {
        public Key {
            bootId = requireText(bootId, "Evidence boot ID");
            worldKey = requireText(worldKey, "Evidence world key");
            if (generation == null || source == null) {
                throw new IllegalArgumentException(
                        "Evidence generation and source are required"
                );
            }
        }
    }

    /** Complete source vocabulary for absence proof. */
    public enum Source {
        DISK,
        LIVE
    }

    /** Result of one source scan; only SEALED can contribute negative evidence. */
    public enum Status {
        OPEN,
        SEALED,
        FAILED
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
