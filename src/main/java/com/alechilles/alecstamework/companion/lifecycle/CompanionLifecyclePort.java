package com.alechilles.alecstamework.companion.lifecycle;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Transaction-local authority for the sole durable companion lifecycle row.
 *
 * <p>Implementations must not open connections, commit transactions, or mutate projections.</p>
 */
public interface CompanionLifecyclePort {
    @Nonnull
    List<CompanionLifecycle> findAll();

    @Nonnull
    Optional<CompanionLifecycle> findByProfile(@Nonnull ProfileId profileId);

    @Nonnull
    List<CompanionLifecycle> findByOwner(@Nonnull OwnerId ownerId);

    @Nonnull
    List<CompanionLifecycle> findByLocation(@Nonnull LifecycleLocation location);

    @Nonnull
    PersistenceMutationResult<CompanionLifecycle> create(@Nonnull CompanionLifecycle initial);

    @Nonnull
    PersistenceMutationResult<CompanionLifecycle> transition(
            @Nonnull LifecycleTransition transition
    );
}
