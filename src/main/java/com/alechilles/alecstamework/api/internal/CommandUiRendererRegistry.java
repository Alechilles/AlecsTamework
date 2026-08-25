package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.commandui.CommandUiRegistration;
import com.alechilles.alecstamework.api.commandui.CommandUiRegistrationResult;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererId;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Thread-safe exact-generation registry for custom command UI renderers. */
public final class CommandUiRendererRegistry implements AutoCloseable {
    private final ConcurrentMap<CommandUiRendererId, Entry> renderers =
            new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<UnregisterListener> listeners =
            new CopyOnWriteArrayList<>();
    private final AtomicLong nextGeneration = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Registers a renderer or returns a stable validation/conflict result. */
    @Nonnull
    public synchronized CommandUiRegistrationResult register(
            @Nullable String rawId,
            @Nullable CommandUiRendererProvider provider
    ) {
        if (closed.get()) {
            return CommandUiRegistrationResult.unavailable(rawId);
        }
        Optional<CommandUiRendererId> parsed = CommandUiRendererId.tryParse(rawId);
        if (parsed.isEmpty()) {
            return CommandUiRegistrationResult.invalid(rawId,
                    "Command UI renderer ID must be a trimmed namespaced ID.");
        }
        CommandUiRendererId id = parsed.orElseThrow();
        if (id.reserved()) {
            return CommandUiRegistrationResult.invalid(rawId,
                    "The tamework: renderer namespace is reserved.");
        }
        if (provider == null) {
            return CommandUiRegistrationResult.invalid(rawId,
                    "Command UI renderer provider is required.");
        }
        Entry entry = new Entry(id, provider, nextGeneration.incrementAndGet());
        if (renderers.putIfAbsent(id, entry) != null) {
            return CommandUiRegistrationResult.conflict(id.value());
        }
        return CommandUiRegistrationResult.registered(entry);
    }

    /** Finds the currently active renderer provider. */
    @Nonnull
    public synchronized Optional<CommandUiRendererProvider> find(
            @Nullable String rawId
    ) {
        Optional<CommandUiRendererId> parsed = CommandUiRendererId.tryParse(rawId);
        if (closed.get() || parsed.isEmpty()) return Optional.empty();
        Entry entry = renderers.get(parsed.orElseThrow());
        return entry == null || !entry.active()
                ? Optional.empty() : Optional.of(entry.provider);
    }

    /** Resolves a renderer and the exact generation that created it. */
    @Nonnull
    public synchronized Optional<ResolvedRenderer> resolve(
            @Nullable String rawId
    ) {
        Optional<CommandUiRendererId> parsed = CommandUiRendererId.tryParse(rawId);
        if (closed.get() || parsed.isEmpty()) return Optional.empty();
        Entry entry = renderers.get(parsed.orElseThrow());
        return entry == null || !entry.active()
                ? Optional.empty()
                : Optional.of(new ResolvedRenderer(
                        entry.id, entry.provider, entry.generation));
    }

    @Nonnull
    public synchronized Set<CommandUiRendererId> listIds() {
        return closed.get() ? Set.of() : Set.copyOf(renderers.keySet());
    }

    /** Returns whether this registry still accepts registrations. */
    public boolean available() {
        return !closed.get();
    }

    /** Adds a listener for exact renderer-generation removal. */
    @Nonnull
    public AutoCloseable subscribeUnregister(@Nonnull UnregisterListener listener) {
        UnregisterListener required = java.util.Objects.requireNonNull(
                listener, "listener");
        if (closed.get()) return () -> { };
        listeners.add(required);
        return () -> listeners.remove(required);
    }

    /** Subscribes only while one exact generation remains active. */
    @Nonnull
    public synchronized ExactSubscription subscribeExactUnregister(
            @Nonnull CommandUiRendererId id,
            long generation,
            @Nonnull UnregisterListener listener
    ) {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(listener, "listener");
        Entry current = renderers.get(id);
        if (closed.get() || current == null || !current.active()
                || current.generation != generation) {
            return new ExactSubscription(false, () -> { });
        }
        ExactUnregisterListener exact = new ExactUnregisterListener(
                id, generation, listener);
        listeners.add(exact);
        return new ExactSubscription(true, exact);
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Entry entry : renderers.values()) {
            entry.closed.set(true);
            notifyUnregister(entry.id, entry.generation);
        }
        renderers.clear();
        listeners.clear();
    }

    private void notifyUnregister(CommandUiRendererId id, long generation) {
        for (UnregisterListener listener : listeners) {
            try {
                listener.unregistered(id, generation);
            } catch (RuntimeException | LinkageError ignored) {
                // One lifecycle listener must not block registry cleanup.
            }
        }
    }

    /** Internal lookup result with the exact renderer registration identity. */
    public record ResolvedRenderer(
            @Nonnull CommandUiRendererId id,
            @Nonnull CommandUiRendererProvider provider,
            long generation
    ) {
    }

    /** Atomic result for an exact-generation lifecycle subscription. */
    public record ExactSubscription(boolean active, @Nonnull AutoCloseable handle) {
        public ExactSubscription {
            java.util.Objects.requireNonNull(handle, "handle");
        }
    }

    @FunctionalInterface
    public interface UnregisterListener {
        void unregistered(CommandUiRendererId id, long generation);
    }

    private final class ExactUnregisterListener
            implements UnregisterListener, AutoCloseable {
        private final CommandUiRendererId id;
        private final long generation;
        private final UnregisterListener delegate;
        private final AtomicBoolean ended = new AtomicBoolean();

        private ExactUnregisterListener(
                CommandUiRendererId id,
                long generation,
                UnregisterListener delegate
        ) {
            this.id = id;
            this.generation = generation;
            this.delegate = delegate;
        }

        @Override
        public void unregistered(CommandUiRendererId removedId,
                                 long removedGeneration) {
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

    private final class Entry implements CommandUiRegistration {
        private final CommandUiRendererId id;
        private final CommandUiRendererProvider provider;
        private final long generation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Entry(CommandUiRendererId id,
                       CommandUiRendererProvider provider,
                       long generation) {
            this.id = id;
            this.provider = provider;
            this.generation = generation;
        }

        @Override
        @Nonnull
        public String id() {
            return id.value();
        }

        @Override
        public long generation() {
            return generation;
        }

        @Override
        public boolean active() {
            return !closed.get() && !CommandUiRendererRegistry.this.closed.get()
                    && renderers.get(id) == this;
        }

        @Override
        public void close() {
            synchronized (CommandUiRendererRegistry.this) {
                if (!closed.compareAndSet(false, true)) return;
                if (renderers.remove(id, this)) {
                    notifyUnregister(id, generation);
                }
            }
        }
    }
}
