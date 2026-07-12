package com.alechilles.alecstamework.ownership;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns provisional identity leases and canonical remaps around durable owner-mutation admission.
 * A successful preparation has already inserted the baseline profile/alias in SQLite, so its
 * provisional cache entry is promoted immediately and is never released by later cancellation.
 */
final class OwnerMutationIdentityLifecycle {
    private final CompanionIdentityResolver identityResolver;
    private final OwnerMutationSnapshotResolver snapshotResolver;
    private final OwnerMutationTerminality terminality;

    OwnerMutationIdentityLifecycle(
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull OwnerMutationSnapshotResolver snapshotResolver,
            @Nonnull OwnerMutationTerminality terminality
    ) {
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.snapshotResolver = Objects.requireNonNull(snapshotResolver, "snapshotResolver");
        this.terminality = Objects.requireNonNull(terminality, "terminality");
    }

    void releaseBeforeDurablePreparation(@Nonnull OwnerMutationSnapshotResolver.Snapshot snapshot) {
        try {
            if (!snapshotResolver.releaseProvisional(snapshot)) {
                terminality.degrade("owner_mutation_provisional_identity_release_failed");
            }
        } catch (RuntimeException | LinkageError failure) {
            terminality.degrade("owner_mutation_provisional_identity_release_failed");
        }
    }

    /** Promotes the cache lease once the durable preparation result is known to be allowed. */
    boolean promotePrepared(@Nonnull OwnerMutationSnapshotResolver.Snapshot snapshot) {
        if (!snapshot.provisionalIdentity()) {
            return true;
        }
        try {
            identityResolver.markDurable(snapshot.profileId(), snapshot.npcUuid());
            return true;
        } catch (RuntimeException | LinkageError failure) {
            terminality.degradeCapability("owner_mutation_prepared_identity_promotion_failed");
            return false;
        }
    }

    @Nonnull
    CompletableFuture<Boolean> cancelPrepared(
            @Nonnull PreparedCompanionPopulationAdmission prepared,
            @Nonnull String reason
    ) {
        return terminality.cancel(prepared, reason);
    }

    boolean remapLive(@Nonnull String profileId,
                      @Nonnull UUID baselineNpcUuid,
                      @Nonnull UUID npcUuid) {
        try {
            identityResolver.remap(profileId, baselineNpcUuid, npcUuid);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            terminality.degrade("owner_mutation_live_identity_remap_failed");
            return false;
        }
    }

    boolean markLiveDurableIfCommitted(
            boolean identityMapped,
            @Nullable CompanionPopulationCommitResult commit,
            @Nonnull String profileId,
            @Nonnull UUID npcUuid
    ) {
        if (!identityMapped || commit == null || commit.ownerCommit() == null
                || !commit.ownerCommit().committed()) {
            return identityMapped;
        }
        try {
            identityResolver.markDurable(profileId, npcUuid);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            terminality.degrade("owner_mutation_identity_cache_degraded");
            return false;
        }
    }
}
