package com.alechilles.alecstamework.companion.bonded;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Resolves bounded durable lease subsets for asynchronous lifecycle reconciliation. */
public final class BondedCompanionLifecycleLeaseResolver {
    private final ActiveLeaseSource leases;
    private final int limit;

    public BondedCompanionLifecycleLeaseResolver(
            @Nonnull ActiveLeaseSource leases, int limit
    ) {
        this.leases = Objects.requireNonNull(leases, "leases");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
    }

    @Nonnull
    public List<BondedCompanionProjectionValidator.LeaseExpectation> inWorld(
            @Nonnull String worldKey
    ) {
        return all().stream().filter(lease -> worldKey.equals(lease.worldKey())).toList();
    }

    @Nonnull
    public List<BondedCompanionProjectionValidator.LeaseExpectation> forOwner(
            @Nonnull UUID ownerUuid
    ) {
        return all().stream().filter(lease -> ownerUuid.equals(lease.ownerUuid())).toList();
    }

    @Nonnull
    public List<BondedCompanionProjectionValidator.LeaseExpectation> forOwnerInWorld(
            @Nonnull UUID ownerUuid, @Nonnull String worldKey
    ) {
        return forOwner(ownerUuid).stream()
                .filter(lease -> worldKey.equals(lease.worldKey())).toList();
    }

    private List<BondedCompanionProjectionValidator.LeaseExpectation> all() {
        return List.copyOf(Objects.requireNonNull(leases.activeLeases(limit), "activeLeases"));
    }

    @FunctionalInterface
    public interface ActiveLeaseSource {
        @Nonnull
        List<BondedCompanionProjectionValidator.LeaseExpectation> activeLeases(int limit);
    }
}
