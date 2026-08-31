package com.alechilles.alecstamework.persistence.diagnostics;

import com.alechilles.alecstamework.persistence.runtime.PersistenceFailureSignal;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Holds one early persistence failure until the telemetry reporter is ready.
 */
public final class PersistenceFailureRelay
        implements Consumer<PersistenceFailureSignal> {

    private final AtomicReference<Consumer<PersistenceFailureSignal>> target =
            new AtomicReference<>();
    private final AtomicReference<PersistenceFailureSignal> pending =
            new AtomicReference<>();

    /** Binds the sole reporter and forwards one pending startup failure. */
    public void bind(@Nonnull Consumer<PersistenceFailureSignal> target) {
        Consumer<PersistenceFailureSignal> normalized = Objects.requireNonNull(
                target, "target"
        );
        if (!this.target.compareAndSet(null, normalized)) {
            throw new IllegalStateException("Persistence failure relay is already bound");
        }
        PersistenceFailureSignal early = pending.getAndSet(null);
        if (early != null) {
            normalized.accept(early);
        }
    }

    /** Removes the current reporter during plugin shutdown. */
    public void unbind(@Nonnull Consumer<PersistenceFailureSignal> target) {
        this.target.compareAndSet(
                Objects.requireNonNull(target, "target"), null
        );
    }

    @Override
    public void accept(@Nonnull PersistenceFailureSignal signal) {
        Objects.requireNonNull(signal, "signal");
        Consumer<PersistenceFailureSignal> current = target.get();
        if (current != null) {
            current.accept(signal);
            return;
        }
        pending.compareAndSet(null, signal);
        current = target.get();
        if (current != null) {
            PersistenceFailureSignal early = pending.getAndSet(null);
            if (early != null) {
                current.accept(early);
            }
        }
    }
}
