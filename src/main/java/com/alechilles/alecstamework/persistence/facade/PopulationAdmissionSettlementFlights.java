package com.alechilles.alecstamework.persistence.facade;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Shares one in-flight terminal result across concurrent settlement callers. */
final class PopulationAdmissionSettlementFlights<T> {
    private static final int MAX_IN_FLIGHT = 1_024;
    private final ConcurrentMap<UUID, CompletableFuture<T>> values =
            new ConcurrentHashMap<>();

    Lease<T> acquire(UUID operationId) {
        synchronized (values) {
            CompletableFuture<T> existing = values.get(operationId);
            if (existing != null) {
                return new Lease<>(existing, false, false);
            }
            if (values.size() >= MAX_IN_FLIGHT) {
                return Lease.rejected();
            }
            CompletableFuture<T> created = new CompletableFuture<>();
            values.put(operationId, created);
            created.whenComplete((ignored, failure) ->
                    values.remove(operationId, created));
            return new Lease<>(created, true, false);
        }
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

    record Lease<T>(CompletableFuture<T> future, boolean owner, boolean saturated) {
        static <T> Lease<T> rejected() {
            return new Lease<>(CompletableFuture.completedFuture(null), false, true);
        }
    }
}
