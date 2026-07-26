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
}
