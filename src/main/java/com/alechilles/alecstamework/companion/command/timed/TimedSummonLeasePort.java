package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Connection-bound canonical port for one timed detail row per profile. */
public interface TimedSummonLeasePort {
    @Nonnull
    Optional<TimedSummonLease> find(@Nonnull ProfileId profileId);

    @Nonnull
    List<TimedSummonLease> findAll();

    @Nonnull
    PersistenceMutationResult<TimedSummonLeaseChange> replace(
            @Nullable Long expectedRevision,
            @Nonnull TimedSummonLease target
    );
}

