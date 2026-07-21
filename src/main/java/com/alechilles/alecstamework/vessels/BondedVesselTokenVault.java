package com.alechilles.alecstamework.vessels;

import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.api.BondedVesselTransition;
import com.alechilles.alecstamework.api.BondedVesselTransitionContext;
import com.alechilles.alecstamework.api.BondedVesselTransitionToken;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Process-local, random-secret token registry. No secret is persisted in the operation journal. */
final class BondedVesselTokenVault {
    private final ConcurrentMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom;
    private final LongSupplier monotonicNanos;
    private final long lifetimeNanos;

    BondedVesselTokenVault(@Nonnull SecureRandom secureRandom,
                           @Nonnull LongSupplier monotonicNanos,
                           long lifetimeNanos) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        if (lifetimeNanos <= 0L) {
            throw new IllegalArgumentException("lifetimeNanos must be positive.");
        }
        this.lifetimeNanos = lifetimeNanos;
    }

    @Nonnull
    BondedVesselTransitionToken issue(@Nonnull BondedVesselOperationRecord operation,
                                      @Nonnull BondedVesselTransitionContext context) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(context, "context");
        long now = monotonicNanos.getAsLong();
        long expiresAt = saturatedAdd(now, lifetimeNanos);
        UUID reservationId;
        do {
            reservationId = randomUuid();
        } while (entries.containsKey(reservationId));
        BondedVesselTransitionToken token = new BondedVesselTransitionToken(
                UUID.fromString(operation.operationId()),
                reservationId,
                UUID.fromString(operation.bindingId()),
                toTransition(operation.action()),
                toState(operation.priorLifecycleState()),
                toState(operation.targetLifecycleState()),
                requireValue(operation.sourceFingerprint(), "sourceFingerprint"),
                requireValue(operation.targetItemId(), "targetItemId"),
                requireValue(operation.replacementFingerprint(), "replacementFingerprint"),
                context.destination(),
                operation.priorGeneration(),
                operation.candidateGeneration(),
                operation.expectedProfileRevision(),
                expiresAt
        );
        entries.put(reservationId, new Entry(token, context, false));
        return token;
    }

    @Nullable
    Entry authenticate(@Nonnull BondedVesselTransitionToken supplied) {
        Objects.requireNonNull(supplied, "supplied");
        Entry entry = entries.get(supplied.reservationId());
        if (entry == null || !entry.token().equals(supplied)) {
            return null;
        }
        if (monotonicNanos.getAsLong() >= supplied.expiresAtMonotonicNanos()) {
            entries.remove(supplied.reservationId(), entry);
            return null;
        }
        return entry;
    }

    @Nullable
    Entry claim(@Nonnull BondedVesselTransitionToken supplied) {
        Entry authenticated = authenticate(supplied);
        if (authenticated == null) {
            return null;
        }
        Entry claimed = authenticated.claimed()
                ? authenticated : new Entry(authenticated.token(), authenticated.context(), true);
        entries.replace(supplied.reservationId(), authenticated, claimed);
        return authenticate(supplied);
    }

    void revoke(@Nonnull BondedVesselTransitionToken token) {
        entries.remove(token.reservationId());
    }

    void revokeOperation(@Nonnull UUID operationId) {
        entries.entrySet().removeIf(entry -> entry.getValue().token().operationId().equals(operationId));
    }

    private UUID randomUuid() {
        return new UUID(secureRandom.nextLong(), secureRandom.nextLong());
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static String requireValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " is missing from prepared operation.");
        }
        return value;
    }

    private static BondedVesselTransition toTransition(BondedVesselOperationRecord.Action action) {
        return switch (action) {
            case SUMMON -> BondedVesselTransition.SUMMON;
            case STORE -> BondedVesselTransition.STORE;
            case REPAIR -> BondedVesselTransition.REPAIR_DEAD_TO_STORED;
            case RELEASE -> BondedVesselTransition.RELEASE;
            default -> throw new IllegalArgumentException("Unsupported public vessel action: " + action);
        };
    }

    private static BondedVesselState toState(
            com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord.LifecycleState state
    ) {
        return BondedVesselState.valueOf(state.name());
    }

    record Entry(@Nonnull BondedVesselTransitionToken token,
                 @Nonnull BondedVesselTransitionContext context,
                 boolean claimed) {
    }
}
