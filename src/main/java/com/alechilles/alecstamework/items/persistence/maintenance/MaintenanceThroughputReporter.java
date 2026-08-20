package com.alechilles.alecstamework.items.persistence.maintenance;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * Samples maintenance activity without scanning every retained key for every
 * submission or completion.
 */
public final class MaintenanceThroughputReporter<K, V> {
    private final LatestWorkCoordinator<K, V> coordinator;
    private final Consumer<MaintenanceMetricsSnapshot> observer;
    private final AtomicLong observations = new AtomicLong();

    public MaintenanceThroughputReporter(
            @Nonnull LatestWorkCoordinator<K, V> coordinator,
            @Nonnull Consumer<MaintenanceMetricsSnapshot> observer
    ) {
        this.coordinator = Objects.requireNonNull(
                coordinator, "coordinator"
        );
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /** Records the first and each power-of-two admission while work remains. */
    public void sampleAdmission() {
        long observation = observations.incrementAndGet();
        if ((observation & (observation - 1L)) == 0L) {
            record();
        }
    }

    /** Records exact final counters when the coordinator retains no work. */
    public void recordIfIdle() {
        if (!coordinator.hasRetainedWork()) {
            observations.set(0L);
            record();
        }
    }

    /** Records one exact point-in-time snapshot. */
    public void record() {
        record(coordinator.metrics());
    }

    /** Publishes a caller-owned exact snapshot without changing its result. */
    public void record(@Nonnull MaintenanceMetricsSnapshot snapshot) {
        try {
            observer.accept(Objects.requireNonNull(snapshot, "snapshot"));
        } catch (Throwable ignored) {
            // Passive measurements cannot change maintenance persistence.
        }
    }
}
