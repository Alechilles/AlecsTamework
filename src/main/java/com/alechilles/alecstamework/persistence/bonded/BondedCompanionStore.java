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

    /** Creates a profile while atomically enforcing its exact family limit. */
    @Nonnull
    BondedCompanionStoreResult<BondedCompanionRecord.Profile>
            createProfile(
                    @Nonnull BondedCompanionOperation operation,
                    @Nonnull BondedCompanionRecord.Profile profile,
                    int maximumOwned
            );

    /** Lists profiles within one exact owner and roster scope. */
    @Nonnull List<BondedCompanionRecord.Profile> listProfiles(
            @Nonnull UUID ownerUuid, @Nonnull String rosterId);

    /** Finds one profile within one exact owner and roster scope. */
    @Nonnull Optional<BondedCompanionRecord.Profile> findProfile(
            @Nonnull UUID ownerUuid, @Nonnull String rosterId,
            @Nonnull String profileId);

    /** Finds one owned profile by its globally stable profile ID. */
    @Nonnull Optional<BondedCompanionRecord.Profile> findProfile(
            @Nonnull UUID ownerUuid, @Nonnull String profileId);

    /**
     * Probes an existing profile-operation result without claiming or mutating.
     *
     * <p>This is the canonical replay fence used before a payment is reserved.
     * Adapter-neutral test stores may retain the absent default.</p>
     */
    @Nonnull
    default Optional<BondedCompanionStoreResult<BondedCompanionRecord.Profile>>
            findProfileOperationByIdentity(
                    @Nonnull BondedCompanionOperationProbe operation) {
        return Optional.empty();
    }

    /** Probes a profile mutation using its complete request hash and scope. */
    @Nonnull
    default Optional<BondedCompanionStoreResult<BondedCompanionRecord.Profile>>
            findProfileOperationByExactRequest(
                    @Nonnull BondedCompanionOperation operation) {
        return Optional.empty();
    }

    /** Returns a terminal revive operation to bounded retention after payment. */
    default boolean markProfileOperationPaymentSettled(
            @Nonnull BondedCompanionOperationProbe operation,
            boolean terminalApplied,
            long retainedUntilMs) {
        return false;
    }

    /** Lists bounded terminal revive rows retained for player-payment repair. */
    @Nonnull
    default List<BondedCompanionOperationProbe>
            listAwaitingProfilePaymentSettlements(
                    @Nonnull UUID ownerUuid, int limit) {
        return List.of();
    }

    /** Lists complete historical groups before consulting flattened receipts. */
    @Nonnull
    default List<BondedCompanionLegacyPaymentSettlementGroup>
            listAwaitingLegacyPaymentSettlementGroups(
                    @Nonnull UUID ownerUuid, int limit) {
        return List.of();
    }

    /** Moves every pinned member of one ambiguous historical group to GC. */
    default int quarantineLegacyPaymentSettlementGroup(
            @Nonnull UUID ownerUuid,
            @Nonnull String operationId,
            long retainedUntilMs) {
        return 0;
    }

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

    /** Lists current namespaced extensions for one exact owned profile. */
    @Nonnull default List<BondedCompanionRecord.ExtensionData> listExtensionData(
            @Nonnull UUID ownerUuid, @Nonnull String rosterId,
            @Nonnull String profileId) {
        return List.of();
    }

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

    /** Returns aggregate-only counts without exposing any durable identity. */
    @Nonnull BondedCompanionStoreDiagnostics diagnostics();
}
