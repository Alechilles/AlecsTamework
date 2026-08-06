package com.alechilles.alecstamework.npc.actions;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Prevents a parent from participating in overlapping delayed breeding pairs.
 *
 * <p>Admissions are scoped by world store identity and remain held until the
 * scheduled birth either runs or is canceled. Calls may arrive from the world
 * thread and delayed executor cancellation paths, so acquisition and release
 * share one lock.</p>
 */
public final class BreedingPairAdmissionRegistry {
    private final Map<Object, Set<UUID>> activeParentsByScope =
            new IdentityHashMap<>();

    /** Creates an empty plugin-lifetime registry. */
    public BreedingPairAdmissionRegistry() {
    }

    @Nullable
    Lease tryAcquire(Object scope, UUID parentA, UUID parentB) {
        if (scope == null || parentA == null || parentB == null
                || parentA.equals(parentB)) {
            return null;
        }
        synchronized (activeParentsByScope) {
            Set<UUID> activeParents = activeParentsByScope.get(scope);
            if (activeParents != null
                    && (activeParents.contains(parentA)
                    || activeParents.contains(parentB))) {
                return null;
            }
            if (activeParents == null) {
                activeParents = new HashSet<>();
                activeParentsByScope.put(scope, activeParents);
            }
            activeParents.add(parentA);
            activeParents.add(parentB);
            return new Lease(this, scope, parentA, parentB);
        }
    }

    private void release(Lease lease) {
        synchronized (activeParentsByScope) {
            if (lease.closed) {
                return;
            }
            lease.closed = true;
            Set<UUID> activeParents = activeParentsByScope.get(lease.scope);
            if (activeParents == null) {
                return;
            }
            activeParents.remove(lease.parentA);
            activeParents.remove(lease.parentB);
            if (activeParents.isEmpty()) {
                activeParentsByScope.remove(lease.scope);
            }
        }
    }

    /** Admission handle released by either birth completion or cancellation. */
    static final class Lease implements AutoCloseable {
        private final BreedingPairAdmissionRegistry registry;
        private final Object scope;
        private final UUID parentA;
        private final UUID parentB;
        private boolean closed;

        private Lease(
                BreedingPairAdmissionRegistry registry,
                Object scope,
                UUID parentA,
                UUID parentB
        ) {
            this.registry = registry;
            this.scope = scope;
            this.parentA = parentA;
            this.parentB = parentB;
        }

        @Override
        public void close() {
            registry.release(this);
        }
    }
}
