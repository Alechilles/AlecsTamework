package com.alechilles.alecstamework.companion.identity;

import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import java.util.List;
import javax.annotation.Nonnull;

/** Transaction-local authority for the set of tool identities linked to one profile. */
public interface CompanionToolLinkPort {
    @Nonnull
    PersistenceMutationResult<CompanionToolLink> link(@Nonnull CompanionToolLink link);

    /**
     * Atomically replaces one profile's complete tool-link set.
     *
     * <p>Existing matching links retain their original creation time.</p>
     */
    @Nonnull
    PersistenceMutationResult<List<CompanionToolLink>> replace(
            @Nonnull ProfileId profileId,
            @Nonnull List<CompanionToolLink> links
    );

    @Nonnull
    List<CompanionToolLink> findByProfile(@Nonnull ProfileId profileId);
}
