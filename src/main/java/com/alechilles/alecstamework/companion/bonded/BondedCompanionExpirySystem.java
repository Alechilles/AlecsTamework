package com.alechilles.alecstamework.companion.bonded;

import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Bounded signed-world-time expiry sweep for bonded leases. */
public final class BondedCompanionExpirySystem {
    private final BondedCompanionWorldLifecycleObserver observer;
    private final ExpiredLeaseSource leases;
    private final int limit;

    public BondedCompanionExpirySystem(
            @Nonnull BondedCompanionWorldLifecycleObserver observer,
            @Nonnull ExpiredLeaseSource leases,
            int limit
    ) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.leases = Objects.requireNonNull(leases, "leases");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
    }

    /** Reconciles at most the configured number of finite expired leases. */
    public int tick(long nowMs) {
        List<BondedCompanionProjectionValidator.LeaseExpectation> candidates =
                Objects.requireNonNull(leases.findExpired(nowMs, limit),
                        "expired leases");
        int expired = 0;
        for (var lease : candidates) {
            if (lease != null && isExpired(lease.expiresAtMs(), nowMs)) {
                observer.onLeaseExpired(lease, nowMs);
                expired++;
                if (expired == limit) {
                    break;
                }
            }
        }
        return expired;
    }

    /** Zero alone means unlimited; negative finite timestamps remain valid. */
    public static boolean isExpired(long expiresAtMs, long nowMs) {
        return expiresAtMs != 0L && expiresAtMs <= nowMs;
    }

    @FunctionalInterface
    public interface ExpiredLeaseSource {
        @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation>
                findExpired(long nowMs, int limit);
    }
}
