package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionChangedEvent;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Isolated, closeable publisher for committed bonded profile changes. */
public final class BondedCompanionChangePublisher implements AutoCloseable {
    private final HytaleLogger logger;
    private final CopyOnWriteArrayList<Consumer<BondedCompanionChangedEvent>>
            listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public BondedCompanionChangePublisher(@Nullable HytaleLogger logger) {
        this.logger = logger;
    }

    /** Subscribes until the returned handle or this publisher is closed. */
    @Nonnull
    public AutoCloseable subscribe(
            @Nonnull Consumer<BondedCompanionChangedEvent> listener
    ) {
        Consumer<BondedCompanionChangedEvent> exact = Objects.requireNonNull(
                listener, "listener"
        );
        if (closed.get()) {
            return () -> { };
        }
        listeners.add(exact);
        return () -> listeners.remove(exact);
    }

    /** Publishes only after durability and the matching world outcome are known. */
    public boolean publishCommitted(
            @Nonnull BondedCompanionChangedEvent event,
            @Nonnull WorldEffectOutcome outcome
    ) {
        Objects.requireNonNull(event, "event");
        if (closed.get() || Objects.requireNonNull(outcome, "outcome")
                == WorldEffectOutcome.UNKNOWN) {
            return false;
        }
        for (Consumer<BondedCompanionChangedEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException failure) {
                logListenerFailure(failure);
            }
        }
        return true;
    }

    private void logListenerFailure(RuntimeException failure) {
        if (logger != null) {
            logger.at(Level.FINE).withCause(failure).log(
                    "A bonded-companion change listener failed."
            );
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            listeners.clear();
        }
    }

    /** Whether the committed mutation's matching physical outcome is known. */
    public enum WorldEffectOutcome { NOT_REQUIRED, CONFIRMED, DEFERRED, UNKNOWN }
}
