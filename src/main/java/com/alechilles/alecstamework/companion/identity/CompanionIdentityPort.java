package com.alechilles.alecstamework.companion.identity;

import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Transaction-local authority for stable profiles, runtime aliases, and tool links.
 *
 * <p>Implementations must not open connections, commit transactions, or publish caches.</p>
 */
public interface CompanionIdentityPort {
    @Nonnull
    Optional<CompanionIdentity> findProfile(@Nonnull ProfileId profileId);

    @Nonnull
    PersistenceMutationResult<CompanionIdentity> createProfile(@Nonnull CompanionIdentity profile);

    @Nonnull
    PersistenceMutationResult<CompanionIdentity> updateProfile(
            @Nonnull CompanionIdentity next,
            long expectedRevision
    );

    @Nonnull
    Optional<CompanionAlias> resolveAlias(@Nonnull NpcAlias alias);

    @Nonnull
    Optional<CompanionAlias> findCurrentAlias(@Nonnull ProfileId profileId);

    @Nonnull
    PersistenceMutationResult<CompanionAlias> leaseAlias(
            @Nonnull ProfileId profileId,
            @Nonnull NpcAlias alias,
            @Nonnull OperationId operationId,
            long mappedAtMs
    );

    @Nonnull
    PersistenceMutationResult<CompanionAlias> promoteAlias(
            @Nonnull NpcAlias alias,
            @Nonnull OperationId operationId,
            long promotedAtMs
    );

    @Nonnull
    PersistenceMutationResult<CompanionAlias> retireAlias(
            @Nonnull NpcAlias alias,
            long retiredAtMs
    );

    @Nonnull
    PersistenceMutationResult<CompanionToolLink> linkTool(@Nonnull CompanionToolLink link);

    @Nonnull
    List<CompanionToolLink> findToolLinks(@Nonnull ProfileId profileId);
}
