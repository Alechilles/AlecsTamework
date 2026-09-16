package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.commandhud.CommandHudRegistration;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistrationResult;
import com.alechilles.alecstamework.api.commandhud.CommandHudSurface;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/** Shared exact-generation lifecycle for the two typed command HUD registries. */
final class CommandHudRegistrationStore<I, D> implements AutoCloseable {
    private final Map<CommandHudSurface, Map<I, Registration<I, D>>> registrations =
            new EnumMap<>(CommandHudSurface.class);
    private final CopyOnWriteArrayList<UnregisterListener<I>> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SurfaceUnregisterListener<I>> surfaceListeners =
            new CopyOnWriteArrayList<>();
    private final Function<I, String> idValue;
    private final AtomicLong nextGeneration = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    CommandHudRegistrationStore(Function<I, String> idValue) {
        this.idValue = java.util.Objects.requireNonNull(idValue, "idValue");
        for (CommandHudSurface surface : CommandHudSurface.values()) registrations.put(surface, new HashMap<>());
    }

    synchronized CommandHudRegistrationResult register(
            CommandHudSurface surface, String rawId, Supplier<Optional<I>> parser, Object provider,
            D descriptor, String invalidIdMessage, String missingProviderMessage,
            String missingDescriptorMessage
    ) {
        if (closed.get()) return CommandHudRegistrationResult.unavailable(rawId);
        Optional<I> parsed = parser.get();
        if (parsed.isEmpty()) return CommandHudRegistrationResult.invalid(rawId, invalidIdMessage);
        if (provider == null) return CommandHudRegistrationResult.invalid(rawId, missingProviderMessage);
        if (descriptor == null) return CommandHudRegistrationResult.invalid(rawId, missingDescriptorMessage);
        I id = parsed.orElseThrow();
        Registration<I, D> entry = new Registration<>(this, surface, id, provider, descriptor,
                nextGeneration.incrementAndGet());
        if (registrations.get(surface).putIfAbsent(id, entry) != null) {
            return CommandHudRegistrationResult.conflict(idValue.apply(id));
        }
        return CommandHudRegistrationResult.registered(entry);
    }

    synchronized Optional<Registration<I, D>> resolve(
            CommandHudSurface surface, Supplier<Optional<I>> parser
    ) {
        Optional<I> parsed = parser.get();
        if (closed.get() || parsed.isEmpty()) return Optional.empty();
        Registration<I, D> entry = registrations.get(surface).get(parsed.orElseThrow());
        return entry == null || !entry.active() ? Optional.empty() : Optional.of(entry);
    }

    synchronized Set<I> ids(CommandHudSurface surface) {
        return closed.get() ? Set.of() : Set.copyOf(registrations.get(surface).keySet());
    }

    boolean available() { return !closed.get(); }

    synchronized boolean active(CommandHudSurface surface, I id, long generation) {
        if (closed.get() || id == null || generation <= 0L) return false;
        Registration<I, D> entry = registrations.get(surface).get(id);
        return entry != null && entry.generation() == generation && entry.active();
    }

    AutoCloseable subscribeUnregister(UnregisterListener<I> listener) {
        synchronized (this) {
            if (closed.get()) return () -> { };
            listeners.add(listener);
        }
        return () -> listeners.remove(listener);
    }

    AutoCloseable subscribeSurfaceUnregister(SurfaceUnregisterListener<I> listener) {
        synchronized (this) {
            if (closed.get()) return () -> { };
            surfaceListeners.add(listener);
        }
        return () -> surfaceListeners.remove(listener);
    }

    synchronized ExactSubscription subscribeExact(
            CommandHudSurface surface, I id, long generation, UnregisterListener<I> listener
    ) {
        Registration<I, D> current = registrations.get(surface).get(id);
        if (closed.get() || current == null || !current.active() || current.generation() != generation) {
            return new ExactSubscription(false, () -> { });
        }
        ExactUnregisterListener exact = new ExactUnregisterListener(surface, id, generation, listener);
        listeners.add(exact);
        return new ExactSubscription(true, exact);
    }

    @Override
    public void close() {
        List<Removal<I>> removed = new ArrayList<>();
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            for (CommandHudSurface surface : CommandHudSurface.values()) {
                for (Registration<I, D> entry : registrations.get(surface).values()) {
                    entry.closed.set(true);
                    removed.add(new Removal<>(surface, entry.registrationId(), entry.generation()));
                }
                registrations.get(surface).clear();
            }
        }
        for (Removal<I> removal : removed) {
            notifyUnregister(removal.surface(), removal.id(), removal.generation());
        }
        listeners.clear();
        surfaceListeners.clear();
    }

    private void unregister(Registration<I, D> entry) {
        boolean removed;
        synchronized (this) {
            if (!entry.closed.compareAndSet(false, true)) return;
            removed = registrations.get(entry.surface()).remove(entry.registrationId(), entry);
        }
        if (removed) notifyUnregister(entry.surface(), entry.registrationId(), entry.generation());
    }

    private synchronized boolean isActive(Registration<I, D> entry) {
        return !closed.get() && !entry.closed.get()
                && registrations.get(entry.surface()).get(entry.registrationId()) == entry;
    }

    private void notifyUnregister(CommandHudSurface surface, I id, long generation) {
        for (UnregisterListener<I> listener : listeners) {
            try {
                listener.unregistered(id, generation);
            } catch (RuntimeException | LinkageError ignored) {
                // One lifecycle listener must not block registry cleanup.
            }
        }
        for (SurfaceUnregisterListener<I> listener : surfaceListeners) {
            try {
                listener.unregistered(surface, id, generation);
            } catch (RuntimeException | LinkageError ignored) {
                // One lifecycle listener must not block registry cleanup.
            }
        }
    }

    static final class Registration<I, D> implements CommandHudRegistration {
        private final CommandHudRegistrationStore<I, D> owner;
        private final CommandHudSurface surface;
        private final I id;
        private final Object provider;
        private final D descriptor;
        private final long generation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Registration(CommandHudRegistrationStore<I, D> owner, CommandHudSurface surface, I id,
                             Object provider, D descriptor, long generation) {
            this.owner = owner;
            this.surface = surface;
            this.id = id;
            this.provider = provider;
            this.descriptor = descriptor;
            this.generation = generation;
        }

        I registrationId() { return id; }
        Object provider() { return provider; }
        D descriptor() { return descriptor; }
        CommandHudSurface surface() { return surface; }

        @Override public String id() { return owner.idValue.apply(id); }
        @Override public long generation() { return generation; }
        @Override public boolean active() { return owner.isActive(this); }
        @Override public void close() { owner.unregister(this); }
    }

    record ExactSubscription(boolean active, AutoCloseable handle) { }

    @FunctionalInterface
    interface UnregisterListener<I> { void unregistered(I id, long generation); }

    @FunctionalInterface
    interface SurfaceUnregisterListener<I> {
        void unregistered(CommandHudSurface surface, I id, long generation);
    }

    private final class ExactUnregisterListener implements UnregisterListener<I>, AutoCloseable {
        private final CommandHudSurface surface;
        private final I id;
        private final long generation;
        private final UnregisterListener<I> delegate;
        private final AtomicBoolean ended = new AtomicBoolean();

        private ExactUnregisterListener(CommandHudSurface surface, I id, long generation,
                                        UnregisterListener<I> delegate) {
            this.surface = surface;
            this.id = id;
            this.generation = generation;
            this.delegate = delegate;
        }

        @Override
        public void unregistered(I removedId, long removedGeneration) {
            if (!id.equals(removedId) || generation != removedGeneration
                    || !ended.compareAndSet(false, true)) return;
            listeners.remove(this);
            delegate.unregistered(removedId, removedGeneration);
        }

        @Override
        public void close() {
            if (ended.compareAndSet(false, true)) listeners.remove(this);
        }
    }

    private record Removal<I>(CommandHudSurface surface, I id, long generation) { }
}
