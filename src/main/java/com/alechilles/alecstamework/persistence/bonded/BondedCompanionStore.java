package com.alechilles.alecstamework.persistence.bonded;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Adapter-neutral authority for durable bonded companion records. */
public interface BondedCompanionStore {
    /** Creates a new revision-zero stored profile under an atomic operation key. */
    @Nonnull BondedCompanionStoreResult<BondedCompanionRecord.Profile> createProfile(
            @Nonnull BondedCompanionOperation operation,
            @Nonnull BondedCompanionRecord.Profile profile);

    /** Lists profiles within one exact owner and roster scope. */
    @Nonnull List<BondedCompanionRecord.Profile> listProfiles(
            @Nonnull UUID ownerUuid, @Nonnull String rosterId);

    /** Finds one profile within one exact owner and roster scope. */
    @Nonnull Optional<BondedCompanionRecord.Profile> findProfile(
            @Nonnull UUID ownerUuid, @Nonnull String rosterId,
            @Nonnull String profileId);

    /** Acquires the sole live lease under an optimistic profile revision. */
    @Nonnull BondedCompanionStoreResult<BondedCompanionRecord.Lease> acquireLease(
            @Nonnull BondedCompanionOperation operation, long expectedRevision,
            @Nonnull BondedCompanionRecord.Lease lease);

    /** Lists active leases within one exact owner and roster scope. */
    @Nonnull List<BondedCompanionRecord.Lease> findActiveLeases(
            @Nonnull UUID ownerUuid, @Nonnull String rosterId);

    /** Revives a dead profile under an optimistic profile revision. */
    @Nonnull BondedCompanionStoreResult<BondedCompanionRecord.Profile> reviveProfile(
            @Nonnull BondedCompanionOperation operation, long expectedRevision,
            long updatedAtMs);

    /** Replaces a complete profile snapshot under an optimistic revision. */
    @Nonnull BondedCompanionStoreResult<BondedCompanionRecord.Profile> updateSnapshot(
            @Nonnull BondedCompanionOperation operation, long expectedRevision,
            @Nonnull BondedCompanionPayload snapshot, long updatedAtMs);

    /** Releases the exact lease and returns its active profile to stored state. */
    @Nonnull BondedCompanionStoreResult<BondedCompanionRecord.Profile> releaseLease(
            @Nonnull BondedCompanionOperation operation, long expectedRevision,
            @Nonnull String leaseToken, long updatedAtMs);

    /** Finds one namespaced extension within the exact owner roster scope. */
    @Nonnull Optional<BondedCompanionRecord.ExtensionData> findExtensionData(
            @Nonnull UUID ownerUuid, @Nonnull String rosterId,
            @Nonnull String profileId, @Nonnull String namespace);

    /** Creates or replaces extension data under compare-and-set semantics. */
    @Nonnull BondedCompanionStoreResult<BondedCompanionRecord.ExtensionData>
            compareAndSetExtensionData(
                    @Nonnull BondedCompanionOperation operation,
                    @Nonnull BondedCompanionRecord.ExtensionData extension,
                    long expectedRevision);

    /** Lists finite leases expired at the supplied signed world timestamp. */
    @Nonnull List<BondedCompanionRecord.Lease> findExpiredLeases(
            long nowMs, int limit);

    /** Enqueues one owner-scoped physical cleanup intent atomically. */
    @Nonnull BondedCompanionStoreResult<BondedCompanionRecord.Cleanup> enqueueCleanup(
            @Nonnull BondedCompanionOperation operation,
            @Nonnull BondedCompanionRecord.Cleanup cleanup);

    /** Lists bounded cleanup intents in one exact owner roster scope. */
    @Nonnull List<BondedCompanionRecord.Cleanup> listCleanup(
            @Nonnull UUID ownerUuid, @Nonnull String rosterId, int limit);

    /** Deletes at most the supplied number of retained cleanup intents. */
    int pruneCleanup(long nowMs, int limit);

    /** Deletes at most the supplied number of expired operation records. */
    int pruneOperations(long nowMs, int limit);
}
