package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationLegacyEvidenceRepository;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.annotation.Nonnull;

/** Bounded first-stage scan of canonical profiles and active dormant snapshot families. */
public final class SqliteProfileStateEvidenceSource implements CompanionPopulationEvidenceSource {
    public static final String COVERAGE_KEY = "profile-state:sqlite";

    private final CompanionPopulationLegacyEvidenceRepository repository;
    private final CompanionPopulationLegacyEvidenceRepository.SnapshotDescriptor snapshot;
    private final Descriptor descriptor;

    public SqliteProfileStateEvidenceSource(
            @Nonnull CompanionPopulationLegacyEvidenceRepository repository
    ) throws Exception {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.snapshot = repository.snapshotDescriptor();
        this.descriptor = new Descriptor(
                COVERAGE_KEY,
                CompanionPopulationCoverageRecord.Dimension.PROFILE_STATE,
                "tamework.sqlite",
                ReconciliationGeneration.forStrings(
                        COVERAGE_KEY,
                        java.util.List.of(snapshot.generation())
                ),
                snapshot.total()
        );
    }

    @Nonnull
    @Override
    public Descriptor descriptor() {
        return descriptor;
    }

    @Nonnull
    @Override
    public CompletableFuture<Batch> scan(long offset, int maxUnits) {
        if (offset < 0L || offset > snapshot.total() || maxUnits <= 0) {
            throw new IllegalArgumentException("Profile-state cursor or batch size is invalid.");
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                CompanionPopulationLegacyEvidenceRepository.Batch loaded =
                        repository.loadBatch(offset, maxUnits, COVERAGE_KEY);
                long nextOffset = offset + loaded.scannedUnits();
                boolean complete = nextOffset >= snapshot.total();
                if (complete && !snapshot.equals(repository.snapshotDescriptor())) {
                    throw new IllegalStateException("Profile/snapshot evidence changed during reconciliation.");
                }
                return new Batch(loaded.evidence(), nextOffset, loaded.scannedUnits(), complete);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }
}
