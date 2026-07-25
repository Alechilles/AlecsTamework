package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import javax.annotation.Nonnull;

/**
 * Frozen live-world authority derived only from one durable timed transition.
 *
 * <p>Platform gateways receive these values instead of recapturing mutable
 * entity state. An exact receipt always belongs to the same operation,
 * request snapshot, alias, and world.</p>
 */
final class TimedSummonWorldAuthority {
    private TimedSummonWorldAuthority() {
    }

    static Start start(
            TimedSummonTransitionRequest request,
            OperationId operationId
    ) {
        if (!request.starting() || request.spawnPlacement() == null) {
            throw new IllegalArgumentException(
                    "Timed start authority requires a start request"
            );
        }
        return new Start(
                operationId,
                request.receiptKey(),
                request.beforeLease().profileId(),
                request.liveAlias(),
                request.worldKey(),
                request.snapshot(),
                request.spawnPlacement()
        );
    }

    static Store store(
            TimedSummonTransitionRequest request,
            OperationId operationId
    ) {
        if (request.starting()) {
            throw new IllegalArgumentException(
                    "Timed store authority requires a store request"
            );
        }
        return new Store(
                operationId,
                request.receiptKey(),
                request.beforeLease().profileId(),
                request.liveAlias(),
                request.worldKey(),
                request.snapshot()
        );
    }

    record Start(
            @Nonnull OperationId operationId,
            @Nonnull String receiptKey,
            @Nonnull ProfileId profileId,
            @Nonnull NpcAlias liveAlias,
            @Nonnull String worldKey,
            @Nonnull CompanionSnapshot snapshot,
            @Nonnull CompanionSpawnPlacement placement
    ) {
        Start {
            require(
                    operationId,
                    receiptKey,
                    profileId,
                    liveAlias,
                    worldKey,
                    snapshot
            );
            if (placement == null
                    || !worldKey.equals(placement.worldKey())) {
                throw new IllegalArgumentException(
                        "Timed start placement authority is inconsistent"
                );
            }
        }
    }

    record Store(
            @Nonnull OperationId operationId,
            @Nonnull String receiptKey,
            @Nonnull ProfileId profileId,
            @Nonnull NpcAlias liveAlias,
            @Nonnull String worldKey,
            @Nonnull CompanionSnapshot snapshot
    ) {
        Store {
            require(
                    operationId,
                    receiptKey,
                    profileId,
                    liveAlias,
                    worldKey,
                    snapshot
            );
        }
    }

    private static void require(
            OperationId operationId,
            String receiptKey,
            ProfileId profileId,
            NpcAlias liveAlias,
            String worldKey,
            CompanionSnapshot snapshot
    ) {
        if (operationId == null || profileId == null || liveAlias == null
                || snapshot == null
                || receiptKey == null || receiptKey.isBlank()
                || worldKey == null || worldKey.isBlank()
                || !profileId.equals(snapshot.profileId())) {
            throw new IllegalArgumentException(
                    "Complete timed world authority is required"
            );
        }
    }
}
