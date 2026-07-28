package com.alechilles.alecstamework.companion.extension;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Transaction-local authority for integration-owned profile extension values.
 *
 * <p>Implementations do not open connections, commit, or mutate canonical lifecycle state.</p>
 */
public interface ProfileExtensionDataPort {
    @Nonnull
    PersistenceReadResult<ProfileExtensionData> find(@Nonnull ProfileExtensionKey key);

    @Nonnull
    PersistenceReadResult<List<ProfileExtensionData>> findNamespace(
            @Nonnull ProfileId profileId,
            @Nonnull String namespace
    );

    /**
     * Creates at expected revision zero or advances an existing value exactly once.
     */
    @Nonnull
    PersistenceMutationResult<ProfileExtensionData> put(
            @Nonnull ProfileExtensionData next,
            long expectedRevision
    );

    /** Advances the exact expected positive revision to a durable deletion tombstone. */
    @Nonnull
    PersistenceMutationResult<ProfileExtensionData> delete(
            @Nonnull ProfileExtensionKey key,
            long expectedRevision,
            long deletedAtMs
    );
}
