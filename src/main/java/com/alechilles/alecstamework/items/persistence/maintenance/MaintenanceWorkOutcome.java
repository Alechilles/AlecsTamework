package com.alechilles.alecstamework.items.persistence.maintenance;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Typed durable, deferred, or failed result returned by a maintenance handler. */
public sealed interface MaintenanceWorkOutcome<V>
        permits MaintenanceWorkOutcome.Durable,
        MaintenanceWorkOutcome.Deferred,
        MaintenanceWorkOutcome.Failed {
    /** Returns a durable successful result. */
    @Nonnull
    static <V> Durable<V> durable() {
        return new Durable<>();
    }

    /** Returns a result that asks the caller-owned scheduler to resume later. */
    @Nonnull
    static <V> Deferred<V> deferred() {
        return new Deferred<>();
    }

    /** Returns a terminal failure result. */
    @Nonnull
    static <V> Failed<V> failed(@Nonnull Throwable failure) {
        return new Failed<>(failure);
    }

    /** Successful durable handler result. */
    record Durable<V>() implements MaintenanceWorkOutcome<V> {
    }

    /** Caller-scheduled pre-write deferral result. */
    record Deferred<V>() implements MaintenanceWorkOutcome<V> {
    }

    /** Terminal handler failure result. */
    record Failed<V>(@Nonnull Throwable failure)
            implements MaintenanceWorkOutcome<V> {
        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
