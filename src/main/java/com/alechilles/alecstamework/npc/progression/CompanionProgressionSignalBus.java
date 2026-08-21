package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Small synchronous process-local signal bus for committed companion progression changes. */
public final class CompanionProgressionSignalBus implements AutoCloseable {
    private static final Object INSTALL_LOCK = new Object();

    @Nullable
    private static volatile CompanionProgressionSignalBus installedBus;
    private final CopyOnWriteArrayList<Consumer<CompanionXpTransition>> listeners =
            new CopyOnWriteArrayList<>();
    private boolean legacyAdapterInstalled;

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

    /** Installs the one bus owned by Tamework for the current runtime. */
    public static boolean installForTamework(@Nonnull Tamework owner,
                                             @Nonnull Object token,
                                             @Nonnull CompanionProgressionSignalBus bus) {
        if (owner == null || owner != Tamework.getInstance() || token == null || bus == null
                || !owner.ownsCompanionProgressionSignalToken(token)) {
            return false;
        }
        synchronized (INSTALL_LOCK) {
            if (installedBus != null && installedBus != bus) {
                return false;
            }
            installedBus = bus;
            return true;
        }
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

    /** Publishes a committed transition through Tamework's installed bus. */
    static void publishCommitted(@Nonnull CompanionXpTransition transition) {
        CompanionProgressionSignalBus bus = installedBus;
        if (bus != null) {
            bus.publish(transition);
        }
    }

    /** Installs the package-owned legacy projection used by Tamework composition. */
    @Nullable
    public AutoCloseable installLegacyAdapter(@Nonnull Tamework owner,
                                              @Nonnull Object token,
                                              @Nonnull TameworkEventBus legacyEvents) {
        if (owner == null || owner != Tamework.getInstance() || token == null || legacyEvents == null
                || !owner.ownsCompanionProgressionSignalToken(token)) {
            return null;
        }
        synchronized (INSTALL_LOCK) {
            if (installedBus != this || legacyAdapterInstalled) {
                return null;
            }
            CompanionXpLegacyAdapter adapter = new CompanionXpLegacyAdapter(this, legacyEvents);
            legacyAdapterInstalled = true;
            return adapter;
        }
    }

    @Override
    public void close() {
        listeners.clear();
        synchronized (INSTALL_LOCK) {
            legacyAdapterInstalled = false;
            if (installedBus == this) {
                installedBus = null;
            }
        }
    }

}
