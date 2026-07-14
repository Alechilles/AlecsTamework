package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationLegacyEvidenceRepository;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/** Bounded first-stage scan of canonical profiles and active dormant snapshot families. */
public final class SqliteProfileStateEvidenceSource implements CompanionPopulationEvidenceSource {
    public static final String COVERAGE_KEY = "profile-state:sqlite";

    private final CompanionPopulationLegacyEvidenceRepository.Snapshot snapshot;
    private final Descriptor descriptor;

    public SqliteProfileStateEvidenceSource(
            @Nonnull CompanionPopulationLegacyEvidenceRepository repository
    ) throws Exception {
        Objects.requireNonNull(repository, "repository");
        this.snapshot = repository.loadSnapshot(COVERAGE_KEY);
        CompanionPopulationLegacyEvidenceRepository.SnapshotDescriptor snapshotDescriptor =
                snapshot.descriptor();
        this.descriptor = new Descriptor(
                COVERAGE_KEY,
                CompanionPopulationCoverageRecord.Dimension.PROFILE_STATE,
                "tamework.sqlite",
                ReconciliationGeneration.forStrings(
                        COVERAGE_KEY,
                        java.util.List.of(snapshotDescriptor.generation())
                ),
                snapshotDescriptor.total()
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
        if (offset < 0L || offset > snapshot.descriptor().total() || maxUnits <= 0) {
            throw new IllegalArgumentException("Profile-state cursor or batch size is invalid.");
        }
        CompanionPopulationLegacyEvidenceRepository.Batch loaded = snapshot.batch(
                offset, maxUnits
        );
        long nextOffset = offset + loaded.scannedUnits();
        boolean complete = nextOffset >= snapshot.descriptor().total();
        return CompletableFuture.completedFuture(new Batch(
                loaded.evidence(), nextOffset, loaded.scannedUnits(), complete
        ));
    }
}
