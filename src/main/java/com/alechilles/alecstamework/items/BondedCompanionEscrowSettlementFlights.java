package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.components
        .TameworkBondedReviveEscrowComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Serializes one actor's escrow settlement across short-lived action contexts.
 *
 * <p>Panel actions intentionally create fresh inventory adapters. A shared,
 * bounded in-flight registry is therefore required to preserve the durable
 * save fence while one asynchronous refund advances slot by slot. Entries are
 * removed as soon as their operation completes and never carry game state.</p>
 */
final class BondedCompanionEscrowSettlementFlights {
    private static final BondedCompanionEscrowSettlementFlights SHARED =
            new BondedCompanionEscrowSettlementFlights();
    private final Map<ActorEscrowKey, Flight> flights = new HashMap<>();

    static BondedCompanionEscrowSettlementFlights shared() {
        return SHARED;
    }

    CompletionStage<Boolean> coordinate(
            ComponentType<EntityStore,
                    TameworkBondedReviveEscrowComponent> escrowType,
            UUID ownerUuid,
            String operationId,
            Supplier<CompletionStage<Boolean>> action
    ) {
        Objects.requireNonNull(escrowType, "escrowType");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(action, "action");
        ActorEscrowKey key = new ActorEscrowKey(escrowType, ownerUuid);
        CompletableFuture<Boolean> result;
        synchronized (flights) {
            Flight active = flights.get(key);
            if (active != null) {
                return operationId.equals(active.operationId())
                        ? active.result() : completed(false);
            }
            result = new CompletableFuture<>();
            flights.put(key, new Flight(operationId, result));
        }
        CompletionStage<Boolean> execution;
        try {
            execution = action.get();
        } catch (RuntimeException | LinkageError failure) {
            finish(key, result, false);
            return result;
        }
        if (execution == null) {
            finish(key, result, false);
            return result;
        }
        execution.whenComplete((value, failure) -> finish(
                key, result,
                failure == null && Boolean.TRUE.equals(value)));
        return result;
    }

    private void finish(
            ActorEscrowKey key,
            CompletableFuture<Boolean> result,
            boolean value
    ) {
        synchronized (flights) {
            Flight active = flights.get(key);
            if (active != null && active.result() == result) {
                flights.remove(key);
            }
        }
        result.complete(value);
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private record Flight(
            String operationId,
            CompletableFuture<Boolean> result
    ) {
    }

    private static final class ActorEscrowKey {
        private final ComponentType<EntityStore,
                TameworkBondedReviveEscrowComponent> escrowType;
        private final UUID ownerUuid;

        private ActorEscrowKey(
                ComponentType<EntityStore,
                        TameworkBondedReviveEscrowComponent> escrowType,
                UUID ownerUuid
        ) {
            this.escrowType = escrowType;
            this.ownerUuid = ownerUuid;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ActorEscrowKey key
                    && escrowType == key.escrowType
                    && ownerUuid.equals(key.ownerUuid);
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(escrowType);
            return 31 * result + ownerUuid.hashCode();
        }
    }
}
