package com.alechilles.alecstamework.npc.progression;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/** Small synchronous process-local signal bus for committed companion progression changes. */
public final class CompanionProgressionSignalBus implements AutoCloseable {
    private final CopyOnWriteArrayList<Consumer<CompanionXpTransition>> listeners =
            new CopyOnWriteArrayList<>();

    /** Registers one listener. The returned handle is idempotent. */
    @Nonnull
    public AutoCloseable subscribe(@Nonnull Consumer<CompanionXpTransition> listener) {
        Consumer<CompanionXpTransition> required = Objects.requireNonNull(listener, "listener");
        listeners.add(required);
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                listeners.remove(required);
            }
        };
    }

    /** Publishes on the calling thread after progression mutation and trait updates. */
    void publish(@Nonnull CompanionXpTransition transition) {
        CompanionXpTransition required = Objects.requireNonNull(transition, "transition");
        for (Consumer<CompanionXpTransition> listener : listeners) {
            try {
                listener.accept(required);
            } catch (RuntimeException | LinkageError ignored) {
                // A presentation or compatibility listener cannot undo progression.
            }
        }
    }

    @Override
    public void close() {
        listeners.clear();
    }

}
