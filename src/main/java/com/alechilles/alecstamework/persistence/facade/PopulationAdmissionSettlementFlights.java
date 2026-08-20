package com.alechilles.alecstamework.persistence.facade;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Shares one in-flight terminal result across concurrent settlement callers. */
final class PopulationAdmissionSettlementFlights<T> {
    private final ConcurrentMap<UUID, CompletableFuture<T>> values =
            new ConcurrentHashMap<>();

    Lease<T> acquire(UUID operationId) {
        CompletableFuture<T> created = new CompletableFuture<>();
        CompletableFuture<T> existing = values.putIfAbsent(operationId, created);
        return existing == null ? new Lease<>(created, true)
                : new Lease<>(existing, false);
    }

    CompletableFuture<T> get(UUID operationId) {
        return values.get(operationId);
    }

    void complete(UUID operationId, CompletableFuture<T> future, T value) {
        future.complete(value);
        values.remove(operationId, future);
    }

    void remove(UUID operationId, CompletableFuture<T> future) {
        values.remove(operationId, future);
    }

    record Lease<T>(CompletableFuture<T> future, boolean owner) {
    }
}
