package com.alechilles.alecstamework.items;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Serializes managed-coop lifecycle pipelines that publish the shared composite index.
 *
 * <p>A lease spans persistence, paired-index refresh, and any resulting source retirement or
 * live projection. This keeps another coop from revoking composite trust while the first pipeline
 * is consuming its committed epoch.</p>
 */
final class ManagedCoopLifecycleMutationGate {
    private final AtomicReference<Lease> active = new AtomicReference<>();
    private final BooleanSupplier releaseAuthorityReady;

    ManagedCoopLifecycleMutationGate() {
        this(() -> true);
    }

    ManagedCoopLifecycleMutationGate(@Nonnull BooleanSupplier releaseAuthorityReady) {
        this.releaseAuthorityReady = Objects.requireNonNull(
                releaseAuthorityReady, "releaseAuthorityReady");
    }

    @Nullable
    Lease tryAcquire(@Nonnull String owner) {
        Lease proposed = new Lease(requireOwner(owner));
        return active.compareAndSet(null, proposed) ? proposed : null;
    }

    void release(@Nonnull Lease lease) {
        active.compareAndSet(Objects.requireNonNull(lease, "lease"), null);
    }

    boolean occupied() {
        return active.get() != null;
    }

    boolean releaseReady() {
        try {
            return releaseAuthorityReady.getAsBoolean();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String requireOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("lifecycle gate owner must not be blank");
        }
        return owner.trim();
    }

    /** Opaque ownership token; only the exact lease may reopen the gate. */
    static final class Lease {
        private final String owner;

        private Lease(String owner) {
            this.owner = owner;
        }

        String owner() {
            return owner;
        }
    }
}
