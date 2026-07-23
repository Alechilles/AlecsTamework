package com.alechilles.alecstamework.companion.snapshot;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Transaction-local authority for immutable versioned snapshot evidence.
 *
 * <p>Implementations must not open connections, commit transactions, or infer lifecycle state.</p>
 */
public interface CompanionSnapshotPort {
    @Nonnull
    Optional<CompanionSnapshot> findById(@Nonnull SnapshotId snapshotId);

    @Nonnull
    Optional<CompanionSnapshot> findCurrent(
            @Nonnull ProfileId profileId,
            @Nonnull SnapshotKind kind
    );

    /** Lists every current snapshot for a profile in stable kind order. */
    @Nonnull
    List<CompanionSnapshot> findCurrentByProfile(@Nonnull ProfileId profileId);

    @Nonnull
    List<CompanionSnapshot> findHistory(
            @Nonnull ProfileId profileId,
            @Nonnull SnapshotKind kind
    );

    @Nonnull
    PersistenceMutationResult<CompanionSnapshot> replaceCurrent(
            @Nonnull CompanionSnapshot snapshot
    );
}
