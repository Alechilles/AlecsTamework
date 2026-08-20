package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Process-local admission single-flight keyed by durable operation identity. */
final class SqliteLifecycleAdmissionSingleFlight {
    private final ConcurrentMap<Key, CompletableFuture<?>> flights =
            new ConcurrentHashMap<>();

    @Nonnull
    <T> CompletionStage<T> submit(
            @Nonnull OperationKind kind,
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull String payloadJson,
            @Nonnull Supplier<? extends CompletionStage<T>> work
    ) {
        if (kind == null || operationId == null || idempotencyKey == null
                || payloadJson == null || work == null) {
            throw new IllegalArgumentException("Complete admission flight key and work are required");
        }
        Key key = new Key(kind, operationId, idempotencyKey, payloadJson);
        CompletableFuture<T> shared = new CompletableFuture<>();
        CompletableFuture<?> existing = flights.putIfAbsent(key, shared);
        if (existing != null) {
            @SuppressWarnings("unchecked")
            CompletableFuture<T> typed = (CompletableFuture<T>) existing;
            return typed;
        }
        try {
            CompletionStage<T> stage = work.get();
            if (stage == null) {
                throw new IllegalStateException("Admission flight returned no completion");
            }
            stage.whenComplete((value, failure) -> {
                if (failure == null) {
                    shared.complete(value);
                } else {
                    shared.completeExceptionally(failure);
                }
                flights.remove(key, shared);
            });
        } catch (Throwable failure) {
            shared.completeExceptionally(failure);
            flights.remove(key, shared);
        }
        return shared;
    }

    private record Key(
            OperationKind kind,
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            String payloadJson
    ) {
    }
}
