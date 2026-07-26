package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionChangedEvent;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.List;
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
    private final Object lifecycleLock = new Object();
    private final Runnable beforeRegistration;
    private final ThreadLocal<Integer> publicationDepth =
            ThreadLocal.withInitial(() -> 0);
    private int activePublications;

    public BondedCompanionChangePublisher(@Nullable HytaleLogger logger) {
        this(logger, () -> { });
    }

    BondedCompanionChangePublisher(
            @Nullable HytaleLogger logger,
            @Nonnull Runnable beforeRegistration
    ) {
        this.logger = logger;
        this.beforeRegistration = Objects.requireNonNull(
                beforeRegistration, "beforeRegistration"
        );
    }

    /** Subscribes until the returned handle or this publisher is closed. */
    @Nonnull
    public AutoCloseable subscribe(
            @Nonnull Consumer<BondedCompanionChangedEvent> listener
    ) {
        Consumer<BondedCompanionChangedEvent> exact = Objects.requireNonNull(
                listener, "listener"
        );
        synchronized (lifecycleLock) {
            if (closed.get()) {
                return () -> { };
            }
            beforeRegistration.run();
            listeners.add(exact);
        }
        return () -> listeners.remove(exact);
    }

    /** Publishes only after durability and the matching world outcome are known. */
    public boolean publishCommitted(
            @Nonnull BondedCompanionChangedEvent event,
            @Nonnull WorldEffectOutcome outcome
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(outcome, "outcome");
        final List<Consumer<BondedCompanionChangedEvent>> snapshot;
        synchronized (lifecycleLock) {
            if (closed.get() || outcome == WorldEffectOutcome.UNKNOWN) {
                return false;
            }
            activePublications++;
            publicationDepth.set(publicationDepth.get() + 1);
            snapshot = List.copyOf(listeners);
        }
        try {
            for (Consumer<BondedCompanionChangedEvent> listener : snapshot) {
                synchronized (lifecycleLock) {
                    if (closed.get()) break;
                }
                try {
                    listener.accept(event);
                } catch (RuntimeException failure) {
                    logListenerFailure(failure);
                }
            }
            return true;
        } finally {
            finishPublication();
        }
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
        boolean interrupted = false;
        synchronized (lifecycleLock) {
            if (closed.compareAndSet(false, true)) {
                listeners.clear();
            }
            int ownPublications = publicationDepth.get();
            while (activePublications > ownPublications) {
                try {
                    lifecycleLock.wait();
                } catch (InterruptedException interruption) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void finishPublication() {
        synchronized (lifecycleLock) {
            activePublications--;
            int remainingDepth = publicationDepth.get() - 1;
            if (remainingDepth == 0) {
                publicationDepth.remove();
            } else {
                publicationDepth.set(remainingDepth);
            }
            lifecycleLock.notifyAll();
        }
    }

    int listenerCount() {
        return listeners.size();
    }

    /** Whether the committed mutation's matching physical outcome is known. */
    public enum WorldEffectOutcome { NOT_REQUIRED, CONFIRMED, DEFERRED, UNKNOWN }
}
