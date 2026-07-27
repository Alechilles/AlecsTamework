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
 * Serializes one actor's escrow mutations across short-lived action contexts.
 *
 * <p>Panel actions intentionally create fresh inventory adapters. A shared,
 * bounded in-flight registry is therefore required to preserve the durable
 * save fence while asynchronous reservation, settlement, or recovery advances.
 * Entries are removed as soon as their operation completes and never carry
 * game state.</p>
 */
final class BondedCompanionEscrowSettlementFlights {
    private static final int MAX_PENDING_PER_ACTOR = 64;
    private static final BondedCompanionEscrowSettlementFlights SHARED =
            new BondedCompanionEscrowSettlementFlights();
    private final Map<ActorEscrowKey, ActorQueue> queues = new HashMap<>();

    static BondedCompanionEscrowSettlementFlights shared() {
        return SHARED;
    }

    <T> CompletionStage<T> coordinate(
            ComponentType<EntityStore,
                    TameworkBondedReviveEscrowComponent> escrowType,
            UUID ownerUuid,
            String operationId,
            Action actionType,
            boolean shareDuplicate,
            Supplier<CompletionStage<T>> action,
            Supplier<T> unavailable
    ) {
        Objects.requireNonNull(escrowType, "escrowType");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(unavailable, "unavailable");
        ActorEscrowKey key = new ActorEscrowKey(escrowType, ownerUuid);
        FlightKey flightKey = new FlightKey(operationId, actionType);
        CompletableFuture<T> result = new CompletableFuture<>();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Void> predecessor;
        ActorQueue queue;
        synchronized (queues) {
            queue = queues.computeIfAbsent(key, ignored -> new ActorQueue());
            if (shareDuplicate) {
                Flight<?> active = queue.shared.get(flightKey);
                if (active != null) return cast(active.result());
            }
            if (queue.pending >= MAX_PENDING_PER_ACTOR) {
                return CompletableFuture.completedFuture(
                        fallback(unavailable));
            }
            if (shareDuplicate) {
                queue.shared.put(flightKey, new Flight<>(result));
            }
            predecessor = queue.tail;
            queue.tail = gate;
            queue.pending++;
        }
        predecessor.whenComplete((ignored, priorFailure) -> run(
                key, queue, flightKey, shareDuplicate, gate,
                result, action, unavailable));
        return result;
    }

    private <T> void run(
            ActorEscrowKey actorKey,
            ActorQueue queue,
            FlightKey flightKey,
            boolean shared,
            CompletableFuture<Void> gate,
            CompletableFuture<T> result,
            Supplier<CompletionStage<T>> action,
            Supplier<T> unavailable
    ) {
        CompletionStage<T> execution;
        try {
            execution = action.get();
        } catch (RuntimeException | LinkageError failure) {
            finish(actorKey, queue, flightKey, shared, gate,
                    result, fallback(unavailable));
            return;
        }
        if (execution == null) {
            finish(actorKey, queue, flightKey, shared, gate,
                    result, fallback(unavailable));
            return;
        }
        execution.whenComplete((value, failure) -> finish(
                actorKey, queue, flightKey, shared, gate, result,
                failure == null ? value : fallback(unavailable)));
    }

    private <T> void finish(
            ActorEscrowKey actorKey,
            ActorQueue queue,
            FlightKey flightKey,
            boolean shared,
            CompletableFuture<Void> gate,
            CompletableFuture<T> result,
            T value
    ) {
        // Complete callers first. A callback that starts the next operation
        // will enqueue behind this gate before it is released below.
        result.complete(value);
        synchronized (queues) {
            if (shared) {
                Flight<?> active = queue.shared.get(flightKey);
                if (active != null && active.result() == result) {
                    queue.shared.remove(flightKey);
                }
            }
            queue.pending--;
        }
        gate.complete(null);
        synchronized (queues) {
            if (queue.pending == 0 && queue.tail == gate) {
                queues.remove(actorKey, queue);
            }
        }
    }

    private <T> T fallback(Supplier<T> unavailable) {
        try {
            return unavailable.get();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> CompletionStage<T> cast(
            CompletableFuture<?> result) {
        return (CompletionStage<T>) result;
    }

    enum Action {
        PREPARE,
        READ,
        COMMIT,
        REFUND
    }

    private static final class ActorQueue {
        private CompletableFuture<Void> tail =
                CompletableFuture.completedFuture(null);
        private final Map<FlightKey, Flight<?>> shared = new HashMap<>();
        private int pending;
    }

    private record FlightKey(String operationId, Action action) {
    }

    private record Flight<T>(CompletableFuture<T> result) {
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
